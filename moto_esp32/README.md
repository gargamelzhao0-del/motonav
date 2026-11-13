# MotoNav 1.75C 导航接收固件

固件版本 **0.3.2**，配套支持 NAV3 的 Android 0.7.0 及以后版本，将真实转向、距离、预计时间及车速显示在 Waveshare ESP32-S3-Touch-AMOLED-1.75C 上。本地开发以 ESP-IDF 5.5.4、官方 BSP 3.0.0、LVGL 9.5.0 为基线，依赖版本由 `dependencies.lock` 记录。界面当前使用英文，支持左右转、斜转、急转、直行及保持左/右方向的箭头，其他转向以明确文字表示。

0.3.2 修正 Android 服务指示的确认支持，以及已配对手机恢复旧 NAV3 订阅时抢占 NAV4 的问题。首个经过认证的 RX 写入选择协议通道。显示驱动沿用 0.2.7；连接诊断、复现证据与实测范围见 [蓝牙联调记录](BLE_SUBSCRIPTION_2026-11-13.md)。

0.3.1 整合已验证的 0.2.7 RGB332 显示驱动与 NAV4 路线输入，保留 40 MHz、双 64 行 DMA 缓冲和原有驱动边界。当前集成版本的手机联调与真实导航并发帧率需要独立记录，不能直接沿用模拟地图的 58/60 FPS。

0.3.0 新增 NAV4 真实路线输入，配合 Android 0.7.8 绘制完整圆屏路线图、当前位置、转向、距离和速度；继续支持 NAV3 箭头导航。路线来自手机 SDK，尚无周边道路/建筑底图、中文路名或天气。新协议见 [NAV4](../protocol/NAV4.md)。此版本尚未完成手机与圆屏联合实机验收，不能沿用 0.2.6 的 42 FPS 数值作为本版保证。

历史 0.2.3 的 `ROUTE MAP / SIMULATED` 性能页使用 **466 × 466 全屏地图**，地图覆盖圆屏四周，文字卡片叠在地图上，用户已确认铺满且文字清楚。地图以面板字节序直接绘入内部 RAM 的 DMA 条带，保持完整分辨率，不再分配整幅地图 Canvas。动态刷新覆盖全部圆屏像素及 3 像素径向余量，只省去外框后方不可见的四角。此页不生成真实导航报文，也尚未接入手机路线。手机联调步骤见 [PHONE_INTEGRATION.md](PHONE_INTEGRATION.md)。

0.2.4 将应用、驱动、业务状态、界面与测试拆分为独立组件：`main/app_main.cpp` 只保留入口，`main/app/` 负责组装与导航业务，`components/` 提供显示、触摸、BLE、平台、核心、UI 和地图图形接口。正式版默认不编入测试；`tests/` 可整体移除后构建。详细代码树、接口调用、线程约束及扩展方式见 [FIRMWARE_LAYERS.md](FIRMWARE_LAYERS.md)。App 端联调约定见 [APP_PROTOCOL.md](APP_PROTOCOL.md)。

0.2.5 进一步分离板级 QSPI 传输，合并命令与像素阶段、按硬件上限分段 DMA，并加入可关闭的驱动耗时计数和独立传输测试。正式版默认使用测量后的 64 行双缓冲配置，保持 40 MHz 总线及完整分辨率。优化取舍和实机结果见 [HARDWARE_TEST_0.2.5.md](HARDWARE_TEST_0.2.5.md)。

0.2.6 根据分阶段测量优化 UI 合成：静态仪表背景预先转换为屏幕字节序并声明真实覆盖范围；实色背景的单行标签复用有界像素缓存，文字/样式变化时重新绘制。驱动接口、真实 DMA 完成计数、40 MHz QSPI、64 行双缓冲及完整圆屏地图均保持原有配置。实机数据和验证范围见 [HARDWARE_TEST_0.2.6.md](HARDWARE_TEST_0.2.6.md)。

