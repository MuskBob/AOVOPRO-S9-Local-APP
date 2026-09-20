# 官方文档依据与后续接入

> 历史说明：下文记录 0.1.x 的边界。0.2.0 已取得车辆凭据和物模型，实现本地认证与控制代码；当前依据与待验证项见 [ble-protocol.md](ble-protocol.md)。

核查日期：2026-09-13。此文件说明代码已经实现的边界，不代表设备协议已验证。

## Android 原生 BLE

- [蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)：Android 12+ 使用 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT。本版最低 Android 12，扫描设置 neverForLocation；没有定位权限。官方指出 neverForLocation 会过滤部分 BLE beacon，因此尚不能保证发现所有广播形态。
- [扫描 BLE 设备](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices)：使用 BluetoothLeScanner，设定 12 秒扫描期限，并在连接和退出前台时停止。
- [连接 GATT 服务](https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server)：使用 connectGatt、BluetoothGattCallback、discoverServices 和 close，设置连接与发现超时，忽略旧连接回调。
- [BLE 数据传输](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)：设备服务与特征由发现结果取得。本版只尝试读取标准 Battery Level，不猜测厂商特征或写入未知数据。
- [Bluetooth SIG Assigned Numbers](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html) 与 [Battery Service 1.1](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/BAS_v1.1/out/en/index-en.html)：标准服务 0x180F，标准电量特征 0x2A19，电量百分比范围 0–100；这不保证 S9 实现了该标准服务。

## 涂鸦公开文档

- [蓝牙通用串口协议的名称解释](https://developer.tuya.com/cn/docs/iot/tuya-cloud-universal-serial-port-access-protocol?id=K9eigf2el456o)：明确区分链路层“蓝牙连接”与通过涂鸦协议建立安全通信的“蓝牙绑定已连接／上线”。此文的串口帧用于设备 MCU 与模组，不能直接作为手机 GATT 写入内容。S9 仪表图标是否对应安全通信状态，尚未实测核实。

- [Android 蓝牙单点](https://developer.tuya.com/cn/docs/app-development/android-bluetooth-ble?id=Kceugyl9omxom)：公开 App SDK 流程包含扫描、连云激活、已绑定设备连接及解绑。已绑定设备的离线连接，不能直接证明一个全新、免账号 App 能独立完成初次认证。
- [配网与 App 限制](https://developer.tuya.com/cn/docs/app-development/extension-sdk-tutorial-deviceconfig?id=Kd8k3w5na6q73)：产品和 App 可配置双向接入限制；默认不限制。强绑定与厂商 App 限制是不同问题。
- [Android 设备管理](https://developer.tuya.com/cn/docs/app-development/devicemanage?id=Ka6ki8r2rfiuu)：已获取的 SDK 设备对象包含 schema、schemaMap、dps 和 productId。GATT 服务列表不能当作这些 DP 定义。
- [Android 设备控制](https://developer.tuya.com/cn/docs/app-development/andoird_device_control?id=Kaixh4pfm8f0y)：使用 DP ID 和正确类型的值下发，并通过设备状态回调核对实际状态。官方灯具示例的 DP 101 不代表 S9 前灯，未将此示例编号写入车辆控制代码。
- [TuyaOS BLE SDK Guide](https://developer.tuya.com/en/docs/iot-device-dev/tuya-ble-sdk-user-guide?id=K9h5zc4e5djd9)：这里的 BLE SDK 是设备固件侧 SDK，不是可直接放入手机、免认证控制成品车的 Android 库。

## 纯本地方案尚待验证的部分

1. 识别用户选中的车辆实际提供哪些服务和特征，是否采用标准涂鸦 BLE 模式。
2. 确认可用于用户自有车辆的本地认证方式，以及是否需要一次性获取绑定凭据。不会在用户未同意时添加账号、云端依赖或重置车辆。
3. 获取并核对产品真实 DP 定义：编号、读写类型、单位、倍率、取值范围和枚举值。原始透传字段可能另有格式。
4. 先读取状态，再以车辆静止时的前灯开关验证正确的数据下发与设备回报。
5. 接入电子锁、巡航、启动模式等功能时，分别核对回报状态与断连行为。发送成功不等于车辆已执行；不会乐观地把界面状态当作真实车况。

代码中没有持有设备密钥，没有绕过绑定，没有发起配对、重置或车辆控制写入。真实模式的不可用状态是明确的功能边界，不能通过打开预览模式改变。
