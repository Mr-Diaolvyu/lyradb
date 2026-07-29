# LyraDB iOS 外壳

版本：`3.0.0`。工程采用 SwiftUI + WKWebView，通过 XcodeGen 管理，不提交生成的 `.xcodeproj`。

## 已实现

- 接受 HTTPS origin 或 origin/api，统一保存并加载 origin 根路径 `/`；
- `Info.plist` 不包含 `NSAllowsArbitraryLoads`；
- WKWebView 只允许与配置服务端相同的 scheme、host、port；
- 非同源 HTTPS 链接交给系统打开，其余协议阻止；
- 登录会话保留在默认 `WKWebsiteDataStore`，不复制到 UserDefaults；
- `LocalAuthentication` 应用锁，设备认证不可用或失败时保持锁定；
- 启用应用锁前先完成一次设备认证；
- `lyradbDownload` 消息桥校验主 frame 与安全源，再通过系统分享/保存面板导出 Blob；
- 文件名清洗、Base64 载荷上限、独立临时目录及完成后清理；
- 连通性探测固定请求同源 /api/app/info，使用临时 URLSession，不存 Cookie 且不跟随重定向。

UserDefaults 只保存服务端 URL 与应用锁开关，不保存密码或会话令牌。

## 生成工程

在 macOS：

```bash
brew install xcodegen
cd mobile/ios
xcodegen generate
```

随后用 Xcode 打开 `LyraDB.xcodeproj`，设置 Team 与签名，再运行到模拟器或真机。

## 验证状态

当前开发环境为 Windows，无法执行 Xcode 编译。源码已做静态加固，但发布前必须在 macOS 验证：

- Swift 5.9 编译与 iOS 16 最低版本；
- HTTPS 证书、跨源跳转与 WSS；
- Face ID / Touch ID / 设备密码成功、取消与不可用分支；
- CSV/JSON 分享保存及用户取消；
- iPad popover 与临时文件清理；
- 清除站点数据后旧会话失效。

项目暂未包含正式 AppIcon 资产；发布前应补充完整 Asset Catalog，并在 `project.yml` 中恢复相应 AppIcon 设置。