0.2.7 在用户确认全屏地图“画面完整清楚，颜色可以接受”后采用 RGB332（256 色）传输，并使用 ESP32-S3 批量像素转换。应用继续调用同一显示接口，LVGL 与地图仍绘制 RGB565；压缩、寄存器和 DMA 由驱动管理。40 MHz 下全圆模拟地图最低/平均 **58/60 FPS**，局部仪表 **60/61**，强制整矩形重绘 **39/39**，各测量约 120.5 秒。最低未达到 60，不能称为稳定锁定 60。支持 `-ColorMode RGB565` 保留完整 16 位配色。详细范围、检查和交付镜像见 [0.2.7 实测记录](HARDWARE_TEST_0.2.7.md)。

本轮未采用像素/几何差分刷新和 16 色错位配置。原独立彩条程序的 DMA 缓冲复用错误已修正，RGB332 彩条与完整地图分别取得用户确认。历史试验与判定过程见 [差分刷新记录](HARDWARE_TEST_60FPS_2026-11-13.md) 和 [低色深复核记录](HARDWARE_TEST_COLOR_MODES_2026-11-13.md)。0.2.6 的原始镜像继续保留为回退版本。

后续产品分工、原型地图移植、40 FPS 预算、共享协议及远程升级路线见 [PROJECT_ARCHITECTURE.md](PROJECT_ARCHITECTURE.md)。该文档为后续设计，不表示全部能力已实现。

本次分层重构、删除测试后的独立构建、COM21 实机回归和正式镜像校验见 [HARDWARE_TEST_0.2.4.md](HARDWARE_TEST_0.2.4.md)。

手机负责定位和高德普通驾车算路。启动导航后由前台服务维持定位及 BLE，手机锁屏和地图页面重建后可以重新附着到同一导航会话。弱定位时保留路线，持续等待新的可信位置及导航指令。结束导航、撤回授权或进程终止后结束该会话。

## 构建和安装

在 ESP-IDF 5.5 环境中进入本目录，执行 `idf.py set-target esp32s3` 和 `idf.py build`。依赖由 IDF Component Manager 下载。GitHub Actions 同时构建 Android APK 和本固件，产物分别为 `motonav-recovery-parking-test` 和 `motonav-175c-navigation-test`。

本机 Windows 可执行 `pwsh -File tools/build.ps1`，默认 RGB332 正常版；增加 `-ColorMode RGB565` 切回完整配色，`-LegacyQspi` 使用厂商 RGB565 IO。该脚本只编译，不访问串口或刷写，并显式关闭未请求的测试选项。独立主机测试：`python tests/run_host_tests.py`，不需要手机、串口或 Android SDK。

沿用旧 `sdkconfig` 时，须在 `menuconfig` 开启 `CONFIG_BT_NIMBLE_ROLE_CENTRAL` 和 `CONFIG_BT_NIMBLE_GATT_CLIENT`；默认配置只对首次生成生效。ESP-IDF 5.5 将 GATT 客户端支持依赖于 Central 配置，圆屏仍只广播并接收手机连接，不主动连接其他设备。缺少该能力时编译会明确报错。

`tests/lvgl_host` 可用 CMake 和桌面 C/C++ 编译器构建真实 LVGL 界面的离屏预览：先完成 IDF 配置下载依赖，再执行 `cmake -S tests/lvgl_host -B build-host`、`cmake --build build-host`，最后执行 `ctest --test-dir build-host --output-on-failure`；也可以运行 `ui_preview <输出目录>`。它验证状态布局以及有效导航自动打断性能页，不模拟硬件 FPS；输出 PPM 可转换为 PNG 查看。

