# VoiceSpreader

Android 手机端与 Windows 版 VoiceSpreader 建立独立的双向音频连接。手机可作为电脑麦克风，Windows 声音也可在一台或多台手机/平板中播放；所有链路都需要用户在应用内明确启用。工程可直接用 `D:\AndroidStudio\bin\studio64.exe` 打开。

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

正式自签名 APK 可执行以下命令生成：

```powershell
powershell -ExecutionPolicy Bypass -File .\package-release.ps1
```

输出位于 `dist\release`。发布密钥和随机口令保存在项目目录外的 `.signing\VoiceSpreader\android`，不会提交到 Git；后续版本必须保留并复用该密钥，才能覆盖安装现有版本。

## 使用方法

1. 确保手机和电脑位于同一个可信局域网。
2. 电脑端进入“设备互联”，显示二维码和六位配对码。
3. 手机端扫描二维码，或输入六位配对码后点击“查找”。
4. 按应用提示授予相机和通知权限。麦克风权限只在首次点击“启用麦克风”时申请，建立连接本身不会访问录音设备。
5. 连接后在电脑端为每台设备选择“麦克风”或“扬声器”角色。启用麦克风后才会创建 `AudioRecord`；停止路由、断开或出错时会立即释放。
6. 手机作为电脑麦克风时，电脑端将多设备音频混音到 VB-CABLE 的 `CABLE Input`，第三方录音软件选择 `CABLE Output`。作为校准基准时，也在同步控制中选择 `CABLE Output`，不需要单独的远程测量选项。
7. 手机作为电脑扬声器时，Windows 向选中的设备发送共享帧序列；Android 使用 80 ms 预缓冲、缺口补静音和重复帧裁剪来降低多设备起播偏差。
8. 连接后可以返回桌面或锁屏。通知或应用中的“断开连接”会结束控制连接和所有音频链路。
9. 如果手机系统仍会在省电时中止长连接，可点击“后台运行保护”，由系统设置页明确允许 VoiceSpreader 不受电池优化限制。

连接成功后，顶部状态会显示为“已连接”；再次点击它即可主动断开。下方实时电平采用单色 18dp 圆角轨道，并同时使用快速上升、缓慢释放的数据平滑和 Material 进度动画，降低短时电平变化带来的跳动感。

## 后台运行与界面边界

- TCP 控制连接和按需音频采集由独立前台服务持有，不再依赖 Activity 是否位于前台。
- 空闲连接只以 `connectedDevice` 类型运行；启用回传后才加入 `microphone` 类型并持有有限时长的 CPU 唤醒锁和高性能 Wi-Fi 锁，停用麦克风、断开或出错时会立即释放。
- 麦克风前台服务只会在应用界面可见且用户主动连接时启动，以符合 Android 14 及以上版本的后台启动限制。
- Android 13 及以上会申请通知权限。即使用户拒绝通知权限，系统仍允许启动前台服务，但系统展示方式可能不同。
- 应用采用 edge-to-edge，并根据 `systemBars` 与 `displayCutout` Insets 动态设置内容安全边距；扫码预览可延伸到屏幕边缘，标题、关闭按钮和底部提示不会压到状态栏、导航栏或摄像头挖孔。
- 主界面采用 Material 3 卡片、状态徽标和日夜主题；应用名与电脑端统一为 VoiceSpreader，启动器图标和界面标志均使用电脑端图标。

用户从系统设置中“强行停止”应用，或厂商系统把应用手动设为“受限”时，Android 仍会终止服务；这是系统安全边界，应用不会绕过。

## 音频与连接协议

手机麦克风使用 48 kHz、PCM16、单声道 `AudioRecord`。支持时选择 `UNPROCESSED`，否则回退到 `VOICE_RECOGNITION`，尽量避免 AGC、降噪和回声消除。每个设备使用本地随机稳定 ID，电脑可在重连后恢复独立的麦克风、扬声器和增益设置。

同一条 TCP 连接采用双向控制：电脑向手机发送麦克风和播放启停命令，手机返回实际状态或错误；只有状态确认为启用后，对应音频才会进入混音或发送链路。每台设备拥有独立有界队列，慢设备不会阻塞其他设备。

二维码地址无法连接时，应用会广播其中的随机会话 ID，在局域网中重新定位电脑，再使用 UDP 回包的真实源 IP 重试。因此电脑存在有线、Wi-Fi、VPN、虚拟机或桥接网卡时，也可自动纠正二维码里的错误接口地址。

二维码包含 128 位随机会话密钥；六位码模式在发现电脑后获取同一临时凭据，TCP 握手会验证会话和密钥。当前 PCM 仍是未加密的局域网传输，只应在可信家庭网络使用；应用禁止备份和迁移内部数据，也不会导出前台服务。
