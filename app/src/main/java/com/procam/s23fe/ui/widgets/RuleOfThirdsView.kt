package com.procam.s23fe.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Overlay that draws the classic 3×3 rule-of-thirds grid plus a faint central
 * horizontal line (horizon aid).
 */
class RuleOfThirdsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val line = Paint().apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val horizon = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 1.0f
    }

    var showGrid: Boolean = true
        set(v) { field = v; invalidate() }

    var showHorizon: Boolean = true
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (showGrid) {
            val third = 1f / 3f
            canvas.drawLine(w * third, 0f, w * third, h, line)
            canvas.drawLine(w * (2 * third), 0f, w * (2 * third), h, line)
            canvas.drawLine(0f, h * third, w, h * third, line)
            canvas.drawLine(0f, h * (2 * third), w, h * (2 * third), line)
        }
        if (showHorizon) canvas.drawLine(0f, h / 2f, w, h / 2f, horizon)
    }
}
