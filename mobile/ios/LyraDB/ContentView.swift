import SwiftUI
import LocalAuthentication

/// 根视图：开启生物识别时先解锁；已配置服务端 → WebView 主界面；未配置 → 服务端配置页。
struct ContentView: View {
    @State private var serverURL: String? = Prefs.serverURL
    @State private var unlocked: Bool = !Prefs.biometricEnabled

    var body: some View {
        Group {
            if !unlocked {
                LockView(onUnlocked: { self.unlocked = true })
            } else if let url = serverURL, !url.isEmpty {
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

/// 锁定视图：Face ID / Touch ID / 设备密码解锁；设备无可用认证方式时直接放行。
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
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            onUnlocked() // 设备未设置任何认证方式：跳过解锁
            return
        }
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "解锁 LyraDB") { ok, _ in
            DispatchQueue.main.async {
                if ok {
                    onUnlocked()
                } else {
                    message = "解锁失败，请重试"
                }
            }
        }
    }
}
