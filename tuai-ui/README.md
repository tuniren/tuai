# TuAI UI

TuAI 知识库的 GitHub 风格阅读界面。使用 React、TypeScript、Vite、Ant Design，通过 HTTP 查询接口浏览分组和文档，通过 MCP 按行读取 Markdown。

已部署：**<http://192.168.6.162:30882>**。Nginx 与 TuAI 后端位于同一个 Pod，对外 NodePort 不限制来源 IP。构建、部署及回滚详见 [发布文档](docs/deployment.md)。

## 启动

需要 Node.js 22.12+（推荐 24 LTS）和 npm：

```sh
npm install
npm run dev
```

访问 <http://127.0.0.1:5173>。当前 Windows 工作站已在 `.tools` 准备项目专用 Node；系统 Node 尚未激活时使用：

```powershell
.\scripts\npm.ps1 run dev
```

顶栏下拉框自动加载所有已入库文档的 namespace，显示各分组的文档数，无需手动输入名称。默认进入 `tuai`；如果该分组不存在，则选择第一个分组。页面不设置登录、Token 或 namespace 白名单。仅包含非 Markdown 文档的分组同样列出，阅读列表显示空状态。

## 当前功能

- 独立 `/search` 搜索页：参考 GitHub 搜索布局，提供文档/记忆类型、namespace、语义/关键词方式、相似度与返回数量筛选；阅读页工具栏的“检索”可进入。
- 搜索结果展示片段预览、字面高亮、来源路径、行号或记忆版本。文档可跳转阅读，记忆可打开详情。URL 保存筛选条件，支持分享、刷新、前进后退。
- 搜索区分加载、无结果与失败，模型故障时可手动切换关键词。手机上使用筛选抽屉；Ctrl/Cmd+K 聚焦查询栏。空查询不发起检索。

- 左侧仅加载当前页的 `.md` / `.markdown` 文档；每页 10 / 20 / 50 条，README 优先，其余按来源路径排序。
- 服务端检索当前分组的标题、路径和全文，忽略大小写，输入后 300ms 自动查询并回到第一页；不调用 Embedding。
- Markdown 阅读与源码切换，代码高亮、GFM 表格、任务列表、章节目录与已入库文档的相对链接跳转。
- 复制全文或代码、下载 Markdown；URL 保存 namespace、文档、检索词和页码，支持刷新与浏览器前进后退。
- 手机抽屉导航、加载与错误提示、空分组提示、手动刷新。

页面通过 `/api/namespaces`、`/api/documents` 加载分组统计和文档页，正文仍用 `kb_read_document` 完整读取，不进行入库、修改或删除。

搜索页通过 `GET /api/search` 查询，示例：`/search?namespace=tuai&q=数据库配置&type=documents&mode=semantic`。namespace 下拉框从 `/api/search/namespaces` 加载文档与记忆的全部分组；默认不选，即搜索所有 namespace，选中后立即筛选，选回“全部命名空间”取消限制。结果显示各自的 namespace，并据此打开正确的文档或记忆。实际展示本次返回条数，不将向量检索的 top K 当成全库总数。需要同步部署新版后端。

## MCP 代理

默认访问链路：

```text
浏览器 → Vite /api/*、/mcp → http://192.168.6.162:30881
```

Vite 开发与预览模式均配置同源代理，并把 Origin/Host 设置为固定上游地址，以符合服务端的 Origin 校验。无需修改 MCP CORS 设置。默认只监听本机。

如需切换服务，复制 `.env.example` 为 `.env.local`：

```dotenv
TUAI_MCP_TARGET=http://192.168.6.162:30881
```

配置变化后重启 Vite。页面、代理及当前 MCP 服务均不要求登录或凭据。

## 完整文档读取

后端每次最多返回 200 行或 20000 个 UTF-16 字符。客户端按行分段加载；遇到 `truncated=true`，缩小区间重新请求，而不是直接跳到返回的 endLine。只有完整加载成功才展示全文。单行本身超限时显示明确错误，避免把缺失内容当作完整文档。

Markdown 不执行原始 HTML。外部链接在新窗口打开；相对文档链接仅在当前知识库收录的文件中跳转，未收录的文件会提示。相对图片尚未提供资源解析接口。

## 检查与构建

```sh
npm test
npm run build
npx playwright install chromium
npm run test:e2e
npm run preview
```

单元测试覆盖多页、字符截断、单行超限、文档变化与取消读取。六项浏览器测试覆盖真实服务阅读、手机、API 错误重试，以及模拟多分组/25 份文档验证下拉选择、按页请求和跨页检索。测试不会写入知识库。

搜索包含 3 项 URL/片段预览单元测试与 7 项浏览器测试，覆盖入口、namespace 选择/取消、跨分组结果链接和记忆详情、刷新/历史、失败恢复、空结果、手机宽度和 HTML 文本安全。搜索浏览器测试拦截接口并使用固定测试数据；原阅读测试继续只读访问实际服务。当前共 8 项单元测试、13 项浏览器测试。若未安装 Playwright Chromium，可通过 `$env:PLAYWRIGHT_CHANNEL='chrome'` 使用本机 Chrome，再运行 `npm run test:e2e`。

构建输出位于 `dist/`。`vite preview` 仅用于本地预览；正式静态站点需配置 `/api/` 和 `/mcp` 同源反向代理，项目 Nginx 配置已经包含两者。当前按公开知识库方式部署，所有 namespace 均可直接访问，不设置访问控制。

## Nginx 构建与 K8s 发布

日常修改后直接使用统一前后端部署脚本：

```powershell
python ../tuai/scripts/deploy_all.py
```

脚本先构建、测试两端，再发布并验证实际线上服务；包含搜索接口、MCP 十个工具、真实语义搜索和全部浏览器用例。详细说明见后端 `docs/full-deployment.md`。以下命令仅用于单独更新前端。

```powershell
.\scripts\build-image.ps1
.\scripts\deploy-k8s.ps1
```

本机无需 Docker；镜像在 6.162 构建，按 digest 发布到 TuAI 的同一个 Pod，Nginx 监听 8081，对外端口 30882。原后端 MCP 端口 30881 保留。前端和后端采用不同的 Kubernetes 字段管理器，发布脚本只管理前端容器及对应的 Service/NetworkPolicy。

## 结构

```text
src/
  Router.tsx                    # / 阅读页和 /search 路由切换
  App.tsx                       # 文档列表、namespace、阅读交互
  pages/SearchPage.tsx          # 独立搜索页与记忆详情
  pages/search.css              # GitHub 风格搜索布局和手机适配
  components/MarkdownReader.tsx # Markdown 渲染与代码复制
  lib/knowledge.ts              # MCP 协议与完整文档加载
  lib/search.ts                 # 搜索 HTTP 客户端、URL 与证据预览
  lib/markdown.ts               # 标题目录与相对链接
  styles.css                    # 阅读布局与响应式样式
e2e/reader.spec.ts              # 真实服务浏览器验证
vite.config.ts                 # 服务端代理
```
