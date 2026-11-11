# MotoNav 独立恢复测试工程（源码草稿，未生成APK）

## 当前状态
从可恢复的0.6源码建立独立工程；不是0.10原源码，不是0.10完整功能恢复。
包名 com.motonav.recovery，versionCode 1，versionName 0.1.0-recovery-preview。
目标是保留旧MotoNav并行测试，不会读取旧App私有数据，不要求卸载旧版。

## 本次源码改动
- 桌面启动入口改为真实地图Activity。首次设置后隐藏配置面板，保留设置和定位重试入口。
- Android Keystore AES-GCM加密保存Android Key；禁用备份，Key不进日志/Intent/Bundle，支持删除已存配置与撤回同意。
- 保存本版隐私告知同意；新版本如改变数据用途，须提升同意版本并重新告知。
- 每次前台检查实际系统权限，不用保存的布尔值冒充定位权限。
- 首次请求粗略/精确定位；拒绝或撤销后不自动循环弹窗。用户主动重试才再次请求或打开系统设置。
- 已授权时开启高德内置定位并显示当前位置；后台停止地图定位。15秒定位时效检查使用单调时间，不把旧位置当新定位。
- 继续保留0.6模拟功能入口，模拟数据不是实际导航。

## 验证与明确缺口
validation/java-tests.txt：原有26,569项纯Java检查通过；新增522项权限决策/定位时效边界检查通过。
这些检查没有执行Android Activity、Keystore、系统权限弹窗或高德SDK。
validation/build-attempt.txt：Gradle 8.9下载因 Network is unreachable 失败。当前缺Gradle发行版及Android SDK，因此assembleDebug、lintDebug未执行成功，APK未生成。
未连接手机/模拟器；加密存储重启/旋转/同签名覆盖升级、权限撤销及定位效果均未实机验证。
旧android目录内验收文档/validation属于0.6历史资料，不代表本次验证。以本文件和根validation为准。

尚未恢复0.7–0.10新增的真实目的地搜索、摩托算路、真实导航状态、完整GTA圆形小地图外观、0.9定位恢复实现、0.10远程更新流程及其测试。
没有语音、后台导航、自动偏航重算、真实数据ESP32传输。不适用于实际骑行。
错误16仍需高德服务授权，本工程不会绕过权限或以汽车路线冒充摩托路线。

## 安装和配置边界
现在无可安装包。后续构建需固定并安全保存新的测试签名，不能使用随机变化的debug证书作为长期更新方案。
新包名须在高德控制台绑定对应Android Key及实际新签名SHA1；旧包名/签名绑定不能假设直接适用。不要把Key或私钥发入聊天。
首次系统定位权限及高德隐私告知仍由使用者确认。新应用不继承旧版数据；旧应用应保留，不卸载、不清数据。
本工程自己后续覆盖升级也需要相同包名、相同签名和更高版本号；实际升级兼容须测试后才能承诺。

## 后续完成顺序
1. 恢复允许下载官方Gradle、Android SDK及Maven依赖的构建环境，运行assembleDebug和lintDebug；修复所有实际编译/lint问题。
2. 生成并持久安全保管测试签名，在设备验证首次设置、拒绝/撤销、进出后台、旋转、强停重启、Keystore失败及同签名升级。
3. 对照0.10 APK辅助分析自有资源/行为，分批恢复真实搜索/摩托导航和视觉界面，再补测试。APK不能恢复原签名私钥。

参考：
https://a.amap.com/lbs/static/unzip/Android_Location_Doc/com/amap/api/location/AMapLocationClient.html
https://developer.amap.com/api/location-sdk-for-android/guide/create-project/dev-attention

## 仓库构建方式
恢复工程提交于独立 recovery 分支下的 android-recovery/，原sim目录不变。
本次仓库不提交二进制Gradle wrapper、APK、构建目录、Key或签名私钥。
使用JDK17、Gradle8.9、Android SDK35：在android-recovery运行 sh tests/run.sh，再运行 gradle --no-daemon assembleDebug lintDebug。
GitHub Actions仅编译/lint/测试，不发布或分发APK，不使用用户Key，也不保存CI临时debug签名。CI成功不等于手机验收，更不能将CI生成的临时签名视为长期升级签名。

独立源码复查补充：SDK定位缺少单调时间戳时，按有效且不超过15秒的定位墙钟时间推算单调时刻；不将陈旧缓存/未来时间标为新定位。无效Key修改不再销毁当前地图。仍需SDK编译与实机验证。
