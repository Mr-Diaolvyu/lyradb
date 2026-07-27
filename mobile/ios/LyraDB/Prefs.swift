import Foundation

/// 本地偏好管理：持久化用户配置的服务端地址与生物识别开关。
enum Prefs {
    private static let keyServerURL = "server_url"
    private static let keyBiometric = "biometric_enabled"

    static var serverURL: String? {
        get { UserDefaults.standard.string(forKey: keyServerURL) }
        set { UserDefaults.standard.set(newValue, forKey: keyServerURL) }
    }

    static var biometricEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: keyBiometric) }
        set { UserDefaults.standard.set(newValue, forKey: keyBiometric) }
    }
}
