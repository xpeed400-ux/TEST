package com.procam.s23fe.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Luminance histogram widget. Call [submit] from your frame pipeline with a
 * 256-entry long histogram; the view takes care of normalization & drawing.
 */
class HistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bg = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val fg = Paint().apply { color = Color.argb(230, 255, 255, 255); strokeWidth = 1f }
    private var bins: IntArray = IntArray(256)
    private var maxBin = 1

    fun submit(histogram: IntArray) {
        if (histogram.size != 256) return
        bins = histogram.copyOf()
        maxBin = (bins.max().takeIf { it > 0 }) ?: 1
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        val step = w / 256f
        for (i in 0 until 256) {
            val v = h * (bins[i].toFloat() / maxBin)
            canvas.drawLine(i * step, h, i * step, h - v, fg)
        }
    }
}
