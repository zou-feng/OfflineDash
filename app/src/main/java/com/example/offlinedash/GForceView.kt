package com.example.offlinedash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

/**
 * G 力圆盘（赛车游戏仪表盘风格，参考地平线 4/5 的 G 力表）。
 *
 * 数据来自真实加速度计（见 [MotionEngine]），不使用陀螺仪：
 *  - 满量程 1.5G，内部刻度环对应 0.5G / 1.0G，日常驾驶小球在中段活动，
 *    急转急刹才会靠近边缘；
 *  - 从圆心到小球画一根**矢量线**，直观表示 G 力的大小与方向；
 *  - 小球带一条渐隐的**折线轨迹**，记录最近的受力路径；
 *  - 十字准线辅助判读，小球始终被限制在圆框内。
 */
class GForceView(context: Context) : View(context) {

    private companion object {
        /** 外圈（触底）对应的 G 值：小球贴到圈圈上即为 1.5G */
        const val FULL_SCALE_G = 1.5f

        /** 0.5G / 1.0G 两道参考刻度环（外圈即 1.5G） */
        const val RING_ONE_G = 0.5f
        const val RING_TWO_G = 1.0f

        /** 轨迹采样点数量（约 1 秒） */
        const val TRAIL_LENGTH = 56
    }

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val discBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** 轨迹环形缓冲，存归一化坐标（-1..1） */
    private val trailX = FloatArray(TRAIL_LENGTH)
    private val trailY = FloatArray(TRAIL_LENGTH)
    private var trailCount = 0
    private var trailHead = 0

    private var ballX = 0f
    private var ballY = 0f
    private var initialized = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val strokeWidth = 3.2f * density
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f - strokeWidth
        if (radius <= 0f) return
        val ballRadius = radius * 0.046f

        // 目标位置：除以满量程后限制在单位圆内（斜向也不会超界）
        var tx = Telemetry.gLateral / FULL_SCALE_G
        var ty = -Telemetry.gLongitudinal / FULL_SCALE_G
        val magnitude = hypot(tx, ty)
        if (magnitude > 1f) {
            tx /= magnitude
            ty /= magnitude
        }

        if (!initialized) {
            ballX = tx
            ballY = ty
            initialized = true
        }
        ballX += (tx - ballX) * 0.18f
        ballY += (ty - ballY) * 0.18f

        pushTrail(ballX, ballY)

        val limit = radius - ballRadius - strokeWidth * 0.5f

        // 外圈
        ringPaint.strokeWidth = strokeWidth
        ringPaint.color = 0xFFFFD60A.toInt()
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // 0.5G / 1.0G 刻度环（外圈即 1.5G）
        innerRingPaint.strokeWidth = 1.1f * density
        innerRingPaint.color = 0x30FFD60A
        canvas.drawCircle(cx, cy, radius * (RING_ONE_G / FULL_SCALE_G), innerRingPaint)
        canvas.drawCircle(cx, cy, radius * (RING_TWO_G / FULL_SCALE_G), innerRingPaint)

        // 刻度数值（画在左上对角线上）
        scalePaint.textSize = radius * 0.085f
        scalePaint.color = 0x88FFE9A3.toInt()
        val diag = 0.7071f
        val r1 = radius * (RING_ONE_G / FULL_SCALE_G)
        val r2 = radius * (RING_TWO_G / FULL_SCALE_G)
        canvas.drawText("0.5", cx - r1 * diag, cy - r1 * diag - radius * 0.02f, scalePaint)
        canvas.drawText("1.0", cx - r2 * diag, cy - r2 * diag - radius * 0.02f, scalePaint)

        // 十字准线
        crossPaint.strokeWidth = 1.1f * density
        crossPaint.color = 0x44FFE9A3
        canvas.drawLine(cx, cy - radius + strokeWidth, cx, cy + radius - strokeWidth, crossPaint)
        canvas.drawLine(cx - radius + strokeWidth, cy, cx + radius - strokeWidth, cy, crossPaint)

        // 渐隐折线轨迹：越旧越淡越细
        trailPaint.color = 0xFFFFD60A.toInt()
        for (i in 1 until trailCount) {
            val newer = trailIndex(i - 1)
            val older = trailIndex(i)
            val age = i.toFloat() / TRAIL_LENGTH
            val fade = (1f - age) * (1f - age)
            trailPaint.alpha = (fade * 150f).toInt()
            trailPaint.strokeWidth = (0.8f + 2.4f * fade) * density
            canvas.drawLine(
                cx + trailX[older] * limit, cy + trailY[older] * limit,
                cx + trailX[newer] * limit, cy + trailY[newer] * limit,
                trailPaint
            )
        }

        // 圆心读盘：深色圆底上显示合 G 值
        val bx = cx + ballX * limit
        val by = cy + ballY * limit
        val discRadius = radius * 0.175f

        discPaint.color = 0xE60C0900.toInt()
        canvas.drawCircle(cx, cy, discRadius, discPaint)
        discBorderPaint.strokeWidth = 1.2f * density
        discBorderPaint.color = 0x3DFFD60A
        canvas.drawCircle(cx, cy, discRadius, discBorderPaint)

        val gValue = if (Telemetry.hasAccel) Telemetry.gTotal else 0f
        valuePaint.textSize = radius * 0.115f
        valuePaint.color = 0xFFFFF3C4.toInt()
        canvas.drawText(
            String.format(Locale.US, "%.2f", gValue),
            cx, cy + radius * 0.035f, valuePaint
        )
        unitPaint.textSize = radius * 0.062f
        unitPaint.color = 0xB3FFB300.toInt()
        canvas.drawText("G", cx, cy + radius * 0.128f, unitPaint)

        // 圆心 → 小球的 G 力矢量线（从读盘边缘起画，不压住数值）
        val vdx = bx - cx
        val vdy = by - cy
        val vectorLength = hypot(vdx, vdy)
        if (vectorLength > discRadius + ballRadius * 0.5f) {
            val ux = vdx / vectorLength
            val uy = vdy / vectorLength
            vectorPaint.color = 0xFFFFD60A.toInt()
            vectorPaint.alpha = 225
            vectorPaint.strokeWidth = 2.2f * density
            canvas.drawLine(cx + ux * discRadius, cy + uy * discRadius, bx, by, vectorPaint)
        }

        // 小球
        ballPaint.color = 0x33FFD60A
        canvas.drawCircle(bx, by, ballRadius * 2.0f, ballPaint)
        ballPaint.color = 0xFFFFD60A.toInt()
        canvas.drawCircle(bx, by, ballRadius, ballPaint)

        if (isAttachedToWindow) {
            postInvalidateOnAnimation()
        }
    }

    /** 第 k 新的轨迹点（k=0 为最新） */
    private fun trailIndex(k: Int): Int = (trailHead - 1 - k + TRAIL_LENGTH * 2) % TRAIL_LENGTH

    private fun pushTrail(x: Float, y: Float) {
        trailX[trailHead] = x
        trailY[trailHead] = y
        trailHead = (trailHead + 1) % TRAIL_LENGTH
        if (trailCount < TRAIL_LENGTH) trailCount++
    }
}
