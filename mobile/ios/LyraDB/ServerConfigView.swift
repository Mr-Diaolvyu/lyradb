import SwiftUI

/// 服务端地址配置页。
///
/// 首次启动或切换服务端时进入。填写 BS 服务端地址（个人自托管或企业），
/// 校验 /api/app/info 可达后回调进入 WebView 主界面。
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

                TextField("服务端地址，如 http://192.168.1.10:8080", text: $serverURL)
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

                Text("说明：手机端为 BS 封装客户端，所有数据库连接与查询均由所连服务端完成。可连接个人自托管服务或企业 BS 服务。")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .padding(.top, 24)
            }
            .padding(24)
        }
    }

    private func normalize(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return "" }
        if !s.hasPrefix("http://") && !s.hasPrefix("https://") {
            s = "http://" + s
        }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    private func enter() {
        let url = normalize(serverURL)
        guard !url.isEmpty else {
            status = "请先填写服务端地址"
            return
        }
        onEnter(url)
    }

    private func testConnection() {
        let url = normalize(serverURL)
        guard !url.isEmpty else {
            status = "请先填写服务端地址"
            return
        }
        testing = true
        status = "连接中…"
        checkServer(base: url) { result in
            DispatchQueue.main.async {
                status = result
                testing = false
            }
        }
    }

    private func checkServer(base: String, completion: @escaping (String) -> Void) {
        guard let u = URL(string: base + "/api/app/info") else {
            completion("地址格式不正确")
            return
        }
        var req = URLRequest(url: u)
        req.timeoutInterval = 8
        URLSession.shared.dataTask(with: req) { data, response, error in
            if let error = error {
                completion("无法连接：\(error.localizedDescription)")
                return
            }
            if let http = response as? HTTPURLResponse, http.statusCode == 200, let data = data,
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
