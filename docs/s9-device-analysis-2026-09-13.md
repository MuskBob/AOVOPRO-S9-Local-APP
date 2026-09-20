# S9 实机诊断与认证接入阻点

依据：用户提供的 s9-device-info.json，采集时间 2026-09-13 18:47:18 +08:00，App 0.1.1。原文件位于用户 Downloads/vivo办公套件，不复制到 APK 或发布包。

## 已确认

1. Android 已完成 GATT 连接与服务发现，返回了 1800、1801、FD50 三个服务。当前问题不能归因为完全无法建立蓝牙链路。
2. FD50 的写入特征是 `00000001-0000-1001-8001-00805f9b07d0`，properties=12，即 Write 和 Write Without Response。
3. 通知特征是 `00000002-0000-1001-8001-00805f9b07d0`，properties=16，即 Notify，存在 CCCD 2902。它们与涂鸦官方 GATT 定义一致。
4. 按 BLE 广播 LTV 长度逐段解析，偏移 7 的 Service Data 为 FD50，紧随 UUID 的状态字节为 0x49。官方文档将该值定义为已绑定状态。偏移 23 的厂商标识为 0x07D0，与官方定义相符。这里识别的是协议，不凭设备名推断车型。
5. 广播名称为 demo，这是实机广播内容，不能理解为本 App 的界面预览模式。
6. 没有标准 Battery Service 180F，因此上一版的标准电量读取没有可读目标。原涂鸦面板中的电量需要另行解析产品数据，不能伪造为 0%。

## 不能从本文件得出的结论

- controlReady=false 是旧 App 写入的状态，不是车辆返回的认证拒绝码。旧 App 根本没有发起涂鸦认证。
- GATT UUID 标识服务或特征，不是涂鸦设备凭据中的 device UUID。
- 文件未提供设备绑定密钥、设备 UUID、云端设备 ID、可读产品 ID 或功能 DP schema，不能据此完成认证和控制。
- 不把 0x49 中的高位直接当作已核验的全部协议版本与安全能力，不猜测其余广播字段的含义。
- 仪表盘蓝牙图标是否以涂鸦安全通信状态为依据，仍是合理推测，未查到 S9 固件的直接证据。

## 下一步实现条件

已确定实际收发通道。接下来需要可用于该绑定设备的凭据，以及适用的认证/会话协议；然后订阅通知、进行认证并验证设备应答。此后还要取得产品 DP 定义，先读取真实状态，再接入控制。

涂鸦官方提供把现有 App 账号下设备关联到开发项目的流程，以及 GET /v1.0/devices/{device_id} 设备详情接口；接口定义包含 uuid、local_key、product_id 等字段。开源 ha_tuya_ble 的本地 BLE 实现使用设备详情得到的 local_key 派生登录密钥，并使用设备 UUID/ID 完成会话。此实现可作为兼容性研究依据，不能证明 S9 必然支持，也不能保证该设备的当前云 API 权限一定返回所需字段。

如用户允许初始化时使用现有账号授权，可先只读查询自己这辆车的详情和 DP 定义，并把适用凭据导入本地 App。Android App 的免登录、日常离线要求不因此更改。未获得用户同意前，不关联账号，不创建云项目，不添加联网权限。没有凭据时不靠重置、解绑、随机写入或重复重连代替认证。

## 来源

- 涂鸦 SDK 移植（GATT UUID、厂商 ID、0x41/0x49 定义）：https://developer.tuya.com/cn/docs/iot-device-dev/Porting-Guide-BLE?id=Kam0xjtz4n6e0
- 涂鸦蓝牙通用串口协议（链路连接与安全通信上线的区别；串口帧不是手机 GATT 帧）：https://developer.tuya.com/cn/docs/iot/tuya-cloud-universal-serial-port-access-protocol?id=K9eigf2el456o
- 官方云设备管理接口：https://developer.tuya.com/cn/docs/cloud/device-management?id=K9g6rfntdz78a
- 官方设备关联流程：https://developer.tuya.com/en/docs/iot/link-devices?id=Ka471nu1sfmkl
- 本地 BLE 认证实现源码：https://github.com/PlusPlus-ua/ha_tuya_ble/blob/main/custom_components/tuya_ble/tuya_ble/tuya_ble.py

本轮完成文件解析和协议依据核对，没有新发 APK，没有声称已完成车辆认证或控制实测。
