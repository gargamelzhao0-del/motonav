# MotoNav 0.2.4 分层重构验证

2026-11-12，COM21，Waveshare ESP32-S3-Touch-AMOLED-1.75C。ESP-IDF 5.5.4 / Component Manager 2.4.10 / BSP 3.0.0 / LVGL 9.5.0 / adapter 0.6.4，保持 40 MHz QSPI、CPU1 显示与 SPI ISR、双 64 行内部 RAM 缓冲和真实 DMA 完成等待。

## 代码与构建

- 业务、显示、触摸、BLE、平台、UI、地图图形按 [FIRMWARE_LAYERS.md](FIRMWARE_LAYERS.md) 分离。NAV3 解析器内容未修改，Android 旧协议测试入口保留。
- 依赖边界检查及 5 个纯 C++ 主机测试程序通过；新增会话测试覆盖任意握手切片、连续帧、未加密输入拒绝、订阅撤回、断连半帧清理和数据时效。
- 原生 LVGL 6 项测试通过，包括无测试组件的正式 UI、手动测试、全负载自动测试、地图自动测试、解码回退、两种地图绘制路径像素一致性。正式页与测试页是不同 LVGL screen，真实导航和配对可以接管。
- 常规正式版和启用自动测试的 ESP32 版本均编译成功。另导出一份完全不含 `tests/` 的源码，使用独立构建目录完整编译成功；生产源码/主目录构建的 sdkconfig 键值一致。
- `tools/verify_production.py` 对两个正式构建均通过：所有测试开关 OFF，组件清单和编译命令不含设备测试或模拟地图源文件。
- `android-recovery/tests/run.sh` 所用的旧 C++ 入口无需改动即可单独编译，并通过原有 Java 编码帧样本。

## COM21 短回归

仅写入应用分区 `0x10000`，保留既有分区表、引导程序和 NVS；没有操作其他串口。

测试版每负载运行 15 秒，预热后约 12.5 秒、11 个采样点。此次验证重构后的启动、绘制、切换和资源生命周期，不代替长时间性能验收：

| 负载 | 最低/平均 FPS | 预热后测量 |
| --- | --- | --- |
| 局部 HUD | 59 / 59 | 12,507 ms |
| 矩形全屏重绘 | 32 / 32 | 12,519 ms |
| 466 × 466 全屏模拟地图 | 36 / 36 | 12,506 ms |

三项按顺序完成，自动返回连接页，地图与测试页面内存释放。70 秒日志未发现 panic、断言、看门狗或 SPI DMA 等待超时。BLE 在此期间广播空闲，未传输手机导航。全屏 **40 FPS 尚未达标**；较长的历史基线仍见 [0.2.3 记录](HARDWARE_TEST_0.2.3.md)。

随后刷回正式版，65 秒启动/运行日志确认 `Firmware 0.2.4 / NAV3 / MotoNav-51CA / device_tests=0`，没有性能测试日志。电脑 BLE 连接后读取固件版本 `0.2.4`，确认 RX `write`、TX `notify`，随后正常断开；没有请求配对或发送 NAV3 导航。手机持续导航、重新配对、动态调节亮度和本版实物触摸手势未单独验收。

正式应用大小 1,096,832 字节；SHA256：

```text
45780c733d9cb6699df9f6c430a14b12685195baaf1d6ba055e5b306f77001c8
```

本地证据保存在忽略目录 `dist/COM21-0.2.4/`：`flash-bench.log`、`serial-bench.log`、`flash-production.log`、`serial-production.log`、`ble-smoke.json` 及 `production/` 镜像。构建记录在 `dist/build-0.2.4-*-final.log`。源码不包含整片 Flash 备份或 NVS 数据。
