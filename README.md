# TuAI

知识库与长期记忆 MCP 服务，以及配套的 Web 阅读和搜索界面。

本仓库统一维护前后端：

| 目录 | 内容 | 技术栈 |
| --- | --- | --- |
| [tuai](tuai/README.md) | 后端、MCP、数据库迁移和部署脚本 | Scala 3、Java 25、http4s、PostgreSQL / pgvector |
| [tuai-ui](tuai-ui/README.md) | 文档阅读与知识、记忆搜索界面 | React、TypeScript、Vite、Ant Design |

## 开发

后端构建与测试（在 `tuai` 目录执行）：

```powershell
.\scripts\maven.ps1 clean verify
```

前端开发（在 `tuai-ui` 目录执行）：

```powershell
npm ci
npm run dev
```

详细配置与开发说明见两端 README。本机凭据、依赖、工具与构建产物不纳入版本控制；配置示例保留在仓库中。

## 统一部署

从仓库根目录执行：

```powershell
python tuai/scripts/deploy_all.py
```

该脚本构建、测试和发布前后端，并验证线上服务。配置与运维说明见 [一键部署文档](tuai/docs/full-deployment.md)。
