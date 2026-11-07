package com.example.offlinedash

/**
 * 进程内共享的遥测数据。
 *
 * 采集侧（[DashService] 或 [MotionEngine]）写，UI 侧（各自定义 View）在每帧读取。
 * 所有字段都是简单的基础类型，用 @Volatile 保证跨线程可见性，避免锁开销。
 * 数据只存在于内存，不写文件、不导出。
 */
object Telemetry {

    /** 速度来源：无数据 */
    const val SOURCE_NONE = 0
    /** 速度来源：GPS */
    const val SOURCE_GPS = 1
    /** 速度来源：加速度积分估算 */
    const val SOURCE_ESTIMATED = 2

    /** GNSS 状态：未知 / 正常 / 未开启 / 无权限 / 无硬件 */
    const val GNSS_UNKNOWN = 0
    const val GNSS_OK = 1
    const val GNSS_PROVIDER_OFF = 2
    const val GNSS_NO_PERMISSION = 3
    const val GNSS_UNSUPPORTED = 4

    @Volatile
    var speedKmh: Float = 0f

    /** G 力总值（单位 G，静止平放时约为 1.00） */
    @Volatile
    var gTotal: Float = 1f

    /** 横向 G（右为正），驱动小球左右偏移 */
    @Volatile
    var gLateral: Float = 0f

    /** 纵向 G（前为正），驱动小球上下偏移 */
    @Volatile
    var gLongitudinal: Float = 0f

    /** 当前 G 值是否有效（有可用的加速度传感器） */
    @Volatile
    var hasAccel: Boolean = false

    @Volatile
    var source: Int = SOURCE_NONE

    @Volatile
    var gnssState: Int = GNSS_UNKNOWN

    /** 定位权限是否已授予（右上角面板直接显示缺失项） */
    @Volatile
    var locationGranted: Boolean = false

    /** 通知权限是否已授予（Android 13+ 才需要） */
    @Volatile
    var notificationGranted: Boolean = true

    /** 参与定位解算的卫星数 */
    @Volatile
    var satsUsed: Int = 0

    /** 可见卫星数 */
    @Volatile
    var satsVisible: Int = 0

    /** 参与定位解算的北斗卫星数 */
    @Volatile
    var bdsUsed: Int = 0

    /** 可见的北斗卫星数 */
    @Volatile
    var bdsVisible: Int = 0

    /** 平均载噪比 C/N0（dB-Hz） */
    @Volatile
    var avgCn0: Float = 0f

    /** 信号强度格数 0..5 */
    @Volatile
    var signalBars: Int = 0
}
