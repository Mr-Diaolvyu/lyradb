# LyraDB

> Light as a lyre, master of all databases — a lightweight, AI-powered database management tool.

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Vue](https://img.shields.io/badge/Vue-3.4-42b883)]()
[![Version](https://img.shields.io/badge/version-3.0.0-334155)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)]()

[简体中文](README.md) | **English**

LyraDB provides one workspace for MySQL, PostgreSQL, Oracle, SQL Server, ClickHouse, SQLite, MongoDB, Redis, and MaxCompute. It combines SQL editing, AI assistance, ER diagrams, import/export, and enterprise governance.

## Capabilities

| Capability | Description |
| --- | --- |
| Multiple data sources | On-demand drivers for relational, document, OLAP, and cloud warehouse sources |
| SQL workbench | Monaco editor, completion, tabs, execution plans, and result export |
| AI assistance | Natural-language SQL; custom AI and webhook egress are denied until hosts are allowlisted |
| Enterprise governance | Workspace-scoped RBAC, grants, SQL-bound approvals, audit, and one-time exports |
| Multiple clients | Browser, jpackage desktop app, and Android / HarmonyOS / iOS WebView shells |
| Reproducible delivery | Lockfile installs, tests, PR CI, version checks, SBOMs, checksums, and provenance attestations |

## Runtime modes

| Scenario | Default edition | Purpose |
| --- | --- | --- |
| Local development / desktop profile | `personal` | Single-user local use |
| `prod` profile / Docker Compose | `enterprise` | Authentication, RBAC, approvals, and audit |

Production has no default administrator password. On an empty enterprise database,
`LYRADB_BOOTSTRAP_ADMIN_USERNAME` and `LYRADB_BOOTSTRAP_ADMIN_PASSWORD` are required. The password must be 12–128 characters, contain uppercase and lowercase letters, a number, and a special character, and must not contain the username. Remove both bootstrap variables from the runtime environment after the first successful initialization.

## Development

Requirements: JDK 17+, Maven 3.8+, Node.js 20, and npm.

```bash
cd backend
mvn spring-boot:run
```

```bash
cd frontend
npm ci
npm run dev
```

Quality gates:

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

## Docker deployment

```bash
cp .env.example .env
# Replace every placeholder and set the real HTTPS origin.
docker compose config
docker compose up -d
```

Compose defaults to `prod + enterprise`, binds only `127.0.0.1:8080`, runs as non-root UID/GID `10001`, uses a read-only root filesystem, and persists `/app/data` and `/home/lyradb/.lyradb`. Missing encryption, database, or CORS settings fail startup. An empty enterprise user database additionally requires bootstrap-admin variables; remove them after initialization.

Keep `LYRADB_COOKIE_SECURE=true` behind an HTTPS reverse proxy. Loopback-only HTTP testing requires explicit `LYRADB_COOKIE_SECURE=false` and an exact HTTP `CORS_ALLOWED_ORIGINS`.

## Packaging

```bash
bash package-server.sh 3.0.0
bash package-desktop.sh 3.0.0
```

PowerShell equivalents are available at the repository root. Packaging uses `npm ci`, runs frontend lint/typecheck/tests/build, and then runs `mvn clean verify`; it stops on any failed command.

## Mobile security

Release mobile clients accept HTTPS only. Android allows an explicit HTTP endpoint only in debug builds. Server URLs are ordinary configuration stored in platform preferences; credentials are not stored there. Authentication stays in the system WebView cookie store, and Blob exports use bounded native save bridges.

Android is built in PR CI. iOS and HarmonyOS still require Xcode and DevEco Studio build and device verification.

## Documentation

- [Configuration](wiki/配置说明.md)
- [Development guide](wiki/开发指南.md)
- [Mobile clients](wiki/移动端.md)
- [Architecture](wiki/系统架构.md)

## License

Released under the [Apache License 2.0](LICENSE).
