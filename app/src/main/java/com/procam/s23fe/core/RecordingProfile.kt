package com.procam.s23fe.core

import android.util.Size

/**
 * Fixed recording profiles exactly matching the ProCam S23 FE spec sheet.
 *
 *  | Mode          | Resolution  | FPS | Bitrate  |
 *  |---------------|-------------|-----|----------|
 *  | FHD_HIGH      | 1920x1080   | 60  | 100 Mbps |
 *  | FHD_STANDARD  | 1920x1080   | 30  | 100 Mbps |
 *  | UHD           | 3840x2160   | 30  |  60 Mbps |
 *
 * Audio is **always** stereo / 48 kHz / 256 kbps AAC.
 */
enum class RecordingProfile(
    val label: String,
    val size: Size,
    val fps: Int,
    val videoBitrate: Int,
    val audioBitrate: Int = 256_000,
    val audioSampleRate: Int = 48_000,
    val audioChannels: Int = 2,
) {
    FHD_HIGH    ("FHD 60",  Size(1920, 1080), 60, 100_000_000),
    FHD_STANDARD("FHD 30",  Size(1920, 1080), 30, 100_000_000),
    UHD         ("UHD 30",  Size(3840, 2160), 30,  60_000_000);

    /** Target fps range used for CONTROL_AE_TARGET_FPS_RANGE (locked). */
    fun aeFpsRange() = android.util.Range(fps, fps)
}