2026-11-12 经用户确认的 COM21 实物检测为 ESP32-S3、32 MB Flash（ID c8:4019）、8 MB OPI PSRAM，因此默认 Flash 配置已从原理图推定的 16 MB 改为实测 32 MB。应用分区仍为 6 MB。其他设备安装前也须以 Flash ID 核对容量及板型，完整备份旧 Flash，再按构建生成的 `flash_args` 写入各镜像。1.75C 的屏幕复位为 GPIO1，触摸复位为 GPIO2。

打开 Android 的真实地图页，设置高德 Key 和权限，点“连接圆屏”选择对应 MotoNav 设备，在系统配对窗口输入屏幕的六位码。选择真实目的地并完成普通驾车算路后，点“开始/结束”。手机支持发送导航状态和数据质量状态，接收端对重复包、CRC 错误、过期数据及旧会话分别处理。

此版交付导航仪表、细分连接/时效状态、重新配对恢复、接收计数；显示性能测试页仅在开发构建中启用。真实路线输入已通过 NAV4 接入；中文路名字图和云端信息消息尚未实现。固件使用单应用分区，远程固件更新需后续引入升级分区和恢复流程。

## 40 FPS 的验证方式

LVGL 刷新和界面服务周期为 16 ms，QSPI 保持 40 MHz，使用两块 64 行内部 RAM 绘制缓冲。LVGL 任务及 SPI 中断固定在 CPU1，NimBLE 固定在 CPU0。LVGL 同步绘制 `RGB565_SWAPPED`，显示驱动在拥有的当前缓冲内压缩为 RGB332 后提交真实 DMA；选择 RGB565 时直接提交原像素。完成回调保留适配器帧计数并通过信号量唤醒显示任务。静态仪表背景预先缓存；列范围相同、行连续的条带使用 CO5300 Memory Write Continue，窗口变化、间断或提交失败时重设地址。CPU 使用 32 KB 指令缓存及 64 KB 数据缓存。数字和转向仅在内容变化时重绘，静态页低刷新率不表示性能不足。

先用 `pwsh -File tools/build.ps1 -DeviceTests` 构建手动测试版，再在未显示有效导航时长按屏幕进入 `DISPLAY TEST / NOT NAVIGATION`；短按依次切换 `PARTIAL HUD`、`FULL FRAME`、`ROUTE MAP / SIMULATED`，长按返回。配对或有效导航到来会自动退出测试并释放地图缓冲。测试只改变本地显示，不生成 GPS、车速或导航报文。

FPS 来自 `esp_lv_adapter` 在最后一块像素传输完成时的计数，不是 16 ms 定时器次数。预热后显示当前 FPS、已采样窗口最低值和平均值，每 5 次采样打印 `display_test` 日志。每种负载至少连续测量 30 秒，记录最低值是否达到 40，以及有无撕裂、触摸丢失、BLE 延迟或花屏。全屏传输结果与局部 HUD 结果必须分开报告；即使全屏测试通过，也不能代表未来动态地图必然达到同一帧率。

0.2.1 历史实测（2026-11-12）：每项预热后约 40.5 秒、36 个采样点，局部 HUD 最低/平均 **59/59 FPS**，强制全屏重绘最低/平均 **31/31 FPS**（窗口值 31–32）。该版全屏 40 FPS 未达到。这些数字来自 BLE 广播空闲时的显示测试，不能替代实际手机导航负载测试。具体证据见 [HARDWARE_TEST_2026-11-12.md](HARDWARE_TEST_2026-11-12.md)。

0.2.2 延长回归至每项预热后约 120.5 秒：局部 HUD 最低/平均 **59/60 FPS**，强制全屏 **31/31 FPS**，320 × 300 动态地图 **41/42 FPS**。地图使用 7 个路线点和 22 条背景道路线段，BLE 仍为广播空闲；不是任意复杂度或真实手机导航并发的保证。完整范围、统计方式与优化记录见 [HARDWARE_TEST_0.2.2.md](HARDWARE_TEST_0.2.2.md)。

