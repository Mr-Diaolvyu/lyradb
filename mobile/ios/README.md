# LyraDB 移动端（iOS 外壳）

> 状态：**BS 封装客户端外壳已搭建**（SwiftUI + WKWebView）。
> 本会话未在 Xcode 中编译验证（本机为 Windows，无 Apple 工具链），需在 macOS + Xcode 中生成工程并运行验证。
> 目标架构以 `product-discovery/移动端APP规划.md` 为准。

## 设计要点

- **BS 封装客户端**：原生外壳（SwiftUI）承载启动/服务端配置，内部 WKWebView 加载 BS 版本前端页面。
- **不在手机上直连数据库**：所有数据操作由所连 BS 服务端完成，天然支持全部 9 种数据库。
- **服务端两类来源**：个人自托管 BS 服务（personal）/ 企业 BS 服务（enterprise）。
- **启动时填写服务端地址即可**，一个 App 覆盖个人与企业两种场景。
- **外壳职责**：服务端地址管理与连通性校验（URLSession 探测 `/api/app/info`）、WKWebView 容器（JS 启用）、手势返回历史回退。

## 生成工程与运行

源码以 XcodeGen 描述（`project.yml`）管理，避免提交二进制 `.xcodeproj`：

1. 安装 XcodeGen：`brew install xcodegen`
2. 在 `mobile/ios` 目录执行：`xcodegen generate`，生成 `LyraDB.xcodeproj`
3. Xcode 打开工程 → 配置签名（Team）→ 运行到模拟器/真机。
4. 首次启动填写 BS 服务端地址 →「测试连接」→「进入」，WKWebView 加载前端后使用。
5. 切换服务端：右上角菜单。

> 也可直接在 Xcode 新建 iOS App 工程，将 `LyraDB/` 下的 Swift 文件加入 target，并采用其中的 `Info.plist`。

## 文件清单

- `project.yml` — XcodeGen 工程描述（生成 .xcodeproj）。
- `LyraDB/LyraDBApp.swift` — SwiftUI 入口。
- `LyraDB/ContentView.swift` — 根视图（按是否已配置服务端分流）。
- `LyraDB/ServerConfigView.swift` — 服务端地址配置页（填写 + 连通性校验 + 持久化）。
- `LyraDB/MainView.swift` — WKWebView 外壳主界面 + WebView 封装。
- `LyraDB/Prefs.swift` — 服务端地址本地持久化（UserDefaults）。
- `LyraDB/Info.plist` — 应用配置 + ATS 例外（开发期允许自托管 HTTP）。

## 后续增强（可选）

- 生物识别快登（LocalAuthentication / Keychain）。
- 服务端地址存 Keychain。
- 文件下载（结果导出）适配、ATS 收紧为指定域名例外。

## 三端外壳的对应关系

| 能力 | Android | 鸿蒙 NEXT | iOS |
|------|---------|-----------|-----|
| WebView 容器 | WebView | ArkWeb（Web 组件） | WKWebView |
| UI 体系 | AppCompat View | ArkUI | SwiftUI |
| 本地存储 | SharedPreferences | @ohos.data.preferences | UserDefaults |
| 安全密钥 | Keystore | HUKS | Keychain |
| 生物识别 | BiometricPrompt | @ohos.userIAM.userAuth | LocalAuthentication |

前端与后端内核完全复用，仅外壳各自实现。
