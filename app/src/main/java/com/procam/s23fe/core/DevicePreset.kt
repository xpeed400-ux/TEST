package com.procam.s23fe.core

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Range

/**
 * Empirically-tuned defaults for the **Samsung Galaxy S23 FE** (Exynos 2200 / GN3 main sensor).
 *
 * Why hard-coded? Because the stock Camera HAL reports huge, often unusable ranges
 * (ISO 20..25600, shutter down to 31 ns). Shooting at those extremes produces garbage.
 * Every value below is picked from field tests on the real body and gives photographers
 * / videographers a *usable* working envelope by default.
 *
 * All of these act as the **UI slider bounds** and **auto-clamp safeguards**. The user
 * can still flip an "Unlock Pro" switch to access the raw HAL ranges (future work).
 */
object DevicePreset {

    // ---------- ISO ----------
    /** Practical ISO floor on GN3 — lower values add almost no DR gain and break black levels. */
    const val ISO_MIN = 50
    /** Practical ISO ceiling for clean 100 Mbps video (grain past this is aggressive). */
    const val ISO_MAX_VIDEO = 3_200
    /** Photo mode can push one stop further since NR / RAW cleanup is available. */
    const val ISO_MAX_PHOTO = 6_400
    /** Default ISO when switching to manual. Matches typical outdoor base. */
    const val ISO_DEFAULT = 100

    // ---------- Shutter (exposure time, ns) ----------
    /** Fastest shutter the app exposes. 1/8000 s. Faster is allowed by HAL but produces artifacts. */
    const val SHUTTER_MIN_NS: Long = 125_000L
    /** Slowest handheld-realistic shutter. 1/4 s (beyond this → hard banding + heat). */
    const val SHUTTER_MAX_NS: Long = 250_000_000L
    /** 180° shutter rule defaults. */
    fun shutterFor180(fps: Int): Long = 1_000_000_000L / (fps * 2)

    // ---------- Focus ----------
    /** LENS_INFO_MINIMUM_FOCUS_DISTANCE reports ~10.0 diopters (≈10cm). We cap UI at 10. */
    const val FOCUS_DIOPTER_MAX = 10f

    // ---------- EV ----------
    /** HAL reports ±20 with step 1/6 EV. We expose ±2 EV (= ±12 steps) which matches most UX. */
    const val EV_STEPS_MIN = -12
    const val EV_STEPS_MAX =  12

    // ---------- White Balance ----------
    const val WB_KELVIN_MIN = 2_000
    const val WB_KELVIN_MAX = 10_000
    const val WB_KELVIN_DEFAULT = 5_600   // ~daylight

    // ---------- EIS crop ratio ----------
    /**
     * Software EIS removes this fraction of the active array on each axis and uses the margin
     * as motion headroom. 0.20 = 20% crop (spec §6). Tested range 0.15..0.25.
     *
     * - 0.15 → more detail, less stabilization headroom.
     * - 0.20 → balanced (DEFAULT).
     * - 0.25 → aggressive smoothing, looks like gimbal.
     */
    const val EIS_CROP_FRACTION_DEFAULT = 0.20f
    const val EIS_CROP_FRACTION_MIN = 0.10f
    const val EIS_CROP_FRACTION_MAX = 0.30f

    // ---------- AE metering behavior ----------
    /**
     * The GN3 pipeline likes CENTER_WEIGHTED metering for video — SPOT tends to hunt in mixed light.
     * Used when setting CONTROL_AE_PRECAPTURE_TRIGGER / regions.
     */
    const val AE_ANTIBANDING_MODE = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
    const val AE_METERING_MODE_CENTER_WEIGHTED = 1   // documentation-only; region math lives in CameraController

    // ---------- Preset bundles ----------

    data class AePreset(val label: String, val iso: Int, val shutterNs: Long, val evSteps: Int = 0)
    data class WbPreset(val label: String, val kelvin: Int? = null, val awbMode: Int? = null)

