# AOVOPRO S9 本地控制 / AOVOPRO S9 Local Control

Android 0.2.10 测试版 / Test release

为用户自有 AOVOPRO S9 10.4 Ah 编写的本地蓝牙 App。日常使用免登录，不申请网络或定位权限。

An unofficial local Bluetooth Android app built for an owner's AOVOPRO S9 10.4 Ah scooter. Once the owner's device credentials have been imported, everyday use requires no account login, internet access, or location permission. The app UI is currently in Chinese; this project introduction is bilingual.

本项目与 AOVOPRO 或涂鸦无官方隶属关系，目前针对一辆车的协议与功能配置开发，不保证所有 S9 或其他固件兼容。

This project is not affiliated with AOVOPRO or Tuya. It targets one scooter's protocol and product configuration; compatibility with other S9 units or firmware versions is not guaranteed.

## 中文说明

0.2.2 已经用户实车确认认证连接成功，能读取电量、里程及设置。0.2.5 压缩首页，补齐四种骑行模式，并记住上次电量与总里程。升级后的首次成功读取会建立历史记录，离线显示明确标注“上次记录”。

## 安装与连接

1. 将本地构建得到的 `dist/S9-local-v0.2.10.apk` 传到手机安装；源码仓库不包含 APK。原用户覆盖安装时保留配置与数据，无需重新登录或导入。首次安装需要自己的 `private/s9-connection.json`（含密钥，不随仓库提供）；要求 Android 12 以上。
2. 打开 App，在“连接车辆”中选择导入连接配置，选择上述 JSON。配置仅需导入一次，不要求涂鸦登录。
3. 停好车辆、开机，断开原涂鸦 App 的连接。权限已授予且蓝牙开启时，打开或回到本 App 会自动扫描并连接配置中的车辆；未找到时可手动重试。本车广播名称曾为 `demo`。
4. 等待车辆认证及状态回报。前灯单击交替发送明确开关指令，首次默认开灯；只在协议确认接收后按车辆保存上次指令，失败保持原方向。浅色高亮表示上次开灯指令已接收，实际灯光状态不回传；通过其他 App 或车身操作后可能不同步。启动模式按本次回报单击切换。其他设置需收到对应状态；底层蓝牙连接不代表车辆认证成功。
5. 若连接失败，在“设置 → 设备诊断”保存诊断文件，供继续排查。

三档限速与自动关机在设置页显示为四行，点击对应行后在底部小卡片使用左右滑条及加减按钮。拖动时暂存目标，松手发送一次；待确认时阻止重复操作，失败回到车辆回报值。列表只显示车辆已回报的数值，关闭卡片丢弃未提交草稿。限速步长 1 km/h，自动关机步长 5 分钟、范围 5–90 分钟。切换英里单位仅换算显示，仍按车型原始公里步长发送。

首页与设置都显示电压、当前温度、电流和功率。电流 DP 21 按官方物模型除以 100 显示 A，功率 DP 22 原值显示 W；读取直接上报值，不用电压乘电流推算。已有实车诊断这两项均为 0，尚未验证动态读数和硬件测量精度。

连接配置含本车密钥，请勿公开。导入后以 Android Keystore 加密保存于 App 私有目录，禁止系统备份；APK 不包含密钥。完成导入可删除手机上的原始 JSON。卸载 App 后需要重新导入。

连接后通过 connectedDevice 前台服务在后台保持蓝牙，并显示电量通知。请允许通知权限；设置中有“电量通知”入口。清除任务或通知中的“断开连接”会结束会话。原子岛需要专用接入资格，通用通知请求在用户手机上未生效，已按用户要求移除；普通电量通知保留。详见 docs/background-notifications.md。

## 功能与边界

- 根据车辆回报显示电量、速度、里程、电压、电流、功率和故障；缺失值显示“—”。
- 支持前灯、电子锁、巡航、零启动 / 非零启动、档位、各档限速及自动关机时间。设置受本车官方物模型范围限制。
- 除前灯外，设置要求本次认证连接最后收到的速度为零，不再以 12 秒作为失效期限。收到新速度会覆盖旧值，断连清空；未收到速度或报告非零时仍拦截。每次等待上一条指令确认，不提前把界面切换成成功状态。
- 不执行解绑、激活未绑定设备、恢复出厂或固件升级。
- 隐藏无数据的续航、循环次数和电池温度。当前温度按车辆上报显示，但传感器位置未注明。不包含地图、手机定位或会员功能。
- 保留独立的“界面预览”；示例数据始终有标记，不发送车辆指令。

本版采用自行实现的本地协议客户端，参考设备端 SDK 源码和公开客户端；不是集成要求云端账户的 SmartLife App SDK。协议依据与限制见 `docs/ble-protocol.md`，开源声明见 `THIRD_PARTY_NOTICES.md`。

## 构建与检查

Windows PowerShell：

```powershell
./tools/bootstrap.ps1
./build-apk.ps1
```

首次运行 bootstrap 下载 JDK 与 Android 构建工具；已有工具可直接运行 build-apk.ps1。实际构建流程为 aapt2、javac、D8、zipalign、apksigner。仅 tools 中的安装脚本随源码发布，工具本体、private、dist、构建输出和签名密钥均被 Git 排除。

