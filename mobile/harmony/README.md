# LyraDB HarmonyOS 外壳

版本：`3.0.1`。工程采用 ArkTS + ArkUI + ArkWeb，业务页面与权限规则来自远端 LyraDB 企业 B/S 服务端。

## 已实现

- 服务端地址只接受不含账号密码的 HTTPS origin 或 `origin/api`，持久化与 ArkWeb 加载地址统一为 origin 根路径 `/`；
- 连通性探测固定请求同源 `/api/app/info`，不会重复拼接 `/api/api`；
- ArkWeb 禁止文件访问与混合内容；
- 加载拦截器仅允许与配置服务端相同的 HTTPS origin；
- `@ohos.userIAM.userAuth` 应用锁失败关闭；
- 启用应用锁前必须完成一次设备认证；
- `LyraDBHarmony.saveBase64` 桥通过系统 `DocumentViewPicker` 让用户选择保存位置；
- 文件名清洗、Base64 载荷上限与覆盖前截断；
- Preferences 只保存规范化的 origin 根页面 URL 与应用锁开关，不保存数据库密码或登录令牌；
- 登录会话由 ArkWeb Cookie 存储管理。

严格同源策略会阻止页面直接加载第三方 CDN 资源。生产前端应把必要静态资源同源部署，外部 AI/Webhook 调用由服务端白名单控制。

## 导入

1. 用 DevEco Studio 打开 `mobile/harmony`。
2. Sync 工程并确认项目声明的 SDK/API 可用。
3. 运行到模拟器或真机。
4. 填写具备有效证书的 HTTPS origin，例如 `https://db.example.com` 或 `https://db.example.com/api/`。

应用图标已经位于 `AppScope` 与 `entry` 的资源目录中。

## 验证状态

当前 Windows 环境未安装 DevEco/HarmonyOS SDK，尚未完成编译与真机验证。发布前必须验证：

- ArkTS 编译、API 兼容性与 HAP 签名；
- HTTPS/WSS、同源拦截和页面静态资源完整性；
- 指纹、人脸、PIN 成功/取消/不可用分支；
- Document Picker 保存 CSV/JSON、取消与覆盖行为；
- 清理站点数据与切换服务端后的会话隔离。

如果目标 SDK 对旧式 `@ohos.*` 导入给出迁移提示，应统一迁移到对应 Kit API，并在真机回归后再发布。