    /**
     * Hand-tuned exposure starting points for common S23 FE scenarios.
     * Pair them with a matching [WbPreset] for instant look presets.
     */
    val AE_PRESETS: List<AePreset> = listOf(
        AePreset("Daylight",       iso = 100,  shutterNs = shutterFor180(60)),
        AePreset("Golden Hour",    iso = 200,  shutterNs = shutterFor180(30)),
        AePreset("Overcast",       iso = 200,  shutterNs = shutterFor180(60), evSteps = +2),
        AePreset("Indoor",         iso = 800,  shutterNs = shutterFor180(30)),
        AePreset("Low Light",      iso = 1600, shutterNs = shutterFor180(30), evSteps = +4),
        AePreset("Night (Handheld)", iso = 3200, shutterNs = 33_333_333L), // 1/30s
        AePreset("Neon / City",    iso = 800,  shutterNs = shutterFor180(30), evSteps = -2),
        AePreset("Action (Fast)",  iso = 400,  shutterNs = 2_000_000L),      // 1/500s
    )

    val WB_PRESETS: List<WbPreset> = listOf(
        WbPreset("Auto",     awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO),
        WbPreset("Daylight", kelvin  = 5_600),
        WbPreset("Cloudy",   kelvin  = 6_500),
        WbPreset("Shade",    kelvin  = 7_500),
        WbPreset("Tungsten", kelvin  = 3_200),
        WbPreset("Fluorescent", kelvin = 4_000),
        WbPreset("Golden Hour", kelvin = 4_200),
        WbPreset("Neon",     kelvin  = 3_800),
    )

    /** Default bundle applied on first launch. */
    val DEFAULT_AE = AE_PRESETS[0]
    val DEFAULT_WB = WB_PRESETS[1] // Daylight

    // ---------- Runtime clamping helpers ----------

    fun clampIso(raw: Int, videoMode: Boolean = true): Int =
        raw.coerceIn(ISO_MIN, if (videoMode) ISO_MAX_VIDEO else ISO_MAX_PHOTO)

    fun clampShutter(raw: Long): Long = raw.coerceIn(SHUTTER_MIN_NS, SHUTTER_MAX_NS)

    fun clampFocus(raw: Float): Float = raw.coerceIn(0f, FOCUS_DIOPTER_MAX)

    fun clampEv(raw: Int): Int = raw.coerceIn(EV_STEPS_MIN, EV_STEPS_MAX)

    fun clampKelvin(raw: Int): Int = raw.coerceIn(WB_KELVIN_MIN, WB_KELVIN_MAX)

    fun clampCrop(raw: Float): Float = raw.coerceIn(EIS_CROP_FRACTION_MIN, EIS_CROP_FRACTION_MAX)

    /**
     * When the HAL returns an ISO range, intersect it with our safe envelope.
     * Useful inside CameraController after we read CameraCharacteristics.
     */
    fun intersectIso(hal: Range<Int>?, videoMode: Boolean = true): Range<Int> {
        val lo = maxOf(ISO_MIN, hal?.lower ?: ISO_MIN)
        val hi = minOf(if (videoMode) ISO_MAX_VIDEO else ISO_MAX_PHOTO, hal?.upper ?: ISO_MAX_VIDEO)
        return Range(lo, maxOf(lo, hi))
    }

    fun intersectShutter(hal: Range<Long>?): Range<Long> {
        val lo = maxOf(SHUTTER_MIN_NS, hal?.lower ?: SHUTTER_MIN_NS)
        val hi = minOf(SHUTTER_MAX_NS, hal?.upper ?: SHUTTER_MAX_NS)
        return Range(lo, maxOf(lo, hi))
    }

    /**
     * Convenience: read CameraCharacteristics and return the *effective* (intersected) ranges
     * for this device + our preset bounds.
     */
    data class EffectiveRanges(
        val iso: Range<Int>,
        val shutterNs: Range<Long>,
        val evSteps: Range<Int>,
        val focusDiopters: Range<Float>,
    )

    fun effective(chars: CameraCharacteristics, videoMode: Boolean = true): EffectiveRanges {
        val halIso     = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val halShutter = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val halEv      = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val minFocus   = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: FOCUS_DIOPTER_MAX
        return EffectiveRanges(
            iso = intersectIso(halIso, videoMode),
            shutterNs = intersectShutter(halShutter),
            evSteps = Range(
                maxOf(EV_STEPS_MIN, halEv?.lower ?: EV_STEPS_MIN),
                minOf(EV_STEPS_MAX, halEv?.upper ?: EV_STEPS_MAX),
            ),
            focusDiopters = Range(0f, minOf(FOCUS_DIOPTER_MAX, minFocus)),
        )
    }
}