0.2.3 的全屏实测、稳定性检查和交付镜像记录见 [HARDWARE_TEST_0.2.3.md](HARDWARE_TEST_0.2.3.md)。不能将 0.2.2 的局部地图成绩当作 466 × 466 全屏成绩。

`-DisplayBench` 生成三种负载依次运行的自动测试版；`-MapBench` 只运行地图。每项默认 43 秒（含 2.5 秒预热），可用 `-BenchSeconds 123` 延长。测试结束后释放独立测试页面并回到导航界面。自动测试显示 `AUTO TEST`，期间忽略触摸；配对或有效导航仍会结束测试。再次执行不带测试参数的构建脚本恢复正式构建，三个测试 CMake 开关全部关闭。正式版不再有长按测试入口。旧开机测试 Kconfig 已移除；删除整个 `tests/` 后仍能构建正式版，详见分层文档。

串口日志可用 `python tools/capture_serial.py --port COM21 --seconds 100 --output dist/serial.log` 限时采集。仅在已确认目标设备并允许重启时添加 `--reset-usb`；输出文件必须不存在，工具不扫描或访问其他串口。

## 实时导航协议 v3

BLE 使用 Nordic UART Service 的标准服务 UUID，RX 为尾段 `0002`，TX 为 `0003`。RX 采用有响应写入并要求经过身份验证的加密配对，TX 通知返回应用层确认。Android 按 20 字节分片发送 ASCII 行，因此也支持 MTU 23。

每行长度最多 95 字节，包含末尾换行。CRC 为 CRC-32/ISO-HDLC，使用八位大写十六进制，计算范围为 CRC 字段前的 ASCII 内容，不含其前面的逗号和换行。

```text
H,3,sequence,session,CRC\n
N,3,sequence,session,mode,icon,turn_m,remaining_m,remaining_s,speed_kmh,fix_age_ms,instruction_age_ms,CRC\n
```

连接后先发序号为 1 的 H。接收端清空旧导航，回复 `A,sequence,NAV3\n`。随后每秒发送完整 N 快照，回复 `A,sequence,OK\n`。序号在单连接中严格递增，重复的相同消息只重复确认，保留原来的新鲜度计时。每次连接都重新握手，旧连接收到的 GATT 回调会被手机丢弃。未知版本及非法消息返回 `E,NAV3\n`。

| mode | 状态 | 字段规则 |
| --- | --- | --- |
| 1 | 等待定位或指令 | icon 为 0，距离、时间、车速为 -1，两种 age 为 0 |
| 2 | 有效导航 | icon 对应高德 IconType，距离以米、时间以秒计，未知车速为 -1 |
| 3 | 已到达 | 与 mode 1 使用同样的空字段 |
| 4 | 已结束 | 与 mode 1 使用同样的空字段 |

有效导航要求手机先通过精确权限、真实位置、精度及来源检查。年龄以 Android 单调时钟计算，并预留 BLE 写入超时的 3 秒传输预算。接收端在剩余有效期结束或 4 秒收不到新快照时隐藏旧指令。重复发送同一来源数据会增加 age，不能延长其有效期。到达和已结束状态在断开后保留，新握手会清除。

`android-recovery/tests/run.sh` 会生成 Java 实际编码帧，并让 C++ 接收器读取这些帧。检查包含跨端兼容、损坏、溢出、过期、重复、重连和会话不匹配。

## 实机验收

1. 在开阔处获得可信定位，验证圆屏转向、距离和车速与手机 SDK 信息一致。
2. 锁屏及切换应用，验证导航持续更新，通知中的结束操作能停止会话。
3. 暂时失去定位时观察等待状态，恢复新位置及新指令后自动继续。
4. 断开 BLE 或重启圆屏，验证恢复当前会话并清除旧转向。
5. 撤回位置权限、结束导航及终止 App 后，验证圆屏清空有效诱导数据。

Android 和固件代码按本仓库 Apache-2.0 许可提供。BSP、LVGL、ESP-IDF 和高德 SDK 适用各自条款。
