package com.example.offlinedash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 运动引擎。
 *
 * 速度：只认 GPS（[onGpsSpeed]）。GPS 掉线平滑归零，绝不用加速度计估算速度。
 *
 * G 力：扣掉重力后的平移加速度，按**屏幕（内容）坐标系**映射成横向 / 纵向。
 * 「内容坐标系」= 用户实际看到的方向：内容上方 = [GForceView] 里「前」标签所指的方向，
 * 内容右方 = 「右」标签所指的方向。显示方向由 [screenRotationFromDisplay] 给出，因此：
 *  - 横屏 / 竖屏都能保证「朝屏幕上的某个标签方向推，球就往那个方向走」，不会轴互换；
 *  - 不依赖任何安装姿态假设，也不需要 GPS 航向或标定。
 * 屏幕法线方向的分量（竖直放时车头加速度落在 -Z 上）无法在 2D 圆盘上表示，直接忽略。
 */
class MotionEngine(context: Context) : SensorEventListener {

    private companion object {
        const val G = 9.80665f
        const val GRAVITY_TAU = 1.2f
        const val GPS_FRESH_MS = 4000L
        const val GPS_MAX_ACCURACY = 100f
        const val TICK_MS = 33L
    }

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var sensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 重力估计（设备坐标系），低通得到 */
    private val gravity = FloatArray(3)
    private var gravityReady = false

    private var lastSensorNs = 0L

    @Volatile
    private var gpsSpeedMs = -1f

    @Volatile
    private var gpsAccuracyM = 999f

    @Volatile
    private var gpsStampMs = 0L

    private var smoothSpeed = 0f
    private var smoothTotal = 1f
    private var smoothLat = 0f
    private var smoothLong = 0f

    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true

        try {
            val sm = sensorManager
            if (sm != null) {
                sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    ?: sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

                sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                Telemetry.hasAccel = sensor != null
            }
        } catch (t: Throwable) {
            Telemetry.hasAccel = false
        }

        handler.post(ticker)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        try {
            sensorManager?.unregisterListener(this)
        } catch (t: Throwable) {
            // ignore
        }
        gravityReady = false
        smoothSpeed = 0f
        gpsSpeedMs = -1f
    }

    /** 由定位回调注入 GPS 速度（m/s）。 */
    fun onGpsSpeed(speedMs: Float, accuracyM: Float, elapsedRealtimeMs: Long) {
        if (speedMs < 0f) return
        gpsSpeedMs = speedMs
        gpsAccuracyM = accuracyM
        gpsStampMs = elapsedRealtimeMs
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    handleAcceleration(event.values[0], event.values[1], event.values[2], raw = true)

                Sensor.TYPE_LINEAR_ACCELERATION ->
                    handleAcceleration(event.values[0], event.values[1], event.values[2], raw = false)
            }
        } catch (t: Throwable) {
            // 单次异常不影响整体
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleAcceleration(rx: Float, ry: Float, rz: Float, raw: Boolean) {
        val dt = dtFromNow()

        val lx: Float
        val ly: Float

        if (raw) {
            // 低通估计重力（设备坐标，任意姿态都收敛），扣掉得到平移加速度
            if (!gravityReady || dt <= 0f) {
                gravity[0] = rx
                gravity[1] = ry
                gravity[2] = rz
                gravityReady = true
            } else {
                val keep = exp(-dt / GRAVITY_TAU)
                gravity[0] = gravity[0] * keep + rx * (1f - keep)
                gravity[1] = gravity[1] * keep + ry * (1f - keep)
                gravity[2] = gravity[2] * keep + rz * (1f - keep)
            }
            lx = rx - gravity[0]
            ly = ry - gravity[1]

            // 合 G 值：含重力的总受力大小（旋转不变标量）
            smoothTotal += ((sqrt(rx * rx + ry * ry + rz * rz) / G) - smoothTotal) * 0.25f
        } else {
            lx = rx
            ly = ry
            smoothTotal += ((sqrt(lx * lx + ly * ly + rz * rz + G * G) / G) - smoothTotal) * 0.25f
        }

        // 映射到屏幕（内容）坐标系：与 UI 朝向一致，不猜测安装姿态
        val m = remapAxesByRotation(lx, ly)
        val lat = m[0]
        val long = m[1]

        smoothLat += ((lat / G) - smoothLat) * 0.45f
        smoothLong += ((long / G) - smoothLong) * 0.45f
        Telemetry.gLateral = smoothLat
        Telemetry.gLongitudinal = smoothLong
    }

    /**
     * 设备坐标 (x, y) → 屏幕（内容）坐标。
     *
     * 内容上方 / 右方在设备坐标系中的方向：
     *  - ROTATION_0   : 上 = +Y，右 = +X
     *  - ROTATION_90  : 上 = +X，右 = -Y
     *  - ROTATION_180 : 上 = -Y，右 = -X
     *  - ROTATION_270 : 上 = -X，右 = +Y
     * 四档都是把「上」绕屏幕法线旋转 -90° 得到「右」，保证左右手系一致、不出现镜像。
     */
    private fun remapAxesByRotation(x: Float, y: Float): FloatArray {
        val sx: Float
        val sy: Float
        when (screenRotationFromDisplay()) {
            android.view.Surface.ROTATION_90 -> {
                sx = -y
                sy = x
            }

            android.view.Surface.ROTATION_180 -> {
                sx = -x
                sy = -y
            }

            android.view.Surface.ROTATION_270 -> {
                sx = y
                sy = -x
            }

            else -> {
                sx = x
                sy = y
            }
        }
        return floatArrayOf(sx, sy)
    }

    private var screenRotationFromDisplayCache = 0
    private var lastRotationPollMs = 0L

    private fun screenRotationFromDisplay(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRotationPollMs > 100L) {
            lastRotationPollMs = now
            screenRotationFromDisplayCache = try {
                val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
                    ?: android.view.Surface.ROTATION_0
            } catch (t: Throwable) {
                android.view.Surface.ROTATION_0
            }
        }
        return screenRotationFromDisplayCache
    }

    private fun dtFromNow(): Float {
        val now = System.nanoTime()
        if (lastSensorNs == 0L) {
            lastSensorNs = now
            return 0f
        }
        val dt = (now - lastSensorNs) / 1_000_000_000f
        lastSensorNs = now
        return dt
    }

    /** 每 33ms 汇总：只认 GPS 速度，掉线平滑归零。 */
    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val gpsFresh = gpsSpeedMs >= 0f &&
                gpsAccuracyM < GPS_MAX_ACCURACY &&
                (now - gpsStampMs) < GPS_FRESH_MS

        val rawSpeed: Float
        val src: Int
        if (gpsFresh) {
            src = Telemetry.SOURCE_GPS
            rawSpeed = gpsSpeedMs * 3.6f
        } else {
            src = Telemetry.SOURCE_NONE
            rawSpeed = 0f
        }

        val alpha = if (gpsFresh) 0.85f else 0.5f
        smoothSpeed += (rawSpeed - smoothSpeed) * alpha
        if (smoothSpeed < 0.2f) smoothSpeed = 0f

        Telemetry.speedKmh = smoothSpeed
        Telemetry.gTotal = if (Telemetry.hasAccel) smoothTotal else 0f
        Telemetry.source = src
    }
}
