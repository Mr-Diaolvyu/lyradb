# LyraDB · 天琴智库

> 轻若天琴，智驭可信数据 —— 面向数据专业团队、可私有化部署的可信 AI 数据智库。

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Vue](https://img.shields.io/badge/Vue-3.4-42b883)]()
[![Version](https://img.shields.io/badge/version-3.1.2-334155)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)]()

**简体中文** | [English](README.en.md)

LyraDB 把多数据库、授权元数据、已验证知识和治理规则连接起来，让每个数据问题都得到**有依据、可执行、可追溯**的答案。它支持 MySQL、PostgreSQL、Oracle、SQL Server、SQLite、ClickHouse、MaxCompute、MongoDB 和 Redis；个人版、企业版与移动端分别承担本地专业控制、团队可信智能和移动治理角色。

## 核心定位

- **有依据**：只让明确授权的元数据和经过审核的知识进入上下文；
- **可执行**：把回答转化为可检查的只读计划和专业 SQL 工作流；
- **受约束**：权限、资源、行数、成本、确认、取消和审批不能被模型绕过；
- **可追溯**：记录证据、知识版本、模型、工具轨迹、计划摘要与审计回执。

完整定义见 [产品定位](docs/product/产品定位.md) 与 [产品战略](docs/product/产品战略.md)。

## 产品形态

| 形态 | 架构 | 运行方式 | 智库角色 |
| --- | --- | --- | --- |
| 个人智库工作台 | 原生 C/S，Java Swing | EXE 进程直接连接数据库；不启动浏览器、WebView 或本地 HTTP 服务 | 本地/离线 AI 辅助与人类专业控制面；API Key 本机加密保存 |
| 团队可信数据智库 | Spring Boot + Vue 3 的 B/S | 服务端集中连接数据源，用户通过 HTTPS 浏览器访问 | 团队知识、受控 Agent、治理、评测与审计 |
| 移动治理伴侣 | 原生外壳 + 系统 WebView | Android / HarmonyOS / iOS 连接远端企业服务 | 审批、告警、审计与任务接管 |

`v3.0.0` 的 Windows 包曾把本地 Spring Boot 网页服务包装为 EXE。`v3.0.1` 起，Windows 个人版已替换为真正的原生客户端；企业 B/S 形态继续独立交付。

## 个人版原生能力

| 能力 | 实现 |
| --- | --- |
| 数据库导航 | 原生树形导航，展示数据库 / Schema / 表 / 视图 / 字段 |
| SQL 工作台 | 原生多标签编辑器、结果表格、限行、精确取消、CSV 导出 |
| 安全执行 | UPDATE/DELETE 无 WHERE、DROP、TRUNCATE 等规则拦截；强制执行必须二次确认 |
| 事务 | JDBC 手动事务、提交与回滚 |
| 连接迁移 | 批量导入、导出连接配置；可下载并填写 `.xlsx` 模板；密码可选择不导出、口令加密或明文高风险导出 |
| 元数据 | 字段与表注释、主键、DDL；普通数据库自动展示所选库 / Schema 的全范围 ER 图并支持过滤 |
| MaxCompute 血缘 | 选择根表后通过 DataWorks OpenAPI 探查真实表血缘或字段血缘；支持手动、选表后、每 30 分钟或每 6 小时触发 |
| 动态驱动 | 首次使用时按需下载并隔离加载 9 类数据库驱动 |
| 智库助手 | 生成、解释、修复、优化和安全审查 SQL；元数据先预览范围与 Token 估算，再由用户显式附加；永不自动执行 AI 输出 |
| AI Provider | DeepSeek、阿里云百炼、OpenAI、智谱 GLM、火山方舟 / 豆包、本地 Ollama及自定义兼容接口 |
| 本地安全 | 数据库密码与 AI Key 使用 AES-256-GCM 加密；主密钥文件收紧为当前用户权限 |

表工作台先加载字段、表注释和结构元数据，再加载数据。普通数据库自动执行最多 200 行的只读预览；MaxCompute 为避免无分区扫描，默认不读取数据，只在用户点击后加载最多 100 行。数据库地图与血缘的完整边界见[数据库地图与血缘](docs/product/数据库地图与血缘.md)。

个人智库助手的模型设置入口位于菜单 `智库 → 模型 / API Key 设置`，或工具栏的 `模型设置`。默认只允许 HTTPS Provider；只有 `localhost`、`127.0.0.1`、`::1` 可使用 HTTP 连接本地模型。

连接导入模板可从个人版工具栏的 `Excel 模板` 或菜单 `文件/工具 → 下载 Excel 导入模板` 获取。模板包含填写示例、字段说明和 9 类数据库参数说明；Excel 中填写的密码与 Secret 为明文，请在导入后妥善删除文件。

## 企业版能力

企业版保留 Vue 3 + Spring Boot B/S 架构，提供工作空间 RBAC、托管数据源、连接迁移、审批后一次性导出、SQL 审批、脱敏、审计、定时报表、SSH 隧道和集中 AI Provider 管理。

企业 AI 以 **Ask Lyra + Data Knowledge Core + Governed Read Agent** 为产品主线：Ask Lyra 可在有限步数内检索已审核知识并创建只读计划，返回工具轨迹、Token 用量、证据与 Context Receipt；数据读取必须先展示不可变计划，再由用户确认。计划状态加密持久化，支持重启恢复、单次认领和跨节点取消；团队知识只有经过数据管家审核后才能进入 AI 上下文。

MaxCompute 专项 Agent 可区分声明、部分实时和完整实时分区/成本证据。外部 Agent Gateway 同时提供类型化 REST 与 MCP `2026-07-28`，共用 Grant、AST、行数、成本、限流、取消和审计边界；MCP 不暴露执行或写入工具。企业版还支持在显式开关、精确主机白名单和地址安全策略下接入私有模型。

除 Ask Lyra 外，高阶 AI 能力默认失败关闭，建议按下列顺序在隔离空间灰度：

```text
LYRADB_AI_KNOWLEDGE_ENABLED=false
LYRADB_AI_READ_AGENT_ENABLED=false
LYRADB_AI_TEAM_KNOWLEDGE_ENABLED=false
LYRADB_AI_QUALITY_ENABLED=false
LYRADB_AI_MAXCOMPUTE_AGENT_ENABLED=false
LYRADB_AI_GATEWAY_ENABLED=false
LYRADB_AI_WRITE_AGENT_ENABLED=false
LYRADB_AI_MAXCOMPUTE_LIVE_EVIDENCE_ENABLED=false
LYRADB_AI_PRIVATE_MODEL_ENABLED=false
LYRADB_AI_PRIVATE_MODEL_ALLOWED_HOSTS=
```

`LYRADB_AI_WRITE_AGENT_ENABLED` 是 3.x 硬门禁：设为 `true` 会拒绝启动，不能用前端、提示词或环境变量误开放写入工具。完整依赖、灰度顺序和证据边界见 [AI 路线图实现状态](docs/product/AI原生路线图状态.md)；写入准入见 [4.0 写入型 Agent 门禁评审](docs/product/AI写入智能体门禁评审.md)。

生产模式没有默认管理员口令；企业空库首次启动必须提供：

- `LYRADB_BOOTSTRAP_ADMIN_USERNAME`
- `LYRADB_BOOTSTRAP_ADMIN_PASSWORD`

初始化成功后应从运行环境移除引导变量。企业部署应放在 HTTPS 反向代理之后，数据库服务账号仍必须遵守最小权限。

## 快速开始

### Windows 个人版

从 GitHub Release 下载 `LyraDB-3.1.2-windows-x64-portable.zip`，完整解压后运行：

```text
LyraDB\LyraDB.exe
```

首次连接某类数据库时需要联网下载对应驱动；下载后的驱动缓存在 `%USERPROFILE%\.lyradb\desktop\drivers`。连接配置与 AI Key 保存在 `%USERPROFILE%\.lyradb\desktop`，敏感值不会以明文写入状态 JSON。

从源码构建需要带 `jpackage` 的 JDK 21 与 Maven：

```powershell
.\package-desktop.ps1 -Version 3.1.2
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
java -jar backend/target/lyradb-backend-3.1.2.jar
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
bash package-server.sh 3.1.2

# 个人版原生桌面
bash package-desktop.sh 3.1.2
```

Windows 使用同名 `.ps1` 脚本。服务端与桌面端是两条独立产物链，不会再互相包装。

## 文档

- [文档中心](docs/README.md)
- [产品定位](docs/product/产品定位.md)
- [产品战略](docs/product/产品战略.md)
- [品牌与信息规范](docs/品牌与信息规范.md)
- [定位迁移记录](docs/product/定位迁移记录-2026-08-04.md)
- [可信 AI 数据智库路线图状态](docs/product/AI原生路线图状态.md)
- [可信 AI 意图—实现差距矩阵](docs/product/AI原生能力差距矩阵.md)
- [可信 AI 运行时架构决策](docs/architecture/ADR-003-可信AI运行时.md)
- [AI 原生智库完成契约](docs/architecture/ADR-004-AI原生完成契约.md)
- [可信 AI API](docs/api/AI原生接口.md)
- [可信 AI 部署配置](docs/operations/AI原生配置与运维.md)
- [可信 AI 验收计划](docs/testing/AI原生验收计划.md)
- [4.0 写入型 Agent 门禁评审](docs/product/AI写入智能体门禁评审.md)
- [系统架构](wiki/系统架构.md)
- [开发指南](wiki/开发指南.md)
- [配置说明](wiki/配置说明.md)
- [后端模块](wiki/后端模块.md)
- [移动端说明](wiki/移动端.md)
- [接口文档](wiki/接口文档.md)

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
