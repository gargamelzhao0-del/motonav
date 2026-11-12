# 远程定位诊断

0.7.2 提供主动开启、最长 60 分钟的本地/远程诊断。App 内「地图设置 → 定位诊断 / 实时日志」可开始、停止、标记和导出。诊断不会启动额外定位，也不改变 15 秒有效期和 100 米算路精度门槛。

## 接收端

使用 Python 3.10+ 运行 `python tools/diagnostic_receiver.py --directory <仓库外私密目录> --port 0 --hours 6`。将目录权限限制为当前用户。程序只监听 127.0.0.1，端口写入 `listener.json`；上传凭证和过期时间位于 `session.json`，不要公开此文件。

用 `cloudflared tunnel --url http://127.0.0.1:<端口>` 创建临时 HTTPS 通道。给测试者的连接格式为 `https://<随机名>.trycloudflare.com/#<session.json 内的 token>`。App 只接受该域名下的 HTTPS 根地址，将片段内的凭证放在 Authorization 请求头，拒绝上传重定向。接收端只公开写入接口，不提供读取日志或文件下载接口。此工具用于临时联调，不是常驻云服务。

接收到的事件保存在私密目录的 `events.jsonl`，`status.json` 给出数量和最近接收时间。每行包含服务端接收时间及客户端 record。事件 ID 由随机会话号和递增 seq 组成，重复批次被确认但不重复落盘；冲突重放拒绝。单批最多 24 条 / 32 KiB，单记录 4 KiB；未知字段、坐标字段、凭证字段、非法数值被拒绝。服务端最多 20 MiB、32 个会话，连接和速率均有限额。

## 排查顺序

1. `location_callback`：查看 error/source、精度、elapsed/wall 数据年龄、callback_gap_ms、卫星数量和可信等级。
2. `location_decision`：区分 SDK 错误、缓存来源、测量过期、无效/模拟位置、重复或乱序。
3. `location_state`：分别查看最近观察点与可导航点年龄，以及 precise_permission_missing、system_location_off、navigation_fix_expired、accuracy_over_100m 等原因。
4. `route_action` / `route_result`：区分 UI 拦截、提交时失效、SDK 拒绝、SDK 返回错误和成功。
5. `lifecycle` / `environment`：对照页面暂停、服务启停、系统定位开关、网络与省电状态。

从 0.7.3 起，`location_callback` 的 `owner=shared` 表示地图与导航共用的定位请求；`location_decision` 仍分别标记 `map` 和 `navigation`，其中 `source` 是验证可信度后解析的原始来源。`location_start` 的 `measurement_stalled` / `recreate_after_stall` / `manual_retry` 区分自动重启、重建客户端和手动重试，`observation_age_ms` 表示距离收到新有效测量的时长。SDK 持续返回错误或旧数据时不会重置这个恢复计时器。

记录不含坐标、目的地、车牌、道路名、Key、设备唯一标识或 SDK 原始异常消息。不得为了诊断把这些信息追加到自由文本字段。`DiagnosticRecord` 与接收器分别执行字段白名单；跨语言测试覆盖实际 Java 编码数据。

App 在断网时仅保留有限内存队列，进程退出不会持续上传；本地记录可导出。Java 崩溃记录采用尽力写入，不能保证进程被强杀、系统 ANR 或原生崩溃时还能上传最后一条记录。调试电脑和临时通道必须保持运行。诊断连接过期后需要重新创建并提供新连接。
