# VoiceSpreader Android

Android 手机作为 VoiceSpreader 的远程听音位置麦克风。工程可直接用 `D:\AndroidStudio\bin\studio64.exe` 打开。

## 本机配置

- Android Studio：`D:\AndroidStudio`
- Android SDK：`D:\Android\SDK`
- Gradle JDK：Android Studio 自带的 `D:\AndroidStudio\jbr`（JDK 21）
- compileSdk：36（CameraX 1.6.1 要求）
- targetSdk：35（当前仅需 `INTERNET` 即可访问局域网）
- minSdk：23
- Android Gradle Plugin：8.9.1
- Kotlin：2.1.0
- Gradle Wrapper：8.11.1

`local.properties` 已写入 SDK 路径，但按照 Android 项目惯例不纳入 Git。

在 Android Studio 中将 **Gradle JDK** 设为 `D:\AndroidStudio\jbr`。命令行可直接运行
`powershell -ExecutionPolicy Bypass -File .\build-local.ps1`，脚本会显式使用同一个 JDK，
不会受到系统中旧版 Java 的影响。

## 使用

1. 电脑运行支持手机麦克风的 VoiceSpreader，点击“配对手机”。
2. 手机和电脑连接同一个可信局域网。
3. 手机扫描二维码，或者输入电脑显示的六位配对码。
4. 允许相机和麦克风权限，把手机放在实际听音位置并保持应用位于前台。
5. 电脑勾选“连续声学跟踪使用手机麦克风”，再开始同步。

手机使用 48 kHz、PCM16、单声道 `AudioRecord`。支持时选择 `UNPROCESSED`，否则回退到 `VOICE_RECOGNITION` 以尽量避免 AGC、降噪和回声消除。每个音频块携带从录音开始累计的 64 位采样帧号；电脑使用采样帧号计算各输出的相对到达差，因此不使用网络包到达时间作为声学时间基准。

## 当前安全边界

二维码包含 128 位随机会话密钥；配对码模式通过 UDP 广播发现电脑后获得同一临时凭据，TCP 握手会验证会话和密钥。首版 PCM 仍是未加密的局域网传输，只应在可信家庭网络使用。后续可加入带证书指纹固定的 TLS 或 Noise 协议。
