# MotoNav 独立恢复测试工程

从可恢复的0.6源码建立，**不是0.10原源码或完整功能恢复，不用于实际骑行**。
包名 `com.motonav.recovery`，版本 `1 / 0.1.0-recovery-preview`。
与旧MotoNav并存，不读取旧App私有数据，不要求卸载旧版；原仓库sim目录保持不变。

## 本次实现
- 桌面启动直接进入真实地图页；首次设置后自动隐藏配置面板。
- Android Keystore保护AES-GCM加密保存Android Key；禁用备份，不写Key日志、Intent或Bundle。
- 保存版本化隐私同意。支持撤回同意、删除加密配置和设备密钥；停止地图不依赖写盘成功。
- 每次前台检查系统实际定位权限。首次请求粗略/精确定位；拒绝或撤销后不循环弹窗，主动重试才再请求或打开设置。
- 已授权时连续前台定位并跟随当前位置，后台停止定位。使用单调时间检查15秒时效；SDK未提供单调时间时只接受有效的新墙钟时间，不把陈旧缓存/未来时间标成新位置。
- 保留0.6模拟功能入口，模拟数据不代表真实导航。

## 验证状态
GitHub Actions首轮提交d38d4fd完成了真实Android `assembleDebug`、`lintDebug`及纯Java检查：
https://github.com/gargamelzhao0-del/motonav/actions/runs/42979339851

首轮之后源码复查修复了单次定位模式及拒绝同意未持久化的问题。**最新提交的构建结果以对应GitHub Actions为准**，不要用旧提交绿灯代替新提交验证。
纯Java检查包含原有26,569项和新增522项权限/时效决策检查。额外源码契约检查只防止已知接线错误回归，不是运行时验收。

未连接手机/模拟器；Keystore、重启/旋转/同签名升级、系统权限、定位和地图鉴权均未实机验证。CI不提供正式安装包，不持久保存临时debug签名。

## 尚未恢复
真实目的地搜索、摩托算路/导航、完整GTA圆形地图外观、0.9完整定位恢复、0.10远程更新流程及相应测试。
没有语音、后台导航、自动偏航重算或真实数据到ESP32。
错误16仍需高德服务授权；本工程不绕过权限，也不以汽车路线冒充摩托路线。

## 构建
使用JDK17、Gradle8.9和Android SDK35，在android-recovery目录执行：
- `sh tests/run.sh`
- `gradle --no-daemon assembleDebug lintDebug`

本仓库只含文本源码和配置，不含二进制Gradle wrapper、APK、Key或签名私钥。GitHub Actions仅测试、编译和lint，不发布APK；没有使用用户Key或生产签名。

## 首次安装前仍需完成
- 固定并安全保管新测试签名，不能把每次CI新生成的debug证书当作可持续升级签名。
- 新包名须在高德控制台绑定相应Android Key与实际签名SHA1，不能假设旧绑定适用；不要把Key或私钥发入聊天。
- 首次高德隐私同意及安卓权限由使用者操作；后续权限仍有效才可自动定位。
- 新应用不继承旧数据。旧App应保留，不卸载或清除其数据。以后本测试App覆盖升级也须相同签名及更高版本号，并实际验证数据兼容。

参考：
https://lbs.amap.com/api/android-sdk/guide/create-map/mylocation
https://a.amap.com/lbs/static/unzip/Android_Location_Doc/com/amap/api/location/AMapLocationClient.html
