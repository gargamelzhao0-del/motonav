# 固件分层与接口（0.2.4）

本文件描述当前已实现的结构。主程序只组装模块；板级实现由独立 ESP-IDF 组件管理，应用不直接调用 BSP、NimBLE 或 LCD/I²C 接口。现有 NAV3 协议保持兼容。

## 目录

```text
moto_esp32/
├── main/
│   ├── app_main.cpp                  入口，只调用 app::run()
│   ├── app/
│   │   ├── navigation_app.*          启动顺序、模块组装、运行状态日志
│   │   └── navigation_service.*      BLE 事件/字节 → 导航会话；加锁快照
│   ├── CMakeLists.txt                应用依赖与可选测试装配
│   └── idf_component.yml             第三方版本锁定
├── components/
│   ├── motonav_display/              CO5300、QSPI/DMA、LVGL 显示端口
│   ├── motonav_touch/                CST9217、方向映射、LVGL 输入适配
│   ├── motonav_ble/                  安全连接、NUS 字节传输、配对事件
│   ├── motonav_platform/             NVS 初始化、设备名、版本、时钟、内存
│   ├── motonav_core/                 纯 C++ 协议、会话、显示状态模型
│   ├── motonav_ui/                   导航页面、仪表控件、可选页面扩展接口
│   └── motonav_graphics/             全屏地图投影、裁剪、RGB565 条带渲染
├── tests/
│   ├── device/motonav_bench/         可选设备测试组件，模拟地图也在这里
│   ├── host/                        协议、会话、状态、几何与栅格测试
│   ├── lvgl_host/                   正式/测试 UI 离屏回归和像素对比
│   ├── fixtures/nav3.frames         Java 编码的跨端测试样本
│   ├── navigation_protocol_test.cpp Android 跨端检查的兼容入口
│   └── run_host_tests.py
└── tools/
    ├── build.ps1                    正式/测试构建开关，不访问串口
    ├── capture_serial.py            指定串口的限时日志
    └── export_source.py             导出干净源码，默认不含 tests
```

每个组件只通过 `include/motonav/...` 暴露公共接口，私有实现不加入其他组件的 include 路径。CMake 使用显式 `REQUIRES/PRIV_REQUIRES`；开启最小组件构建，只有实际依赖进入链接。遵循 ESP-IDF 的 `components/` 约定，`main/` 因此只保留应用代码。

```mermaid
flowchart TD
    Entry[main/app_main] --> App[应用组装 navigation_app]
    App --> Service[导航业务 navigation_service]
    Service --> Core[纯 C++ 会话与 NAV3]
    Service --> BLE[BLE 传输接口]
    Service --> Platform[平台接口]
    App --> UI[导航 UI]
    UI --> Core
    App --> Display[显示驱动接口]
    App --> Touch[触摸驱动接口]
    App -. 构建开关 .-> Bench[tests/device 测试组件]
    Bench --> UI
    Bench --> Graphics[地图图形组件]
    Display --> BSP[官方 BSP / ESP-IDF]
    Touch --> BSP
    BLE --> NimBLE[NimBLE]
```

驱动不包含导航协议、页面或模拟数据；核心不包含 RTOS、LVGL 或硬件头文件；导航 UI 不包含任何驱动头文件。图形组件只接受相机与几何，不连接蓝牙，也不内置模拟路线。应用在启动时注入快照读取器；测试在启动时注入时钟/FPS 服务。

## 应用调用接口

| 公共头文件 | 接口 | 责任 |
| --- | --- | --- |
| `motonav/drivers/display.h` | `display_init / display_start` | 初始化固定板型的显示及启动 LVGL 服务 |
| 同上 | `display_lock / display_unlock` | 应用任务访问 LVGL 时的递归互斥锁，超时单位 ms，-1 无限等待 |
| 同上 | `display_set_brightness(0..100)` | 与绘制串行，等真实 DMA 完成后发送亮度命令 |
| 同上 | `display_enable_fps / reset_fps / read_fps` | 可选的真实传输完成帧计数，正式版默认不开启 |
| `motonav/drivers/touch.h` | `touch_init(display, &input)` | 初始化并绑定 LVGL 输入；应用使用 LVGL 点击/长按事件 |
| `motonav/drivers/ble_transport.h` | `ble_start(name, revision, callbacks)` | 启动单连接加密认证 NUS 服务和广播；复制名称/版本 |
| 同上 | `ble_notify(data, length)` | 在 BLE 回调内回复通知，复制数据并报告资源/发送失败 |
| `motonav/drivers/platform.h` | `platform_init / device_name / firmware_version` | 初始化 NVS、板级身份、版本 |
| 同上 | `monotonic_us / monotonic_ms / memory_stats` | 单调时间与内存统计 |
| `motonav/core/navigation_session.h` | `NavigationSession` | 链路状态、配对/订阅、分片输入和只读快照；调用方负责串行化 |
| `motonav/ui/navigation_ui.h` | `create_navigation_ui` | 通过 `SnapshotReader` 拉取业务快照，默认无可选页面 |
| `motonav/graphics/route_map.h` | `create_route_map / render_route_map` | 创建地图对象，复制并投影有界几何；不拥有外部数据指针 |

