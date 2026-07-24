# LyraDB 移动端（Android 瘦客户端参考骨架）

> 状态：**参考骨架**，本会话未在 Android Studio 中编译验证（无 Android 工具链）。
> 旨在可直接导入 Android Studio 后补全依赖、连后端联调。架构与 `product-discovery/mobile-app.md` 一致。

## 设计要点
- **瘦客户端**：不直连数据库；所有数据经企业后端 `/api/*`（会话 Cookie + JWT/Session）。
- 复用后端契约：`/auth/login` `/grants/mine` `/ent/query` `/ai/chat` `/approvals` `/audit/mine` `/app/info`。
- 移动 MVP（按规划）：登录 / 我的数据源（逻辑，无连接信息）/ AI 对话查数据 / 审批中心（移动审批）/ 操作审计（本人）。编辑/迁移/ER 不上移动端。

## 后端地址配置
- 默认指向本机开发后端：`http://10.0.2.2:8080/api`（Android 模拟器访问宿主机 8080）。
- 真机：改为后端实际 IP，并在 `AndroidManifest.xml` 允许明文 HTTP（`usesCleartextTraffic`）或用 HTTPS。

## 导入步骤
1. Android Studio → Open → 选 `mobile/android` 根目录（需自行生成 `settings.gradle.kts`/`build.gradle.kts` 根工程，或新建 Empty Compose 工程后把 `app/` 内容并入）。
2. Sync Gradle（联网下载 Compose/Retrofit/OkHttp 依赖）。
3. 运行到模拟器/真机，登录 `admin/admin`（企业版）。
4. 与企业版后端（`LYRADB_EDITION=enterprise`，端口 8080）联调。

## 文件清单
- `app/build.gradle.kts` — 依赖（Compose/Retrofit/OkHttp/ViewModel）。
- `app/src/main/AndroidManifest.xml` — 权限 + 明文 HTTP 允许。
- `app/src/main/java/io/github/lexaquila/lyradb/mobile/MainActivity.kt` — 入口 + 导航。
- `.../network/ApiClient.kt` — Retrofit + OkHttp（会话 Cookie 持久化）。
- `.../network/ApiService.kt` — 后端接口镜像。
- `.../network/Models.kt` — 数据类。
- `.../ui/Screens.kt` — 登录/我的数据源/AI/审批/审计 Compose 界面。

## 鸿蒙 NEXT（ArkTS）说明
鸿蒙端逻辑等价：用 `@ohos.net.http` 替代 OkHttp、ArkUI 替代 Compose、HUKS 替代 Keystore；接口契约与本骨架一致。需在 DevEco Studio 中按 `mobile-app.md` 新建 ArkTS 工程。
