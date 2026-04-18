package com.procam.s23fe.recorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.procam.s23fe.core.RecordingProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thin but strict wrapper over [MediaRecorder] that enforces the ProCam spec:
 *
 *  - Video  : H.264 High, 100 Mbps (FHD) / 60 Mbps (UHD), exact FPS, MP4 container.
 *  - Audio  : **stereo / 48 kHz / 256 kbps AAC** (no mono fallback — Samsung's stock
 *             camera frequently records mono, so we force these values).
 *  - Orientation is injected via [MediaRecorder.setOrientationHint] based on sensor
 *             orientation + device rotation, which fixes the "rotation bug" described
 *             in section 10 of the spec.
 *
 * The recorder exposes its input [Surface] **before** start so it can be used as a
 * Camera2 output target while configuring the capture session.
 */
class ProCamRecorder(private val context: Context) {

    private val tag = "ProCamRecorder"
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var isStarted = false

    /**
     * @param sensorOrientation value of [CameraCharacteristics.SENSOR_ORIENTATION] for the
     *                          chosen camera (typically 90 on back sensors).
     * @param deviceRotation    current [android.view.Display.getRotation] value (0/90/180/270).
     */
    fun prepare(
        profile: RecordingProfile,
        bitrateOverride: Int? = null,
        sensorOrientation: Int,
        deviceRotation: Int,
    ): Surface {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }

        val outFile = createOutputFile()
        currentFile = outFile

        // Sources FIRST
        r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)

        // Container
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(outFile.absolutePath)

        // -------- VIDEO --------
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoEncodingBitRate(bitrateOverride ?: profile.videoBitrate)
        r.setVideoFrameRate(profile.fps)
        r.setVideoSize(profile.size.width, profile.size.height)
        r.setVideoEncodingProfileLevel(
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            MediaCodecInfo.CodecProfileLevel.AVCLevel51
        )
        // MediaRecorder's I-frame interval is controlled by the codec internally.
        // For finer-grained control (GOP / CBR) migrate to MediaCodec later.

        // -------- AUDIO (force spec) --------
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(profile.audioChannels)          // stereo
        r.setAudioSamplingRate(profile.audioSampleRate)     // 48 000 Hz
        r.setAudioEncodingBitRate(profile.audioBitrate)     // 256 000 bps

        // -------- ORIENTATION --------
        r.setOrientationHint(computeOrientationHint(sensorOrientation, deviceRotation))

        r.prepare()
        recorder = r
        return r.surface
    }

    fun start() {
        val r = recorder ?: error("prepare() first")
        r.start()
        isStarted = true
    }

    /** Returns the file that was written, or null if nothing recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = currentFile
        try {
            if (isStarted) r.stop()
        } catch (e: RuntimeException) {
            Log.w(tag, "recorder.stop() threw", e)
            f?.delete()
            return null
        } finally {
            try { r.reset() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
            recorder = null; isStarted = false
        }
        f?.let { publishToMediaStore(it) }
        return f
    }

    fun isRecording(): Boolean = isStarted

    // region helpers

    private fun createOutputFile(): File {
        val base = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "ProCam/Video"
        )
        if (!base.exists()) base.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(base, "PROCAM_$ts.mp4")
    }

    private fun publishToMediaStore(file: File) {
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProCam/Video")
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
        } catch (e: Exception) {
            Log.w(tag, "MediaStore publish failed", e)
        }
    }

    /**
     * Combine sensor orientation with device rotation.
     * This is the canonical Google-recommended formula:
     *   hint = (sensorOrientation - deviceDegrees + 360) % 360  (front)
     *   hint = (sensorOrientation + deviceDegrees) % 360          (back)
     *
     * We assume the back-facing main camera throughout this app, so we use the back formula.
     */
    private fun computeOrientationHint(sensorOrientation: Int, deviceRotation: Int): Int {
        val deviceDegrees = when (deviceRotation) {
            Surface.ROTATION_0   -> 0
            Surface.ROTATION_90  -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation + deviceDegrees) % 360
    }

    // endregion
}
