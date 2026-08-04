# LyraDB 可信 AI 验收计划

- 产品定位：[LyraDB · 天琴智库](../product/产品定位.md)

- 适用分支：`codex/ai-native-foundation`
- 状态：自动化验证已完成；真实环境验收待提供测试条件
- 原则：测试替身证明代码边界，真实环境证明协议兼容、数据库方言、业务口径和费用观测。

## 1. 自动化验证

在仓库根目录执行：

```powershell
mvn -o -pl backend -am test
mvn -o -pl desktop -am test
Set-Location frontend
npm run lint
npm run typecheck
npm run test
npm run build
```

### 1.1 2026-08-03 验证结果

| 验证层 | 结果 |
|---|---|
| Core | 96 项通过，0 失败 |
| Enterprise Backend | 235 项通过，0 失败 |
| Personal Desktop | 67 项通过，0 失败 |
| Frontend | ESLint、TypeScript、5 个测试文件/30 项测试、Vite 生产构建全部通过 |
| 数据迁移 | Flyway V1–V7 在空库、重复执行和历史库基线上通过 |
| 服务端制品 | `lyradb-backend-3.1.2.jar`，97,504,668 字节 |
| 制品 SHA-256 | `FED264AFC15919934ABB769B64ECD97B343B2DBD0B5C658A26803919691BD1D0` |
| 前端生产依赖审计 | 104 个生产依赖，已知漏洞 0 |

仓库自带 `package-server.ps1` 已完整执行“前端依赖锁定安装 → lint → typecheck → test → build → 静态资源嵌入 → 后端 `clean verify`”，退出码为 0。构建中的 CORS 拒绝、回滚失败、审计异常和 MongoDB 本地拒连日志来自预期负向测试，不代表回归失败。

完整 `npm audit` 仍报告 11 个仅开发/测试工具链告警，涉及 Vite、vue-tsc、测试工具及其传递依赖；`npm audit --omit=dev` 为 0，且当前审计未给出完整的非破坏性修复路径。本轮不执行 `--force` 大版本升级，后续应在独立依赖升级迭代中验证新版本后再合入，并禁止将 Vite 开发服务器暴露到不可信网络。

以上结果证明代码边界和可打包性，不替代真实 Provider、数据库、MaxCompute 与外部 MCP 客户端验收。

自动化必须覆盖：

- 有界工具编排的未知工具、重复调用、超步数与只计划不执行；
- 计划跨重启持久化、单次认领、摘要/所有权/有效期复检与终态清密文；
- 跨节点取消请求与本机 JDBC Statement 取消；
- 知识跨工作空间隔离、仅 `VERIFIED` 可检索、摄取只生成草稿；
- 自动黄金集必须完整运行并显式确认 Provider 调用；
- MaxCompute 声明证据、部分实时证据、完整实时证据和一次性预检；
- Gateway 令牌过期、撤销、密码轮换、Grant 回收、限流和 Origin；
- MCP `server/discover`、`tools/list`、`tools/call`、请求大小、协议头、未知工具和无执行工具；
- 私有模型默认拒绝、精确主机白名单、DNS 地址安全与 HTTPS 策略；
- Flyway 空库和历史库升级到 V7。

## 2. 真实环境测试条件

完成环境验收需要以下最小条件。均建议使用隔离测试空间和只读账号，不需要提供生产业务数据。

### 2.1 AI Provider

- 一个 OpenAI Chat Completions 兼容端点、模型名和测试 Key，或可从 LyraDB 测试主机访问的私有模型固定主机；
- 允许执行约 8 条黄金集模型调用和少量 Ask Lyra 工具调用；
- 如验证语义检索，再提供兼容 Embeddings 的模型名；否则保持语义检索关闭。

验收：普通回答、知识检索、工具调用、Token 用量、自动黄金集、错误脱敏、超时和限流均符合预期。

### 2.2 通用数据库

- MySQL、PostgreSQL 或 SQL Server 中至少一个非生产实例；
- 一个数据库只读账号；
- 两张无敏感数据的测试表，含可验证的主外键或统计字段；
- 对测试用户配置只允许这些表、最大 100 行的 READ_ONLY Grant。

验收：计划生成后不自动执行；确认后返回真实结果；篡改摘要、回收 Grant、过期、重复确认和取消均失败关闭。

### 2.3 MaxCompute

- 一个测试 Project、只读访问凭据和可查询的测试表；
- 至少一张分区表，明确分区列和小规模分区；
- 允许执行元数据、EXPLAIN/成本估算类只读命令；
- 提供可接受的测试成本上限。

验收：前 100 行样本仅在业务口径核验需要且得到明确授权时查看；分区元数据、扫描量、成本和证据模式与控制台结果一致；预检摘要只能消费一次。

### 2.4 MCP 客户端

- 一个支持 Streamable HTTP 和自定义 Header 的 MCP 测试客户端；
- LyraDB 测试地址与客户端 Origin（如有）；
- 一个绑定 READ_ONLY Grant 的短期 Gateway 令牌。

验收：协议发现、按 Scope 裁剪工具、知识检索和计划创建成功；MCP 看不到执行/写入工具；撤销令牌后立即失效。

## 3. 验收记录

每轮记录环境标识、版本、时间、执行人、测试账号权限范围、命令、通过/失败、脱敏日志和证据摘要。禁止把 Key、密码、原始业务样本或完整 SQL 密文写入报告。

| 层级 | 当前结论 | 完成条件 |
|---|---|---|
| 代码与自动化 | **通过**（2026-08-03） | 331 项 Core/Backend、67 项 Desktop、30 项 Frontend 测试及生产打包均成功 |
| 公网/私有 Provider | 待环境 | 自动黄金集与工具调用 E2E |
| 通用数据库 | 待环境 | 计划、确认、结果、取消和越权负例 E2E |
| MaxCompute | 待环境 | 实时分区/成本证据与真实控制台一致 |
| MCP | 待环境 | 独立客户端协议、Scope 与撤销演练 |

真实环境验收通过前，产品状态只能标记为“代码实现并通过自动化验证，待灰度”，不能标记为生产可用。
