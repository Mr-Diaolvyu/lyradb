# LyraDB 可信 AI 部署配置

- 产品定位：[LyraDB · 天琴智库](../product/产品定位.md)

- 适用版本：3.1.2 AI Native Foundation
- 默认策略：Ask Lyra 可用；高阶能力、私有模型出站、MaxCompute 实时证据和 Agent Gateway 均失败关闭。

## 1. 能力开关与依赖

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `LYRADB_AI_ASK_ENABLED` | `true` | Ask Lyra 基础回答 |
| `LYRADB_AI_KNOWLEDGE_ENABLED` | `false` | 仅检索 `VERIFIED` 知识 |
| `LYRADB_AI_READ_AGENT_ENABLED` | `false` | 受治理只读计划、确认、执行和取消 |
| `LYRADB_AI_TEAM_KNOWLEDGE_ENABLED` | `false` | 草稿、摄取与人工审核闭环 |
| `LYRADB_AI_QUALITY_ENABLED` | `false` | 黄金集与自动回归 |
| `LYRADB_AI_MAXCOMPUTE_AGENT_ENABLED` | `false` | MaxCompute 预检与诊断 |
| `LYRADB_AI_GATEWAY_ENABLED` | `false` | 类型化 REST 与 MCP Gateway |
| `LYRADB_AI_WRITE_AGENT_ENABLED` | `false` | 3.x 硬门禁；设为 `true` 会拒绝启动 |

启动依赖：Read Agent 依赖 Ask；Team Knowledge 依赖 Knowledge Core；MaxCompute Agent 依赖 Read Agent；Gateway 同时依赖 Knowledge Core 与 Read Agent。错误组合会在启动时失败，而不是只隐藏前端入口。

## 2. 知识与评测

| 环境变量 | 默认值 | 有效范围 |
|---|---:|---|
| `LYRADB_AI_MAX_KNOWLEDGE_CONTEXT_CHARS` | `12000` | `1000-100000` |
| `LYRADB_AI_KNOWLEDGE_SEMANTIC_ENABLED` | `false` | 启用混合向量检索 |
| `LYRADB_AI_KNOWLEDGE_EMBEDDING_MODEL` | `text-embedding-v3` | 最长 200 字符 |
| `LYRADB_AI_KNOWLEDGE_LEXICAL_WEIGHT` | `0.65` | `0-1` |

语义检索必须同时启用 Knowledge Core 并配置可用的 Embedding 模型。不可用时按受控降级策略回到关键词检索，并在回执中记录遗漏原因。

## 3. 只读 Agent 与多节点

| 环境变量 | 默认值 | 有效范围 |
|---|---:|---|
| `LYRADB_AI_READ_AGENT_PLAN_TTL_SECONDS` | `300` | `30-3600` 秒 |
| `LYRADB_AI_READ_AGENT_MAX_ROWS` | `1000` | `1-10000` |
| `LYRADB_AI_READ_AGENT_MAX_COST_MICROS` | `0` | 非负；`0` 表示不接受声明成本 |
| `LYRADB_AI_EXECUTION_NODE_ID` | 空 | 可选，最长 128 字符 |
| `LYRADB_AI_CANCEL_POLL_INTERVAL_MS` | `1000` | `250-60000` 毫秒 |

生产多节点建议为每个实例设置稳定且唯一的 `LYRADB_AI_EXECUTION_NODE_ID`。取消请求持久化到数据库；执行节点按轮询间隔检查，并同时向本机正在运行的 JDBC Statement 派发取消。

## 4. MaxCompute 实时证据

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `LYRADB_AI_MAXCOMPUTE_LIVE_EVIDENCE_ENABLED` | `false` | 使用当前只读连接采集分区、EXPLAIN/成本证据 |
| `LYRADB_AI_MAXCOMPUTE_LIVE_EVIDENCE_REQUIRED` | `false` | 缺少完整实时证据时拒绝生成可消费预检 |

响应中的 `evidenceMode` 区分 `DECLARED_ONLY`、`LIVE_PARTIAL` 和 `LIVE_COMPLETE`。调用方声明的扫描量或成本不会被标记为实时值。强制实时证据必须同时启用 MaxCompute Agent 和实时证据开关。

## 5. Agent Gateway 与 MCP

| 环境变量 | 默认值 | 有效范围 |
|---|---:|---|
| `LYRADB_AI_GATEWAY_REQUESTS_PER_MINUTE` | `120` | `1-10000` |
| `LYRADB_AI_GATEWAY_EXPENSIVE_REQUESTS_PER_MINUTE` | `20` | 不高于普通上限 |
| `LYRADB_AI_GATEWAY_ALLOWED_ORIGINS` | 空 | 浏览器来源精确白名单 |
| `LYRADB_AI_MCP_MAX_REQUEST_BYTES` | `1048576` | `1 KiB-5 MiB` |

Gateway 令牌正文只显示一次，数据库只保存摘要。上线前必须完成签发、调用、密码轮换、Grant 回收、撤销和限流演练。

## 6. 私有模型

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `LYRADB_AI_PRIVATE_MODEL_ENABLED` | `false` | 允许创建 `PRIVATE` 部署模式 Provider |
| `LYRADB_AI_PRIVATE_MODEL_ALLOWED_HOSTS` | 空 | 逗号分隔的精确主机名，不接受通配符 |
| `LYRADB_AI_PRIVATE_MODEL_REQUIRE_HTTPS` | `true` | 仅在受控测试网显式设为 `false` |

私有模型接入步骤：

1. 核对模型服务兼容 OpenAI Chat Completions 接口，并确定固定主机名。
2. 在服务端启用私有模型并配置精确主机白名单；不要填写路径、端口通配或 `*.example.com`。
3. 默认保留 HTTPS；只有隔离网络测试服务才临时允许 HTTP。
4. 在“管理 → AI”创建 `PRIVATE` Provider。无鉴权内网服务可留空 API Key。
5. 先运行自动黄金集，再开放 Read Agent。

服务端会在保存和每次调用前重新解析 DNS，并拒绝未明确允许的任意地址、链路本地、多播和白名单外主机。`PUBLIC` Provider 始终执行公网 HTTPS 策略。

## 7. 密钥与日志

AI Key 与计划正文使用 AES-GCM 加密。不要把真实 Key、连接凭据或业务样本写进环境样例、日志、评测问题或故障摘要。运行指标只记录固定操作名、调用数、失败数与耗时。
