package com.procam.s23fe.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.procam.s23fe.camera.CameraController
import com.procam.s23fe.core.DevicePreset
import com.procam.s23fe.core.ManualControls
import com.procam.s23fe.core.RecordingProfile
import com.procam.s23fe.databinding.ActivityMainBinding
import com.procam.s23fe.gpu.LutCatalog
import com.procam.s23fe.gpu.LutEngine
import com.procam.s23fe.gpu.LutLibrary
import com.procam.s23fe.gpu.LutRenderThread
import com.procam.s23fe.recorder.ProCamRecorder
import com.procam.s23fe.thermal.ThermalManager

/**
 * Main (and only) camera screen. Wires together every subsystem:
 *   - CameraController        (Camera2, manual controls, EIS crop, AE/AWB lock)
 *   - LutEngine + LutRenderThread (GPU LUT pipeline)
 *   - ProCamRecorder          (100 Mbps H.264 + stereo 48 kHz/256 k AAC)
 *   - DevicePreset            (S23 FE tuned defaults & clamps)
 *   - HUD                     (Figma-style Material 3 overlay)
 */
class MainActivity : AppCompatActivity(), CameraController.Listener {

    private val tag = "MainActivity"
    private lateinit var binding: ActivityMainBinding

    private lateinit var camera: CameraController
    private lateinit var recorder: ProCamRecorder
    private lateinit var lutEngine: LutEngine
    private lateinit var renderThread: LutRenderThread
    private lateinit var thermal: ThermalManager

    private var profile = RecordingProfile.FHD_HIGH
    // Start fully automatic — user can pick a preset from the left strip to go manual.
    private var manual = ManualControls()
    private var hud = HudState()
    private var sensorOrientation = 90
    private val mainHandler = Handler(Looper.getMainLooper())

    // EIS crop cycles 15/20/25%
    private val eisCrops = floatArrayOf(0.15f, 0.20f, 0.25f)
    private var eisIdx = 1 // default = 20%

    private var aePresetIdx = 0
    private var wbPresetIdx = 1
    private var lutIdx = 0
    private var manualPanelVisible = false

    private var recordStartElapsedMs = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            if (recorder.isRecording()) {
                val s = (SystemClock.elapsedRealtime() - recordStartElapsedMs) / 1000
                binding.txtTimer.text = "%02d:%02d".format(s / 60, s % 60)
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) onPermissionsGranted()
        else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        camera = CameraController(this).apply { listener = this@MainActivity }
        recorder = ProCamRecorder(this)
        lutEngine = LutEngine(this)
        renderThread = LutRenderThread(lutEngine)
        thermal = ThermalManager(this)

