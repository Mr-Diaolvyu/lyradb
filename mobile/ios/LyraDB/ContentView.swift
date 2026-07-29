import LocalAuthentication
import SwiftUI

/// 根视图：开启应用锁时必须通过设备认证；未配置有效 HTTPS 地址时进入配置页。
struct ContentView: View {
    @State private var serverURL: String? = Prefs.serverURL
    @State private var unlocked: Bool = !Prefs.biometricEnabled

    var body: some View {
        Group {
            if !unlocked {
                LockView(onUnlocked: { self.unlocked = true })
            } else if let rawURL = serverURL,
                      let appURL = ServerURLPolicy.canonicalAppURL(rawURL) {
                MainView(serverURL: appURL.absoluteString, onSwitchServer: {
                    self.serverURL = nil
                })
            } else {
                ServerConfigView(onEnter: { url in
                    Prefs.serverURL = url
                    self.serverURL = url
                })
            }
        }
        .onAppear(perform: migrateSavedServerURL)
    }

    private func migrateSavedServerURL() {
        guard let rawURL = serverURL else { return }
        guard let appURL = ServerURLPolicy.canonicalAppURL(rawURL) else {
            Prefs.serverURL = nil
            serverURL = nil
            return
        }
        let canonical = appURL.absoluteString
        if canonical != rawURL {
            Prefs.serverURL = canonical
            serverURL = canonical
        }
    }
}

/// 锁定视图：Face ID、Touch ID 或设备密码解锁。认证能力不可用时保持锁定。
struct LockView: View {
    var onUnlocked: () -> Void
    @State private var message: String = ""

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.fill")
                .font(.largeTitle)
                .foregroundColor(.secondary)
            Text("LyraDB 已锁定")
                .font(.headline)
            Text(message)
                .font(.footnote)
                .foregroundColor(.secondary)
            Button("解锁", action: authenticate)
                .buttonStyle(.borderedProminent)
        }
        .onAppear(perform: authenticate)
    }

    private func authenticate() {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            message = "设备认证不可用，LyraDB 保持锁定。请先在系统中设置锁屏凭据。"
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "解锁 LyraDB") {
            success,
            _ in
            DispatchQueue.main.async {
                if success {
                    onUnlocked()
                } else {
                    message = "解锁失败，请重试"
                }
            }
        }
    }
}
