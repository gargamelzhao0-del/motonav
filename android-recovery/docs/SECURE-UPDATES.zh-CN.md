# 应用更新与发布

从 0.6.0（versionCode 6）起，在地图设置的「应用更新」中可手动检查，也可开启「新版提醒」。发布渠道使用 GitHub Releases 和 Android 系统安装器。

## 用户流程

1. 点击「检查更新」会请求固定发布源。开启新版提醒后，Android 在联网时约每小时调度一次清单检查，同一个更高版本只通知一次。点击通知会打开更新页并重新检查。
2. 清单通过基础校验后展示版本与下载体积。用户再次点击“下载并验证”。
3. 下载成功后验证 SHA-256、APK 实际包名/版本/minSdk，以及 APK 当前签名与本机安装的当前签名集合完全一致。任何缺失、无法解析或不匹配都拒绝。
4. 用户点击“交给系统安装”。若 Android 未允许该来源，先说明后由用户选择打开本应用专属的系统设置；用户自行决定是否授权。
5. 返回设置只更新按钮/说明，**绝不自动继续安装**。用户需要再次点击安装。APK 被重新校验后以只读 content URI 交给 Android 安装器，由系统和用户最终决定。
6. 打开安装器不等于安装成功，本应用不会报“已安装”。没有静默安装、自动卸载、权限绕过或清除数据。

仅停车时操作。此测试应用不用于实际骑行。后台新版提醒默认关闭，启用后只检查清单。关闭提醒会取消任务和通知。离开或暂停更新页面会取消页面发起的检查、下载及安装前校验。回到页面可重新操作。旋转/重建页面可能丢失待安装状态并需要重新下载，这是安全优先的当前限制。

## 唯一清单来源

固定 URL：

`https://github.com/gargamelzhao0-del/motonav/releases/latest/download/motonav-update.json`

没有任意源输入、测试源覆盖、远程配置源或仓库重定向迁移。此入口使用 GitHub Releases 的 latest 发布；仓库没有公开可用清单、GitHub 不可达、重定向规则变化或限流时会失败关闭。

schemaVersion 1 恰好包含以下八个字段。示例仅用于说明，SHA/大小必须替换为最终发布 APK 的真实值，**不可将该示例直接发布**：

```json
{
  "schemaVersion": 1,
  "applicationId": "com.motonav.recovery",
  "versionCode": 6,
  "versionName": "0.6.0",
  "minSdk": 26,
  "apkUrl": "https://github.com/gargamelzhao0-del/motonav/releases/download/v0.6.0/MotoNav-0.6.0.apk",
  "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "sizeBytes": 12345678
}
```

规则：

- 所有字段必填，不接受未知字段；数字必须为 JSON 整数，不能用字符串/小数替代。
- `applicationId` 必须为 **com.motonav.recovery**。`com.motonav.companion` 是 Java namespace，不是此安装包 ID。
- `versionCode` 必须大于本机版本，且在 1 至 2147483647 之间；同版重装和降级均拒绝。
- `versionName` 最长 80 字符，以英文字母/数字开头，只允许英文字母、数字、点、下划线、加号、空格、连字符；不允许控制字符。
- `minSdk` 不低于 26，且不高于设备 Android API。APK 中的版本号、版本名和 minSdk 必须与清单完全相同。
- `apkUrl` 必须为上述固定仓库的 `/releases/download/<tag>/<filename>.apk` HTTPS 地址，无用户名、密码、端口、查询串或 fragment。tag 和文件名只允许英文字母/数字开头，随后为英文字母、数字、点、下划线或连字符，各不超过 160 字符，且禁止 `..` 和编码路径片段。
- `sha256` 是完整文件的 64 位十六进制 SHA-256，不是签名证书指纹。
- `sizeBytes` 是完整 APK 的准确字节数，范围为 1 至 157286400（150 MiB）。只支持单个完整 APK，不支持 split APK/APKS/XAPK 等集合格式。

## 发布到远程更新渠道

仓库的发布工作流由 `android-vX.Y.Z` 标签触发。正式渠道持续使用同一个签名密钥库，仓库 Actions Secrets 配置以下四项。密钥库原件和口令由维护者在仓库之外保管。

