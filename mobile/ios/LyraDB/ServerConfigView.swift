import SwiftUI

/// 首次启动或切换服务端时进入。发布构建只接受 HTTPS 服务端。
struct ServerConfigView: View {
    var onEnter: (String) -> Void

    @State private var serverURL: String = Prefs.serverURL ?? ""
    @State private var status: String = ""
    @State private var testing: Bool = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text("LyraDB")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .padding(.top, 40)
                Text("连接到你的 BS 服务端")
                    .font(.subheadline)
                    .foregroundColor(.secondary)

                TextField("HTTPS origin 或 /api（最终加载根 /）", text: $serverURL)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.URL)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .padding(.top, 24)

                Text(status)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: testConnection) {
                    Text(testing ? "连接中…" : "测试连接")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(testing)

                Button(action: enter) {
                    Text("进入")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)

                Text("发布版仅连接 HTTPS 服务端。数据库凭据与会话由服务端和 WebKit Cookie 管理，本地偏好仅保存服务端地址与应用锁开关。")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .padding(.top, 24)
            }
            .padding(24)
        }
    }

    private func validatedURL() -> URL? {
        ServerURLPolicy.canonicalAppURL(serverURL)
    }

    private func enter() {
        guard let url = validatedURL() else {
            status = "请输入 HTTPS origin 或 origin/api，不要包含账号、查询或片段"
            return
        }
        onEnter(url.absoluteString)
    }

    private func testConnection() {
        guard let url = validatedURL() else {
            status = "请输入 HTTPS origin 或 origin/api，不要包含账号、查询或片段"
            return
        }
        testing = true
        status = "连接中…"
        serverURL = url.absoluteString
        checkServer(appURL: url) { result in
            DispatchQueue.main.async {
                status = result
                testing = false
            }
        }
    }

    private func checkServer(appURL: URL, completion: @escaping (String) -> Void) {
        guard let url = ServerURLPolicy.appInfoURL(for: appURL) else {
            completion("地址格式不正确")
            return
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        let session = URLSession(
            configuration: configuration,
            delegate: NoRedirectDelegate(),
            delegateQueue: nil
        )
        session.dataTask(with: request) { data, response, error in
            defer { session.finishTasksAndInvalidate() }
            if let error = error {
                completion("无法连接：\(error.localizedDescription)")
                return
            }
            if let http = response as? HTTPURLResponse,
               http.statusCode == 200,
               http.url?.scheme?.lowercased() == "https",
               let data = data,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let edition = json["edition"] as? String ?? "?"
                let auth = json["authRequired"] as? Bool ?? false
                let version = json["version"] as? String ?? "?"
                completion("连接成功：\(edition) 版（v\(version)），\(auth ? "需登录" : "免登录")")
            } else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? -1
                completion("连接失败：HTTP \(code)")
            }
        }.resume()
    }
}

/// 连通性探测不跟随重定向，防止用户配置的地址把探测请求带往其他源。
private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
