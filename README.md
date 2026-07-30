# LyraDB · 天琴智库

> 轻若天琴，智驭万库 —— 原生桌面体验与企业数据治理并重的 AI 数据库工作台。

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Vue](https://img.shields.io/badge/Vue-3.4-42b883)]()
[![Version](https://img.shields.io/badge/version-3.1.1-334155)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)]()

**简体中文** | [English](README.en.md)

LyraDB 支持 MySQL、PostgreSQL、Oracle、SQL Server、SQLite、ClickHouse、MaxCompute、MongoDB 和 Redis。个人版是数据库直连的原生桌面客户端；企业版是独立部署的 B/S 管理平台。两者共享驱动、元数据模型和 SQL 安全内核，但不再把网页包装成桌面程序。

## 产品形态

| 形态 | 架构 | 运行方式 | AI 能力 |
| --- | --- | --- | --- |
| 个人版桌面 | 原生 C/S，Java Swing | EXE 进程直接连接数据库；不启动浏览器、WebView 或本地 HTTP 服务 | 个人可用；API Key 在本机加密保存 |
| 企业版 Web | Spring Boot + Vue 3 的 B/S | 服务端集中连接数据源，用户通过 HTTPS 浏览器访问 | 企业可用；由工作空间管理员配置并受 RBAC、审计约束 |
| 企业移动端 | 原生外壳 + 系统 WebView | Android / HarmonyOS / iOS 连接远端企业服务 | 使用企业服务端提供的能力 |

`v3.0.0` 的 Windows 包曾把本地 Spring Boot 网页服务包装为 EXE。`v3.0.1` 起，Windows 个人版已替换为真正的原生客户端；企业 B/S 形态继续独立交付。

## 个人版原生能力

| 能力 | 实现 |
| --- | --- |
| 数据库导航 | 原生树形导航，展示数据库 / Schema / 表 / 视图 / 字段 |
| SQL 工作台 | 原生多标签编辑器、结果表格、限行、精确取消、CSV 导出 |
| 安全执行 | UPDATE/DELETE 无 WHERE、DROP、TRUNCATE 等规则拦截；强制执行必须二次确认 |
| 事务 | JDBC 手动事务、提交与回滚 |
| 连接迁移 | 批量导入、导出连接配置；可下载并填写 `.xlsx` 模板；密码可选择不导出、口令加密或明文高风险导出 |
| 元数据 | 字段、主键、DDL 与 JDBC 外键 ER 图；可手动采集选中范围并保存 JSON / Markdown 快照 |
| 动态驱动 | 首次使用时按需下载并隔离加载 9 类数据库驱动 |
| AI 助手 | 生成、解释、修复、优化和安全审查 SQL；元数据先预览范围与 Token 估算，再由用户显式附加；永不自动执行 AI 输出 |
| AI Provider | DeepSeek、阿里云百炼、OpenAI、智谱 GLM、火山方舟 / 豆包、本地 Ollama及自定义兼容接口 |
| 本地安全 | 数据库密码与 AI Key 使用 AES-256-GCM 加密；主密钥文件收紧为当前用户权限 |

个人版 AI 设置入口位于菜单 `AI → Provider / API Key 设置`，或工具栏的 `AI 设置`。默认只允许 HTTPS Provider；只有 `localhost`、`127.0.0.1`、`::1` 可使用 HTTP 连接本地模型。

连接导入模板可从个人版工具栏的 `Excel 模板` 或菜单 `文件/工具 → 下载 Excel 导入模板` 获取。模板包含填写示例、字段说明和 9 类数据库参数说明；Excel 中填写的密码与 Secret 为明文，请在导入后妥善删除文件。

## 企业版能力

企业版保留 Vue 3 + Spring Boot B/S 架构，提供工作空间 RBAC、托管数据源、管理员下载 Excel 模板并批量导入连接配置、审批后一次性导出、SQL 审批、脱敏、审计、定时报表、SSH 隧道和集中 AI Provider 管理。企业 AI 元数据同样采用手动采集、预览、显式附加和文档下载。生产模式没有默认管理员口令；企业空库首次启动必须提供：

- `LYRADB_BOOTSTRAP_ADMIN_USERNAME`
- `LYRADB_BOOTSTRAP_ADMIN_PASSWORD`

初始化成功后应从运行环境移除引导变量。企业部署应放在 HTTPS 反向代理之后，数据库服务账号仍必须遵守最小权限。

## 快速开始

### Windows 个人版

从 GitHub Release 下载 `LyraDB-3.1.1-windows-x64-portable.zip`，完整解压后运行：

```text
LyraDB\LyraDB.exe
```

首次连接某类数据库时需要联网下载对应驱动；下载后的驱动缓存在 `%USERPROFILE%\.lyradb\desktop\drivers`。连接配置与 AI Key 保存在 `%USERPROFILE%\.lyradb\desktop`，敏感值不会以明文写入状态 JSON。

从源码构建需要带 `jpackage` 的 JDK 21 与 Maven：

```powershell
.\package-desktop.ps1 -Version 3.1.1
```

脚本会运行 core/desktop 测试，生成原生 app-image，真实启动 EXE 并验证：

- Swing 原生入口可初始化；
- 未启动浏览器、WebView、本地 HTTP 服务；
- 个人 AI 配置入口可用；
- 9 类数据库驱动注册完整。

### 企业版开发

要求 JDK 17+、Maven 3.8+、Node.js 20+。

```bash
cd frontend
npm ci
npm run dev
```

```bash
# 仓库根目录
mvn -B -ntp -pl backend -am clean package
java -jar backend/target/lyradb-backend-3.1.1.jar
```

前端开发服务器默认位于 `http://localhost:5173`，并把 `/api` 代理到后端 `8080`。

### Docker 企业部署

```bash
cp .env.example .env
# 替换全部占位值，填写真实 HTTPS 来源和首次管理员变量
docker compose config
docker compose up -d
```

Compose 默认使用 `prod + enterprise`，仅绑定宿主机 `127.0.0.1:8080`，以 UID/GID `10001` 非 root 用户运行，并持久化 `/app/data` 与驱动缓存。

## 质量检查

```bash
# 共享内核与企业后端
mvn -B -ntp -pl backend -am clean verify

# 共享内核与个人原生桌面
mvn -B -ntp -pl desktop -am clean verify
```

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm run test
npm run build
```

CI 将企业 B/S 与 Windows 原生桌面拆成独立任务。桌面任务必须通过真实 EXE 架构冒烟；发布包同时提供 SHA-256、CycloneDX SBOM 与制品来源证明。

## 项目结构

```text
lyradb/
├── core/                # 共享驱动、元数据模型、SQL 解析与安全审核
├── desktop/             # 个人版原生 Swing 客户端与本地 AI
├── backend/             # 企业版 Spring Boot API、RBAC、审批与审计
├── frontend/            # 企业版 Vue 3 前端
├── mobile/              # 连接企业 B/S 的 Android / HarmonyOS / iOS 客户端
├── .github/workflows/   # 企业 CI、原生 EXE CI 与标签发布
└── wiki/                # 架构、配置、接口与开发文档
```

## 打包

```bash
# 企业版服务端（包含 Vue 前端）
bash package-server.sh 3.1.1

# 个人版原生桌面
bash package-desktop.sh 3.1.1
```

Windows 使用同名 `.ps1` 脚本。服务端与桌面端是两条独立产物链，不会再互相包装。

## 文档

- [系统架构](wiki/系统架构.md)
- [开发指南](wiki/开发指南.md)
- [配置说明](wiki/配置说明.md)
- [后端模块](wiki/后端模块.md)
- [移动端说明](wiki/移动端.md)
- [接口文档](wiki/接口文档.md)

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
