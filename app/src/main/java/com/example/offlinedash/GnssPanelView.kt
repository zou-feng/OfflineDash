package com.example.offlinedash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * 右上角状态面板（紧凑型）。
 *
 * 尺寸完全按内容自适应，只占右上角一小块，缺什么直接显示什么：
 *  - 定位权限缺失 → 「定位: 无权限」
 *  - GPS 总开关关闭 → 「GPS 关闭」
 *  - 无 GPS 硬件 → 「无 GPS 硬件」
 *  - 通知权限缺失 → 「通知: 未授权」
 *  - 正常 → 「卫星: N」+「信号: 强/中/弱」与 5 格条形图
 */
class GnssPanelView(context: Context) : View(context) {

    private class Row(val text: String, val bars: Int, val color: Int)

    private val density = resources.displayMetrics.density

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val rect = RectF()

    /** 紧凑排版参数 */
    private val padH = 9f * density
    private val padV = 6f * density
    private val textSize = 11f * density

    private val okColor = 0xFFFFE9A3.toInt()
    private val warnColor = 0xFFFFB300.toInt()

    init {
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = textSize
    }

    private fun barWidth() = textSize * 0.28f
    private fun barGap() = textSize * 0.13f
    private fun barArea() = barWidth() * 5 + barGap() * 4 + textSize * 0.30f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = buildRows()
        textPaint.textSize = textSize

        var maxWidth = 0f
        for (row in rows) {
            var w = textPaint.measureText(row.text)
            if (row.bars > 0) w += barArea()
            if (w > maxWidth) maxWidth = w
        }

        val desiredWidth = (maxWidth + padH * 2f).toInt()
        val rowHeight = textSize * 1.35f
        val desiredHeight = (rows.size * rowHeight + padV * 2f).toInt()

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val corner = 9f * density
        panelPaint.color = 0x8C120E04.toInt()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, corner, corner, panelPaint)

        borderPaint.strokeWidth = 1f * density
        borderPaint.color = 0x2EFFD60A
        canvas.drawRoundRect(rect, corner, corner, borderPaint)

        val rows = buildRows()
        if (rows.isEmpty()) return

        val rowHeight = (h - padV * 2f) / rows.size
        textPaint.textSize = textSize

        var centerY = padV + rowHeight * 0.5f
        for (row in rows) {
            drawRow(canvas, w, centerY, row)
            centerY += rowHeight
        }
    }

    private fun drawRow(canvas: Canvas, w: Float, centerY: Float, row: Row) {
        val baseline = centerY + textSize * 0.35f
        var textRight = w - padH

        if (row.bars > 0) {
            textRight -= barArea()
        }

        textPaint.color = row.color
        canvas.drawText(row.text, textRight, baseline, textPaint)

        if (row.bars > 0) {
            val barWidth = barWidth()
            val barGap = barGap()
            val maxBarHeight = textSize * 0.85f
            val barBottom = centerY + textSize * 0.38f
            var left = w - padH - (barWidth * 5 + barGap * 4)
            for (i in 0 until 5) {
                val barHeight = maxBarHeight * (0.42f + 0.145f * i)
                rect.set(left, barBottom - barHeight, left + barWidth, barBottom)
                barPaint.color = if (i < row.bars) 0xFFFFD60A.toInt() else 0x33FFD60A
                canvas.drawRoundRect(rect, barWidth * 0.3f, barWidth * 0.3f, barPaint)
                left += barWidth + barGap
            }
        }
    }

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>(3)

        if (!Telemetry.locationGranted) {
            rows.add(Row(context.getString(R.string.perm_location_denied), 0, warnColor))
        } else {
            when (Telemetry.gnssState) {
                Telemetry.GNSS_PROVIDER_OFF ->
                    rows.add(Row(context.getString(R.string.gnss_off), 0, warnColor))

                Telemetry.GNSS_UNSUPPORTED ->
                    rows.add(Row(context.getString(R.string.gnss_unsupported), 0, warnColor))

                Telemetry.GNSS_NO_PERMISSION ->
                    rows.add(Row(context.getString(R.string.perm_location_denied), 0, warnColor))

                else -> {
                    rows.add(
                        Row(context.getString(R.string.gnss_sats, Telemetry.satsUsed), 0, okColor)
                    )
                    rows.add(
                        Row(context.getString(R.string.gnss_beidou, Telemetry.bdsUsed), 0, okColor)
                    )
                    rows.add(
                        Row(
                            context.getString(
                                R.string.gnss_signal,
                                signalWord(Telemetry.signalBars)
                            ),
                            Telemetry.signalBars, okColor
                        )
                    )
                }
            }
        }

        if (!Telemetry.notificationGranted) {
            rows.add(Row(context.getString(R.string.perm_notification_denied), 0, warnColor))
        }

        return rows
    }

    private fun signalWord(bars: Int): String = when {
        bars >= 4 -> context.getString(R.string.signal_strong)
        bars == 3 -> context.getString(R.string.signal_medium)
        bars >= 1 -> context.getString(R.string.signal_weak)
        else -> context.getString(R.string.signal_none)
    }
}
