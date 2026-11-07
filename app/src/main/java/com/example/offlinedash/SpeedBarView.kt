package com.example.offlinedash

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/**
 * 速度进度条：速度数字下方的细长光带，0~[maxKmh] 填充。
 * 车机性能模式下常见的极简速度指示，用渐变 + 头部光点表现。
 */
class SpeedBarView(context: Context) : View(context) {

    /** 满格对应的速度（km/h） */
    var maxKmh: Float = 240f

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val rect = RectF()
    private var gradient: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(0xFF8A6A00.toInt(), 0xFFFFC107.toInt(), 0xFFFFE9A3.toInt()),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val thickness = min(h, 7f * density)
        val top = (h - thickness) / 2f
        val radius = thickness / 2f

        trackPaint.color = 0x22FFD60A
        rect.set(0f, top, w, top + thickness)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        val progress = (Telemetry.speedKmh / maxKmh).coerceIn(0f, 1f)
        if (progress > 0.002f) {
            val head = (w * progress).coerceAtLeast(thickness)
            fillPaint.shader = gradient
            rect.set(0f, top, head, top + thickness)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            headPaint.color = 0x66FFD60A
            canvas.drawCircle(head, h / 2f, thickness * 1.7f, headPaint)
            fillPaint.shader = null
        }

        if (isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }
}
