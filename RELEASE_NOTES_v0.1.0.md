# VoiceSpreader Android v0.1.0

这是 VoiceSpreader Windows v1.1.0 的 Android 声学校准伴侣应用首个正式发布版本。

## 主要功能

- 通过二维码或六位配对码连接局域网中的 VoiceSpreader Windows 端。
- 仅在用户明确启用后回传麦克风数据，用于多设备自动校准和播放中漂移跟踪。
- 使用 Android 音频硬件时间戳传递采样帧位置，降低调度和网络到达时间对测量的影响。
- 支持前台服务、锁屏运行、双向麦克风状态同步和多网卡地址纠正。
- 支持 Material 3 深浅色主题及异形屏安全区域。

APK 使用项目自签名密钥签署。Android 安装时需要允许对应文件来源安装未知应用；发布私钥未上传到 GitHub。
