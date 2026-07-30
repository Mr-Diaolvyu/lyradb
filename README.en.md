# LyraDB

> A native personal database workbench and an enterprise B/S data-governance platform.

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Vue](https://img.shields.io/badge/Vue-3.4-42b883)]()
[![Version](https://img.shields.io/badge/version-3.1.0-334155)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)]()

[简体中文](README.md) | **English**

LyraDB supports MySQL, PostgreSQL, Oracle, SQL Server, SQLite, ClickHouse, MaxCompute, MongoDB, and Redis.

## Editions

| Product | Architecture | AI |
| --- | --- | --- |
| Personal desktop | Native Java Swing C/S client; direct database connections; no browser, WebView, or local HTTP server | Available to individuals; API keys are encrypted locally |
| Enterprise Web | Separately deployed Spring Boot + Vue B/S platform | Centrally configured and governed by workspace RBAC and audit |
| Enterprise mobile | Native shell plus system WebView connected to the remote enterprise server | Uses enterprise-server capabilities |

The `v3.0.0` Windows package wrapped a local Web server. Starting with `v3.0.1`, the personal Windows build is a real native desktop client. The enterprise B/S server remains a separate artifact.

## Native desktop capabilities

- Native database navigator, SQL tabs, result grids, exact cancellation, transactions, and CSV export.
- Bulk connection import/export with omitted, password-encrypted, or explicitly acknowledged plaintext credentials.
- Column, primary-key, DDL, and JDBC foreign-key ER metadata; selected metadata can be saved as JSON or Markdown.
- SQL safety review with explicit confirmation for destructive statements.
- AI SQL generation, explanation, repair, optimization, and security review. Metadata is collected manually, previewed with a token estimate, and attached only after explicit confirmation. AI output is never executed automatically.
- DeepSeek, Alibaba Cloud Model Studio, OpenAI, GLM, Volcengine/Doubao, local Ollama, and custom OpenAI-compatible endpoints.
- AES-256-GCM protection for database secrets and AI keys in the local state store.

AI settings are available from `AI → Provider / API Key Settings`. HTTPS is required except for loopback-hosted local models.

## Build

Native Windows package, using JDK 21 with `jpackage`:

```powershell
.\package-desktop.ps1 -Version 3.1.0
```

Enterprise server:

```bash
bash package-server.sh 3.1.0
```

Quality gates:

```bash
mvn -B -ntp -pl desktop -am clean verify
mvn -B -ntp -pl backend -am clean verify
```

```bash
cd frontend
npm ci
npm run lint
npm run typecheck
npm run test
npm run build
```

The Windows packaging script builds the native app image, launches the real EXE in smoke-test mode, and verifies that no browser, WebView, or local HTTP server is started.

## Docker enterprise deployment

```bash
cp .env.example .env
# Replace all placeholders and configure the real HTTPS origin.
docker compose config
docker compose up -d
```

The Compose template uses `prod + enterprise`, binds only to `127.0.0.1:8080`, runs as non-root UID/GID `10001`, and requires explicit bootstrap administrator and encryption settings.

## License

Released under the [Apache License 2.0](LICENSE).
