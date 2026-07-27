import SwiftUI
import WebKit

/// WKWebView 外壳主界面。
///
/// 移动端为 BS 封装客户端：本视图仅承载一个 WKWebView，加载用户配置的远端 BS 服务端地址，
/// 复用其 Vue 前端完成登录、数据源管理、SQL 查询、结果导出等全部业务。本地不承载任何数据库驱动与连接。
/// 菜单提供：切换服务端、生物识别解锁开关、退出登录并清缓存。
struct MainView: View {
    let serverURL: String
    var onSwitchServer: () -> Void

    @State private var biometricOn: Bool = Prefs.biometricEnabled
    @State private var reloadToken = UUID()

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
                                Prefs.biometricEnabled.toggle()
                                biometricOn = Prefs.biometricEnabled
                            }
                            Button("退出登录并清缓存", role: .destructive, action: clearCacheAndReload)
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
        }
    }

    /// 清除 Cookie/缓存（企业版登录态）后重建 WebView 重新加载
    private func clearCacheAndReload() {
        let types = WKWebsiteDataStore.allWebsiteDataTypes()
        WKWebsiteDataStore.default().removeData(ofTypes: types, modifiedSince: .distantPast) {
            reloadToken = UUID()
        }
    }
}

/// WKWebView 的 SwiftUI 封装。
struct WebView: UIViewRepresentable {
    let urlString: String

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.allowsBackForwardNavigationGestures = true
        if let url = URL(string: urlString) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // 服务端地址变更由 ContentView 重建视图处理，这里无需额外操作
    }
}
