# LyraDB · Lyra Intelligence Hub

> Lightweight by design, trusted by evidence — a self-hostable Trusted AI Data Intelligence Hub for data professionals.

[![Java](https://img.shields.io/badge/Java-17-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)]()
[![Vue](https://img.shields.io/badge/Vue-3.4-42b883)]()
[![Version](https://img.shields.io/badge/version-3.1.2-334155)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)]()

[简体中文](README.md) | **English**

LyraDB connects multiple databases, governed metadata, verified knowledge, and policy-aware agents so every data question can lead to an evidence-backed, actionable, and traceable answer. It supports MySQL, PostgreSQL, Oracle, SQL Server, SQLite, ClickHouse, MaxCompute, MongoDB, and Redis.

The database navigator, SQL editor, and result grid remain the professional human-control surface. LyraDB does not turn model output into an unreviewed database action.

See the authoritative [product positioning](docs/product/产品定位.md) and [product strategy](docs/product/产品战略.md).

## Product editions

| Product | Architecture | AI |
| --- | --- | --- |
| Personal Intelligence Workbench | Native Java Swing C/S client; direct database connections; no browser, WebView, or local HTTP server | Local AI assistance and a professional human-control surface; API keys are encrypted locally |
| Enterprise Trusted Data Intelligence | Separately deployed Spring Boot + Vue B/S platform | Team knowledge, governed read agents, evaluation, RBAC, and audit |
| Mobile Governance Companion | Native shell plus system WebView connected to the remote enterprise server | Approval, audit, alerts, and task handoff through enterprise capabilities |

The `v3.0.0` Windows package wrapped a local Web server. Starting with `v3.0.1`, the personal Windows build is a real native desktop client. The enterprise B/S server remains a separate artifact.

## Personal Intelligence Workbench

- Native database navigator, SQL tabs, result grids, exact cancellation, transactions, and CSV export.
- Bulk connection import/export with omitted, password-encrypted, or explicitly acknowledged plaintext credentials. A downloadable `.xlsx` template includes examples, field guidance, and all nine supported database types; passwords and secrets entered in Excel remain plaintext.
- Column, primary-key, DDL, and JDBC foreign-key ER metadata; selected metadata can be saved as JSON or Markdown.
- SQL safety review with explicit confirmation for destructive statements.
- Knowledge Assistant for SQL generation, explanation, repair, optimization, and security review. Metadata is collected manually, previewed with a token estimate, and attached only after explicit confirmation. Model output is never executed automatically.
- DeepSeek, Alibaba Cloud Model Studio, OpenAI, GLM, Volcengine/Doubao, local Ollama, and custom OpenAI-compatible endpoints.
- AES-256-GCM protection for database secrets and AI keys in the local state store.

Model settings are available from `Intelligence → Model / API Key Settings`. HTTPS is required except for loopback-hosted local models.

## Enterprise Trusted Data Intelligence

The enterprise product combines Ask Lyra, a verified Data Knowledge Core, governed read plans, AI quality evaluation, MaxCompute-specific evidence, and a scoped Agent Gateway. Every real read is constrained by the current user, workspace, grant, SQL AST, resource envelope, row and cost limits, confirmation, cancellation, and audit.

Advanced capabilities are installed but fail closed by default. They require isolated real-provider, database, MaxCompute, and MCP-client validation before production enablement. The 3.x Write Agent remains hard-disabled.

Implementation status and rollout boundaries are documented in the [AI-native roadmap](docs/product/AI原生路线图状态.md).

## Build

Native Windows package, using JDK 21 with `jpackage`:

```powershell
.\package-desktop.ps1 -Version 3.1.2
```

Enterprise server:

```bash
bash package-server.sh 3.1.2
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

## Documentation

- [Documentation center](docs/README.md)
- [Product positioning](docs/product/产品定位.md)
- [Product strategy](docs/product/产品战略.md)
- [Brand and messaging guidelines](docs/品牌与信息规范.md)
- [Trusted AI API](docs/api/AI原生接口.md)

Released under the [Apache License 2.0](LICENSE).
