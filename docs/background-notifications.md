# 后台连接与车辆数据通知（0.2.10）

适配目标：用户的 iQOO 13 / OriginOS 6；本机未连接该手机，下面是实现与验证边界。

## 后台连接

依据 Android [BLE 后台通信指南](https://developer.android.com/develop/connectivity/bluetooth/ble/background)，在 App 前台发起连接时启动 connectedDevice 类型前台服务。S9Application 持有 BLE 控制器，Activity 仅订阅界面状态，onStop 不关闭连接，onDestroy 移除界面监听。后台不启动新的自动扫描。

VehicleService 第一行显示 `S9 电量 · 80%`，第二行显示 `38.10 V · 2.3 km`。使用本次认证连接的电量、电压 DP 20（除以 100）和单次里程 DP 5（除以 10），不读取历史缓存；未知电压/里程显示 `— V · — km`。任一显示值变化都会刷新通知，不必等电量改变。实际更新依赖车辆回传，现有查询周期约 5 秒，不保证固定刷新频率。

断开连接移除通知。任务被划除、通知“断开连接”或用户在界面主动断开会停止连接。系统终止进程仍会断连；不声称前台服务能绕过系统强制停止或厂商电池限制。

## 原子岛

0.2.4 曾通过 Android 公开 API 请求通知提升；用户的 iQOO 13 / OriginOS 6 实测只显示普通通知。0.2.5 按用户要求移除这项尝试及 POST_PROMOTED_NOTIFICATIONS 权限，保留后台连接服务和普通电量通知。

[vivo 原子岛集成说明](https://help.aliyun.com/zh/document_detail/3030718.html) 指向 vivo 接入权限申请和本地通知参数文档。仅支持 OriginOS 并不代表应用自动取得原子岛资格。此版未申请专用权限，也未接入网络推送 SDK。不保证通用 Android 请求会转换为 iQOO 的原子岛。

诊断保留通知是否开启、后台服务错误、手机型号和 Android API。

## 验证

已完成 Java 编译、APK 签名校验及 21 项桌面 UI 检查。没有 Android 真机或已运行模拟器，后台存活、通知外观、厂商原子岛和车辆切灯行为仍待实测。
