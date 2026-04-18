package com.procam.s23fe.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Implements the "온도 과열 → 비트레이트 감소" rule from section 14 of the spec.
 *
 * Returns an **effective bitrate** given a requested bitrate and the current thermal
 * status. Designed to be called once right before [ProCamRecorder.prepare].
 */
class ThermalManager(context: Context) {

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** 0 = NONE, 1 = LIGHT, 2 = MODERATE, 3 = SEVERE, 4 = CRITICAL … */
    fun currentStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0

    /**
     * Derates a requested bitrate based on thermal status.
     *   NONE/LIGHT  → 100 %
     *   MODERATE    →  80 %    (e.g. 100 Mbps → 80 Mbps)
     *   SEVERE+     →  60 %
     */
    fun effectiveBitrate(requested: Int): Int = when (currentStatus()) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT     -> requested
        PowerManager.THERMAL_STATUS_MODERATE  -> (requested * 0.80f).toInt()
        else                                  -> (requested * 0.60f).toInt()
    }
}
