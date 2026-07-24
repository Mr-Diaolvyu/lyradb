# 贡献指南 · Contributing to LyraDB

感谢你对 **LyraDB（天琴智库）** 的关注！无论是报告缺陷、提出想法还是提交代码，我们都非常欢迎。

## 📋 参与方式

- **报告缺陷**：通过 [Issues](https://github.com/Mr-Diaolvyu/lyradb/issues) 提交 Bug，请使用 Bug 模板并尽量附上复现步骤、环境信息和日志。
- **功能建议**：同样在 Issues 中使用「功能请求」模板描述你的场景与期望。
- **提交代码**：Fork 仓库 → 新建分支 → 提交 Pull Request（见下文流程）。

## 🛠️ 开发环境

| 组件 | 版本要求 |
|------|----------|
| JDK | 17+ |
| Maven | 3.6+ |
| Node.js | 18+ / npm |

```bash
# 后端
cd backend
mvn spring-boot:run          # 默认 http://localhost:8080/api

# 前端
cd frontend
npm install
npm run dev                  # 默认 http://localhost:5173
```

发行版通过环境变量 `LYRADB_EDITION` 切换：`personal`（默认，无认证）/ `enterprise`（RBAC/审批/审计）。更多配置见 [wiki/配置说明.md](wiki/配置说明.md)。

## 🌿 分支与提交规范

- 从 `main` 切出功能分支，命名建议：`feat/xxx`、`fix/xxx`、`docs/xxx`、`refactor/xxx`。
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)：

  ```
  <type>: <简要描述>

  常用 type：feat / fix / docs / refactor / test / chore
  例：feat: 新增 ClickHouse 分区信息展示
  ```

- 一个 PR 聚焦一件事，保持提交历史清晰。

## 🔀 Pull Request 流程

1. Fork 本仓库并克隆到本地。
2. 基于最新 `main` 创建分支：`git checkout -b feat/your-feature`。
3. 完成修改，确保后端 `mvn -o compile` 通过、前端可正常构建。
4. 若改动涉及接口 / 结构 / 配置，请同步更新 `wiki/` 下对应文档。
5. 推送分支并发起 PR，填写 PR 模板，关联相关 Issue（如 `Closes #123`）。

## ✅ 代码风格

- **后端**：遵循现有包结构（`config` / `controller` / `driver` / `model` / `repository` / `service`），注释使用简体中文，与周边代码保持一致。
- **前端**：TypeScript + Vue 3 组合式 API，组件与 Store 命名沿用现有约定。
- **安全**：涉及数据库元数据拼接时，务必对标识符做校验 / 转义，防止 SQL 注入。

## 📄 许可证

提交代码即表示你同意你的贡献以 [Apache License 2.0](LICENSE) 授权发布。

---

有任何疑问，欢迎在 Issues 中交流。感谢你的贡献！ 🎉
