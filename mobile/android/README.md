# LyraDB Android 外壳

版本：`3.1.1`。这是 Kotlin + Android WebView 的企业 B/S 客户端，数据库访问与授权均由远端 LyraDB 服务端完成。

## 已实现

- 发布构建只接受 HTTPS；debug 构建可显式填写 HTTP 调试地址；
- 接受 origin 或 origin/api，统一保存并加载 origin 根路径 `/`；精确校验 scheme、host、port；
- 禁止混合内容、文件/内容协议访问与第三方 Cookie；
- 使用 HttpOnly 会话 Cookie，不把密码或令牌写入 SharedPreferences；
- `BiometricPrompt` / 设备凭据应用锁，能力异常或认证失败时保持锁定；
- Blob 导出经 `LyraDBAndroid` 最小桥写入 MediaStore Downloads；
- 文件名清洗、Base64 载荷上限、退出登录清 Cookie 与缓存；
- release Manifest 禁止明文流量，debug Manifest 仅为本机调试放开。

SharedPreferences 只保存服务端 URL 和应用锁开关，它们不是凭据，因此没有伪装成 Keystore 加密存储。

## 导入与运行

1. Android Studio 打开 `mobile/android`。
2. 使用 JDK 17，Sync Gradle。
3. 运行 Debug 构建。
4. 填写服务端 origin（也兼容已带 `/api`），客户端加载根 `/` 前端并探测 `/api/app/info`。

Android 模拟器访问宿主机开发服务可显式填写：

```text
http://10.0.2.2:8080/
```

该地址只适用于 debug。release 必须使用具备有效证书的 HTTPS 地址。

## 自动化

仓库 PR CI 使用 Gradle 8.2.1 执行：

```bash
gradle --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

项目当前未提交 Gradle Wrapper，因此本地优先通过 Android Studio Sync；后续应补充并校验 Wrapper checksum。

## 发布前真机检查

- release 包拒绝 HTTP 与跨源导航；
- 设备认证取消/不可用时不会进入 WebView；
- CSV/JSON Blob 能保存到下载目录；
- 超大文件给出限制提示；
- 清缓存后旧企业会话失效；
- release 签名、目标 SDK 权限与系统下载通知正常。
