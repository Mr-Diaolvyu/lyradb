# LyraDB 可信 AI API

- 产品定位：[LyraDB · 天琴智库](../product/产品定位.md)

- 适用版本：3.1.2 AI Native Foundation
- 接口基线：`/ai`、`/agent-gateway/v1`、`/agent-gateway/mcp`
- 安全原则：模型只可检索知识或创建只读计划；执行必须由独立的人类确认通道触发。

## 1. 身份与通用约束

企业 Web API 使用当前登录会话、工作空间和 CSRF 防护。服务端不接受客户端提交的用户、角色或工作空间作为授权依据。Agent Gateway 使用只显示一次的 Bearer 令牌；令牌绑定单一工作空间、用户、READ_ONLY Grant 和 Scope，每次请求都会重新校验用户状态、密码版本、成员关系与 Grant。

所有 SQL 在计划和执行阶段都要经过只读 AST、授权资源、行数和成本边界校验。`runId`、`planSha256`、预检摘要或令牌都不是永久授权。

## 2. 企业 Web API

| 方法与路径 | 作用 | 主要门禁 | 影响 |
|---|---|---|---|
| `GET /ai/capabilities` | 返回服务端能力开关与写入硬门禁 | 已登录 | 只读 |
| `POST /ai/tasks` | 提交 `CHAT`、`FIND_TABLE` 或 `METADATA` 后台任务 | 当前用户、空间与 Grant；请求标识去重 | 返回任务状态，不等待模型或采集完成 |
| `GET /ai/tasks` | 恢复当前用户在当前空间的任务和结果 | 每次重新验证 Grant 及安全指纹 | 返回排队、执行中、完成或失败状态 |
| `POST /ai/agent/orchestrate` | 最多四步调用知识检索或只读计划工具 | Ask Lyra + Knowledge Core + Governed Read Agent、当前 Grant | 回答或创建待确认计划，不执行 |
| `POST /ai/agent/read/plans` | 为候选 SQL 创建不可变计划 | READ_ONLY Grant、AST、资源、行数、成本 | 加密持久化计划正文 |
| `POST /ai/agent/read/plans/{runId}/execute` | 单次消费计划并执行 | 重新鉴权、计划摘要、有效期、执行前复检 | 受控读取并生成回执 |
| `POST /ai/agent/read/plans/{runId}/cancel` | 取消计划或运行 | 当前用户与工作空间所有权 | 持久化取消请求并尝试中止语句 |
| `POST /ai/metadata/snapshots` | 采集显式范围内的元数据 | 当前 Grant 与范围 | 创建短期一次性快照，不读取样本行 |
| `POST /ai/knowledge/ingestions/metadata/{snapshotId}` | 将快照转为知识草稿 | Team Knowledge、快照所有权 | 只创建 `DRAFT`，不发布 |
| `GET /ai/knowledge/verified` | 查看可被 AI 引用的已审核知识 | Knowledge Core、当前工作空间 | 只读 |
| `POST /ai/knowledge/{id}/review` | 核验、驳回或退役知识 | `STEWARD` 或 `DS_ADMIN` | 变更知识状态并审计 |
| `GET /ai/quality/dashboard` | 查看黄金集与最近回归 | AI Quality | 只读 |
| `POST /ai/quality/evaluate/auto` | 使用默认 Provider 跑完整黄金集 | `STEWARD`/`DS_ADMIN`，显式确认模型调用 | 产生模型调用与持久化评测记录 |
| `POST /ai/maxcompute/preflight` | 校验分区、扫描量、成本与授权 | MaxCompute Agent、READ_ONLY Grant | 创建单次预检摘要，不执行 SQL |
| `POST /ai/maxcompute/diagnose` | 对脱敏任务摘要做确定性诊断 | MaxCompute Agent | 不自动重试 |
| `GET /ai/operations/metrics` | 查看进程调用指标和持久运行状态 | `DS_ADMIN`/`STEWARD`/`AUDITOR` | 只读；不含提示词、SQL 或密钥 |

### AI 助手后台任务

