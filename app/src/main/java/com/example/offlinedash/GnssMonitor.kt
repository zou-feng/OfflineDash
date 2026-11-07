package com.example.offlinedash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.GpsStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 卫星与 GPS 信号监控。
 *
 * API 24+ 使用 [GnssStatus.Callback] 获取卫星数量与 C/N0；
 * 更低版本回退到已废弃的 [GpsStatus.Listener]（本工程 minSdk 26，此分支为兼容保留）。
 * 每 2 秒轮询一次 GPS 开关状态，用于在界面上显示「GPS 关闭」。
 */
class GnssMonitor(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    private var gnssCallback: GnssStatus.Callback? = null

    @Suppress("DEPRECATION")
    private var gpsStatusListener: GpsStatus.Listener? = null

    private var started = false

    private val statePoller = object : Runnable {
        override fun run() {
            refreshProviderState()
            if (started) handler.postDelayed(this, 2000L)
        }
    }

    fun start() {
        if (started) return
        started = true

        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager = lm
            if (lm == null) {
                Telemetry.gnssState = Telemetry.GNSS_UNSUPPORTED
                return
            }
            if (!hasLocationPermission()) {
                Telemetry.locationGranted = false
                Telemetry.gnssState = Telemetry.GNSS_NO_PERMISSION
                return
            }
            Telemetry.locationGranted = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val callback = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        handleGnssStatus(status)
                    }

                    override fun onStarted() = Unit

                    override fun onStopped() = Unit

                    override fun onFirstFix(ttffMillis: Int) = Unit
                }
                gnssCallback = callback
                // 单参数版本自 API 24 起可用；回调发生在注册线程（主线程）
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(callback)
            } else {
                val listener = object : GpsStatus.Listener {
                    override fun onGpsStatusChanged(event: Int) {
                        if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS ||
                            event == GpsStatus.GPS_EVENT_FIRST_FIX
                        ) {
                            handleLegacyStatus()
                        }
                    }
                }
                gpsStatusListener = listener
                @Suppress("DEPRECATION")
                lm.addGpsStatusListener(listener)
            }
        } catch (t: Throwable) {
            Telemetry.gnssState = Telemetry.GNSS_UNSUPPORTED
        }

        refreshProviderState()
        handler.post(statePoller)
    }

    fun stop() {
        started = false
        handler.removeCallbacks(statePoller)
        try {
            val lm = locationManager
            if (lm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    gnssCallback?.let { lm.unregisterGnssStatusCallback(it) }
                } else {
                    @Suppress("DEPRECATION")
                    gpsStatusListener?.let { lm.removeGpsStatusListener(it) }
                }
            }
        } catch (t: Throwable) {
            // ignore
        }
        gnssCallback = null
        gpsStatusListener = null
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleGnssStatus(status: GnssStatus) {
        try {
            var used = 0
            var visible = 0
            var cn0Sum = 0f
            var bdsUsed = 0
            var bdsVisible = 0
            val count = status.satelliteCount
            for (i in 0 until count) {
                visible++
                val isBds = status.getConstellationType(i) == GnssStatus.CONSTELLATION_BEIDOU
                if (isBds) bdsVisible++
                if (status.usedInFix(i)) {
                    used++
                    if (isBds) bdsUsed++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 > 0f) cn0Sum += cn0
                }
            }
            val avg = if (used > 0) cn0Sum / used else 0f
            publishSatellites(used, visible, avg, bdsUsed, bdsVisible)
        } catch (t: Throwable) {
            // ignore
        }
    }

    @Suppress("DEPRECATION")
    private fun handleLegacyStatus() {
        try {
            val lm = locationManager ?: return
            val status = lm.getGpsStatus(null) ?: return
            var used = 0
            var visible = 0
            var snrSum = 0f
            for (sat in status.satellites) {
                visible++
                if (sat.usedInFix()) {
                    used++
                    if (sat.snr > 0f) snrSum += sat.snr
                }
            }
            val avg = if (used > 0) snrSum / used else 0f
            publishSatellites(used, visible, avg)
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun publishSatellites(
        used: Int,
        visible: Int,
        avgCn0: Float,
        bdsUsed: Int = 0,
        bdsVisible: Int = 0
    ) {
        Telemetry.satsUsed = used
        Telemetry.satsVisible = visible
        Telemetry.bdsUsed = bdsUsed
        Telemetry.bdsVisible = bdsVisible
        Telemetry.avgCn0 = avgCn0
        Telemetry.signalBars = barsOf(used, avgCn0)
    }

    private fun barsOf(used: Int, avgCn0: Float): Int {
        if (used <= 0 || avgCn0 <= 0f) return 0
        return when {
            avgCn0 >= 36f -> 5
            avgCn0 >= 30f -> 4
            avgCn0 >= 24f -> 3
            avgCn0 >= 16f -> 2
            else -> 1
        }
    }

    private fun refreshProviderState() {
        try {
            if (!hasLocationPermission()) {
                Telemetry.locationGranted = false
                Telemetry.gnssState = Telemetry.GNSS_NO_PERMISSION
                publishSatellites(0, 0, 0f)
                return
            }
            Telemetry.locationGranted = true
            val lm = locationManager ?: return
            if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                Telemetry.gnssState = Telemetry.GNSS_UNSUPPORTED
                return
            }
            val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            Telemetry.gnssState = if (enabled) Telemetry.GNSS_OK else Telemetry.GNSS_PROVIDER_OFF
            if (!enabled) {
                publishSatellites(0, 0, 0f)
            }
        } catch (t: Throwable) {
            // ignore
        }
    }
}