        wireUi()
        ensurePermissions()
    }

    // region permission / lifecycle

    private fun ensurePermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsGranted() else permLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsGranted() {
        renderThread.startRender()
        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, h2: Int) {
                renderThread.setPreviewSurface(h.surface, w, h2)
                openCameraIfNeeded()
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                renderThread.setPreviewSurface(null, 0, 0)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(timerTick)
        try { camera.close() } catch (_: Exception) {}
        try { if (recorder.isRecording()) recorder.stop() } catch (_: Exception) {}
        try { renderThread.release() } catch (_: Exception) {}
    }

    // endregion

    // region camera open

    private var cameraOpened = false
    private fun openCameraIfNeeded() {
        if (cameraOpened) return
        val id = camera.pickBackCameraId() ?: run {
            Log.e(tag, "No back camera"); finish(); return
        }
        camera.open(id) {
            val chars = camera.characteristics()
            sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val camTex = renderThread.cameraSurfaceTexture().apply {
                setDefaultBufferSize(profile.size.width, profile.size.height)
            }
            val cameraSurface = Surface(camTex)
            camera.setProfile(profile)
            camera.setManualControls(manual)
            camera.setStabilization(hw = true, sw = true, cropFraction = eisCrops[eisIdx])
            camera.configureSession(
                preview = cameraSurface,
                record  = null,
                still   = null
            ) { cameraOpened = true; updateHud() }
        }
    }

    // endregion

    // region UI wiring

    @SuppressLint("ClickableViewAccessibility")
    private fun wireUi() {
        // record button (View + drawable)
        binding.btnRecord.setOnClickListener {
            if (recorder.isRecording()) stopRecording() else startRecording()
        }
        binding.btnProfile.setOnClickListener { cycleProfile() }
        binding.btnToggleManual.setOnClickListener { toggleManualPanel() }

        binding.btnAePreset.setOnClickListener { pickAePreset() }
        binding.btnWbPreset.setOnClickListener { pickWbPreset() }
        binding.btnEisCrop.setOnClickListener { cycleEisCrop() }
        binding.txtLut.setOnClickListener { cycleLut() }

        // Sliders
        binding.sliderLutIntensity.addOnChangeListener { _, v, _ -> lutEngine.setIntensity(v / 100f) }
        binding.sliderIso.addOnChangeListener { _, v, _ ->
            manual = manual.copy(iso = v.toInt().takeIf { it > 0 })
            applyManual()
        }
        binding.sliderShutter.addOnChangeListener { _, v, _ ->
            val ns = if (v <= 0) null else 1_000_000_000L / v.toInt().coerceAtLeast(1)
            manual = manual.copy(shutterNs = ns)
            applyManual()
        }
        binding.sliderFocus.addOnChangeListener { _, v, _ ->
            manual = manual.copy(focusDiopters = v.takeIf { it > 0f })
            applyManual()
        }
        binding.sliderEv.addOnChangeListener { _, v, _ ->
            manual = manual.copy(evSteps = v.toInt())
            applyManual()
        }
        binding.sliderWb.addOnChangeListener { _, v, _ ->
            manual = manual.copy(wbKelvin = v.toInt().takeIf { it > 0 }, awbMode = null)
            applyManual()
        }

        // Tap-to-focus reticle (camera-region AF hookup is a TODO; see PR description)
        binding.previewSurface.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                binding.afTarget.show(ev.x, ev.y)
                v.performClick()
            }
            true
        }

        // Initial labels
        binding.btnAePreset.text = "Auto"
        binding.btnWbPreset.text = "Auto"
        binding.btnEisCrop.text = "${(eisCrops[eisIdx] * 100).toInt()}%"
    }

    private fun applyManual() {
        camera.setManualControls(manual)
        updateHud()
    }

    private fun toggleManualPanel() {
        manualPanelVisible = !manualPanelVisible
        binding.manualPanel.visibility = if (manualPanelVisible) View.VISIBLE else View.GONE
        binding.btnToggleManual.isSelected = manualPanelVisible
    }

    private fun cycleProfile() {
        profile = when (profile) {
            RecordingProfile.FHD_HIGH     -> RecordingProfile.FHD_STANDARD
            RecordingProfile.FHD_STANDARD -> RecordingProfile.UHD
            RecordingProfile.UHD          -> RecordingProfile.FHD_HIGH
        }
        camera.setProfile(profile)
        binding.btnProfile.text = profile.label
        updateHud()
    }

    private fun cycleLut() {
        val all = LutLibrary.all(this)
        if (all.isEmpty()) return
        lutIdx = (lutIdx + 1) % all.size
        val e = all[lutIdx]
        lutEngine.setLut(e.data, e.size)
        binding.txtLut.text = e.label
    }

    private fun cycleEisCrop() {
        eisIdx = (eisIdx + 1) % eisCrops.size
        val f = eisCrops[eisIdx]
        camera.setStabilization(hw = true, sw = true, cropFraction = f)
        binding.btnEisCrop.text = "${(f * 100).toInt()}%"
    }

    private fun pickAePreset() {
        val items = DevicePreset.AE_PRESETS.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Auto-Exposure preset")
            .setItems(items) { _, which ->
                aePresetIdx = which
                val p = DevicePreset.AE_PRESETS[which]
                manual = manual.copy(iso = p.iso, shutterNs = p.shutterNs, evSteps = p.evSteps)
                binding.btnAePreset.text = p.label
                applyManual()
            }
            .show()
    }

    private fun pickWbPreset() {
        val items = DevicePreset.WB_PRESETS.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("White Balance preset")
            .setItems(items) { _, which ->
                wbPresetIdx = which
                val p = DevicePreset.WB_PRESETS[which]
                manual = manual.copy(wbKelvin = p.kelvin, awbMode = p.awbMode)
                binding.btnWbPreset.text = p.label
                applyManual()
            }
            .show()
    }

    // endregion

    // region record flow

    private fun startRecording() {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        }
        val effectiveBitrate = thermal.effectiveBitrate(profile.videoBitrate)
        val recSurface = recorder.prepare(
            profile = profile,
            bitrateOverride = effectiveBitrate,
            sensorOrientation = sensorOrientation,
            deviceRotation = rotation,
        )
        renderThread.setRecordSurface(recSurface, profile.size.width, profile.size.height)
        recorder.start()
        camera.onRecordingStarted()

        binding.btnRecord.setBackgroundResource(com.procam.s23fe.R.drawable.bg_record_button_active)
        binding.recordingDot.visibility = View.VISIBLE
        binding.txtTimer.visibility = View.VISIBLE
        recordStartElapsedMs = SystemClock.elapsedRealtime()
        mainHandler.post(timerTick)

        hud = hud.copy(recording = true, bitrateMbps = effectiveBitrate / 1_000_000)
        updateHud()
    }

    private fun stopRecording() {
        recorder.stop()
        camera.onRecordingStopped()
        renderThread.setRecordSurface(null, 0, 0)

        binding.btnRecord.setBackgroundResource(com.procam.s23fe.R.drawable.bg_record_button)
        binding.recordingDot.visibility = View.INVISIBLE
        binding.txtTimer.visibility = View.GONE
        mainHandler.removeCallbacks(timerTick)

        hud = hud.copy(recording = false)
        updateHud()
    }

    // endregion

    // region CameraController.Listener

    override fun onCaptureResult(result: TotalCaptureResult) {
        mainHandler.post {
            hud = hud.copy(
                iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: hud.iso,
                shutterNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: hud.shutterNs,
                focusDiopters = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE) ?: hud.focusDiopters,
                fps = profile.fps,
                profile = profile,
                thermalStatus = thermal.currentStatus()
            )
            updateHud()
        }
    }

    override fun onError(msg: String, t: Throwable?) {
        Log.e(tag, "Camera error: $msg", t)
    }

    // endregion

    private fun updateHud() {
        binding.txtIso.text = hud.iso.toString()
        binding.txtShutter.text = hud.shutterLabel()
        binding.txtFps.text = hud.fps.toString()
        binding.txtBitrate.text = "${if (hud.recording) hud.bitrateMbps else profile.videoBitrate / 1_000_000}Mbps"
        binding.txtFocus.text = hud.focusLabel()
        binding.txtProfile.text = profile.label
        binding.btnProfile.text = profile.label
        binding.txtEv.text = (manual.evSteps).toString()
        binding.txtStab.text = if (hud.stabilizationOn) "EIS ${(eisCrops[eisIdx] * 100).toInt()}%" else "OFF"
        binding.txtThermal.text = "T${hud.thermalStatus}"
    }
}
