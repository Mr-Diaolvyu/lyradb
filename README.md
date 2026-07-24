# LyraDB · 天琴智库

> 轻若天琴，智驭万库 —— 一款**轻量、AI 驱动**的通用数据库管理工具。

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Vue](https://img.shields.io/badge/Vue-3.4-42b883)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)]()

LyraDB（天琴智库）把 **MySQL、PostgreSQL、Oracle、SQL Server、ClickHouse、SQLite、MongoDB、Redis、MaxCompute** 等多种数据库当作「一等公民」，在同一个界面里无缝连接、查询与管理。相比 DBeaver 更轻量、相比 Navicat 更开放，桌面个人高效、Web 团队协作，同一套代码两种部署。

---

## ✨ 核心特性

| 特性 | 说明 |
|------|------|
| 🗄️ 多数据库一等公民 | 关系型 / 文档型 / OLAP 全覆盖，驱动按需动态下载，新增数据库仅需一行配置 |
| ✏️ SQL 编辑器 | 基于 Monaco Editor，语法高亮、智能补全、多标签页，自动识别各方言 |
| 🤖 AI 辅助查询 | 自然语言转 SQL（NL2SQL），支持多 AI 提供商配置 |
| 📊 ER 图 | 自动生成数据库实体关系图（Vue Flow） |
| 📤 数据导入导出 | 支持 CSV / Excel / SQL 格式 |
| 🔐 SSH 隧道 | 跳板机端口转发，安全连接内网数据库 |
| 🏢 企业版 RBAC | 用户管理、数据源授权、审批工作流、操作审计 |
| 📱 移动端 | Android 瘦客户端（Jetpack Compose），复用后端 API |
| 🖥️ 桌面打包 | jpackage 生成 app-image / MSI 安装包，自带精简 JRE |

## 🧱 技术栈

**后端**：Spring Boot 3.2.5（Java 17）· Spring Data JPA + H2（内嵌存储连接配置）· Spring Security · Jasypt（凭据加密）· Maven Resolver（动态驱动下载）· Apache MINA SSHD

**前端**：Vue 3.4 + TypeScript · Vite 5 · Element Plus · Monaco Editor · VXE Table · Pinia · Vue Router · Vue Flow

**移动端**：Kotlin + Jetpack Compose · Retrofit + OkHttp（瘦客户端，数据经后端 API）

## 📦 发行版

| 版本 | 环境变量 | 说明 |
|------|----------|------|
| 个人版 | `LYRADB_EDITION=personal`（默认） | 无认证，单用户桌面体验 |
| 企业版 | `LYRADB_EDITION=enterprise` | 多用户登录、RBAC、审批、审计 |

## 🚀 快速开始

### 环境要求
- JDK 17+
- Maven 3.6+
- Node.js 18+ / npm

### 启动后端
```bash
cd backend
mvn spring-boot:run
# 默认 http://localhost:8080/api
```

### 启动前端
```bash
cd frontend
npm install
npm run dev
# 默认 http://localhost:5173
```

### 桌面打包
```bash
bash package-desktop.sh
# 产物: backend/target/desktop/LyraDB
```

## 🗂️ 项目结构

```
lyradb/
├── backend/             # Spring Boot 后端（io.github.lexaquila.lyradb）
│   └── src/main/java/io/github/lexaquila/lyradb/
│       ├── config/      # 安全、CORS、WebSocket、启动引导
│       ├── controller/  # REST 控制器
│       ├── driver/      # 多数据库驱动管理
│       ├── model/       # 实体 & DTO
│       ├── repository/  # JPA 仓库
│       └── service/     # 业务服务层
├── frontend/            # Vue 3 前端
├── mobile/android/      # Android 移动端
└── wiki/                # 项目 Wiki
```

## ⚙️ 配置说明

关键环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `LYRADB_EDITION` | `personal` | 发行版：personal / enterprise |
| `LYRADB_H2_PATH` | `./data/lyradb` | H2 连接配置库文件路径 |
| `JASYPT_PASSWORD` | （dev 内置默认） | 凭据加密口令，**生产环境必须显式设置** |

> 详见 [wiki/配置说明.md](wiki/配置说明.md)。

## 📖 文档

完整文档见 [`wiki/`](wiki/)：系统架构、后端模块、前端模块、接口文档、开发指南、移动端、配置说明。

## 👤 作者

**lexaquila** —— 个人开源项目。

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。该协议允许自由使用、修改与商用（含专利授权），使用时需保留版权与许可声明。
