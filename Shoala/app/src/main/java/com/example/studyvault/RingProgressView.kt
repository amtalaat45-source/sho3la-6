package com.example.studyvault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Circular progress ring shown behind the timer digits on the focus screen. */
class RingProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokePx = resources.displayMetrics.density * 8f
    private val oval = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = ContextCompat.getColor(context, R.color.surface_highest)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 70
    }

    /** 0..100 */
    var progress: Int = 0
        set(value) {
            val v = value.coerceIn(0, 100)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    /** false = no target (count-up mode): draw a calm, full, faint ring instead of an arc. */
    var showsProgress: Boolean = true
        set(value) {
            if (value != field) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = strokePx / 2f
        oval.set(
            paddingLeft + half,
            paddingTop + half,
            width - paddingRight - half,
            height - paddingBottom - half
        )
        canvas.drawOval(oval, trackPaint)
        if (!showsProgress) {
            canvas.drawOval(oval, idlePaint)
        } else if (progress > 0) {
            canvas.drawArc(oval, -90f, 360f * progress / 100f, false, progressPaint)
        }
    }
}
