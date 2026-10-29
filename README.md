# damn-clock-in

BOE 移动门户的本地 Android 环境和独立的页面导航测试 App。**目前仅导航到考勤页面，
不提交任何打卡记录**。BOE APK 由用户本地提供，不提交到 Git。

## 页面测试 App

源码在 [app/](app/)，包名 `com.luskyle.clockintest`，支持 Android 10+，
不需要 root，也不需要真机长期连接电脑。

- 立即测试：打开 BOE → 工作台 → 考勤打卡，识别目标页后停止。
- 上下班双卡片：分别设置时间段和开关，iOS 风格小时/分钟滚轮，24 小时制。
- 默认上班 07:50–08:10、下班 17:30–17:32，均可修改；两个计划默认关闭。
- 仅中国大陆工作日执行：跳过普通周末和法定假期，调休补班执行；两个时段独立随机测试。
- 已内置国务院公布的 2026 年日历，离线可用；未接入年份暂停并提示更新，不猜测工作日。
- 支持通知、权限检查、计划恢复、重复保护和本地记录。
- 专用设备可勾选保持测试面板亮屏；不解除锁屏、不伪造定位，不自动处理 BOE 的权限或登录。

```bash
# 使用已有 Java 17 / Gradle 8.9 / SDK 34，离线运行单元测试、lint、构建
bash scripts/build-android.sh

# 在本项目模拟器中安装并打开面板；不会因此启动导航或定时
bash scripts/android.sh helper-install
bash scripts/android.sh helper-open
```

构建 APK：`app/build/outputs/apk/debug/app-debug.apk`。
真机安装步骤、授权说明、测试边界见 [测试助手说明](docs/android-helper.md)。

## 已验证的运行方案

- 应用：`BOEYiDongMenHu.apk`，BOE移动门户 4.1.1，包名 `com.mobile.boe`，仅含 ARM64 原生库。
- 独立窗口：Android Emulator **36.1.9**，Google APIs Android 15 / API 35 x86_64 镜像，使用系统内置的 `libndk_translation.so` 运行 ARM64 代码。
- 专用 AVD：`DamnClockIn_Google_API35`。与 desktop-robot 的 AVD 分离，不修改其应用、Kiosk 配置或数据。
- 图形参数：`swiftshader_indirect`，关闭 Vulkan。当前主机上的 Emulator 37.2.12 + host 图形启动时挂起并崩溃，因此使用已实测成功的上述组合。
- 2026-10-29 实测：BOE 安装运行成功；用户登录后，测试助手已验证从工作台进入考勤页面并停止，未提交打卡。

此前 Cuttlefish 虽然允许安装，但缺少 ARM 转译，启动时出现
`EM_AARCH64 instead of EM_X86_64`。本项目的 `damn_clock_in` Cuttlefish 组已停止；
不要用它替代上述 Google APIs AVD。

## 使用

所有命令从本项目根目录执行，入口为 [scripts/android.sh](scripts/android.sh)。

```bash
# 首次创建；若已存在则保留数据，不重复创建
bash scripts/android.sh init

# 在独立终端中运行，保持该终端打开；出现安卓桌面后继续下面的命令
bash scripts/android.sh start
```

另开终端：

```bash
bash scripts/android.sh install
bash scripts/android.sh launch
bash scripts/android.sh status

# 不再使用时关闭本项目模拟器，保留数据
bash scripts/android.sh stop
```

脚本按 AVD 名查找设备，不假设固定的 `emulator-5554`，不操作其他项目实例。
`install` 使用 `adb install -r` 保留应用数据；`launch` 唤醒屏幕、启动应用，
并在五秒后检查进程。不会自动登录、同意协议或授予权限。
启动不加载旧快照，不擦除 userdata；请正常关闭模拟器，避免强杀进程。

## 外置资源

大型镜像、SDK、AVD 和用户数据仍放在 `/media/luskyle/DATA/android-lab`，不复制进仓库。
脚本不会下载或安装工具，默认使用现有资源：

| 资源 | 相对 android-lab 的位置 |
| --- | --- |
| Android SDK | `voice/tools/android-sdk` |
| Emulator 36.1.9 | `voice/tools/emulator-audio-36.1.9/emulator/emulator` |
| Java 17（创建 AVD 使用） | `voice/tools/amazon-corretto-17.0.20.12.1-linux-x64` |
| 系统镜像 | `voice/tools/android-sdk/system-images/android-35/google_apis/x86_64` |
| 项目 AVD 数据 | `DamnClockIn_Google_API35.avd` |

支持 `ANDROID_LAB_ROOT`、`ANDROID_SDK_ROOT`、`ANDROID_EMULATOR_BIN`、
`ANDROID_AVD_NAME`、`ANDROID_AVD_PATH`、`JAVA_HOME` 和 `BOE_APK` 环境变量；
详见 `bash scripts/android.sh help`。更换工具或镜像后须重新验证 ARM 转译与应用启动，
不能仅凭安装成功判断可运行。

## 排查

先运行 `status` 获取实际设备序列号，再使用同一 SDK 的 adb，避免不同 adb
版本交替使用导致服务重启：

```bash
ADB=/media/luskyle/DATA/android-lab/voice/tools/android-sdk/platform-tools/adb
SERIAL=emulator-5554 # 替换为 status 输出
"$ADB" -s "$SERIAL" logcat -b crash -d
"$ADB" -s "$SERIAL" shell getprop ro.dalvik.vm.native.bridge
"$ADB" -s "$SERIAL" shell pidof com.mobile.boe
```

预期 native bridge 为 `libndk_translation.so`。脚本清除启动进程继承的
`LD_LIBRARY_PATH` 和 `QT_IM_MODULE`，避免宿主 ROS/Flutter 库干扰模拟器的 Qt/图形加载。
