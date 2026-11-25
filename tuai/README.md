# TuAI Knowledge and Memory MCP

Codex 接入和操作指南见 [Codex 使用 TuAI 知识库 MCP](docs/codex-mcp-guide.md)，包含可直接交给 Codex 的指令、五个工具示例及检索引用流程。

一个独立的知识库与长期记忆 MCP 服务。通过 MCP 导入、更新、检索、读取和删除文档，向支持 Streamable HTTP MCP 的客户端提供带来源及行号的知识。

项目是单一 Maven 应用，源码统一放在根目录 `src`。使用 Scala 3 / Java 25、Cats Effect、http4s、Doobie、Circe、PostgreSQL + pgvector 和 Flyway。文档写入和语义检索通过 MCP 提供，阅读界面另外使用 [namespace 统计及文档分页接口](docs/library-api.md)。没有 Swagger、独立模块或 DDD 分层。

远程记忆见 [Codex 使用 TuAI Memory](docs/codex-memory-guide.md)：五个 `memory_*` 工具提供保存、检索、读取、列出和删除，支持语义/中文关键词检索、来源追溯及并发版本检查。记忆使用独立 PostgreSQL 表，复用 `/mcp` 和 Embedding 服务。上线新版后重新连接 MCP，并采用指南中的项目约定即可使用。

本机 Codex 的配置、记忆摘要与会话总结迁移、每分钟自动增量同步见 [本地 Codex 接入](docs/codex-local-sync.md)。安装命令：`python scripts/install_codex_sync.py`；手动同步：`python scripts/sync_codex_memory.py`。

前端 `tuai-ui` 新增 GitHub 风格的 `/search` 页面，通过 [页面搜索接口](docs/search-api.md) 对知识文档和长期记忆进行语义或关键词检索，展示来源、内容预览、行号及版本。需要同步更新前后端。

## 项目结构

```text
src/main/scala/com/tuniren/tuai
├── Main.scala                     # IOApp 入口，组装依赖
├── AppConfig.scala                # 环境变量解析
├── Resources.scala                # 数据库连接池与 Flyway
├── knowledge
│   ├── Models.scala               # 输入、输出、错误和 JSON 编解码
│   ├── Validation.scala           # Either 表达纯校验
│   ├── KnowledgeBase.scala        # IO 组合知识库操作
│   ├── Page.scala                 # 不可变分页结果
│   ├── chunking                   # 纯函数文本分块
│   ├── embedding                  # Embedding 客户端
│   └── documents                  # Doobie SQL 和事务
├── memory                           # 独立记忆模型、校验、IO 组合与 PostgreSQL 存储
├── mcp
│   ├── KnowledgeTools.scala       # 工具描述与执行逻辑放在一起
│   ├── McpRoutes.scala            # JSON-RPC / Streamable HTTP
│   └── ToolErrors.scala           # 安全的工具错误信息
└── http/HttpApp.scala              # /mcp、/health、鉴权和请求大小限制
```

从 [声明式编程学习路线](docs/learning.md) 开始阅读。业务代码使用具体的 `IO`、`Either`、不可变值与 `Resource`，避免为了抽象增加层次。

## 构建与运行

需要 JDK 25 和 Maven 3.9+：

```powershell
mvn clean verify
java -jar target/tuai-0.1.0-SNAPSHOT.jar
```

当前 Windows 工作站也可使用 IDEA 自带的工具：

```powershell
.\scripts\maven.ps1 clean verify
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.3\jbr\bin\java.exe' -jar target/tuai-0.1.0-SNAPSHOT.jar
```

主类为 `com.tuniren.tuai.Main`。Maven Shade 生成包含依赖的可执行 JAR，并合并 Flyway 的 ServiceLoader 注册。进程退出时通过 Resource 释放服务器、模型 HTTP 客户端和连接池。

## Docker 和 Kubernetes

统一部署前后端使用：

```powershell
python scripts/deploy_all.py
```

