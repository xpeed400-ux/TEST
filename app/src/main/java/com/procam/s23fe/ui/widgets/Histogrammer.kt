package com.procam.s23fe.ui.widgets

import android.graphics.ImageFormat
import android.media.Image

/**
 * Builds a 256-bin luminance histogram from the Y plane of a YUV_420_888 image.
 * Cheap enough to run on every ~10th preview frame on an Exynos 2200.
 */
object Histogrammer {

    fun fromYPlane(image: Image, downsample: Int = 8): IntArray {
        require(image.format == ImageFormat.YUV_420_888) { "Need YUV_420_888 input" }
        val y = image.planes[0]
        val buf = y.buffer
        val rowStride = y.rowStride
        val pixelStride = y.pixelStride
        val w = image.width; val h = image.height
        val hist = IntArray(256)
        val row = ByteArray(rowStride)
        var r = 0
        while (r < h) {
            buf.position(r * rowStride)
            val toRead = minOf(rowStride, buf.remaining())
            buf.get(row, 0, toRead)
            var c = 0
            while (c < w) {
                val v = row[c * pixelStride].toInt() and 0xFF
                hist[v]++
                c += downsample
            }
            r += downsample
        }
        return hist
    }
}