| Secret | 内容 |
|---|---|
| `MOTONAV_RELEASE_KEYSTORE_BASE64` | 稳定签名密钥库文件的 Base64 内容 |
| `MOTONAV_RELEASE_KEYSTORE_PASSWORD` | 密钥库口令 |
| `MOTONAV_RELEASE_KEY_ALIAS` | 签名别名 |
| `MOTONAV_RELEASE_KEY_PASSWORD` | 签名密钥口令 |

1. 在 `app/build.gradle` 增大 `versionCode`，设置新的 `versionName`，并确认目标提交的 Android CI 通过。
2. 对审核通过的提交创建与版本名对应的标签，例如 `android-v0.6.0`，然后执行 `git push origin refs/tags/android-v0.6.0`。推送标签会触发正式发布。
3. 工作流构建 release APK，以稳定密钥签名，从最终 APK 读取版本与包名并生成清单。已有更新版本时，会比对公开的签名指纹，并要求 `versionCode` 递增。
4. 工作流将 APK、清单和签名指纹一起上传到 GitHub Release，并设为 latest。其他用途的发布请避免替换该 latest 入口。CI 的停车测试产物带临时签名，其中的清单仅供验证发布格式。

首次使用这个渠道的设备需要安装稳定签名的基线 APK。启用提醒后，设备会在下一次后台检查时看到同签名的更高版本。原先的临时 CI 签名无法覆盖安装稳定签名包；需由使用者决定如何迁移并核对高德绑定。

## 信任边界与网络

- 网络仅使用平台 HTTPS/TLS 验证；不替换信任管理器、主机校验器，不接受明文或证书异常。
- HTTP 自动重定向关闭，最多跟随 5 次。GitHub 源必须留在固定仓库对应发布路径；APK 不允许换 tag 或文件名。清单的 latest 入口可以重定向至该仓库具体版本的同名清单。
- 允许 GitHub 发布资产 CDN 的精确主机 `release-assets.githubusercontent.com` 和 `objects.githubusercontent.com`，路径前缀必须是 `/github-production-release-asset/`。CDN URL 只能由已信任仓库请求的重定向产生，不能作为清单中的起始 APK URL。允许 CDN 自带签名查询串，但不把它展示或写入日志。
- CDN 主机/路径名单是本应用的失败关闭策略，不是对 GitHub 永远不改变分发方式的保证。若平台变更，需要审查并更新应用，不能临时放开任意主机。
- 清单最多 32 KiB、APK 最多 150 MiB，流式读取时持续检查上限和声明大小，不依赖 Content-Length。拒绝非 identity Content-Encoding、空文件、截断文件和超限文件。
- 每次连接/读取超时 10 秒；清单总预算 30 秒，下载总预算 300 秒，预算在连接响应与每次读取前后检查。阻塞调用可能直到其 10 秒超时才返回，因此这不是硬实时的总截止时间。
- 取消同时设置标记并断开当前连接；工作线程外完成下载、hash 和 APK 解析，UI 线程只处理展示与系统界面启动。
- 请求不附加地图 Key、坐标、导航状态、设备 ID、认证 token 或账号凭据。GitHub/CDN 仍能看到访问者的网络 IP。错误只显示固定说明，不展示 URL、异常详情、路径、签名内容或导航/地图诊断。

清单本身没有额外的脱机签名：固定仓库 HTTPS + 必需的文件 SHA-256 用于来源/完整性检查，**实际 APK 当前签名与已安装应用一致**才是安装身份约束。SHA-256 不能替代签名信任。此实现不能修复已被攻陷的签名私钥或设备/系统，也不会使已被恶意签名的合法升级变安全。

## 签名、迁移及渠道约束

