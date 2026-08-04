# LyraDB 可信 AI 数据智库：实现状态与灰度顺序

- 产品定位：[LyraDB · 天琴智库](产品定位.md)

- 评审日期：2026-08-03
- 实现分支：`codex/ai-native-foundation`
- 产品基线：3.1.2
- 状态说明：代码实现与全量自动化复核已完成，等待真实环境灰度；不代表已经发布、部署或默认开放。

## 1. 本轮实现结论

LyraDB 的企业 AI 已从单一聊天入口调整为四个互相约束的产品面：

1. **Ask Lyra**：在最多四步的服务端工具循环中检索已验证知识或创建只读计划，并返回轨迹、用量、证据与 Context Receipt。
2. **智库运营**：支持元数据自动摄取为草稿、个人贡献、人工审核、Verified Query、混合检索、自动黄金集和 Gateway 身份治理。
3. **受控执行**：所有数据读取先生成不可变计划，经用户确认计划摘要后，才进入既有 AST、Grant、脱敏、行数限制、取消与审计链；计划支持加密持久化、重启恢复和跨节点取消。
4. **开放与运维**：MCP/REST 双协议、实时/持久状态指标，以及显式白名单的私有模型接入。

写入型 Agent 不属于本轮交付。3.x 即使把环境变量设为 `true`，服务端也会拒绝启动，防止误开放。

## 2. 路线图状态

| 阶段 | 能力 | 当前实现 | 默认状态 | 仍需上线证据 |
|---|---|---|---|---|
| 3.2 | AI Foundation | 统一证据、上下文回执、权限包络、风险等级、四步类型化工具编排、Feature Flag、低基数指标 | Ask 开；高阶关 | 真实 Provider 与数据库灰度 |
| 3.3 | Ask Lyra + Knowledge Core | 仅检索 `VERIFIED`；元数据摄取只建草稿；关键词/可选向量混合检索；编排轨迹可见 | 关 | 小范围真实口径试点 |
| 3.4 | Governed Read Agent | 加密持久化计划、单次悲观锁认领、执行前复检、跨重启/节点取消、终态清密文 | 关 | 多数据库预生产只读 E2E |
| 3.5 | Team Knowledge + AI Quality | 人工审核、版本化黄金集、默认 Provider 自动完整回归、质量门禁 | 关 | 建立基线并连续观测 |
| 3.6 | MaxCompute Intelligence Agent | 分区 AST、实时元数据/EXPLAIN/成本证据分级、持久单次预检、失败诊断 | 关 | 真实 MaxCompute Project 验证成本与方言 |
| 3.7 | Agent Gateway | 短期身份、Scope、限流、Origin、受控 REST 与 MCP `2026-07-28`、每次再鉴权 | 关 | 外部客户端联调、轮换和撤销演练 |
| 4.0 | Governed Write Agent | 只有服务端硬门禁，没有写工具 | **强制关闭** | 见写入 Agent 门禁评审 |

## 3. 关键执行边界

### Ask Lyra

- 输入：用户问题、逻辑数据源、可选且显式选择的元数据快照。
- 过滤：当前工作空间、当前用户 Grant、`VERIFIED` 知识、上下文字符上限。
- 碰撞裁决：权限、来源或摘要不一致时失败关闭；未命中证据时明确返回遗漏项。
- 最终影响：只生成建议与 SQL，不自动执行。

### 受控只读 Agent

- 输入：问题、候选只读 SQL、默认数据库、行数和成本声明。
- 过滤：只读 AST、Grant、资源范围、服务端行数与成本预算、短时计划有效期。
- 碰撞裁决：执行时重新校验用户、工作空间、Grant、SQL 摘要和计划摘要；任一变化即拒绝。
- 最终影响：复用企业查询治理链读取，并生成可审计回执；取消会向运行中的数据库语句派发中止信号。

### Agent Gateway

- 输入：Bearer 令牌和固定的类型化工具请求。
- 过滤：令牌摘要、有效期、撤销、用户状态、密码版本、成员关系、READ_ONLY Grant、Scope。
- 碰撞裁决：每次请求重新鉴权；Gateway 身份不继承签发者权限，也不能绑定平台管理员。
- 最终影响：REST 可按独立 Scope 提供知识、计划、人工确认执行和 MaxCompute 分析；MCP 只提供知识、计划、预检与诊断，不暴露执行、任意工具、Shell 或写入端点。