没有签名密钥时构建脚本会生成本地开发密钥。只有使用原密钥签名，才能覆盖安装原安装包；不要上传密钥或车辆配置。

协议测试：

```powershell
New-Item -ItemType Directory -Force build/protocol-tests
& ./tools/jdk/jdk-17.0.20.1+1/bin/javac.exe -encoding UTF-8 -d build/protocol-tests app/src/main/java/com/s9local/app/TuyaBleProtocol.java tests/ProtocolTest.java
& ./tools/jdk/jdk-17.0.20.1+1/bin/java.exe -cp build/protocol-tests com.s9local.app.ProtocolTest
```

界面预览与检查（需 Node、Playwright 和 Edge）：

```powershell
npm install
npm run preview
# 另一个终端运行
npm run test:ui
```

0.2.9 的 30 项桌面 UI 检查通过，包含底部调节卡片、单击控制、连续锁梁动画、历史读数与四项遥测。0.2.10 仅修改通知及版本信息，编译与签名校验通过。协议编解码已通过 32 项 JVM 协议检查。

用户的 0.2.2 诊断已经证明认证和状态读取成功，0.2.3 的前灯明确开关指令由用户确认可用。桌面检查不能替代各版本的 Android 真机交互与车辆验证。

## English

### Features

- Local BLE authentication and control after a one-time import of the owner's connection configuration.
- Battery level, trip and total distance, voltage, reported temperature, current, power, and fault status. Unavailable readings appear as a dash. The temperature sensor location is unspecified.
- Electronic lock with a continuous shackle animation, headlight control, cruise control, and zero/non-zero start modes.
- Walking, economy, normal, and sport riding modes. Three separate speed limits and auto power-off use bottom-sheet sliders with minus/plus buttons.
- The latest battery level and total distance are retained locally and labeled as previous readings while disconnected.
- A foreground Bluetooth service and a standard notification showing battery, voltage, and trip distance. Data refresh depends on scooter reports; vivo Atomic Island integration is not included.
- A clearly labeled interface preview that does not send vehicle commands.

### Install and connect

1. Android 12 or newer is required. Build the APK locally using the PowerShell commands above; APKs are not included in this source repository.
2. Import your own connection JSON through the app's connection page. Device credentials are not supplied by this repository. They are stored locally using Android Keystore encryption after import; the APK contains no vehicle key.
3. Turn on the scooter, disconnect the original Tuya app, and grant Nearby devices permission. The app scans for and authenticates the configured scooter when opened. Allow notifications for the foreground connection notification.
4. For upgrades, use the same signing key and install over the existing app to retain its settings. A build signed with a different key cannot replace the existing installation directly.
5. Diagnostics can be exported manually from the settings page. Uninstalling the app removes its saved connection configuration.

### Behavior and limitations

- The headlight does not report its physical state. A tap alternates explicit on/off commands using the last acknowledged command; the highlight indicates an accepted on command, not verified illumination. Other apps or physical controls can put this sequence out of sync.
- Other controls require a zero-speed report from the current authenticated connection. Unknown or non-zero speed blocks changes. Settings are confirmed from vehicle reports; commands are serialized.
- Speed-limit adjustments follow the product's native 1 km/h steps even when displayed in mph. Auto power-off supports 5–90 minutes in 5-minute steps.
- Current is reported DP 21 divided by 100, in amperes; power is DP 22 in watts. Available vehicle diagnostics report zero for both. Their dynamic behavior and measurement accuracy have not been verified.
- The app does not unbind devices, activate unbound devices, reset firmware, or perform firmware updates. It has no maps, GPS tracking, cloud account, or subscription features.
- Clearing the task, selecting Disconnect in the notification, or system termination ends the connection. A foreground service does not guarantee immunity from manufacturer background restrictions.

### Build, tests, and source layout

On Windows, run `./tools/bootstrap.ps1` once to download the Android tools and JDK, then `./build-apk.ps1`. Existing local tools can be reused without running bootstrap again. The build uses aapt2, javac, D8, zipalign, and apksigner. A development signing key is generated locally if none exists; retain your original key for compatible upgrades.

For UI checks, install Node.js and Microsoft Edge, run `npm install`, start `npm run preview`, then run `npm run test:ui` in a second terminal. The Java protocol test commands are listed above. Recorded checks cover 30 desktop UI cases and 32 protocol cases; v0.2.10 also passed compilation and APK signature verification. These checks do not replace testing on an Android phone and the scooter.

Android code is in `app/src/main/java`, UI assets in `app/src/main/assets`, tests in `tests`, and implementation notes in `docs`. Vehicle credentials (`private/`), signing keys, downloaded tools, APKs, and build output are excluded from Git. Only the tool setup scripts are included.

The local protocol client references Tuya device-side SDK sources and the public ha_tuya_ble implementation. See [protocol notes](docs/ble-protocol.md) and [third-party notices](THIRD_PARTY_NOTICES.md) for sources and attribution.





