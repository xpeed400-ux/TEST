package com.procam.s23fe.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.procam.s23fe.core.DevicePreset
import com.procam.s23fe.core.ManualControls
import com.procam.s23fe.core.RecordingProfile
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Low-level wrapper around [android.hardware.camera2] implementing the critical spec points:
 *
 *  - CONTROL_AE_TARGET_FPS_RANGE is **always** clamped to (fps, fps) so 60 fps never collapses to 30.
 *  - At `startRecording()` we latch `CONTROL_AE_LOCK` and `CONTROL_AWB_LOCK` to stop exposure pulsing.
 *  - A 20 % center crop is applied through `SCALER_CROP_REGION` as a software EIS.
 *  - Manual ISO / shutter / focus / EV / WB are pushed into every repeating request when non-null.
 */
class CameraController(private val context: Context) {

    interface Listener {
        fun onReady() {}
        fun onError(msg: String, t: Throwable? = null) {}
        fun onCaptureResult(result: TotalCaptureResult) {}
    }

    private val tag = "CameraController"
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var sensorRect: Rect? = null

    private val cameraThread = HandlerThread("ProCamCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val sessionExecutor = Executors.newSingleThreadExecutor()

    private var currentProfile: RecordingProfile = RecordingProfile.FHD_HIGH
    private var manual: ManualControls = ManualControls()
    private var stabilizationOn: Boolean = true
    private var softwareEisEnabled: Boolean = true
    private var eisCropFraction: Float = DevicePreset.EIS_CROP_FRACTION_DEFAULT

    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var stillSurface: Surface? = null
    private var isRecording = false

    var listener: Listener? = null

    /** Pick the logical back camera (S23 FE main 50MP sensor). */
    fun pickBackCameraId(): String? = try {
        manager.cameraIdList.firstOrNull {
            val c = manager.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: CameraAccessException) {
        Log.e(tag, "cameraIdList failed", e); null
    }

    @SuppressLint("MissingPermission")
    fun open(cameraId: String, onOpened: () -> Unit) {
        characteristics = manager.getCameraCharacteristics(cameraId)
        sensorRect = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                onOpened()
                listener?.onReady()
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) {
                listener?.onError("CameraDevice error: $error")
                device.close()
            }
        }, cameraHandler)
    }

    fun configureSession(
        preview: Surface,
        record: Surface?,
        still: Surface?,
        onConfigured: () -> Unit,
    ) {
        previewSurface = preview
        recordSurface = record
        stillSurface = still
        val device = cameraDevice ?: return

        val outputs = mutableListOf(OutputConfiguration(preview))
        record?.let { outputs += OutputConfiguration(it) }
        still?.let { outputs += OutputConfiguration(it) }

        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            sessionExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreviewRepeating()
                    onConfigured()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener?.onError("CaptureSession configure failed")
                }
            }
        )
        device.createCaptureSession(cfg)
    }

    // region Repeating requests

    private fun newRequest(template: Int): CaptureRequest.Builder? {
        val d = cameraDevice ?: return null
        val b = d.createCaptureRequest(template)
        applyCommonControls(b)
        return b
    }

    private fun applyCommonControls(b: CaptureRequest.Builder) {
        // Always lock fps to the profile (THE fix for "60fps collapses to 30fps")
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, currentProfile.aeFpsRange())

        // Stabilization – hardware (OIS-assisted EIS)
        if (stabilizationOn) {
            b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }

        // Software EIS – configurable center crop of the sensor active array (default 20%)
        if (softwareEisEnabled) {
            centerCropRegion(eisCropFraction)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }
        }

        // Anti-banding tuned for Korea / 60Hz mixed lighting on GN3.
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, DevicePreset.AE_ANTIBANDING_MODE)

        // Manual controls (each dimension falls back to auto if null)
        val m = manual
        val fullManual = m.iso != null && m.shutterNs != null

        if (fullManual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, m.iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, m.shutterNs)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, m.evSteps)
        }

        if (m.focusDiopters != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, m.focusDiopters)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        when {
            m.awbMode != null -> {
                b.set(CaptureRequest.CONTROL_AWB_MODE, m.awbMode)
            }
            m.wbKelvin != null -> {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, WhiteBalance.gainsForKelvin(m.wbKelvin))
                b.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
            }
            else -> b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }

        if (isRecording) {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
    }

    private fun startPreviewRepeating() {
        val s = session ?: return
        val preview = previewSurface ?: return
        val record = recordSurface
        val b = newRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
        b.addTarget(preview)
        record?.let { b.addTarget(it) } // attach even before recording so surface stays primed
        s.setRepeatingRequest(b.build(), captureCallback, cameraHandler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            listener?.onCaptureResult(result)
        }
    }

    // endregion

    // region Public API

    fun setProfile(profile: RecordingProfile) {
        currentProfile = profile
        rebuildRepeating()
    }

    fun setManualControls(m: ManualControls) {
        manual = m.sanitized(videoMode = true)
        rebuildRepeating()
    }

    fun setStabilization(hw: Boolean, sw: Boolean, cropFraction: Float = eisCropFraction) {
        stabilizationOn = hw
        softwareEisEnabled = sw
        eisCropFraction = DevicePreset.clampCrop(cropFraction)
        rebuildRepeating()
    }

    /** Call this when MediaRecorder.start() is invoked. Locks AE/AWB. */
    fun onRecordingStarted() {
        isRecording = true
        rebuildRepeating()
    }

    fun onRecordingStopped() {
        isRecording = false
        rebuildRepeating()
    }

    private fun rebuildRepeating() = try {
        startPreviewRepeating()
    } catch (e: Exception) {
        Log.w(tag, "rebuildRepeating failed", e)
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        session = null; cameraDevice = null
    }

    // endregion

    // region Helpers

    /**
     * Center-crops the sensor active array by [cropFraction] on each axis.
     * 0.20f = 20 % cropped → 80 % width/height kept for software EIS headroom.
     */
    private fun centerCropRegion(cropFraction: Float): Rect? {
        val s = sensorRect ?: return null
        val cx = s.centerX(); val cy = s.centerY()
        val halfW = (s.width()  * (1f - cropFraction) / 2f).toInt()
        val halfH = (s.height() * (1f - cropFraction) / 2f).toInt()
        return Rect(
            max(s.left,  cx - halfW),
            max(s.top,   cy - halfH),
            min(s.right, cx + halfW),
            min(s.bottom, cy + halfH),
        )
    }

    fun characteristics(): CameraCharacteristics? = characteristics

    // endregion
}
