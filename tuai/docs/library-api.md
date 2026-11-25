# 知识库阅读接口

独立搜索页的语义/关键词搜索接口见 [页面搜索接口](search-api.md)，使用 `GET /api/search`，支持知识片段与长期记忆。下列接口继续服务于原有阅读页。

为阅读界面提供 namespace 统计、Markdown 文档分页和关键词检索。当前部署公开访问，无登录、Token 或 namespace 白名单。接口与 MCP 共用 8080 端口，由前端 Nginx 通过 `/api/` 转发。

## Namespace 统计

`GET /api/namespaces`

```json
[
  {"namespace":"tuai","documents":6,"markdownDocuments":6}
]
```

按 namespace 排序，统计所有已入库文档的分组，包括只有非 Markdown 文档的分组。`documents` 是总文档数，`markdownDocuments` 是来源后缀为 `.md` / `.markdown` 的文档数（忽略大小写）。分组不独立建表，没有文档的分组不会出现。

## 文档分页与检索

`GET /api/documents?namespace=tuai&query=部署&limit=20&offset=0`

| 参数 | 含义 |
| --- | --- |
| namespace | 必填，1..128 个非空白字符 |
| query | 可选，最多 1000 字符；按标题、来源路径和正文进行忽略大小写的字面子串匹配 |
| limit | 默认 20，允许 1..100 |
| offset | 默认 0，非负整数 |

返回 `{items, total, limit, offset}`，items 结构与 MCP 文档列表一致，仅包含 Markdown 文档。README 优先，其余按 source 和 ID 稳定排序；计数与分页在同一数据库快照读取。关键词采用参数化 SQL，`%` 和 `_` 是普通字符，不是通配符。该检索不调用 Embedding。

## 文档定位

- `GET /api/documents/{documentId}?namespace=tuai`：取得指定文档的摘要，支持打开不在当前页的文档链接。
- `GET /api/documents/resolve?namespace=tuai&source=tuai/docs/deployment.md`：按完整来源路径定位文档，支持跨页相对链接。

正文通过原有 MCP `kb_read_document` 分段完整读取，避免重复实现行数与字符截断规则。HTTP 参数错误返回 400，文档不存在返回 404，存储失败返回 503，JSON 的 `message` 字段提供错误说明。
