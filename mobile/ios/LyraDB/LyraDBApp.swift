import SwiftUI

/// LyraDB 移动端 iOS 外壳入口。
///
/// 移动端为 BS 封装客户端：原生外壳（SwiftUI）承载启动/服务端配置，
/// 内部 WKWebView 加载远端 BS 前端，所有数据库连接与查询均由所连服务端完成。
@main
struct LyraDBApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