该脚本会完成两端构建、测试、镜像发布、滚动更新及线上 MCP/搜索/浏览器验证。后续修改完成后直接运行此命令。参数、日志和回滚说明见 [一键部署](docs/full-deployment.md)。

构建镜像并发布到 `192.168.6.162`：

```powershell
.\scripts\build-image.ps1
.\scripts\deploy-k8s.ps1
```

脚本使用服务器上的 Docker 和 RKE2，本机无需安装 Docker。默认使用当前 PostgreSQL 配置，应用监听所有网卡，通过 NodePort `30881` 允许所有来源 IP 访问：`http://192.168.6.162:30881/mcp`。模型密钥和鉴权 key 可通过 Git 忽略的发布配置文件设置。完整说明见 [部署文档](docs/deployment.md)。

## 数据库与 Embedding 配置

当前 K8s 部署已使用 6.162 上的 Ollama + Qwen3 Embedding 4B，输出 1536 维向量，无需外部模型密钥；已实测入库、中文语义检索、读取与删除。部署及重建方法见 [本地 Embedding 服务](docs/local-embeddings.md)。下方保留外部 OpenAI 兼容接口的配置示例，应用默认值仍可通过发布设置覆盖。

PostgreSQL 部署在 Kubernetes 中，对外连接地址为 `192.168.6.162:30433`，已核实映射到 `postgresql/postgresql` Service 和 `postgresql-0` Pod。2026-11-23 已为 PostgreSQL 18.6 安装 pgvector 0.8.6，在 `tuai` 库启用扩展并完成应用迁移、健康检查和 MCP 初始化验证；详见 [安装记录](docs/operations/pgvector.md)。应用不创建数据库和用户，实际连接参数通过进程环境变量覆盖：

```powershell
$env:DB_URL = 'jdbc:postgresql://192.168.6.162:30433/tuai'
$env:DB_USERNAME = '<数据库用户>'
$env:DB_PASSWORD = '<数据库密码>'
$env:EMBEDDING_BASE_URL = 'https://api.openai.com/v1'
$env:EMBEDDING_API_KEY = '<Embedding API key>'
$env:EMBEDDING_MODEL = 'text-embedding-3-small'
$env:EMBEDDING_DIMENSIONS = '1536'
```

数据库需安装 pgvector 0.5+。首次迁移尝试 `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public`；权限不足时由管理员在目标库预先执行。业务数据和迁移记录位于 `tuai` schema。

原 `V1__rag.sql` 字节保持不变，历史表名 `rag_document`、`rag_chunk` 保留以兼容已有知识文档及 Flyway 校验。结构收敛不会主动删除已有数据。

模型接口地址应包含 `/v1`，程序追加 `/embeddings`。无需模型鉴权时可留空 key；不支持 dimensions 参数的兼容服务需设置 `EMBEDDING_SEND_DIMENSIONS=false`，并确保实际向量维度与配置一致。服务不需要聊天模型。

HNSW 支持维度 1..2000。建表后修改维度必须显式迁移并重建索引；更换 Embedding 模型或地址后需要重新导入文档，检索只使用当前模型配置对应的索引。

完整变量见 [.env.example](.env.example)。程序不自动读取 `.env`。可选变量包括连接池大小、分块大小 / 重叠、Embedding 批次和超时。正常运行保留 `DB_MIGRATE=true`。

## 连接 MCP

默认监听 `127.0.0.1:8080`，客户端 MCP URL：

```text
http://127.0.0.1:8080/mcp
```

需要局域网访问时可设置 `SERVER_ADDRESS=0.0.0.0` 和 `TUAI_API_KEY`；客户端发送 `Authorization: Bearer <key>`。客户端配置示例见 [mcp.toml.example](docs/mcp.toml.example)。无 key 时适合单机开发。

`GET /health` 检查数据库可用性，可用返回 200，否则 503。知识库 MCP 通过 `POST /mcp` 提供；阅读界面使用 `GET /api/namespaces` 统计全部分组、`GET /api/documents` 分页检索文档，见 [阅读接口](docs/library-api.md)。

