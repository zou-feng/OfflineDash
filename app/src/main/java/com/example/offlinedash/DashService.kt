package com.example.offlinedash

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台定位服务：保证应用退到后台后仍能持续拿到 GPS 与传感器数据。
 *
 * - manifest 中声明 foregroundServiceType="location"
 * - Android 8.0+ 创建通知渠道并常驻通知
 * - Android 14 起，类型为 location 的前台服务要求已授予定位权限，未授权时直接结束自身
 * - 所有系统 API 调用均带 try-catch 与版本判断
 */
class DashService : Service(), LocationListener {

    companion object {
        private const val CHANNEL_ID = "offlinedash_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var motion: MotionEngine? = null
    private var gnss: GnssMonitor? = null
    private var locationManager: LocationManager? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        if (!startForegroundSafely()) {
            // 无定位权限时，类型为 location 的前台服务在 Android 14 上不允许启动
            Telemetry.gnssState = Telemetry.GNSS_NO_PERMISSION
            stopSelf()
            return
        }

        motion = MotionEngine(this).also { it.start() }
        gnss = GnssMonitor(this).also { it.start() }
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted && !startForegroundSafely()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            locationManager?.removeUpdates(this)
        } catch (t: Throwable) {
            // ignore
        }
        gnss?.stop()
        motion?.stop()
        super.onDestroy()
    }

    // ---------------- 前台通知 ----------------

    private fun startForegroundSafely(): Boolean {
        if (!hasLocationPermission()) return false
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(), type
            )
            foregroundStarted = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dash)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        } catch (t: Throwable) {
            // ignore
        }
    }

    // ---------------- 定位 ----------------

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = lm
            if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                Telemetry.gnssState = Telemetry.GNSS_UNSUPPORTED
                return
            }
            // 只订阅 GPS：网络定位的速度不可靠，会污染速度显示；500ms 间隔降低显示延迟
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper()
            )
        } catch (t: Throwable) {
            // 定位不可用时不崩溃，界面会回退到估算模式
        }
    }

    /** 上一次定位，用于在设备不提供 speed 字段时自行按位移推算 */
    private var lastLocation: Location? = null

    override fun onLocationChanged(location: Location) {
        try {
            val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
            var speed = if (location.hasSpeed()) location.speed else -1f

            // 部分设备/机型 hasSpeed() 恒为 false，用位移/时间自行推算
            if (speed < 0f) {
                val prev = lastLocation
                if (prev != null && accuracy < 30f) {
                    val dtSec = (location.time - prev.time) / 1000f
                    if (dtSec in 0.5f..5f) {
                        speed = prev.distanceTo(location) / dtSec
                    }
                }
            }
            lastLocation = location

            if (speed < 0f) return
            motion?.onGpsSpeed(speed, accuracy, SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            // ignore
        }
    }

    @Deprecated("由 GnssMonitor 轮询替代")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }
}
