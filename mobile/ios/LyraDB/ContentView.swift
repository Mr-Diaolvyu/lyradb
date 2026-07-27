import SwiftUI

/// 根视图：已配置服务端 → WebView 主界面；未配置 → 服务端配置页。
struct ContentView: View {
    @State private var serverURL: String? = Prefs.serverURL

    var body: some View {
        Group {
            if let url = serverURL, !url.isEmpty {
                MainView(serverURL: url, onSwitchServer: {
                    self.serverURL = nil
                })
            } else {
                ServerConfigView(onEnter: { url in
                    Prefs.serverURL = url
                    self.serverURL = url
                })
            }
        }
    }
}
