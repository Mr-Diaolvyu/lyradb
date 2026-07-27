# LyraDB 移动端（鸿蒙 NEXT 外壳）

> 状态：**BS 封装客户端外壳已搭建**（ArkTS + ArkUI + ArkWeb）。
> 本会话未在 DevEco Studio 中编译验证（本机无鸿蒙工具链），导入后需补应用图标并运行验证。
> 目标架构以 `product-discovery/移动端APP规划.md` 为准。

## 设计要点

- **BS 封装客户端**：原生外壳（ArkTS + ArkUI）承载启动/服务端配置，内部 ArkWeb（`Web` 组件）加载 BS 版本前端页面。
- **不在手机上直连数据库**：所有数据操作由所连 BS 服务端完成，天然支持全部 9 种数据库。
- **服务端两类来源**：个人自托管 BS 服务（personal）/ 企业 BS 服务（enterprise）。
- **启动时填写服务端地址即可**，一个 App 覆盖个人与企业两种场景。
- **外壳职责**：服务端地址管理与连通性校验（`@ohos.net.http` 探测 `/api/app/info`）、ArkWeb 容器（JS/DOMStorage）、返回键历史回退。

## 导入步骤

1. DevEco Studio（NEXT / API 12+）→ Open → 选 `mobile/harmony` 根目录。
2. 在 `entry/src/main/resources/base/media/` 放入应用图标 `app_icon.png`（module.json5 与 app.json5 已引用 `$media:app_icon`）。
3. Sync 后运行到鸿蒙模拟器/真机。
4. 首次启动填写 BS 服务端地址 →「测试连接」→「进入」，ArkWeb 加载前端后使用。

## 文件清单

- `AppScope/app.json5` — 应用级配置（包名 / 版本 / 图标）。
- `entry/src/main/module.json5` — 模块配置 + 权限（INTERNET / GET_NETWORK_INFO）+ Ability 注册。
- `entry/src/main/ets/entryability/EntryAbility.ets` — 入口 Ability，加载 `pages/Index`。
- `entry/src/main/ets/pages/Index.ets` — ArkWeb 外壳主界面（JS/DOMStorage/返回键回退）。
- `entry/src/main/ets/pages/ServerConfig.ets` — 服务端地址配置页（填写 + 连通性校验 + 持久化）。
- `entry/src/main/resources/base/profile/main_pages.json` — 页面路由表。

## 后续增强（可选）

- 生物识别快登（`@ohos.userIAM.userAuth` + HUKS）。
- 服务端地址加密存储。
- 文件下载（结果导出）适配、证书校验/错误页/离线提示。

## 与 Android 外壳的对应关系

| 能力 | Android | 鸿蒙 NEXT |
|------|---------|-----------|
| WebView 容器 | WebView | ArkWeb（Web 组件） |
| UI 体系 | AppCompat View | ArkUI |
| 本地存储 | SharedPreferences | @ohos.data.preferences |
| 安全密钥 | Keystore | HUKS |
| 生物识别 | BiometricPrompt | @ohos.userIAM.userAuth |

前端与后端内核完全复用，仅外壳各自实现。
