# LyraDB 移动端（Android 外壳）

> 状态：**BS 封装客户端外壳已实现**（原生外壳 + WebView 加载 BS 前端）。
> 本会话未在 Android Studio 中编译验证（本机无 Android 工具链），导入后需 Sync + 运行验证。
> 目标架构以 `product-discovery/移动端APP规划.md` 为准。

## 设计要点

- **BS 封装客户端**：原生外壳（Kotlin + AppCompat View 体系）承载启动/服务端配置/系统集成，内部 WebView 加载 BS 版本前端页面。
- **不在手机上直连数据库**：所有数据操作由所连 BS 服务端完成，天然支持全部 9 种数据库。
- **服务端两类来源**：
  - **个人自托管 BS 服务**（personal）：用户自部署（本机 Docker / 家庭服务器 / 小云主机），凭据归用户、无管理员/租户。
  - **企业 BS 服务**（enterprise）：团队部署，受 RBAC / 审批 / 审计治理。
- **启动时填写服务端地址即可**，一个 App 覆盖个人与企业两种场景。
- **外壳职责**：服务端地址管理与连通性校验、WebView 容器（JS/DOMStorage/Cookie 持久化）、结果导出下载、返回键历史回退。

## 接口复用

移动端 UI 完全复用 BS 前端（Vue 3），前端通过 HTTP(S)/WS(S) 调 BS 服务端 `/api/*`，与浏览器访问完全一致。外壳仅在配置页探测 `/api/app/info` 判断 edition 与可达性，不直接调业务 API。

## 服务端地址配置

- **模拟器**：`http://10.0.2.2:8080`（Android 模拟器访问宿主机 BS 服务）
- **真机**：填写 BS 服务实际地址（如 `http://192.168.1.10:8080`），生产环境要求 HTTPS

## 导入步骤

1. Android Studio → Open → 选 `mobile/android` 根目录。
2. Sync Gradle（联网下载 AppCompat / Material / WebKit / 协程依赖）。
3. 运行到模拟器/真机。
4. 首次启动填写 BS 服务端地址（个人自托管或企业）→「测试连接」→「进入」，WebView 加载前端后使用。
5. 切换服务端 / 退出登录：右上角菜单。

## 文件清单

- `app/build.gradle.kts` — 依赖（AppCompat / Material / WebKit / 协程）。
- `app/src/main/AndroidManifest.xml` — 权限（网络 / 下载 / 通知）+ 两个 Activity 注册。
- `.../mobile/MainActivity.kt` — WebView 外壳主界面（JS/Cookie/下载/返回键/菜单）。
- `.../mobile/ServerConfigActivity.kt` — 服务端地址配置页（填写 + 连通性校验 + 持久化）。
- `.../mobile/PrefsManager.kt` — 服务端地址本地持久化。
- `.../res/layout/activity_server_config.xml` — 配置页布局。

## 后续增强（可选）

- 生物识别快登（`BiometricPrompt` + Android Keystore）。
- 服务端地址加密存储（现用 SharedPreferences 明文，可升级为 EncryptedSharedPreferences）。
- 原生桥接（JavascriptInterface）暴露生物识别/安全存储给前端。
- 推送通知、证书校验/错误页/离线提示。

## 鸿蒙 NEXT（ArkTS）说明

鸿蒙端外壳逻辑等价：用 ArkWeb（Web 组件）替代 Android WebView、ArkUI 替代 AppCompat View、HUKS 替代 Keystore、@ohos.userIAM 替代 BiometricPrompt。前端与后端内核完全复用，仅外壳各自实现。工程见 `mobile/harmony`，需在 DevEco Studio 中打开。
