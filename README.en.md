# LyraDB

> Light as a lyre, master of all databases — a **lightweight, AI-powered** universal database management tool.

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Vue](https://img.shields.io/badge/Vue-3.4-42b883)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)]()

[简体中文](README.md) | **English**

LyraDB treats **MySQL, PostgreSQL, Oracle, SQL Server, ClickHouse, SQLite, MongoDB, Redis, and MaxCompute** as first-class citizens, letting you connect, query and manage them all from a single interface. Lighter than DBeaver, more open than Navicat — efficient on the desktop for individuals, collaborative on the web for teams, all from one codebase with two deployment modes.

---

## ✨ Key Features

| Feature | Description |
|---------|-------------|
| 🗄️ Multi-DB first-class support | Relational / document / OLAP coverage; drivers downloaded on demand; adding a database takes just one line of config |
| ✏️ SQL editor | Built on Monaco Editor — syntax highlighting, autocomplete, multi-tab, automatic dialect detection |
| 🤖 AI-assisted queries | Natural language to SQL (NL2SQL), configurable across multiple AI providers |
| 📊 ER diagrams | Auto-generated entity-relationship diagrams (Vue Flow) |
| 📤 Import & export | Supports CSV / Excel / SQL formats |
| 🔐 SSH tunneling | Bastion-host port forwarding for secure access to intranet databases |
| 🏢 Enterprise RBAC | User management, data-source authorization, approval workflows, operation auditing |
| 📱 Mobile | Android thin client (Jetpack Compose) reusing the backend API |
| 🖥️ Desktop packaging | jpackage builds app-image / MSI installers bundled with a trimmed JRE |

## 🧱 Tech Stack

**Backend**: Spring Boot 3.2.5 (Java 17) · Spring Data JPA + H2 (embedded storage for connection configs) · Spring Security · Jasypt (credential encryption) · Maven Resolver (dynamic driver download) · Apache MINA SSHD

**Frontend**: Vue 3.4 + TypeScript · Vite 5 · Element Plus · Monaco Editor · VXE Table · Pinia · Vue Router · Vue Flow

**Mobile**: Kotlin + Jetpack Compose · Retrofit + OkHttp (thin client; data flows through the backend API)

## 📦 Editions

| Edition | Environment variable | Description |
|---------|----------------------|-------------|
| Personal | `LYRADB_EDITION=personal` (default) | No authentication, single-user desktop experience |
| Enterprise | `LYRADB_EDITION=enterprise` | Multi-user login, RBAC, approvals, auditing |

## 🚀 Getting Started

### Requirements

- JDK 17+
- Maven 3.6+
- Node.js 18+ / npm

### Run the backend

```bash
cd backend
mvn spring-boot:run
# Defaults to http://localhost:8080/api
```

### Run the frontend

```bash
cd frontend
npm install
npm run dev
# Defaults to http://localhost:5173
```

### Desktop packaging

```bash
bash package-desktop.sh
# Output: backend/target/desktop/LyraDB
```

## 🗂️ Project Structure

```
lyradb/
├── backend/             # Spring Boot backend (io.github.lexaquila.lyradb)
│   └── src/main/java/io/github/lexaquila/lyradb/
│       ├── config/      # Security, CORS, WebSocket, bootstrap
│       ├── controller/  # REST controllers
│       ├── driver/      # Multi-database driver management
│       ├── model/       # Entities & DTOs
│       ├── repository/  # JPA repositories
│       └── service/     # Business service layer
├── frontend/            # Vue 3 frontend
├── mobile/android/      # Android mobile client
└── wiki/                # Project wiki
```

## ⚙️ Configuration

Key environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `LYRADB_EDITION` | `personal` | Edition: personal / enterprise |
| `LYRADB_H2_PATH` | `./data/lyradb` | Path to the H2 file storing connection configs |
| `JASYPT_PASSWORD` | (built-in default in dev) | Credential-encryption secret; **must be set explicitly in production** |

> See [wiki/配置说明.md](wiki/配置说明.md) for details.

## 📖 Documentation

Full documentation lives in [`wiki/`](wiki/): system architecture, backend modules, frontend modules, API reference, development guide, mobile, and configuration.

## 🤝 Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening an issue or pull request.

## 👤 Author

**lexaquila** — a personal open-source project.

## 📄 License

Released under the [Apache License 2.0](LICENSE). The license permits free use, modification and commercial use (with a patent grant); you must retain the copyright and license notices.
