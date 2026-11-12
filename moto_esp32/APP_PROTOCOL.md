# App 与圆屏固件对接

本目录由固件端独立维护。固件 0.2.2 保持 Android 0.7.x 的 NAV3 字节协议；固件改动不要求 App 修改导航算法。0.6.1 尚无真实导航 NAV3 发送链路。手机联调见 [PHONE_INTEGRATION.md](PHONE_INTEGRATION.md)，后续显示和数据需求见 [DISPLAY_PLAN.md](DISPLAY_PLAN.md)，其中扩展功能尚未实现。

## 连接顺序

1. 扫描 `MotoNav-XXXX`，服务 UUID `6e400001-b5a3-f393-e0a9-e50e24dcca9e`。
2. 系统建立经过身份验证的加密配对，用户输入圆屏显示的六位 PIN；保持前导零。重新配对会删除当前对端的旧 bond 并要求新的 PIN，不清除其他设备或全盘 NVS。
3. 订阅 TX `6e400003-b5a3-f393-e0a9-e50e24dcca9e` 的通知。
4. 向 RX `6e400002-b5a3-f393-e0a9-e50e24dcca9e` 发送有响应写入；RX 要求加密和身份验证。尚未订阅 TX 时写入被拒绝。
5. 发送 HELLO，收到匹配 `NAV3` 的确认后，才能发送真实导航快照。断线后重新执行订阅和握手，不能直接续发旧序号。

设备信息服务 `0x180A` 的 Firmware Revision `0x2A26` 返回固件版本字符串，例如 `0.2.2`。它是诊断信息；协议能力仍以 HELLO 的 `NAV3` 确认为准。

## 消息与时效

使用 ASCII，行末 LF，每行含 LF 最多 95 字节。可按 20 字节分片，以兼容 MTU 23。CRC 为 CRC-32/ISO-HDLC，8 位大写十六进制，覆盖最后一个逗号之前的全部内容。

```text
H,3,sequence,session,CRC\n
N,3,sequence,session,mode,icon,turn_m,remaining_m,remaining_s,speed_kmh,fix_age_ms,instruction_age_ms,CRC\n
```

每次连接的 HELLO 序号为 1，session 为正整数。随后 N 序号递增。HELLO 回复 `A,sequence,NAV3\n`，N 回复 `A,sequence,OK\n`，非法帧回复 `E,NAV3\n`。

| mode | 含义 | 数据规则 |
| --- | --- | --- |
| 1 | 等待可信位置或导航指令 | icon=0；所有距离、时间、车速=-1；两种 age=0 |
| 2 | 有效导航 | 距离为米，时间为秒，速度为 km/h；速度未知时=-1 |
| 3 | 已到达 | 与 mode 1 相同的空字段 |
| 4 | 已结束 | 与 mode 1 相同的空字段 |

- `icon` 使用现有高德 SDK IconType。未知图标显示 `CHECK PHONE MAP`，不猜测转向。
- age 必须反映数据来源的实际年龄，不能每次发送时归零。当前 App 已预留 3 秒传输预算。
- 圆屏在 4 秒没有新快照，或位置/指令剩余有效期耗尽后清除有效转向；上限为 15 秒。
- 重复帧仅重发 ACK，不刷新导航时效。旧序号、其他 session、错误 CRC 和越界字段均拒绝。
- 断线立即清除有效诱导；到达/结束提示可保留到下次握手。
- 40 FPS 属于圆屏本地渲染目标，手机仍按现有约 1 Hz 发送导航快照，无需通过 BLE 推送 40 张画面。

## 联调与扩展

`tests/nav3.frames` 与 Android `NavigationProtocolTests` 实际编码结果逐字节对照。运行 `python tests/run_host_tests.py --android-source <android-recovery 路径>` 可验证当前 App 编码与固件接收器的互通；需在环境中设置 JAVA_HOME。

当前协议尚不传输道路名称、路线几何、天气、消息或云端卡片。请勿在 NAV3 尾部直接追加字段：现有接收器会拒绝。以后扩展此类在线信息时需先约定带版本协商、长度及缓存界限的新消息，并保留 NAV3 兼容入口。
