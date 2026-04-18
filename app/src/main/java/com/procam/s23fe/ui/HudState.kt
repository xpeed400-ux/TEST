package com.procam.s23fe.ui

import com.procam.s23fe.core.RecordingProfile

/**
 * Observable state for the HUD overlay.
 * All values are pure data — the UI is a projection of this class.
 */
data class HudState(
    val iso: Int = 0,
    val shutterNs: Long = 0L,
    val fps: Int = 0,
    val bitrateMbps: Int = 0,
    val focusDiopters: Float = 0f,
    val stabilizationOn: Boolean = true,
    val recording: Boolean = false,
    val profile: RecordingProfile = RecordingProfile.FHD_HIGH,
    val thermalStatus: Int = 0,
) {
    fun shutterLabel(): String =
        if (shutterNs <= 0) "—"
        else "1/${(1_000_000_000.0 / shutterNs).toInt().coerceAtLeast(1)}"

    fun focusLabel(): String =
        if (focusDiopters <= 0f) "∞"
        else "${"%.2f".format(1f / focusDiopters)}m"
}
