import Foundation

/// 移动外壳统一服务端地址策略。
///
/// 接受 HTTPS origin 或 origin/api，持久化与 WebView 加载值统一为
/// origin 根路径 /；探测地址固定为同源 origin/api/app/info。
enum ServerURLPolicy {
    static func canonicalAppURL(_ raw: String) -> URL? {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        if !value.contains("://") {
            value = "https://" + value
        }
        guard var components = URLComponents(string: value),
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
        let port = components.port ?? 443
        guard (1...65535).contains(port) else { return nil }

        let acceptedPaths = ["", "/", "/api", "/api/"]
        guard acceptedPaths.contains(components.percentEncodedPath) else { return nil }
        components.scheme = "https"
        components.host = host
        components.percentEncodedPath = "/"
        return components.url
    }

    static func appInfoURL(for appURL: URL) -> URL? {
        guard var components = URLComponents(
            url: appURL,
            resolvingAgainstBaseURL: false
        ) else {
            return nil
        }
        components.percentEncodedPath = "/api/app/info"
        components.query = nil
        components.fragment = nil
        return components.url
    }
}
