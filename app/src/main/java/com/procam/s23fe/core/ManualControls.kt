package com.procam.s23fe.core

/**
 * Holds the current state of every manual camera control.
 * Any value set to `null` means "auto" for that dimension.
 *
 * All numeric bounds come from [DevicePreset] — do NOT invent values here.
 */
data class ManualControls(
    /** ISO sensitivity, clamped to [DevicePreset.ISO_MIN]..[DevicePreset.ISO_MAX_VIDEO]. */
    val iso: Int? = null,
    /** Exposure time in nanoseconds. 1/60s = 16_666_666 ns. */
    val shutterNs: Long? = null,
    /**
     * Focus distance in diopters (1/meters). 0.0 = infinity.
     * Range comes from LENS_INFO_MINIMUM_FOCUS_DISTANCE (capped by DevicePreset).
     */
    val focusDiopters: Float? = null,
    /** Exposure compensation steps (EV * step size from EV_STEP). */
    val evSteps: Int = 0,
    /** WB mode from CameraMetadata.CONTROL_AWB_MODE_*, or null for AUTO. */
    val awbMode: Int? = null,
    /** Custom color temperature in Kelvin; non-null activates manual WB via RGB gains. */
    val wbKelvin: Int? = null,
) {
    /** Apply [DevicePreset] clamps so invalid values never reach Camera2. */
    fun sanitized(videoMode: Boolean = true): ManualControls = copy(
        iso            = iso?.let { DevicePreset.clampIso(it, videoMode) },
        shutterNs      = shutterNs?.let { DevicePreset.clampShutter(it) },
        focusDiopters  = focusDiopters?.let { DevicePreset.clampFocus(it) },
        evSteps        = DevicePreset.clampEv(evSteps),
        wbKelvin       = wbKelvin?.let { DevicePreset.clampKelvin(it) },
    )

    companion object {
        /** Factory for an [AePreset] + [WbPreset] combo. */
        fun fromPresets(ae: DevicePreset.AePreset, wb: DevicePreset.WbPreset): ManualControls =
            ManualControls(
                iso = ae.iso,
                shutterNs = ae.shutterNs,
                evSteps = ae.evSteps,
                awbMode = wb.awbMode,
                wbKelvin = wb.kelvin,
            )
    }
}
