# VoiceSpreader Android v1.2.4.3

- 应用版本号更新为 `1.2.4.3`（versionCode `10243`）。
- 全屏触摸板背景跟随应用 `background` 明/暗资源，不再强制黑色。
- 触摸板绑定前台音频服务，复用已经认证的 TCP 连接；连接未就绪时给出一次明确提示。
- TCP socket 开启 keep-alive，并在握手中声明 `audio`、`input.touchpad` 能力。
- 触摸帧写入失败时交由前台服务进入统一重连流程，避免继续向失效连接发送数据。

