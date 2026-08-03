# VoiceSpreader

Android 手机端把听音位置的麦克风数据持续回传给 Windows 版 VoiceSpreader，用于设备间的声学校准和播放中漂移跟踪。工程可直接用 `D:\AndroidStudio\bin\studio64.exe` 打开。

## 本机配置

- Android Studio：`D:\AndroidStudio`
- Android SDK：`D:\Android\SDK`
- Gradle JDK：Android Studio 自带的 `D:\AndroidStudio\jbr`（JDK 21）
- compileSdk / targetSdk：36
- minSdk：23
- Android Gradle Plugin：8.9.1
- Kotlin：2.1.0
- Gradle Wrapper：8.11.1
- Material Components：1.14.0

`local.properties` 已写入本机 SDK 路径，但按 Android 项目惯例不纳入 Git。在 Android Studio 中把 Gradle JDK 设为 `D:\AndroidStudio\jbr`。命令行可执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-local.ps1
```

调试 APK 位于 `app\build\outputs\apk\debug\app-debug.apk`。这是个人调试版本，不需要申请发布签名。

## 使用方法

1. 确保手机和电脑位于同一个可信局域网。
2. 电脑端点击“连接手机”，显示二维码和六位配对码。
3. 手机端扫描二维码，或输入六位配对码后点击“查找”。
4. 按应用提示授予相机、麦克风和通知权限。相机只在扫码时申请，麦克风只在准备连接时申请。
5. 连接后可以返回桌面或锁屏。系统会显示持续的麦克风前台服务通知；通知或应用中的“断开连接”都可立即停止回传。
6. 如果手机系统仍会在省电时中止长连接，可点击“后台运行保护”，由系统设置页明确允许 VoiceSpreader 不受电池优化限制。

## 后台运行与界面边界

- 音频采集和 TCP 连接由独立的麦克风前台服务持有，不再依赖 Activity 是否位于前台。
- 服务声明 `microphone|connectedDevice` 类型，并持有有限时长的 CPU 唤醒锁和高性能 Wi-Fi 锁；停止、断开或出错时会统一释放。
- 麦克风前台服务只会在应用界面可见且用户主动连接时启动，以符合 Android 14 及以上版本的后台启动限制。
- Android 13 及以上会申请通知权限。即使用户拒绝通知权限，系统仍允许启动前台服务，但系统展示方式可能不同。
- 应用采用 edge-to-edge，并根据 `systemBars` 与 `displayCutout` Insets 动态设置内容安全边距；扫码预览可延伸到屏幕边缘，标题、关闭按钮和底部提示不会压到状态栏、导航栏或摄像头挖孔。
- 主界面采用 Material 3 卡片、状态徽标和日夜主题；应用名与电脑端统一为 VoiceSpreader，启动器图标和界面标志均使用电脑端图标。

用户从系统设置中“强行停止”应用，或厂商系统把应用手动设为“受限”时，Android 仍会终止服务；这是系统安全边界，应用不会绕过。

## 音频与连接协议

手机使用 48 kHz、PCM16、单声道 `AudioRecord`。支持时选择 `UNPROCESSED`，否则回退到 `VOICE_RECOGNITION`，尽量避免 AGC、降噪和回声消除。每个音频块携带从录音开始累计的 64 位采样帧号；电脑使用采样帧号计算各输出的相对到达差，不把网络包抵达时间当成声学基准。

二维码地址无法连接时，应用会广播其中的随机会话 ID，在局域网中重新定位电脑，再使用 UDP 回包的真实源 IP 重试。因此电脑存在有线、Wi-Fi、VPN、虚拟机或桥接网卡时，也可自动纠正二维码里的错误接口地址。

二维码包含 128 位随机会话密钥；六位码模式在发现电脑后获取同一临时凭据，TCP 握手会验证会话和密钥。当前 PCM 仍是未加密的局域网传输，只应在可信家庭网络使用；应用禁止备份和迁移内部数据，也不会导出前台服务。
