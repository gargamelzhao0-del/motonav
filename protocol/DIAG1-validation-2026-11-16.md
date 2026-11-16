# 蓝牙诊断转发交付记录（2026-11-16）

实现提交：`ee1bcabc9c60b4692e526eb911673c3a90a029b0`，分支 `codex/ble-diagnostics`。协议和使用方法见 [DIAG1](DIAG1.md)。

## 已完成

- Android 0.7.14 / versionCode 22 已经通过纯 Java/跨端回归、Android 编译和 Lint，使用原固定签名发布。[发布记录](https://github.com/gargamelzhao0-del/motonav/releases/tag/android-v0.7.14)、[签名发布流水线](https://github.com/gargamelzhao0-del/motonav/actions/runs/43818755320)。下载后的 APK SHA-256 与发布清单一致：`32aed558b15bacf5b767bda1d67c08b1985d1180f6805a4b2027d6ad65f22611`。
- 原固定签名指纹为 `af9e2f4cb0997fe77ae884e9d7797a7c6b933c1bfdebc3993386ae418aeacde1`；发布流水线校验证书和升级版本连续性。本地也核对了发布的签名指纹文件与仓库记录。
- ESP-IDF 5.5.4 正式构建通过；`MOTONAV_BLE_DIAGNOSTICS=OFF` 也已构建并确认组件没有进入链接。正式镜像不包含设备测试或模拟地图。
- 64 项实际 Android `BleLink` 回调检查通过，覆盖导航抢占、单个 GATT 操作、停止、换会话、拒绝、超时和断线。45 项 `Diagnostics` 检查包含蓝牙会话开关、到期和过期回调隔离。
- 104 项 DIAG1 Java 检查生成 11 个实际 NAV3/NAV4 边界样本，由 C++ 接收器校验。固件主机测试通过，包含真实 BLE 驱动、独立诊断通道、CRC、分片、超时、有界队列、断线 epoch、接收限流和内存分配失败。
- 最终补充验证：接收队列满时不提交重组状态，队列恢复后原样重试最后一个 ATT 分片可以成功，保持 BLE 公共接口的错误零消费约定。
- COM21 的 MAC 已核对为 `c4:9e:7e:0c:51:c8`。读取并备份原 0.3.2 的完整 6 MiB 应用分区，核对现有分区表与新构建相同后，只在 `0x10000` 写入新应用并通过刷写哈希校验。没有擦除 NVS，也没有改写引导程序和分区表。
- 45 秒启动采集确认 `Firmware 0.3.4 / NAV3+NAV4 / MotoNav-51CA / device_tests=0 / ble_diagnostics=1`，显示初始化为 40 MHz QSPI、RGB332、双 64 行缓冲，BLE 广播正常。采样期间空闲内部剩余堆保持 51759 字节；这是未连接手机时的观测，不是导航负载余量保证。

固件应用 SHA-256：`58065fe410a3532e7ead7168444f6eac978ce084b59ff952dee720ce85e76bcf`。原工作目录 `moto_esp32/dist/release-0.3.4/` 保存应用、ELF、无测试源码包、校验和及刷写前备份/日志。

## 实机验收边界

USB 采集器已在 14:46（北京时间）启动，持续一小时，只打开 COM21，不主动复位设备。日志目录是 `moto_esp32/dist/COM21-ble-diagnostics-20261116-144639/`。手机需覆盖安装 0.7.14，连接圆屏，在“真实地图 → 设置 → 定位诊断与实时日志”点“开始蓝牙实时诊断”，再复现导航。

截至本记录整理时尚未收到手机 DIAG1 摘要，手机→BLE→ESP32→USB 的完整实机链路仍待验收；圆屏画面也需要用户确认。没有重新测量导航并发帧率，不能沿用早期模拟地图的 58/60 FPS。

`WAITING FOR GUIDANCE` 的实际根因尚未确认。软件边界测试只能说明手机 LIVE / 编码 WAITING 在定位或指令年龄 12001–15000 ms 时可能出现；需要实际 `phone_mode_at_tx`、`wire_mode`、`wire_reason`、年龄、导航序号/ACK 与设备状态一起判断。这次没有改变导航数据的新鲜度限制。
