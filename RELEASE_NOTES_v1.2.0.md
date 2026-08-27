# VoiceSpreader Android v1.2.0

此版本开始建设独立于 Windows 多设备同步的“设备互联”功能。

## 当前可用

- 通过局域网二维码或六位配对码连接 VoiceSpreader Windows。
- 按需启用 48 kHz PCM16 单声道手机麦克风回传。
- 支持由 Windows 端远程启用或停用麦克风。
- 配合 Windows 端 VB-CABLE 路由，可作为 Audition 等软件的录音输入。
- 接收 Windows 端 48 kHz PCM16 双声道音频，并通过低延迟 `AudioTrack` 在应用内播放。
- 手机麦克风回传与 Windows 声音播放可以同时运行。

## 后续链路

- Android 允许捕获的媒体声音传到 Windows。
- Windows 麦克风在 VoiceSpreader Android 内监听。
