# OfflineDash

Android 离线车载仪表盘。把手机固定在车里，用作速度显示。

## 功能

- **速度**：GPS 实测速度，单位 km/h。
- **G 力**：纵向 / 横向 G 值，矢量线 + 最近轨迹，满量程 1.5 G。
- **卫星**：可见卫星数、北斗卫星数、信号强度。
- **速度条**：当前速度在量程中的位置。
- **横竖屏**：按钮切换。
- **后台采集**：前台服务，切到后台仍持续定位。
- 不申请 `INTERNET` 权限。

## 安装

1. 从 [Releases](../../releases) 下载 `OfflineDash-1.0.apk`
2. 安装
3. 授予定位权限

运行要求：Android 8.0（API 26）及以上。

## 使用说明

| 界面元素 | 说明 |
| --- | --- |
| 速度数字 | 当前速度，km/h |
| 速度来源 | `GPS` / `估算` / `无数据` |
| G 力圆盘 | 中心读数为 G 力总值；矢量线指向当前加速度方向；轨迹为最近一小段历史 |
| 左上角 | 可见卫星数 |
| 右上角 | 北斗卫星数 |
| 信号条 | 强 / 中 / 弱 |
| 底部光条 | 当前速度在量程中的位置 |
| 切换按钮 | 横竖屏 |

速度来源为「估算」时由加速度计推算，不是 GPS 实测。无卫星信号时速度显示「无数据」。

## 编译

| 项 | 要求 |
| --- | --- |
| JDK | 17 |
| Android SDK | API 34 |
| Gradle | 8.7（仓库自带 wrapper，无需另装） |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |

```bash
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release-unsigned.apk
```

Windows 用 `gradlew.bat`。

## 项目结构

```
OfflineDash/
├── app/src/main/java/com/example/offlinedash/
│   ├── MainActivity.kt          界面骨架、权限请求、横竖屏切换
│   ├── DashService.kt           前台服务：GPS 与传感器数据源
│   ├── DashboardLayout.kt       仪表盘布局与视图编排
│   ├── GnssMonitor.kt           GNSS 状态监听
│   ├── GnssPanelView.kt         卫星状态面板
│   ├── MotionEngine.kt          加速度计速度估算与平滑
│   ├── GForceView.kt            G 力圆盘
│   ├── SpeedBackgroundView.kt   尘埃粒子背景
│   ├── SpeedBarView.kt          底部速度光条
│   └── Telemetry.kt             数据模型
├── app/src/main/res/            图标、文案（zh / en）、主题
├── build.gradle.kts             根构建脚本
├── settings.gradle.kts
└── gradle/wrapper/
```

## 许可

MIT
