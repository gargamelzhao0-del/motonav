# Android 与高德 SDK 仿真验证

本项目现在区分三类证据，避免将逻辑检查当作真机导航通过。

| 层次 | 实际运行内容 | 可以验证 | 不能证明 |
| --- | --- | --- | --- |
| 本地确定性回放 | 生产控制器、人工控制时钟及 SDK/UI 替身 | 回调时序、定位过期、重算、取消与恢复 | Android 生命周期、高德 native SDK、GPS |
| Android Emulator | Android 11 Google APIs x86_64，ARM native translation，真实 APK、Android 对象和高德 SDK 初始化 | Activity 启动与重建、实际 SDK 数据对象、真实初始化回调、回放诊断分支 | 有效 Key 的线上算路、真实卫星定位、圆屏性能 |
| 手机内 SDK 模拟导航 | 当前安装包、手机已保存的 Key、高德实际算路和 NaviType.EMULATOR | 服务授权、算路、模拟位置和转弯指令能否连续回调 | 手机 GPS 精度、真实道路匹配、BLE/40 FPS |

## 手机操作

安装 0.7.7 后，结束真实导航，进入“地图设置 → SDK 模拟导航测试 → 开始 SDK 模拟测试”。保持页面打开，测试最多 90 秒。

使用北京公开固定测试路线，不使用当前位置。只有收到至少 5 个模拟位置和 5 个模拟导航指令，或有实际位置与指令后到达终点，才报告模拟测试通过。鉴权、算路失败、模式错误和超时各自报告；离开页面立即停止，不自动重启，不发送圆屏数据。使用现有 Key 会产生正常的高德算路调用。

- 模拟测试通过、真实导航失败：说明本次模拟路径的 SDK/服务/指令链正常，下一步结合真实定位与导航日志定位；不能直接断言手机硬件损坏。
- 模拟测试也失败：记录页面错误码、算路阶段及两类回调计数，优先排查 Key 服务权限、网络、native SDK 与生命周期。
- 配置尚未完成：不会启动 SDK 或伪造成功结果。

## 诊断信息

原来“定位或导航指令暂时中断”现拆分为 GPS 弱、地图定位过期、地图定位过期但 SDK 位置仍更新、未收到首条指令、指令过期、等待中断后的新位置。观察导航 SDK 的位置流只用于诊断，不会把地图的旧位置时间改成当前时间。

远程诊断使用已有的 `navigation_state` 事件，新增状态值 `sdk_position`、`sdk_instruction`、`waiting`、`sdk_replay_instruction`、`sdk_replay_end`。只记录时效、精度、类型、计数和枚举原因，不上传坐标、道路名或 Key。须先主动开启诊断。

## 运行与证据

本地：在 `android-recovery` 中运行 `sh tests/run.sh`。控制器回放新增“SDK 位置与指令正常、地图位置已过期”的复现场景，验证它进入 WAITING，并在新的地图位置与指令到达后恢复。

实际 Android：`.github/workflows/android-emulator.yml` 在临时 GitHub Linux runner 上创建 Android 11 AVD，构建并安装真实 App 与测试 APK，通过 `am instrument` 执行 AndroidJUnitRunner，解析完整测试结果后才判定 CI 成功。外部脚本通过 `adb emu geo fix` 每秒送入公开测试点，Android GPS provider 测试必须实际收到至少两条新回调。证据在卸载前导出；脚本与测试都检查模拟器身份，不操作任意已连接设备。不会更改开发电脑的虚拟化设置或已连接的其他 ADB 设备。

下载该 workflow 的 `motonav-android-emulator-evidence` artifact，可查看 JUnit 报告、Android fingerprint/ABI、App 页面截图、确定性回放结果和高德初始化实际结果。`init_failure` 与 `init_success` 都必须如实记录；初始化回调到达不等于线上导航成功。CI 没有用户有效 Key，也不持有正式签名私钥，使用故意无效的测试 Key，不会把未执行的线上算路写成通过。

官方参考：[Android ARM 模拟支持](https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html)、[高德模拟导航示例](https://github.com/amap-demo/android-navi/blob/master/app/src/main/java/com/amap/navi/demo/navigation/EmulatorActivity.java)、[高德导航位置回调](https://a.amap.com/lbs/static/unzip/Android_Navi_Doc/com/amap/api/navi/model/AMapNaviLocation.html)。
