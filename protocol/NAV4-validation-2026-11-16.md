# 无有效 GPS 时保留路线：0.7.15 / 0.3.6

## 原因

手机 0.7.14 的蓝牙诊断在室内联调中报告 `gps_weak`、源状态和编码状态均为 `WAITING`、地图点数为零。原实现存在两处耦合：`JourneyController.deviceMap()` 仅在 LIVE 时导出几何，NAV4 类型 2 在非 LIVE 状态必须清空位置/路线字段；固件 `RouteReceiver.scene()` 和 UI 同样仅在 LIVE 时显示地图。因此，即使手机已经规划了真实路线，弱 GPS 也会让圆屏只剩等待文字。已恢复的 DIAG1 链路用于证明此状态，不是地图显示修复本身。

GPS 弱可以阻止实时位置与诱导，不能作为删除已规划路线的依据。底图瓦片、周边街道和建筑尚未接入；本轮修复的是已规划路线几何的显示。

## 实现

源提交 `3b90b4f76eeaab3adcbf2f856ce18c95055f966a`。Android 0.7.15（versionCode 23）与固件 0.3.6 使用现有服务协商 NAV4P，新增独立计划报文，不改变旧 NAV3/NAV4 快照格式或 GATT 表。手机缓存实际 SDK 路线总览，圆屏分别存储计划与实时窗口；计划报文不刷新定位、指令或心跳计时。

GPS 弱、等待指令或指引过期时显示全屏总览与状态，不显示车辆位置、速度或转向距离。有效位置和指令恢复后自动切换实时跟随；结束、到达、断线、撤销订阅或加密失效时清除计划。绘制接口通过 `MapGeometry.show_position` 控制位置标记，驱动无需感知导航状态，测试仍位于独立 tests 目录。

## 已验证

- Android 全套本地主机检查通过，包括真实 BleLink 回调 80 项、JourneyController 85 项，以及与 C++ 接收器互通的 NAV4/NAV4P 433 项检查。
- 固件主机测试通过，包括 CRC/会话/序号/边界、计划不延长导航寿命、旧固件协商回退、停止/到达清理和原有驱动、队列、诊断检查。
- 12 项真实 LVGL 离屏检查全部通过：首次指引前的总览、实时跟随、GPS 弱后的保留、过期/结束、无虚假车辆标记、RGB332 和解码器/标签缓存参考像素比较。
- [完整 CI](https://github.com/gargamelzhao0-del/motonav/actions/runs/43826304270) 通过 Android 编译/lint、固件移除 tests 后构建、可选性能测试构建和 LVGL 检查。
- COM21 于 2026-11-16 15:50 刷入 0.3.6，写入地址仅 `0x10000`，烧录工具哈希校验通过；启动日志版本正确、DIAG1 RX 句柄仍为 31。未改分区表/bootloader，未擦除 NVS。
- [0.7.15 发布流程](https://github.com/gargamelzhao0-del/motonav/actions/runs/43826803614) 通过稳定签名及版本连续性检查，并发布 [覆盖升级包](https://github.com/gargamelzhao0-del/motonav/releases/tag/android-v0.7.15)。

固件二进制 SHA256：`29ad6d3e030d1ee8d28c3367f5f625b67913b23a6d19ff27384bdb1741e91bac`。

APK SHA256：`6d25753ecfd0200d53aca6a618abf45c1431cbd7c6b31009677e7271f7895665`；签名 SHA256 保持 `af9e2f4cb0997fe77ae884e9d7797a7c6b933c1bfdebc3993386ae418aeacde1`。

## 实机验收边界

已请求手机覆盖安装 0.7.15、连接 MotoNav-51CA 并重新规划路线。完整手机到圆屏的路线预览和视觉效果仍待用户操作及串口确认；本地主机测试不能替代此项。开阔室外实时定位、道路诱导与本轮蓝牙并发帧率尚未实测。
