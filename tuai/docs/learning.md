# 从 TuAI 学习声明式编程

这份路线按“纯值 → 纯函数 → 副作用描述 → 程序组合 → 资源 → 事务 → 协议”展开。先读一个入库用例，再向两边展开；不需要先掌握整个 Cats 生态。

## 1. 用类型表达数据和可能失败

从 [Models.scala](../src/main/scala/com/tuniren/tuai/knowledge/Models.scala) 和 [Validation.scala](../src/main/scala/com/tuniren/tuai/knowledge/Validation.scala) 开始。

`case class` 是不可变数据。`Option[ExistingDocument]` 表示可能没有文档；`Either[BadInput, UpsertRequest]` 表示校验结果。阅读一个类型签名时，先回答“输入是什么，输出有哪些可能”，再看实现。

练习：给 source 增加一条规则，先让校验返回 `Left(BadInput(...))`，然后在测试中检查它。不要在校验中访问数据库、记录日志或修改全局状态。

## 2. 用组合表达分块

阅读 [TextChunker.scala](../src/main/scala/com/tuniren/tuai/knowledge/chunking/TextChunker.scala)。

- `scanLeft`：从字符序列累计得到每个位置所在行。
- `Iterator.unfold`：给定起点，描述当前窗口以及下一个起点；没有下一个窗口时返回 `None`。
- `filter`：保留含实际内容的窗口。
- `zipWithIndex.map`：把窗口转换成带序号的分块值。

不必为了“函数式”消灭局部变量；这里的 `val` 用来给中间计算命名，帮助读懂规则。判断重点是函数能否由输入独立决定结果。

练习：用测试描述“优先按空行分块”，再修改边界选择函数，保持全文覆盖和 Unicode 不被切坏。

## 3. IO 是程序描述

阅读 [KnowledgeBase.scala](../src/main/scala/com/tuniren/tuai/knowledge/KnowledgeBase.scala)。

```scala
val program: IO[UpsertResult] = knowledge.upsert(request)
```

这一步构造一个程序值，不会立刻发送 Embedding 请求或操作数据库。`for` 把上一步的结果交给下一步，它会组合 `flatMap` / `map`，不是另起线程，也不会自动并行。

`IO.fromEither` 把纯校验结果接入效果程序；`IO.pure` 提供已知值；`IO(...)` 延迟同步操作；阻塞 JDBC / Flyway 工作交给 Doobie 或 `IO.blocking`。计算失败后，后续步骤不会继续。

练习：先看 `EmbeddingTest` 中构造 `program` 前后的请求计数，再运行它，观察描述与执行的区别。

## 4. 在真实边界使用小接口

[DocumentStore](../src/main/scala/com/tuniren/tuai/knowledge/documents/DocumentStore.scala) 和 [Embeddings](../src/main/scala/com/tuniren/tuai/knowledge/embedding/Embeddings.scala) 描述两种外部能力，都直接返回具体的 `IO`。

它们没有 DDD 的层次约束，也没有 `F[_]` 泛型。价值在于用例不需要知道 SQL、连接池或 HTTP 报文；测试可以用 `Ref` 保存内存状态。`Ref.modify` 表达原子状态转换，比共享可变 Map 更方便处理并发测试。

练习：实现一个返回固定向量的 `Embeddings`，运行入库流程；再让它失败，验证旧文档没有被覆盖。

## 5. 用 Resource 表达生命周期

阅读 [Main.scala](../src/main/scala/com/tuniren/tuai/Main.scala) 和 [Resources.scala](../src/main/scala/com/tuniren/tuai/Resources.scala)。

`Resource` 把获取和释放配在一起。组合后，任何中间步骤失败、正常退出或取消，已经获取的资源都会按相反顺序释放。`server.useForever` 保持服务存活；入口 `IOApp` 负责执行和关闭。

生产代码不手工调用 `unsafeRunSync`。看到要调用它才能把两部分接起来时，先考虑能否通过 `map`、`flatMap` 或 `Resource` 组合。

## 6. 一个 transact 对应一个事务

阅读 [PostgresDocumentStore.scala](../src/main/scala/com/tuniren/tuai/knowledge/documents/PostgresDocumentStore.scala)。

`sql"..."` 描述带绑定参数的 SQL。`query`、`update` 和 `ConnectionIO` 的 `for` 都在描述数据库程序。`program.transact(xa)` 才把整个程序变成一个可运行的 `IO`，统一处理连接、提交和回滚。

`replace` 的文档 upsert、删除旧分块、批量写入新分块只有一次 `transact`。如果给每句 SQL 各加一次 `transact`，它们就不再属于同一个原子操作。Embedding 先执行完，数据库事务中不包含网络模型调用。

练习：阅读 `DoobieStoreTest`：第一条新分块成功、第二条被约束拒绝，最终仍然保留原分块。这比只检查异常类型更能说明事务的作用。

## 7. 用值描述 MCP 工具，再接上执行函数

阅读 [KnowledgeTools.scala](../src/main/scala/com/tuniren/tuai/mcp/KnowledgeTools.scala)。

每个 `KnowledgeTool` 包含 JSON 描述与 `Json => IO[Json]` 执行函数。工具名称、输入 schema、读写提示和函数声明放在一起；`descriptions` 通过 map 得到，调用注册表由同一份数据生成。这样工具发现和实际执行来自同一个描述。

[McpRoutes.scala](../src/main/scala/com/tuniren/tuai/mcp/McpRoutes.scala) 只处理 JSON-RPC 报文、协议分派和错误封装。知识库的入库、检索规则仍在 KnowledgeBase，传输协议不会混进业务计算。

练习：沿着一次 `kb_upsert_document` 调用，从工具描述、参数解码、纯校验、IO 组合一直读到 ConnectionIO 的事务。再把同一个 KnowledgeBase 接到一个测试程序中，观察业务逻辑无需依赖 HTTP 请求。

## 先不急着引入的东西

项目暂不使用效果多态 `F[_]`、Tagless Final、`EitherT` 或自定义复杂类型类。这些工具可以解决实际问题，但不应成为阅读一个 知识库用例的前置门槛。先把纯函数、数据建模、IO 组合、Resource 和事务边界练熟。
