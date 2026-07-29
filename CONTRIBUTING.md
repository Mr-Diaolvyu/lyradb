# 贡献指南

感谢参与 LyraDB。缺陷、需求和代码修改都应尽量给出可复现信息，并保持单个 PR 聚焦一个主题。

## 开发环境

| 工具 | 版本 |
| --- | --- |
| JDK | 17+；桌面打包需带 jpackage 的 JDK 21 |
| Maven | 3.8+ |
| Node.js | 20 |
| npm | 随 Node.js 20，必须使用仓库锁文件 |

后端：

```bash
cd backend
mvn spring-boot:run
```

前端：

```bash
cd frontend
npm ci
npm run dev
```

本地默认是 `dev + personal`，仅用于回环地址开发。不要把 dev 默认密钥用于共享或生产环境；生产/Compose 默认使用 enterprise，并要求显式配置密钥、H2 密码和 CORS 来源；企业空用户库首次启动还必须提供初始管理员变量，初始化后应移除。

## 分支与提交

- 从最新 `main` 创建分支，建议使用 `feat/`、`fix/`、`docs/`、`refactor/`、`test/` 前缀。
- 提交信息遵循 Conventional Commits，例如 `fix: 阻止旧查询响应覆盖新结果`。
- 不提交 `.env`、数据库文件、动态驱动缓存、构建产物或真实凭据。
- 不使用 `git add .` 盲目暂存；先复核变更文件。

## 提交前检查

```bash
cd frontend
npm run lint
npm run typecheck
npm run test
npm run build
```

```bash
cd backend
mvn -B -ntp clean verify
```

如果改动 Android 外壳，还应执行：

```bash
cd mobile/android
gradle --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

iOS 和 HarmonyOS 改动必须分别在 Xcode / DevEco Studio 编译；无法使用目标工具链时，应在 PR 中明确列为未验证项，不能声称已通过。

## Pull Request 要求

PR 描述至少包含：

- 问题与根因；
- 行为变化和兼容性影响；
- 实际执行的测试及结果；
- 未能验证的工具链/环境；
- 涉及配置、接口、迁移或用户流程时同步更新的文档。

PR CI 会执行前端质量门禁、后端 `clean verify`、Compose 配置校验和 Android Debug 构建。不要通过 `-DskipTests`、降低 lint 级别或吞掉命令退出码绕过门禁。

## 安全规则

- 不记录密码、Cookie、令牌、完整连接串或 Axios 原始错误对象。
- 企业角色与资源归属必须在后端校验；前端隐藏按钮不是授权。
- 所有写请求保留 CSRF 保护，登录前先获取 `/auth/csrf`。
- 审批必须绑定最终 SQL、格式、数据源和数据库；不能审批一种操作后执行另一种。
- 外部 AI 与 Webhook 必须通过服务端主机白名单，默认空即禁用。
- 新数据库结构通过 Flyway 迁移，生产不使用 Hibernate `ddl-auto=update`。
- 新依赖应说明用途、许可证与供应链风险，并保持 lockfile / Maven 版本可复现。

## 许可证

提交代码即表示同意按 [Apache License 2.0](LICENSE) 发布贡献。
