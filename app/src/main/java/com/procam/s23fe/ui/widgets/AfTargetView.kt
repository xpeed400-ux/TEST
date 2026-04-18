package com.procam.s23fe.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Animated AF reticle that pulses briefly after a focus tap. */
class AfTargetView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        isAntiAlias = true
    }

    private var cx = -1f; private var cy = -1f
    private var scale = 1f
    private var alphaPct = 0f

    fun show(x: Float, y: Float) {
        cx = x; cy = y
        animateReticle()
    }

    private fun animateReticle() {
        ValueAnimator.ofFloat(1.4f, 1f).apply {
            duration = 220
            addUpdateListener {
                scale = it.animatedValue as Float
                alphaPct = 1f - (it.animatedFraction * 0.2f)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cx < 0) return
        paint.alpha = (alphaPct * 255).toInt().coerceIn(0, 255)
        val r = 40f * scale
        canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint)
        canvas.drawLine(cx - r * 1.3f, cy, cx - r * 0.9f, cy, paint)
        canvas.drawLine(cx + r * 0.9f, cy, cx + r * 1.3f, cy, paint)
        canvas.drawLine(cx, cy - r * 1.3f, cx, cy - r * 0.9f, paint)
        canvas.drawLine(cx, cy + r * 0.9f, cx, cy + r * 1.3f, paint)
    }
}