初始化顺序见 `main/app/navigation_app.cpp`：

```cpp
ESP_ERROR_CHECK(drivers::platform_init());
ESP_ERROR_CHECK(navigation.init());
ESP_ERROR_CHECK(drivers::display_init(&display));
ESP_ERROR_CHECK(drivers::touch_init(display));
ESP_ERROR_CHECK(drivers::display_lock());
create_navigation_ui(display, name, reader); // reader 由应用实现
drivers::display_unlock();
ESP_ERROR_CHECK(drivers::display_start());
ESP_ERROR_CHECK(drivers::ble_start(name, drivers::firmware_version(), navigation.callbacks()));
```

这些驱动目前按单板、每次启动初始化一次设计。返回 `esp_err_t`，应用决定错误处置；初始化中途失败不支持原地重试或热卸载，应记录错误后重启。NVS 初始化失败不会自动擦除，保留已有配对数据。`ble_start` 底层资源初始化失败也按启动失败处理。

### 线程与数据所有权

- BLE 回调运行于 NimBLE 主机任务（CPU0），只更新业务状态和回复短通知，不调用 LVGL。不在其他任务或 ISR 中直接调用 `ble_notify`；后续主动上报应增加有界队列，把发送调度到 BLE 线程。
- RX 回调中的字节数组只在回调期间有效；一个回调可以包含半帧、多帧或任意片段。驱动不解析 NAV3。业务会话在取消订阅、失去安全状态或断连时清理半帧与握手；新握手后才恢复诱导。
- `NavigationService` 是业务状态的唯一持有者。快照复制与协议输入受互斥锁保护；释放业务锁后再发送 BLE 通知。UI 在 LVGL 线程通过读取器拉取快照，因此没有 BLE → LVGL 的跨线程写对象。
- LVGL 服务运行于 CPU1；SPI 中断仍在 CPU1 分配。双 64 行内部 RAM 缓冲、466 × 466、40 MHz QSPI、RGB565_SWAPPED、真实 DMA 完成信号量及 CO5300 连续条带逻辑保留。
- 从普通应用任务调用 LVGL 时先 `display_lock()`，完成后解锁；LVGL 自己的事件和定时回调已经持锁。亮度接口自己加锁，并在完成上一笔 DMA 后操作面板。
- Dashboard 的点数组必须与页面同寿命。导航页持有自己的 Dashboard；测试组件创建独立 LVGL screen，退出时切回导航页并删除测试 screen，连同地图/背景缓存一起释放。
- 地图接口调用必须持 LVGL 锁。路线最多 512 点、背景道路最多 64 段；输入在函数返回前复制/投影。当前自定义绘制单元依赖 LVGL 9.5.0 私有接口，不能单独升级 LVGL 而跳过回归。

## 正式构建与可删除测试

默认构建 **不含设备测试、模拟路线、自动测试或测试手势入口**：

```powershell
pwsh -File tools/build.ps1
```

需要在台架上手动切换负载时：

```powershell
pwsh -File tools/build.ps1 -DeviceTests
```

仅这个开发版本支持待机长按进入、短按切换、长按退出。自动测试仍支持：

```powershell
pwsh -File tools/build.ps1 -DisplayBench -BenchSeconds 123
pwsh -File tools/build.ps1 -MapBench -BenchSeconds 43
```

原 `CONFIG_MOTONAV_BOOT_DISPLAY_TEST` 已删除，避免 Kconfig 遗留值把测试带入正式包。脚本每次显式设置 `MOTONAV_DEVICE_TESTS`、`MOTONAV_AUTORUN_BENCH`、`MOTONAV_AUTORUN_MAP_BENCH`，无测试参数就全部关闭。直接使用 IDF 时要注意 CMake 缓存；恢复正式版使用：

```text
idf.py -DMOTONAV_DEVICE_TESTS=OFF -DMOTONAV_AUTORUN_BENCH=OFF -DMOTONAV_AUTORUN_MAP_BENCH=OFF build
```

**可整体删除 `tests/`，不需要修改任何生产源码或提供空实现。** 正式构建的测试开关必须关闭；删除后误开测试会收到明确 CMake 错误。项目的 GitHub Actions 测试任务会使用 tests，正式发布源码的编译不依赖它们。

验证或导出纯生产源码：

```text
python tools/export_source.py dist/source-production
```

在导出目录内正常执行 `tools/build.ps1` 即可。输出目录必须不存在；不导出本地构建目录、整片 Flash 备份、NVS 或下载依赖。需要交付测试源码时添加 `--with-tests`。

## 后续新增功能

新传感器或存储驱动新增为独立组件，公开小接口并返回真实读数/错误，不在驱动中创建页面。应用增加业务服务，UI 只接收状态；云端消息与地图路线接收也分别建立有界业务模块，避免重新塞回 `app_main.cpp`。

目前只封装已经接入的显示、触摸、BLE 与基础平台服务；未实现电池/充电、IMU、RTC、音频、TF 卡业务或固件 OTA。正式导航仍为 NAV3 仪表，真实路线输入尚待两端联调。全屏地图图形组件保留，模拟路线只存在于测试模块；本次重构不等于已达成全屏 40 FPS。
