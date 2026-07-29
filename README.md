# LyraDB · 天琴智库

> 轻若天琴，智驭万库 —— 轻量、AI 驱动的通用数据库管理工具。

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Vue](https://img.shields.io/badge/Vue-3.4-42b883)]()
[![Version](https://img.shields.io/badge/version-3.0.0-334155)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)]()

**简体中文** | [English](README.en.md)

LyraDB 统一连接 MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse、SQLite、MongoDB、Redis、MaxCompute 等数据源，提供查询编辑、AI 辅助、ER 图、导入导出以及企业治理能力。同一代码库支持个人桌面与企业 Web 部署。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 多数据源 | 驱动按需下载，统一管理关系型、文档型、OLAP 与云数仓 |
| SQL 工作台 | Monaco 编辑器、补全、多标签、执行计划与结果导出 |
| AI 辅助 | 自然语言生成 SQL；自定义 AI 与 Webhook 默认禁止出站，需主机白名单 |
| 企业治理 | 工作空间 RBAC、数据源授权、SQL 绑定审批、审计与一次性导出 |
| 多端客户端 | Web、jpackage 桌面端，以及 Android / HarmonyOS / iOS WebView 外壳 |
| 可复现交付 | npm 锁定安装、Maven 测试、PR CI、版本一致性检查与 SHA-256 发布校验 |

## 运行模式

| 场景 | 默认 edition | 认证与用途 |
| --- | --- | --- |
| 本地开发 / 桌面 profile | `personal` | 单用户本地使用 |
| `prod` profile / Docker Compose | `enterprise` | 多用户、RBAC、审批与审计 |

生产模式没有默认管理员口令。企业空库首次启动必须提供
`LYRADB_BOOTSTRAP_ADMIN_USERNAME` 与 `LYRADB_BOOTSTRAP_ADMIN_PASSWORD`；后者须为 12–128 位，包含大小写字母、数字、特殊字符，且不得包含用户名。首次初始化成功后应从运行环境移除这两个引导变量。

## 快速开始

### 开发环境

要求 JDK 17+、Maven 3.8+、Node.js 20 与 npm。

```bash
cd backend
mvn spring-boot:run
```

```bash
cd frontend
npm ci
npm run dev
```

开发前端默认在 `http://localhost:5173`，并把 `/api` 代理到后端 `8080`。开发 profile 只用于本机，不代表生产安全配置。

### 质量检查

```bash
cd frontend
npm run lint
npm run typecheck
npm run test
npm run build
```

```bash
cd backend
mvn -B clean verify
```

PR 工作流还会构建 Android Debug APK。iOS 与 HarmonyOS 需要各自平台工具链，当前源码已加固但仍须在 Xcode / DevEco Studio 真机复核。

### Docker 企业部署

```bash
cp .env.example .env
# 编辑 .env：替换所有占位值，并填写真实 HTTPS 来源
docker compose config
docker compose up -d
```

Compose 默认：

- `prod + enterprise`，不使用 Hibernate `ddl-auto=update` 绕过 Flyway；
- 仅绑定 `127.0.0.1:8080`，建议由同机 HTTPS 反向代理对外服务；
- 以 UID/GID `10001` 非 root 用户运行，根文件系统只读；
- 数据卷为 `/app/data`，驱动缓存为 `/home/lyradb/.lyradb`；
- 缺少 Jasypt、H2 或 CORS 配置时拒绝启动；企业空用户库还要求初始管理员变量，初始化后应移除；
- Webhook 与自定义 AI 出站白名单默认空。

仅本机 HTTP 验证时，需在 `.env` 中同时显式设置
`LYRADB_COOKIE_SECURE=false` 与准确的 `CORS_ALLOWED_ORIGINS=http://localhost:8080`。此 Compose 文件是企业安全模板；个人版请使用 dev/desktop profile 或另行维护部署配置。

## 打包

```bash
# 服务端 fat jar，默认版本 3.0.0；可将版本作为第一个参数
bash package-server.sh 3.0.0

# 桌面 app-image
bash package-desktop.sh 3.0.0
```

Windows 可执行对应的 `.ps1` 脚本。所有打包脚本均使用 `npm ci`，先运行前端 lint / 类型检查 / 测试，再运行 `mvn clean verify`；任一命令失败即停止。

## 项目结构

```text
lyradb/
├── backend/             # Spring Boot API、治理、驱动与迁移
├── frontend/            # Vue 3 + TypeScript
├── mobile/android/      # Kotlin + Android WebView
├── mobile/harmony/      # ArkTS + ArkWeb
├── mobile/ios/          # SwiftUI + WKWebView
├── .github/workflows/   # PR CI 与标签发布
└── wiki/                # 架构、接口、配置与开发文档
```

移动发布构建仅接受 HTTPS 服务端；服务端地址属于普通配置，保存在平台偏好存储中。认证会话保留在系统 WebView 的 Cookie 存储里，不把密码或令牌写入 Preferences / UserDefaults。三端导出通过受控原生桥保存 Blob 文件。

## 文档

- [配置说明](wiki/配置说明.md)
- [开发指南](wiki/开发指南.md)
- [移动端说明](wiki/移动端.md)
- [接口文档](wiki/接口文档.md)
- [系统架构](wiki/系统架构.md)

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