## 4. 推荐灰度顺序

1. 在隔离测试空间启用 Knowledge Core 与 Team Knowledge，录入少量已核验口径。
2. 运行完整黄金集，达到门禁后再启用 Governed Read Agent。
3. 使用只读、低行数 Grant 完成 MySQL/PostgreSQL/MaxCompute 预生产验证。
4. 单独试点 MaxCompute Agent，核对真实分区语义与成本估算偏差。
5. 最后试点 Gateway，完成签发、使用、密码轮换、Grant 回收和令牌撤销演练。
6. 写入 Agent 继续保持硬关闭，直到独立 4.0 评审通过。

## 5. 功能开关

| 环境变量 | 默认值 | 作用 |
|---|---:|---|
| `LYRADB_AI_ASK_ENABLED` | `true` | Ask Lyra 建议入口 |
| `LYRADB_AI_KNOWLEDGE_ENABLED` | `false` | 已验证知识检索 |
| `LYRADB_AI_READ_AGENT_ENABLED` | `false` | 受控只读计划与执行 |
| `LYRADB_AI_TEAM_KNOWLEDGE_ENABLED` | `false` | 团队草稿与审核闭环 |
| `LYRADB_AI_QUALITY_ENABLED` | `false` | 黄金集与质量仪表 |
| `LYRADB_AI_MAXCOMPUTE_AGENT_ENABLED` | `false` | MaxCompute 专项预检和诊断 |
| `LYRADB_AI_GATEWAY_ENABLED` | `false` | 外部 Agent Gateway |
| `LYRADB_AI_WRITE_AGENT_ENABLED` | `false` | 3.x 硬门禁；设为 `true` 会拒绝启动 |
| `LYRADB_AI_KNOWLEDGE_SEMANTIC_ENABLED` | `false` | 知识混合向量检索 |
| `LYRADB_AI_MAXCOMPUTE_LIVE_EVIDENCE_ENABLED` | `false` | 实时分区、EXPLAIN 和成本证据 |
| `LYRADB_AI_MAXCOMPUTE_LIVE_EVIDENCE_REQUIRED` | `false` | 缺少完整实时证据时失败关闭 |
| `LYRADB_AI_PRIVATE_MODEL_ENABLED` | `false` | 允许受控私有模型出站 |
| `LYRADB_AI_PRIVATE_MODEL_ALLOWED_HOSTS` | 空 | 私有模型精确主机白名单 |
| `LYRADB_AI_EXECUTION_NODE_ID` | 空 | 多节点执行实例标识 |

高级能力具有依赖关系：团队知识依赖 Knowledge Core；只读 Agent 依赖 Ask Lyra；MaxCompute Agent 依赖只读 Agent；Gateway 依赖 Knowledge Core 与只读 Agent。服务端会在启动和请求两层校验，不能只靠前端绕过。

完整配置、有效范围和私有模型上线步骤见 [可信 AI 部署配置](../operations/AI原生配置与运维.md)。接口和协议边界见 [可信 AI API](../api/AI原生接口.md)。

## 6. 当前证据边界

- 已有：Core 96 项、Enterprise Backend 235 项、Personal Desktop 67 项、Frontend 30 项测试全部通过；Spring 启动、Flyway V1–V7、前端生产构建和服务端端到端打包通过。
- 制品：`lyradb-backend-3.1.2.jar`（97,504,668 字节），SHA-256 为 `FED264AFC15919934ABB769B64ECD97B343B2DBD0B5C658A26803919691BD1D0`。
- 尚无：真实企业数据样本、真实 MaxCompute 账户成本观测、外部 MCP 客户端协议认证、生产质量连续观测。
- 因此：本轮可判定为“实现完成、自动化通过、待灰度验收”，不能宣称高阶 AI 已生产可用。
- 真实环境所需条件和用例见 [可信 AI 验收计划](../testing/AI原生验收计划.md)。
