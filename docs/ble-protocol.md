# 本地蓝牙实现依据（2026-09-14）

## 已取得的车辆事实

经用户授权，通过涂鸦开发者平台中国数据中心的只读接口取得 UUID、local_key、产品 ID 和物模型。密钥仅保存在 Git 排除的 `private/`。

- 产品 p0emi0c7，类别 hbc，名称 E-Scooter，模型 erz7ok。
- 设备详情：`GET /v2.0/cloud/thing/{device_id}`；物模型：`GET /v2.0/cloud/thing/{device_id}/model`。
- 手机诊断确认 FD50 服务、写特征 `00000001-0000-1001-8001-00805f9b07d0`、通知特征 `00000002-0000-1001-8001-00805f9b07d0` 和 CCCD。
- 取得云端资料不等于通过实车 BLE 认证。

## 协议来源

[涂鸦官方 Port SDK](https://developer.tuya.com/en/docs/iot-device-dev/Porting-Guide-BLE?id=Kam0xjtz4n6e0) 定义服务、通知和 ATT 长度。

设备端 SDK 公开镜像：[hjytry/tuya-ble-sdk](https://github.com/hjytry/tuya-ble-sdk)，源文件标注 Copyright 2014–2019 Tuya Inc. / Apache-2.0。参考副本在 `tools/tuya-reference/sdk`，不包含在 APK 中。

- `sdk/src/tuya_ble_data_handler.c`：V4 设备信息请求携带两字节接收长度，本版固定 20 字节 GATT 数据，载荷 `00 14`。
- 响应提供版本、绑定状态、六字节随机值及设备虚拟 ID。客户端核对目标 ID，遇到证书认证要求停止。
- 已绑定设备 PAIR_REQ 进入 BONDING_CONN，返回 2 表示已有绑定。本版不执行解绑、重置、升级或未绑定设备激活。
- V4 写 DP：0x0027，版本 0、四字节业务序号、DP ID、类型、两字节长度和值。
- 状态 0x8006/0x8007：七字节元数据后接 DP。回执复制七字节头并附状态 0。模式 0/2 用于界面，带时间的旧记录不更新当前控制状态。

加密封包参考 [ha_tuya_ble](https://github.com/PlusPlus-ua/ha_tuya_ble/blob/main/custom_components/tuya_ble/tuya_ble/tuya_ble.py)：登录密钥 MD5(local_key 前六字节)，会话密钥 MD5(前六字节 + 车辆随机数)。AES-CBC、随机 IV、网络字节序帧头、CRC16 和补零。BLE 协议不能替换成 Wi-Fi Tuya LAN 或 MCU 串口帧。

## 车辆功能映射

| DP | 字段 | 类型 / 范围 |
| --- | --- | --- |
| 1 | 锁车 | bool |
| 2 / 3 | 速度 / 电量 | integer，scale 0 / 0–100 |
| 5 / 12 | 单次 / 总里程 | integer ÷ 10 km |
| 8 / 13 | 前灯 / 巡航 | bool |
| 15 | 模式 | walk / eco / normal / sport，0–3 |
| 16 | 启动 | zero_start=0 / not_zero_start=1 |
| 20 / 21 | 电压 / 电流 | integer ÷ 100 |
| 22 / 25 | 功率 / 故障 | integer / enum |
| 101 | 一档限速 | 5–15 km/h，步长 1 |
| 102 | 二档限速 | 10–24 km/h，步长 1 |
| 103 | 三档限速 | 20–31 km/h，步长 1 |
| 104 | 自动关机 | 5–90 分钟，步长 5 |

完整无密钥物模型位于 `app/src/main/assets/vehicle-model.json`。这些范围来自该产品配置，尚需验证本车固件实际行为。

## 实车验证

0.2.1 的后续实车日志定位到 `Invalid padding`：177 字节完整接收，security=4，解密长度及 CRC 均通过，transportFill=0。0.2.2 去除 CRC 后 AES 对齐区的全零假设，与 SDK `ble_cmd_data_crc_check` 的消息边界一致。保留 AES 整块要求、0–15 字节对齐区域上限、CRC 和车辆身份检查。用独立合成向量复现并验证修正；尚未确认后续绑定认证或控制是否成功。

2026-09-19 收到的 0.2.0 诊断（采集于 09-14）显示：订阅成功、请求已发送两片、收到十次通知，在输出有效帧日志之前触发 IllegalArgumentException。没有记录具体原因或载荷，不能推定密钥错误、车辆拒绝认证或某一种分包错误。

0.2.1 参考 SDK `tuya_ble_mutli_tsf_protocol.c` 中 `trsmitr_recv_pkg_decode` 的按总长度截取行为，兼容固定 20 字节 ATT 末包的尾部填充，以及未完成帧内完全相同的上一片重复通知。CRC、加密格式和身份检查保留。错误输出限于程序固定字符串、长度及阶段，不导出认证数据。此修改的效果待实车确认。

覆盖安装 0.2.0，导入配置，原 App 断开后重连。观察通知订阅 → 设备信息 → 已绑定认证 → 电量和功能状态。停车后先验证前灯回报，随后逐项验证其它功能。尚需验证 OriginOS 6 的写无响应回调、Keystore 和生命周期；失败时手动保存诊断，文件不包含密钥或认证数据包。
