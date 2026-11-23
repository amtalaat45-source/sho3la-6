package com.example.studyvault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Bar chart for the stats page. Flat colours: today's bar is full accent,
 * the other days are a quieter tint of the same colour.
 */
class WeeklyBarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: List<Pair<String, Long>> = emptyList()
    private var isMonthlyMode: Boolean = false

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val accentColor = ContextCompat.getColor(context, R.color.accent)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_high)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val labelColor = ContextCompat.getColor(context, R.color.text_tertiary)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val valueColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val valueColorToday = ContextCompat.getColor(context, R.color.text_primary)

    fun setData(chartData: List<Pair<String, Long>>, isMonthly: Boolean = false) {
        this.data = chartData
        this.isMonthlyMode = isMonthly
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty() || width == 0 || height == 0) return

        val usableWidth = (width - paddingLeft - paddingRight).toFloat()
        if (usableWidth <= 0f) return

        valuePaint.textSize = sp(10f)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

        val highestMinutes = data.maxOf { it.second }
        val maxVal = maxOf(60L, highestMinutes)
        val chartBottom = height - paddingBottom - dp(20f)
        val chartTop = paddingTop + dp(18f)
        val chartHeight = maxOf(dp(4f), chartBottom - chartTop)

        val todayIndex = if (isMonthlyMode) {
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH) - 1
        } else {
            data.size - 1
        }

        val colWidth = usableWidth / data.size
        val barWidth = if (isMonthlyMode) colWidth * 0.62f else colWidth * 0.46f
        val cornerRadius = if (isMonthlyMode) dp(2f) else dp(6f)
        val labelY = height - paddingBottom - dp(4f)

        data.forEachIndexed { index, (label, minutes) ->
            val isToday = index == todayIndex
            val pos = if (rtl) data.size - 1 - index else index
            val centerX = paddingLeft + (pos * colWidth) + (colWidth / 2f)
            val left = centerX - (barWidth / 2f)
            val right = centerX + (barWidth / 2f)

            canvas.drawRoundRect(
                RectF(left, chartTop, right, chartBottom),
                cornerRadius, cornerRadius, trackPaint
            )

            if (minutes > 0) {
                val barHeight = (minutes.toFloat() / maxVal.toFloat()) * chartHeight
                val top = chartBottom - barHeight

                barPaint.color = accentColor
                barPaint.alpha = if (isToday) 255 else 110
                canvas.drawRoundRect(RectF(left, top, right, chartBottom), cornerRadius, cornerRadius, barPaint)

                if (!isMonthlyMode || minutes == highestMinutes) {
                    valuePaint.color = if (isToday) valueColorToday else valueColor
                    canvas.drawText("${minutes}د", centerX, top - dp(5f), valuePaint)
                }
            }

            labelPaint.color = if (isToday) valueColorToday else labelColor
            if (!isMonthlyMode) {
                labelPaint.textSize = sp(11f)
                canvas.drawText(label, centerX, labelY, labelPaint)
            } else {
                val dayNum = index + 1
                val show = dayNum == 1 || dayNum == data.size ||
                        (dayNum % 5 == 0 && data.size - dayNum >= 2)   // avoids 30/31 overlap
                if (show) {
                    labelPaint.textSize = sp(9f)
                    canvas.drawText(label, centerX, labelY, labelPaint)
                }
            }
        }
    }
}