默认 `/ai` 页面提供问答、找表与结构采集。受控读取计划、MaxCompute 预检、知识草稿保留在 `/ai/advanced` 高级工具入口；这些高级操作仍使用原有确认流程及接口。

`POST /ai/tasks` 必填 `requestId`（16–64 位字母、数字或连字符）、`kind`、`grantedSourceName`。问答和找表使用 `message`；问答可附加 `history`、`attachMetadata`、`metadataSnapshotId`；结构采集使用 `database`、`schemas`、`tables`。相同用户、空间及请求标识的重复请求返回原任务，内容不一致则拒绝。

状态为 `QUEUED → RUNNING → SUCCEEDED / FAILED`。页面展示状态、等待动画及耗时，不生成虚构的思考过程。切换页面、关闭浏览器和刷新均不会取消服务端任务；重新登录同一账号、进入同一空间后可以读取结果。此队列为单服务进程内存实现，完成后结果保留一小时；重启不恢复，集群需保持会话落在同一实例。每用户最多四个活动任务，线程池最多四线程、排队十六个任务、全局最多保留二百五十六个任务。结构快照仍遵循原有三十分钟有效期及一次性消费约束。

元数据采集将 MySQL 数据库作为 Schema 匹配，兼容请求中的 `*`，仍逐表应用授权白名单与黑名单。空结果直接失败并提示检查范围、授权与连接权限，不创建可附加的空快照。界面不再把授权中的通配符当作具体 Schema 选项。

## 3. 计划确认状态机

```text
PLANNED --确认并成功认领--> RUNNING --> COMPLETED
   |                         |   \
   |                         |    -> FAILED
   |                         -> CANCEL_REQUESTED -> CANCELLED
   -> CANCELLED / EXPIRED
```

计划正文使用 AES-GCM 加密保存；单次认领依赖数据库悲观锁，支持多节点重启后继续确认。进入终态后清空密文。执行前会再次验证用户、工作空间、Grant、SQL AST、资源包络和摘要，任一变化都会失败关闭。

## 4. MCP 2026-07-28

端点为 `POST /agent-gateway/mcp`，使用 Streamable HTTP JSON 响应。请求需同时携带：

- `Authorization: Bearer <一次性展示的 Gateway 令牌>`
- `MCP-Protocol-Version: 2026-07-28`
- `Content-Type: application/json`
- 浏览器来源存在时必须命中 `LYRADB_AI_GATEWAY_ALLOWED_ORIGINS`

支持的方法：`server/discover`、`tools/list`、`tools/call`。工具列表由令牌 Scope 动态裁剪：

- `knowledge.search`
- `sql.read.plan`
- `maxcompute.preflight`
- `maxcompute.diagnose`

MCP 不提供 `sql.read.execute`、Shell、任意 URL 或写入工具。`sql.read.plan` 只返回待确认句柄；执行需进入受控 REST 的独立人类确认通道。

同一令牌也可使用 `/agent-gateway/v1` 类型化 REST。REST 的执行端点仍要求 `READ_EXECUTE` Scope 和计划摘要，不能由 MCP 工具列表间接获得。

## 5. 回执与可观测性

Ask Lyra、只读执行和 MaxCompute 预检会返回 `contextReceipt`，包括用途、Provider、模型、证据摘要、已应用策略、遗漏上下文和稳定内容摘要。工具编排另外返回每一步的工具名、裁决和 Token 用量。

`/ai/operations/metrics` 的调用计数和时延是进程级指标，重启会归零；只读 Agent 状态来自数据库并按工作空间隔离。指标名为固定低基数枚举，不接受用户输入作为标签。

## 6. 明确不兼容的用法

- 不允许把模型回答当成已经执行的数据结果。
- 不允许重复消费计划或 MaxCompute 预检摘要。
- 不允许客户端通过修改 `grantedSourceName`、`runId` 或工作空间参数扩大权限。
- 不允许自动摄取直接进入 `VERIFIED`。
- 不允许在 3.x 启用 Write Agent；服务会拒绝启动。
