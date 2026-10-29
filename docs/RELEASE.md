# 永不迟到 v0.4.3 · 测试交付

**我的打卡时间我做主。**

本版仍只导航到 BOE 考勤页面，绝不点击蓝牙打卡、定位打卡或提交按钮。
名称不构成不会迟到、可靠唤醒或考勤成功的保证。

## 本版内容

- 应用及首页名称“永不迟到”，同步通知和无障碍服务名称。
- 抽象几何恶魔手握三叉戟，刺穿并撑裂 BOE 移动门户图标的视觉设计。
  仅为图标表达，不改动或破坏移动门户 App，不代表 BOE 官方应用或授权合作。
- 突出大块碎裂的蓝色门户图标，恶魔缩小并移至右下角；BOE 字母保持自然比例，
  O 使用正圆，不拉高或压窄文字，保持自适应裁切安全边距。
- 上下班时间段、滚轮选择、中国工作日规则和熄屏导航机制保留。
- GitHub Actions 执行 JVM 单元测试、lint、debug/release APK 与设备测试 APK 编译；
  自动发布以 `v` 开头且与应用版本一致的标签为 GitHub 预发布。

## 交付文件与签名边界

| 文件 | 用途 |
| --- | --- |
| `yongbu-chidao-0.4.3-test.apk` | 可安装的测试签名 APK，非生产签名 |
| `yongbu-chidao-0.4.3-unsigned.apk` | 未签名 release APK，不能直接安装，供自行生产签名 |
| `INSTALL.md` | 本说明 |
| `SHA256SUMS` | 上述文件的 SHA-256 校验值 |

CI 在隔离 runner 上生成临时 Android debug 密钥，不上传、不持久化签名私钥。
**不同 CI 运行的测试 APK 签名可能不同，也可能与本机安装版本不同，不能保证覆盖升级。**
遇到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，不要直接卸载日常使用版本；
先记录时间设置、关闭计划，决定是否接受卸载导致配置和授权丢失，再重新安装。
本机保持同一开发密钥构建的版本可覆盖本机此前测试包；不要把它与 CI 签名混淆。
仓库不包含生产 keystore、密码或自动托管生产签名。需要稳定生产升级时，
由维护者离线保管并使用同一个私钥自行签名；切勿将密钥提交到仓库。

先下载到同一目录并运行 `sha256sum -c SHA256SUMS`。
测试设备安装：`adb -s <设备序列号> install -r yongbu-chidao-0.4.3-test.apk`。
不要对未签名 APK 直接执行安装。

## 安装后验收

1. 安装移动门户并由用户自行登录。打开“永不迟到”，确认首页标语及图标。
2. 允许运行设置中的通知、精确闹钟，手动开启
   **永不迟到 · 页面导航（不提交）** 无障碍服务。
3. 允许厂商后台运行、自启动；在解锁亮屏时先检查“锁屏显示权限”。
4. 检查上班 `07:50–08:10`、下班 `17:30–17:32`。首次安装计划默认关闭；
   覆盖升级保留用户已有设置，不擅自重置。
5. 使用“立即测试”或独立的“两分钟后息屏测试”，确认只到考勤页且没有提交。
   **此交付及 CI 不会自动打开 BOE，也不会开启任何每日计划。**
6. 安全锁屏需手动解锁；强行停止、关机、长时间休眠和厂商限制无法保证运行。
   当前仅内置官方 2026 年工作日日历；未接入年份暂停，年度自动更新尚未实现。

CI 没有用户 BOE APK、登录会话或真机，因此不执行 BOE 导航、不冒充真机验收。
设备仪器测试需要另按[真机说明](https://github.com/luskyle/damn-clock-in/blob/v0.4.3/docs/android-helper.md)
在明确选定的测试设备执行。

## 维护者发布步骤

1. 更新 `app/build.gradle.kts` 的 `versionCode`、`versionName` 及本交付说明的版本。
2. 本机构建：
   `bash scripts/build-android.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`
3. `bash scripts/package-release.sh`，然后 `cd dist && sha256sum -c SHA256SUMS`。
4. 提交并推送代码；审阅 main 分支 CI 后创建与版本一致的标签，
   例如 `git tag v0.4.3 && git push origin v0.4.3`。
5. 在 Actions 检查成功，再检查 Release 的四项资产及校验值。失败时不要宣称已发布。
   标签发布为测试预发布，不能当作生产商店版本。