- Android 28+ 比较 `SigningInfo.getApkContentsSigners()` 的完整当前签名集合；不以历史证书重叠作为通过条件。兼容 Android 9 的 archive parser，查询同时设置 GET_SIGNATURES 与 GET_SIGNING_CERTIFICATES。Android 26–27 使用平台 GET_SIGNATURES。
- 缺失、空、重复签名，或 APK 无法正常解析时失败关闭。清单中不能指定“可信签名”来覆盖本机身份。
- 当前策略有意不接受证书轮换，即使 Android 自身可能支持签名谱系轮换，也需将来单独设计并审查迁移方案。
- CI 停车测试包使用临时签名，正式远程更新渠道使用稳定签名。同包名的覆盖更新仍要求签名完全一致。
- 从这种临时签名首次迁移到稳定签名，通常需要用户明确同意后自行卸载重装（可能丢失此测试应用本地配置/数据），或者另选不同 applicationId 并重新配置。当前 updater 不做迁移、不自动卸载、不导出敏感配置、不保证无缝升级。高德绑定也可能需要按新包名/证书重新配置。
- 该实现面向 GitHub/侧载测试分发。Google Play 不允许用 REQUEST_INSTALL_PACKAGES 实现自我更新。若以后上架 Play，必须为该分发渠道移除本模块和相应安装权限，并采用相应的渠道更新机制，不能直接提交此侧载更新版本。政策参考：[Google Play REQUEST_INSTALL_PACKAGES 政策](https://support.google.com/googleplay/android-developer/answer/12085295)。

## 文件及生命周期

- 下载写入应用私有缓存 `verified-updates` 下唯一随机的 `.pending.apk`，该名字不被 provider 接受。
- 验证通过后改为只读文件，再原子重命名为随机 32 位小写十六进制 `.apk`。绝不覆盖已有待安装文件；创建/验证均使用 canonical 目录。
- provider 不导出，只能通过精确的临时 content URI 读授权访问。不接受路径穿越、编码路径、查询/fragment、写模式或写操作，不返回真实路径。只暴露显示文件名、文件大小、APK MIME 和只读描述符。
- 安装前再次检查大小、SHA、包信息、版本和当前签名。给安装器的授权只有 READ，不授予 WRITE 或持久访问。
- 用户取消、失败或页面销毁会删除本次未交付的下载。Activity 暂停时取消异步操作；延迟完成结果不能复活旧页面或自动安装。已成功交给安装器的不可变文件不在 onPause/onDestroy 删除，避免安装器尚未读取时文件消失。
- 已交付文件和异常退出遗留文件最多可被 provider 读取 24 小时，下次主动检查会清理过期缓存。系统也可能更早清理缓存；没有后台清理任务。过期权限即使尚未由系统撤回，也无法通过 provider 读取过期文件。

## 本次验证与未验证项

`sh tests/run.sh` 覆盖已有功能、发布渠道版本及签名连续性，还有 updater 纯 Java 检查，包括固定源/路径/跳转拒绝、schema 字段规则、版本防回退、APK 元数据一致性、缺失/不一致/多签名、hash、provider 文件名、超限/截断/空流/超时/取消。Python 静态守卫检查 manifest 权限/组件、当前签名 API、不可变文件、重校验、暂停取消、设置返回不自动安装、只读 URI 和敏感数据隔离。

**静态守卫及纯 Java 测试不是 Android 运行时测试。** CI 还会实际构建 APK、执行 lint、用临时证书签名并生成发布清单。真机或模拟器仍需覆盖 Android 26、28/29、35 上的以下场景。

- 启停新版提醒、拒绝通知权限、设备重启、通知去重、点击通知后重新检查。
- 清单 404/缺失字段/错误类型/无新版本、断网/限流/错误跳转/超大响应。
- 合法同签名更高版本完整 APK；错误 hash/大小/包名/版本/minSdk/签名、unsigned/损坏 APK、split APK、已安装版本变更。
- 在连接/下载/hash/包解析中取消、快速重复点击、Home/Back、旋转、透明上层 Activity/多窗口暂停、恢复页面，确认不启动后台安装、不泄漏旧回调。
- 安装来源权限拒绝/开启/撤回、返回后再次点击；安装器取消/成功、APK 已过期/缓存被清除、Activity 在安装器读取前销毁。
- 验证外部未获授权应用不能读取 provider，获只读授权也不能写、越界或请求 pending 文件。
- 更新后地图 Key/隐私同意/导航/定位功能回归，检查没有日志泄漏和未经授权的数据迁移。

Android 参考：[SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo)、[安装来源设置](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES)、[ContentProvider 安全说明](https://developer.android.com/guide/topics/providers/content-provider-creating#Permissions)。
