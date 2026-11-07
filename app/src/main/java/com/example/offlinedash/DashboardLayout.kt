package com.example.offlinedash

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale
import kotlin.math.min

/**
 * 仪表盘根布局 —— 车机性能模式排版。
 *
 * 信息层级（从主到次）：
 *  1. **速度数字**：占据屏幕短边的 28%，是整个界面唯一的视觉主体；
 *  2. 单位 km/h 与细长的速度光条；
 *  3. 一行小字：速度来源 + 合 G 值；
 *  4. G 力圆盘（短边的 35%）；
 *  5. 右上角紧凑状态面板、左上角横竖屏切换按钮。
 *
 * 竖屏时速度居中偏上、圆盘在下方；横屏时速度靠左、圆盘靠右，避免横向被压扁。
 */
class DashboardLayout(context: Context) : ViewGroup(context) {

    private val density = resources.displayMetrics.density

    private val background = SpeedBackgroundView(context)
    private val gForceView = GForceView(context)
    private val gnssPanel = GnssPanelView(context)
    private val speedBar = SpeedBarView(context)

    /** 点击左上角按钮时回调，由 Activity 切换方向 */
    var orientationToggleListener: (() -> Unit)? = null

    private val orientationButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_rotate)
        setBackgroundResource(R.drawable.toggle_bg)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.orientation_toggle)
        isClickable = true
        val p = (10 * density).toInt()
        setPadding(p, p, p, p)
    }

    private val speedText = TextView(context).apply {
        setTextColor(0xFFFFD60A.toInt())
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
        gravity = Gravity.CENTER
        text = "0"
    }

    private val unitText = TextView(context).apply {
        setTextColor(0xFFFFB300.toInt())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        gravity = Gravity.CENTER
        letterSpacing = 0.14f
        text = context.getString(R.string.unit_kmh)
    }

    private val infoText = TextView(context).apply {
        setTextColor(0xFFA8894E.toInt())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        gravity = Gravity.CENTER
        text = context.getString(R.string.source_short_none)
    }

    private var lastBase = -1
    private var lastLandscape = false
    private var updating = false

    private val textUpdater = object : Runnable {
        override fun run() {
            refreshTexts()
            gnssPanel.invalidate()
            if (updating) postDelayed(this, 33L)
        }
    }

    init {
        addView(background)
        addView(gForceView)
        addView(speedBar)
        addView(speedText)
        addView(unitText)
        addView(infoText)
        addView(gnssPanel)
        addView(orientationButton)
        orientationButton.setOnClickListener { orientationToggleListener?.invoke() }
        refreshTexts()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!updating) {
            updating = true
            postDelayed(textUpdater, 33L)
        }
    }

    override fun onDetachedFromWindow() {
        updating = false
        removeCallbacks(textUpdater)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w <= 0 || h <= 0) {
            setMeasuredDimension(w, h)
            return
        }

        val landscape = w > h
        val base = min(w, h)

        if (base != lastBase || landscape != lastLandscape) {
            lastBase = base
            lastLandscape = landscape

            // 速度数字：Boost 模式风格，占屏幕短边 46%，是界面上绝对的主体
            val availableTextWidth = w * (if (landscape) 0.46f else 0.92f)
            val heroSize = if (landscape) {
                min(availableTextWidth * 0.46f, h * 0.44f)
            } else {
                min(base * 0.46f, availableTextWidth * 0.5f)
            }
            speedText.setTextSize(TypedValue.COMPLEX_UNIT_PX, heroSize)
            unitText.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                maxOf(heroSize * 0.17f, 13f * density)
            )
            infoText.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                maxOf(heroSize * 0.078f, 11f * density)
            )
        }

        background.measure(exact(w), exact(h))

        val toggleSize = (42 * density).toInt()
        orientationButton.measure(exact(toggleSize), exact(toggleSize))

        gnssPanel.measure(
            MeasureSpec.makeMeasureSpec((w * 0.55f).toInt(), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec((h * 0.25f).toInt(), MeasureSpec.AT_MOST)
        )

        val wrapW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST)
        val wrapH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
        speedText.measure(wrapW, wrapH)
        unitText.measure(wrapW, wrapH)
        infoText.measure(wrapW, wrapH)

        val barWidth = (w * (if (landscape) 0.34f else 0.62f)).toInt()
        speedBar.measure(exact(barWidth), exact((9 * density).toInt()))

        // G 力圆盘：横屏独占右半屏（高度 84%）；竖屏取宽度 62%，与横屏视觉占比协调
        val side = if (landscape) {
            min(w * 0.46f, h * 0.84f)
        } else {
            min(w * 0.62f, h * 0.32f)
        }.toInt().coerceAtLeast(140)
        gForceView.measure(exact(side), exact(side))

        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return

        val landscape = w > h
        background.layout(0, 0, w, h)
        background.setFocus(
            if (landscape) 0.26f else 0.5f,
            if (landscape) 0.30f else 0.30f
        )

        val edge = (w * (if (landscape) 0.022f else 0.04f)).toInt()

        // 卫星面板：横屏放左上角；竖屏因前置摄像头在左上角，改放右上角
        val panelLeft = if (landscape) edge else (w - edge - gnssPanel.measuredWidth)
        gnssPanel.layout(
            panelLeft, edge,
            panelLeft + gnssPanel.measuredWidth, edge + gnssPanel.measuredHeight
        )

        // 横竖屏切换按钮在卫星面板下方（与面板同一侧对齐）
        val toggleTop = edge + gnssPanel.measuredHeight + (8 * density).toInt()
        val toggleLeft = if (landscape) edge else (w - edge - orientationButton.measuredWidth)
        orientationButton.layout(
            toggleLeft, toggleTop,
            toggleLeft + orientationButton.measuredWidth, toggleTop + orientationButton.measuredHeight
        )

        // 速度数字所在的水平中轴：竖屏居中，横屏靠左
        val centerX = if (landscape) (w * 0.26f).toInt() else w / 2

        val heroTop = (h * (if (landscape) 0.20f else 0.19f)).toInt()
        speedText.layout(
            centerX - speedText.measuredWidth / 2, heroTop,
            centerX + speedText.measuredWidth / 2, heroTop + speedText.measuredHeight
        )

        val unitTop = speedText.bottom + (h * 0.006f).toInt()
        unitText.layout(
            centerX - unitText.measuredWidth / 2, unitTop,
            centerX + unitText.measuredWidth / 2, unitTop + unitText.measuredHeight
        )

        val barTop = unitText.bottom + (h * 0.022f).toInt()
        speedBar.layout(
            centerX - speedBar.measuredWidth / 2, barTop,
            centerX + speedBar.measuredWidth / 2, barTop + speedBar.measuredHeight
        )

        val infoTop = speedBar.bottom + (h * 0.014f).toInt()
        infoText.layout(
            centerX - infoText.measuredWidth / 2, infoTop,
            centerX + infoText.measuredWidth / 2, infoTop + infoText.measuredHeight
        )

        // G 力圆盘
        val half = gForceView.measuredWidth / 2
        val dialCx: Int
        val dialCy: Int
        if (landscape) {
            dialCx = (w * 0.75f).toInt()
            dialCy = (h * 0.50f).toInt()
        } else {
            dialCx = w / 2
            dialCy = (infoText.bottom + h) / 2
        }
        gForceView.layout(dialCx - half, dialCy - half, dialCx + half, dialCy + half)
    }

    private fun refreshTexts() {
        val speed = if (Telemetry.hasAccel || Telemetry.source != Telemetry.SOURCE_NONE) {
            Telemetry.speedKmh
        } else {
            0f
        }
        speedText.text = String.format(Locale.US, "%.0f", speed)

        val source = when (Telemetry.source) {
            Telemetry.SOURCE_GPS -> context.getString(R.string.source_short_gps)
            Telemetry.SOURCE_ESTIMATED -> context.getString(R.string.source_short_estimated)
            else -> context.getString(R.string.source_short_none)
        }
        val total = if (Telemetry.hasAccel) Telemetry.gTotal else 0f
        infoText.text = String.format(Locale.US, "%s   %.2f G", source, total)
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