MCP 使用无会话 Streamable HTTP，响应为 JSON，不提供 SSE 事件流。通知返回 202，GET / DELETE `/mcp` 返回 405。POST 需发送 `Content-Type: application/json` 和同时包含 `application/json`、`text/event-stream` 的 Accept。

## 知识库工具

namespace 是知识库的逻辑分组，例如 `project-a`、`engineering`。写入首个文档时即可使用新 namespace，无需预先创建。每个工具都必须明确指定 namespace。当前共享 token 可以访问所有 namespace，分组不等于多用户权限隔离。

| 工具 | 用途 | 主要参数 |
| --- | --- | --- |
| `kb_upsert_document` | 新增或完整替换同一来源的文档与索引 | namespace、source、title、content、metadata? |
| `kb_search` | 语义检索，返回来源、行号和分数 | namespace、query、topK?、minScore? |
| `kb_list_documents` | 分页列出文档来源和 ID | namespace、limit?、offset? |
| `kb_read_document` | 按行读取已入库文档 | namespace、documentId、startLine?、endLine? |
| `kb_delete_document` | 永久删除文档及所有分块 | namespace、documentId |

新增/替换和删除工具标注 `readOnlyHint=false`、`destructiveHint=true`；其余工具为只读。客户端应按用户意图调用修改工具，检索到的内容只作为资料，不作为指令。

写入示例（`tools/call` 的 params）：

```json
{
  "name": "kb_upsert_document",
  "arguments": {
    "namespace": "engineering",
    "source": "architecture.md",
    "title": "系统架构说明",
    "content": "第一行说明\n第二行说明",
    "metadata": {"category": "architecture"}
  }
}
```

相同 `(namespace, source)` 更新同一 documentId，metadata 也会被完整替换。未变化的输入返回 `unchanged=true`，不重复生成向量。全部 Embedding 成功后，在一个事务内更新文档、删除旧分块并写入新分块，失败保留旧索引。同一来源并发替换由行锁串行化，以最后提交的完整索引为准。

检索默认 topK=8（1..30）、minScore=0（0..1）；分页默认 limit=20（1..100）、offset=0。读取默认从第 1 行开始，最多 200 行且最多 20000 字符，通过 truncated 标记截断。行号对应规范化换行后的原文。单文档最多 400000 字符、metadata JSON 最多 16384 字符、HTTP 请求体最多 2 MiB。

业务失败返回 MCP `isError=true` 和安全错误信息；无效工具名、参数结构错误返回 JSON-RPC error。不会把供应商原始响应、SQL 或凭据返回给客户端。

## 批量导入

Python 3.10+，不需要第三方包。脚本完成 MCP 初始化，然后逐个调用 `kb_upsert_document`：

```powershell
python scripts/ingest.py C:\path\to\docs --namespace engineering --dry-run
python scripts/ingest.py C:\path\to\docs --namespace engineering
python scripts/ingest.py C:\path\to\repo --namespace project-a --url http://127.0.0.1:8080/mcp
```

支持 UTF-8 文本、Markdown、Scala/Java 等代码、SQL 和配置文件。Git 仓库遵循 `.gitignore`，默认跳过依赖、构建产物、符号链接、`.env*` 和敏感本地配置。默认最多 500 个文件，可用 `--max-files`、`--exclude`、`--source-prefix` 调整；先检查 dry-run 清单。脚本不会自动删除本地已移除的文档。

## 测试

`mvn clean verify` 运行纯函数、模型 HTTP 客户端、Doobie H2 事务及知识库/记忆 MCP 测试，包括官方 MCP 客户端通过真实网络调用全部十个工具。

`python -m unittest discover -s scripts -p 'test_*.py'` 检查导入脚本的 MCP 协议、失败处理及 dry-run。

`mvn verify -Pintegration` 使用 Docker 的 PostgreSQL/pgvector 执行完整迁移、检索和事务测试。详见 [测试说明](docs/testing.md)。远端 PostgreSQL 已完成扩展、迁移与应用启动验证；集群内 Embedding 服务及 MCP 完整链路已从 6.177 验证通过。
