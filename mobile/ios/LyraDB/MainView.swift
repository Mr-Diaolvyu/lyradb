import SwiftUI
import WebKit

/// WKWebView 外壳主界面。
///
/// 移动端为 BS 封装客户端：本视图仅承载一个 WKWebView，加载用户配置的远端 BS 服务端地址，
/// 复用其 Vue 前端完成登录、数据源管理、SQL 查询、结果导出等全部业务。本地不承载任何数据库驱动与连接。
struct MainView: View {
    let serverURL: String
    var onSwitchServer: () -> Void

    var body: some View {
        NavigationStack {
            WebView(urlString: serverURL)
                .navigationTitle("LyraDB")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Menu {
                            Button("切换服务端", action: onSwitchServer)
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
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
