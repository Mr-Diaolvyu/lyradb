import LocalAuthentication
import SwiftUI
import UIKit
import WebKit

/// WKWebView 外壳主界面。
///
/// 移动端只承载用户配置的 HTTPS 服务端；数据库连接、查询与授权均由服务端处理。
/// Cookie 保留在 WKWebsiteDataStore 中，不把会话或密码写入 UserDefaults。
struct MainView: View {
    let serverURL: String
    var onSwitchServer: () -> Void

    @State private var biometricOn: Bool = Prefs.biometricEnabled
    @State private var reloadToken = UUID()
    @State private var securityMessage: String?

    var body: some View {
        NavigationStack {
            WebView(urlString: serverURL)
                .id(reloadToken)
                .navigationTitle("LyraDB")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Menu {
                            Button("切换服务端", action: onSwitchServer)
                            Button(biometricOn ? "关闭生物识别解锁" : "启用生物识别解锁") {
                                toggleBiometric()
                            }
                            Button("退出登录并清缓存", role: .destructive, action: clearCacheAndReload)
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
                .alert(
                    "安全提示",
                    isPresented: Binding(
                        get: { securityMessage != nil },
                        set: { if !$0 { securityMessage = nil } }
                    )
                ) {
                    Button("知道了", role: .cancel) {}
                } message: {
                    Text(securityMessage ?? "")
                }
        }
    }

    private func toggleBiometric() {
        if biometricOn {
            Prefs.biometricEnabled = false
            biometricOn = false
            return
        }

        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            securityMessage = "设备未设置可用的生物识别或锁屏凭据，无法启用应用锁。"
            return
        }
        context.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: "验证身份并启用 LyraDB 应用锁"
        ) { success, _ in
            DispatchQueue.main.async {
                guard success else {
                    securityMessage = "身份验证未通过，应用锁未启用。"
                    return
                }
                Prefs.biometricEnabled = true
                biometricOn = true
            }
        }
    }

    /// 清除所有 WebKit 站点数据，确保 HttpOnly 会话 Cookie 也被移除。
    private func clearCacheAndReload() {
        let types = WKWebsiteDataStore.allWebsiteDataTypes()
        WKWebsiteDataStore.default().removeData(ofTypes: types, modifiedSince: .distantPast) {
            reloadToken = UUID()
        }
    }
}

/// WKWebView 的 SwiftUI 封装。只允许配置服务端的同源 HTTPS 页面在应用内加载。
struct WebView: UIViewRepresentable {
    let urlString: String

    func makeCoordinator() -> Coordinator {
        Coordinator(trustedOrigin: TrustedOrigin(urlString: urlString))
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.userContentController.add(context.coordinator, name: Coordinator.downloadHandler)

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.allowsLinkPreview = false

        if let url = URL(string: urlString), context.coordinator.isTrusted(url: url) {
            webView.load(URLRequest(url: url))
        } else {
            context.coordinator.showAlert(title: "地址无效", message: "发布版仅允许加载 HTTPS 服务端。")
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // 服务端地址变更由 ContentView 重建视图处理。
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.stopLoading()
        uiView.navigationDelegate = nil
        uiView.configuration.userContentController.removeScriptMessageHandler(
            forName: Coordinator.downloadHandler
        )
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        static let downloadHandler = "lyradbDownload"
        private static let maxBase64Characters = 48 * 1024 * 1024
        private let trustedOrigin: TrustedOrigin?

        init(trustedOrigin: TrustedOrigin?) {
            self.trustedOrigin = trustedOrigin
        }

        func isTrusted(url: URL) -> Bool {
            trustedOrigin?.matches(url: url) == true
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }
            if isTrusted(url: url) {
                decisionHandler(.allow)
                return
            }

            // 非同源 HTTPS 链接交给系统浏览器，其余协议直接阻止。
            if url.scheme?.lowercased() == "https" {
                UIApplication.shared.open(url)
            }
            decisionHandler(.cancel)
        }

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard message.name == Self.downloadHandler,
                  message.frameInfo.isMainFrame,
                  trustedOrigin?.matches(securityOrigin: message.frameInfo.securityOrigin) == true,
                  let payload = message.body as? [String: Any],
                  let fileName = payload["fileName"] as? String,
                  let base64 = payload["base64"] as? String,
                  !base64.isEmpty,
                  base64.count <= Self.maxBase64Characters,
                  let data = Data(base64Encoded: base64, options: [.ignoreUnknownCharacters])
            else {
                showAlert(title: "导出失败", message: "下载请求无效或文件过大。")
                return
            }

            do {
                let directory = FileManager.default.temporaryDirectory
                    .appendingPathComponent(UUID().uuidString, isDirectory: true)
                try FileManager.default.createDirectory(
                    at: directory,
                    withIntermediateDirectories: true
                )
                let fileURL = directory.appendingPathComponent(sanitizeFileName(fileName))
                try data.write(to: fileURL, options: [.atomic])
                presentShareSheet(fileURL: fileURL, cleanupDirectory: directory)
            } catch {
                showAlert(title: "导出失败", message: "无法保存下载文件。")
            }
        }

