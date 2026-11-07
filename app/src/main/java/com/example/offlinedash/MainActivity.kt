package com.example.offlinedash

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 仪表盘主界面。
 *
 * 方向：只支持竖屏与横屏两种，启动默认竖屏，由左上角按钮手动切换，
 * 不使用重力感应自动旋转。
 *
 * 权限：已获得定位权限时启动 [DashService] 前台服务；未获得时退化为本地传感器模式，
 * 并把缺失的权限直接反映到右上角状态面板。
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERM = 1001
    }

    private var requested = false
    private var localMotion: MotionEngine? = null
    private var dashboard: DashboardLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 默认竖屏，之后由用户手动切换
        if (savedInstanceState == null) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        applyCutoutMode()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val layout = DashboardLayout(this)
        layout.orientationToggleListener = { toggleOrientation() }
        dashboard = layout
        setContentView(layout)

        goImmersive()
        refreshPermissionState()
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        refreshPermissionState()
        if (hasLocationPermission()) {
            startDashService()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onDestroy() {
        localMotion?.stop()
        localMotion = null
        super.onDestroy()
    }

    // ---------------- 横竖屏手动切换 ----------------

    private fun toggleOrientation() {
        val next = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        try {
            requestedOrientation = next
        } catch (t: Throwable) {
            // 个别 ROM 拒绝时忽略，界面不崩溃
        }
    }

    // ---------------- 全屏沉浸 ----------------

    private fun applyCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    private fun goImmersive() {
        try {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (t: Throwable) {
            // 低版本或个别 ROM 失败时忽略，界面依然可用
        }
    }

    // ---------------- 权限 ----------------

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    /** 把权限状态同步给界面，缺失的项会直接显示在右上角面板里 */
    private fun refreshPermissionState() {
        Telemetry.locationGranted = hasLocationPermission()
        Telemetry.notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun ensurePermissions() {
        if (hasLocationPermission()) {
            startDashService()
            return
        }
        if (!requested) {
            requested = true
            try {
                ActivityCompat.requestPermissions(this, neededPermissions(), REQ_PERM)
            } catch (t: Throwable) {
                startLocalSensors()
            }
        } else {
            startLocalSensors()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return

        refreshPermissionState()
        if (hasLocationPermission()) {
            startDashService()
        } else {
            Toast.makeText(this, getString(R.string.toast_no_location), Toast.LENGTH_LONG).show()
            startLocalSensors()
        }
    }

    // ---------------- 数据采集入口 ----------------

    private fun startDashService() {
        try {
            val intent = Intent(this, DashService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            // 前台服务启动失败（个别 ROM 限制）→ 退化为本地传感器模式
            startLocalSensors()
        }
    }

    private fun startLocalSensors() {
        if (localMotion != null) return
        Telemetry.satsUsed = 0
        Telemetry.satsVisible = 0
        Telemetry.signalBars = 0
        try {
            localMotion = MotionEngine(this).also { it.start() }
        } catch (t: Throwable) {
            Telemetry.hasAccel = false
        }
    }
}
