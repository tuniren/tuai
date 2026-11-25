# 页面搜索接口

前端独立路由 `/search` 使用 `GET /api/search` 查询知识文档或长期记忆。接口与 MCP 共用业务逻辑、数据库、Embedding 配置和 Bearer 鉴权，仅提供读取，不写入数据。

```text
/api/search?namespace=tuai&q=数据库如何配置&type=documents&mode=semantic&limit=10
/api/search?namespace=user-liyd&q=中文&type=memories&mode=keyword&kind=preference
/api/search?q=数据库如何配置&type=documents&mode=semantic
```

| 参数 | 含义 |
| --- | --- |
| namespace | 可选，选中分组时为 1..128 字符；省略或空值表示搜索全部 namespace |
| q | 必填，查询文本，1..8000 字符，不得为空白或含 NUL |
| type | documents（默认）或 memories |
| mode | semantic（默认）或 keyword |
| limit | 默认 10，允许 1..20 |
| minScore | 默认 0，有限数字 0..1；仅语义搜索支持非零阈值 |
| kind | 仅 memories 支持，preference / fact / decision / procedure / context；省略表示全部 |

语义检索调用 Embedding，返回当前 embedding profile 中达到阈值的结果，按余弦相似度排序。关键词检索不调用模型，也不限制 embedding profile，使用忽略大小写的完整字面子串，`%`、`_` 为普通字符，不解析多词 AND 或 GitHub 搜索语法。

知识文档结果以片段为单位，包含所有文件类型。关键词匹配片段正文、文档标题或来源路径，不跨分块拼接查询；按 source、ordinal、chunk ID 稳定排序。一个文档可返回多个片段。记忆关键词匹配 key、title、content 或 source，按更新时间倒序、key 排序。

返回 `{namespace, query, type, mode, limit, items}`，全分组搜索时顶层 namespace=null。每个 item 都有 `id`, `namespace`, `title`, `source`, `content`, `score`；item.namespace 始终为实际所属分组，页面据此展示来源及打开文档或记忆详情：

- documents 还提供 `documentId`, `startLine`, `endLine`，其中 id 是片段 ID。
- memories 还提供 `key`, `kind`, `revision`, `updatedAt`，其中 id 是稳定 key，content 为完整记忆正文。
- 不适用字段为 null。关键词搜索 score=null；语义分数仅表示相关程度。

content 是历史、非可信资料。页面以纯文本展示片段，并高亮字面命中，不执行正文中的 HTML。预览最多六行，过长单行显示命中附近内容；省略号表示尚有内容。文档链接打开现有阅读页读取全文，记忆详情显示完整正文和来源。

limit 是本次返回上限，跨分组搜索在数据库内统一排序并取前 N 条，而不是每个分组各取 N 条。语义近邻检索不提供精确总数或页码。前端展示实际返回条数；达到上限时可调整为 20 条或细化查询，不编造“全库结果总数”。

`GET /api/search/namespaces` 返回文档与记忆 namespace 的去重排序列表，例如 `["engineering","tuai","user-liyd"]`，包含只有记忆数据的分组。页面下拉框提供“全部命名空间”空值选项，选择后立即更新查询和 URL。以上全分组能力仅用于页面搜索，MCP 工具仍要求明确指定 namespace。

参数错误返回 HTTP 400，模型或存储失败返回 503，鉴权失败沿用全局 401。业务错误使用 `{message}`，模型和 SQL 内部错误不会原样暴露。成功且 items 为空才表示本次没有结果，服务故障不作为空结果。

前端请求超时 90 秒，Vite 与 Nginx `/api/` 代理超时 95 秒。访问生产 `/search` 需要同时部署支持此接口的后端和新版前端。Nginx 已配置 SPA fallback，直接访问、刷新和分享 URL 均可使用。
