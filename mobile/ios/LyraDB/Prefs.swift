import Foundation

/// 本地偏好管理：持久化用户配置的服务端地址。
enum Prefs {
    private static let keyServerURL = "server_url"

    static var serverURL: String? {
        get { UserDefaults.standard.string(forKey: keyServerURL) }
        set { UserDefaults.standard.set(newValue, forKey: keyServerURL) }
    }
}
