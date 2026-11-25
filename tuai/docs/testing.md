# 测试

单应用构建：

```powershell
mvn clean verify
# 当前 Windows 工作站也可用：
.\scripts\maven.ps1 clean verify
```

所有 JVM 测试使用 Scala 3 + JUnit 5。`unsafeRunSync` 仅位于测试边界，生产程序由 IOApp 运行。

默认测试不需要外部 PostgreSQL 或实际模型服务，覆盖：

- 分块全文覆盖、重叠、行号、Unicode 边界和纯校验。
- Circe 缺省字段/null 默认值。
- Embedding 客户端的延迟执行、批次、顺序恢复、无效向量、错误脱敏、超时和资源释放。
- 正式 Doobie 分块写入程序在 H2 内存库中的事务回滚、namespace 隔离和级联删除。
- 完整 MCP 知识库流程：新增、幂等重导、检索、读取、更新、分页、跨 namespace 删除拒绝和删除。
- 入库失败保留旧索引、无效参数不调用模型、鉴权、健康检查、请求大小上限和协议错误。
- 写工具与只读工具的提示正确，旧业务 REST 和 Swagger 路由已不存在。
- 完整记忆 MCP 流程：新增、回忆、纠正、忘记、版本冲突、namespace 分组、中文关键词及模型故障时保留旧记录。
- 页面搜索 HTTP 接口：文档与记忆字段、模型故障时切换关键词、所有文件类型、字面通配符、参数校验、鉴权及错误脱敏。
- 官方 MCP 客户端通过真实 Ember 网络监听器调用全部十个知识库与记忆工具。

协议/用例测试使用 Ref 内存文档库，不证明 PostgreSQL SQL 可运行。H2 测试执行正式 ConnectionIO 分块事务，不能验证 pgvector 运算或 PostgreSQL upsert。

## 导入脚本

```powershell
python -m unittest discover -s scripts -p 'test_*.py'
```

使用本机 HTTP 测试服务验证 MCP 初始化、通知、认证 header、工具名、成功/失败响应，以及 dry-run 不建立网络连接。

本地记忆同步器另有 6 项测试，覆盖只读文件白名单、按主题拆分、增量 hash、远端版本冲突保护、写入响应丢失后的读回、旧主题清理而保留知识归档、UTF-16 上限、敏感值脱敏及保留原 Codex 配置。与导入器一起执行上述 unittest 命令，共 9 项。

## PostgreSQL 集成测试

需要 Docker Linux 容器：

```powershell
mvn verify -Pintegration
```

KnowledgeBasePostgresIT 用 Resource 启动独立的 pgvector/pgvector:pg17，运行正式 Flyway 迁移和 Hikari/Doobie SQL，检查幂等、向量检索、namespace、整体回滚、并发替换不混合及级联删除。MemoryPostgresIT 验证 V1 升级保留文档、记忆持久读取、中文关键词、语义检索、并发版本检查、事务回滚和删除后重建的旧版本保护。模型使用确定性测试实现，不调用付费接口，也不使用业务库。

默认 verify 不运行 *IT。无 Docker 时可以显式指定外部 PostgreSQL 管理连接，测试会为每个用例创建随机 `tuai_memory_it_*` 临时数据库并在结束时删除，不迁移或写入 URL 指向的管理数据库。需要建库/删库权限以及已安装的 pgvector：

```powershell
$env:TUAI_TEST_POSTGRES_ADMIN_URL = 'jdbc:postgresql://<host>:<port>/postgres'
$env:TUAI_TEST_POSTGRES_USERNAME = '<测试管理员>'
$env:TUAI_TEST_POSTGRES_PASSWORD = '<密码>'
.\scripts\maven.ps1 verify -Pintegration
```

2026-11-23 记忆功能已通过 22 项默认测试及 2 项真实 PostgreSQL 18.6 / pgvector 0.8.6 集成测试。集成测试使用远程服务器上独立临时数据库并自动清理，业务库和线上服务未在本次开发中升级。Embedding 使用确定性测试实现；真实模型下的 memory 链路需要部署后联调。历史安装记录见 [pgvector](operations/pgvector.md)。

2026-11-24 搜索页面后端已通过 25 项默认测试和 2 项 PostgreSQL 集成测试，新增 SQL 校验覆盖中文、英文大小写、`%_` 字面匹配、非 Markdown 文档、namespace 和旧 embedding profile 的关键词检索。前端测试和运行方式见 `tuai-ui/README.md`。

同日新增可选 namespace 搜索后，默认测试增至 26 项；PostgreSQL 集成测试覆盖两种搜索模式的跨 namespace 查询、全局结果上限及记忆分组列表。页面检索省略 namespace 搜索全部分组，MCP 仍要求明确分组。浏览器验证增加分组选择/取消、URL 历史、跨分组文档链接及记忆详情所属分组。
