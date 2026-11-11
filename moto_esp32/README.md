# MotoNav 1.75C 导航接收固件

固件版本 **0.2.0**，配套支持 NAV3 的 Android 0.7.0 及以后版本，将真实转向、距离、预计时间及车速显示在 Waveshare ESP32-S3-Touch-AMOLED-1.75C 上。本地开发以 ESP-IDF 5.5.4、官方 BSP 3.0.0、LVGL 9.5.0 为基线，依赖版本由 `dependencies.lock` 记录。界面当前使用英文，支持左右转、斜转、急转、直行及保持左/右方向的箭头，其他转向以明确文字表示。

代码在此目录独立开发，App 端联调约定见 [APP_PROTOCOL.md](APP_PROTOCOL.md)。`main/navigation_protocol.h` 负责接收与时效，`main/display_model.h` 负责显示状态，`main/navigation_ui.cpp` 负责 LVGL 界面，`main/main.cpp` 负责硬件和 BLE 生命周期。

手机负责定位和高德摩托算路。启动导航后由前台服务维持定位及 BLE，手机锁屏和地图页面重建后可以重新附着到同一导航会话。弱定位时保留路线，持续等待新的可信位置及导航指令。结束导航、撤回授权或进程终止后结束该会话。

## 构建和安装

在 ESP-IDF 5.5 环境中进入本目录，执行 `idf.py set-target esp32s3` 和 `idf.py build`。依赖由 IDF Component Manager 下载。GitHub Actions 同时构建 Android APK 和本固件，产物分别为 `motonav-recovery-parking-test` 和 `motonav-175c-navigation-test`。

本机 Windows 可执行 `pwsh -File tools/build.ps1`，参数支持另行指定 IDF 和 Python 环境。该脚本只编译，不访问串口或刷写。独立主机测试：`python tests/run_host_tests.py`，不需要手机、串口或 Android SDK。

`tests/lvgl_host` 可用 CMake 和桌面 C/C++ 编译器构建真实 LVGL 界面的离屏预览：先完成 IDF 配置下载依赖，再执行 `cmake -S tests/lvgl_host -B build-host`、`cmake --build build-host`，最后运行 `ui_preview <输出目录>`。它验证状态布局以及有效导航自动打断性能页，不模拟硬件 FPS；输出 PPM 可转换为 PNG 查看。

项目按用户原理图上的 128 Mbit Flash 配置为 16 MB，使用 8 MB OPI PSRAM。应用分区为 6 MB。安装前以实际设备的 Flash ID 核对容量及板型，按构建生成的 `flash_args` 写入各镜像。此提交尚未进行刷写或实机测试。1.75C 的屏幕复位为 GPIO1，触摸复位为 GPIO2。

打开 Android 的真实地图页，设置高德 Key 和权限，点“连接圆屏”选择对应 MotoNav 设备，在系统配对窗口输入屏幕的六位码。选择真实目的地并完成摩托算路后，点“开始/结束”。手机支持发送导航状态和数据质量状态，接收端对重复包、CRC 错误、过期数据及旧会话分别处理。

此版交付导航仪表、细分连接/时效状态、重新配对恢复、接收计数以及独立显示性能测试页。动态地图、中文路名字图和云端信息消息尚未实现。固件使用单应用分区，远程固件更新需后续引入升级分区和恢复流程。

## 40 FPS 的验证方式

LVGL 刷新和界面服务周期为 16 ms，使用官方 BSP 的 QSPI 40 MHz、双缓冲及局部刷新，LVGL 任务固定在 CPU1，NimBLE 固定在 CPU0。数字和转向只在内容变化时重绘，不能把静态页面的低刷新率解释为性能不足。

在未显示有效导航时长按屏幕进入 `DISPLAY TEST / NOT NAVIGATION`；短按切换 `PARTIAL HUD` 与 `FULL FRAME`，长按返回。配对或有效导航到来会自动退出测试。测试只改变本地显示，不生成 GPS、车速或导航报文。

FPS 来自 `esp_lv_adapter` 在最后一块像素传输完成时的计数，不是 16 ms 定时器次数。预热后显示当前 FPS、已采样窗口最低值和平均值，每 5 次采样打印 `display_test` 日志。每种负载至少连续测量 30 秒，记录最低值是否达到 40，以及有无撕裂、触摸丢失、BLE 延迟或花屏。全屏传输结果与局部 HUD 结果必须分开报告；即使全屏测试通过，也不能代表未来动态地图必然达到同一帧率。

`CONFIG_MOTONAV_BOOT_DISPLAY_TEST` 可使台架构建启动后直接进入测试；默认关闭。当前没有经确认的圆屏串口，因此尚无实机 FPS 数值。

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
