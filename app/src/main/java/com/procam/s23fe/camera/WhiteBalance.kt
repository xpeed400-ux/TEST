package com.procam.s23fe.camera

import android.hardware.camera2.params.RggbChannelVector

/**
 * Very small Kelvin → RGGB-gain approximation that's "good enough" for live WB dial UX.
 * Real production code should calibrate per-sensor using COLOR_CORRECTION_TRANSFORM,
 * but this table gives a natural warm/cool feel from ~2000K to ~10000K.
 */
object WhiteBalance {

    fun gainsForKelvin(kelvin: Int): RggbChannelVector {
        val k = kelvin.coerceIn(2000, 10000).toDouble() / 100.0
        // Tanner Helland approximation, clamped
        val r = when {
            k <= 66 -> 1.0
            else -> (329.698727446 * Math.pow(k - 60, -0.1332047592)) / 255.0
        }
        val g = when {
            k <= 66 -> (99.4708025861 * Math.log(k) - 161.1195681661) / 255.0
            else -> (288.1221695283 * Math.pow(k - 60, -0.0755148492)) / 255.0
        }
        val b = when {
            k >= 66 -> 1.0
            k <= 19 -> 0.0
            else -> (138.5177312231 * Math.log(k - 10) - 305.0447927307) / 255.0
        }

        // Convert white-point RGB into per-channel gains (inverse, normalized to green = 1.0)
        val gr = (1.0 / r.coerceAtLeast(0.05)).toFloat()
        val gg = (1.0 / g.coerceAtLeast(0.05)).toFloat()
        val gb = (1.0 / b.coerceAtLeast(0.05)).toFloat()
        val norm = gg
        return RggbChannelVector(gr / norm, 1f, 1f, gb / norm)
    }
}