        private func sanitizeFileName(_ value: String) -> String {
            let lastComponent = URL(fileURLWithPath: value).lastPathComponent
            let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-"))
            let cleaned = lastComponent.unicodeScalars
                .map { allowed.contains($0) ? String($0) : "_" }
                .joined()
            let limited = String(cleaned.prefix(120))
            if limited.isEmpty || limited == "." || limited == ".." {
                return "lyradb-export.bin"
            }
            return limited
        }

        private func presentShareSheet(fileURL: URL, cleanupDirectory: URL) {
            DispatchQueue.main.async {
                guard let controller = Self.topViewController() else {
                    try? FileManager.default.removeItem(at: cleanupDirectory)
                    return
                }
                let sheet = UIActivityViewController(
                    activityItems: [fileURL],
                    applicationActivities: nil
                )
                if let popover = sheet.popoverPresentationController {
                    popover.sourceView = controller.view
                    popover.sourceRect = CGRect(
                        x: controller.view.bounds.midX,
                        y: controller.view.bounds.midY,
                        width: 1,
                        height: 1
                    )
                }
                sheet.completionWithItemsHandler = { _, _, _, _ in
                    try? FileManager.default.removeItem(at: cleanupDirectory)
                }
                controller.present(sheet, animated: true)
            }
        }

        func showAlert(title: String, message: String) {
            DispatchQueue.main.async {
                guard let controller = Self.topViewController() else { return }
                let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "知道了", style: .default))
                controller.present(alert, animated: true)
            }
        }

        private static func topViewController(
            from root: UIViewController? = UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first?.rootViewController
        ) -> UIViewController? {
            if let navigation = root as? UINavigationController {
                return topViewController(from: navigation.visibleViewController)
            }
            if let tab = root as? UITabBarController {
                return topViewController(from: tab.selectedViewController)
            }
            if let presented = root?.presentedViewController {
                return topViewController(from: presented)
            }
            return root
        }
    }
}

private struct TrustedOrigin {
    let scheme: String
    let host: String
    let port: Int

    init?(urlString: String) {
        guard let components = URLComponents(string: urlString),
              components.scheme?.lowercased() == "https",
              let host = components.host?.lowercased(),
              !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil
        else {
            return nil
        }
        let resolvedPort = components.port ?? 443
        guard (1...65535).contains(resolvedPort) else {
            return nil
        }
        scheme = "https"
        self.host = host
        port = resolvedPort
    }

    func matches(url: URL) -> Bool {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let candidateHost = components.host?.lowercased()
        else {
            return false
        }
        return components.scheme?.lowercased() == scheme
            && candidateHost == host
            && (components.port ?? 443) == port
    }

    func matches(securityOrigin: WKSecurityOrigin) -> Bool {
        securityOrigin.`protocol`.lowercased() == scheme
            && securityOrigin.host.lowercased() == host
            && (securityOrigin.port == 0 ? 443 : securityOrigin.port) == port
    }
}

private extension UIWindowScene {
    var keyWindow: UIWindow? {
        windows.first(where: { $0.isKeyWindow })
    }
}
