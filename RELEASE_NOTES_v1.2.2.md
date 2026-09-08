# VoiceSpreader Android v1.2.2

## 双端麦克风同步

- 握手声明支持麦克风请求和命令序号。
- 手机端启停请求携带请求编号，Windows 命令携带命令序号。
- Android 对重复或迟到的 Windows 命令只回报当前状态，不会重复启动或停止 AudioRecord。
- 实际采集状态回报带回对应命令序号，帮助 Windows 丢弃过期状态。
- 保留旧版 Windows 的两字节麦克风控制帧兼容。
