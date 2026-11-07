package com.example.offlinedash

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 黑金背景 + 自由漂浮粒子。
 *
 * 粒子是全屏随机散布的浮动光点，只有两个运动来源：
 *  - 自身极缓慢的漂移（静止时安静地浮着）；
 *  - 跟随 GPS 真实车速向屏幕下方「向后」掠过，开车越快退得越快。
 *
 * 粒子不听陀螺仪/加速度计，手机怎么转都不影响它的流动方向。
 * 配色统一在暗金（尘埃质感），细小、暗淡、无光晕。全部用 Canvas 实时绘制，不使用图片。
 */
class SpeedBackgroundView(context: Context) : View(context) {

    private companion object {
        const val PARTICLE_COUNT = 380

        const val DUST_HI = 0xFFA8894E.toInt()
        const val DUST_MID = 0xFF7A5E28.toInt()
        const val DUST_LO = 0xFF54401B.toInt()

        /** 速度带来的持续后退（归一化宽度/秒，每 km/h），只来自 GPS 真实车速 */
        const val FLOW_PER_KMH = 0.005f

        /** 粒子自身漂移的上限（归一秒），静止时轻微漂浮即可 */
        const val DRIFT_MARGIN = 0.02f
    }

    private class Dot(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var size: Float,
        var depth: Float,
        var tint: Int,
        var baseAlpha: Float
    )

    private val density = resources.displayMetrics.density
    private val random = Random(System.nanoTime())

    private val dots = ArrayList<Dot>(PARTICLE_COUNT)

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var animator: ValueAnimator? = null
    private var lastFrameNs = 0L
    private var ready = false
    private var focusX = 0.5f
    private var focusY = 0.30f
    private var glowCx = 0f
    private var glowCy = 0f
    private var glowRadius = 1f
    private var glowShader: RadialGradient? = null

    /** 粒子场的整体后退偏移（归一化），只由 GPS 车速驱动 */
    private var offsetY = 0f

    /** 柔光中心（归一化坐标），由布局指定，通常放在速度数字背后 */
    fun setFocus(nx: Float, ny: Float) {
        if (nx == focusX && ny == focusY) return
        focusX = nx
        focusY = ny
        ready = false
        invalidate()
    }

    init {
        repeat(PARTICLE_COUNT) {
            val tint = when (random.nextInt(3)) {
                0 -> DUST_HI
                1 -> DUST_MID
                else -> DUST_LO
            }
            dots.add(
                Dot(
                    x = random.nextFloat(),
                    y = random.nextFloat(),
                    vx = (random.nextFloat() - 0.5f) * 2f * DRIFT_MARGIN,
                    vy = (random.nextFloat() - 0.5f) * 2f * DRIFT_MARGIN,
                    size = 0.9f + random.nextFloat() * 1.6f,
                    depth = 0.55f + random.nextFloat() * 0.75f,
                    tint = tint,
                    baseAlpha = 15f + random.nextFloat() * 30f
                )
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ready = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFrameLoop()
    }

    override fun onDetachedFromWindow() {
        stopFrameLoop()
        super.onDetachedFromWindow()
    }

    private fun startFrameLoop() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun stopFrameLoop() {
        animator?.cancel()
        animator = null
        lastFrameNs = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (!ready) {
            buildBase(w, h)
            ready = true
        }
        canvas.drawRect(0f, 0f, w, h, basePaint)

        val speed = Telemetry.speedKmh
        val energy = (speed / 160f).coerceIn(0f, 1f)

        // 速度数字背后的金色柔光，强度随速度提升，带极轻微呼吸
        val phase = (System.nanoTime() % 3_000_000_000L) / 3_000_000_000f
        val breathe = 0.5f + 0.5f * sin(phase * 6.2831855f)
        glowPaint.alpha = (10f + energy * 58f + breathe * 6f).toInt()
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f
        else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNs = now

        updateFieldOffset(dt, speed)
        drawDots(canvas, w, h, dt, energy)
    }

    private fun buildBase(w: Float, h: Float) {
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF0C0C0C.toInt(), 0xFF050505.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        glowCx = w * focusX
        glowCy = h * focusY
        glowRadius = hypot(w * 0.62f, h * 0.45f) / 2f
        glowShader = RadialGradient(
            glowCx, glowCy, glowRadius,
            intArrayOf(0xFFFFC53D.toInt(), 0x66FFA000.toInt(), 0x00FFA000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = glowShader
    }

    /**
     * 更新粒子场偏移：只跟随 GPS 真实车速，固定向屏幕下方「向后」掠过。
     * 停车时几乎不动，开车越快退得越快。不再受手机陀螺仪/转动影响。
     */
    private fun updateFieldOffset(dt: Float, speed: Float) {
        offsetY += speed * FLOW_PER_KMH * dt
    }

    private fun drawDots(canvas: Canvas, w: Float, h: Float, dt: Float, energy: Float) {
        val boost = 1f + energy * 0.8f

        for (d in dots) {
            // 自身的缓慢漂浮
            d.x += d.vx * dt
            d.y += d.vy * dt
            if (d.x < 0f) d.x += 1f
            if (d.x > 1f) d.x -= 1f
            if (d.y < 0f) d.y += 1f
            if (d.y > 1f) d.y -= 1f

            // 叠加整体后退偏移后循环包裹
            val x = d.x
            var y = d.y + offsetY * d.depth
            y -= floor(y)

            val px = x * w
            val py = y * h
            val radius = d.size * density
            val alpha = (d.baseAlpha * boost).coerceIn(0f, 90f).toInt()

            dotPaint.color = d.tint
            dotPaint.alpha = alpha
            canvas.drawCircle(px, py, radius, dotPaint)
        }
    }
}
