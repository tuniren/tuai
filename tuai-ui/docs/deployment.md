# Nginx 构建与同 Pod 发布

前后端统一发布入口为 `python ../tuai/scripts/deploy_all.py`（从本前端目录运行），后续改完默认直接执行此脚本。它完成两端构建、单元测试、镜像推送、滚动更新、真实搜索与浏览器验证。详细参数和发布记录见后端 `docs/full-deployment.md`；下文是单独前端发布的底层流程。

2026-11-24 已通过统一脚本发布新版搜索页：<http://192.168.6.162:30882/search>。后端同步升级，MCP 十个工具、真实语义/关键词查询和 12 项线上浏览器测试通过。最新镜像与验证结果以 后端 `.tools/deployment-latest.json` 为准。

2026-11-23 已发布到 **<http://192.168.6.162:30882>**。知识库默认分组是 `tuai`，已从 Windows 浏览器和 6.177 验证网页、Markdown 读取及 MCP 代理。现有 MCP 地址 `http://192.168.6.162:30881/mcp` 继续可用。

## 两步发布

在项目根目录执行：

```powershell
.\scripts\build-image.ps1
.\scripts\deploy-k8s.ps1
```

或在具备 Node.js 22.12+、npm、Python 3.10+、OpenSSH 的机器上使用：

```sh
python scripts/build_image.py
python scripts/deploy_k8s.py
```

当前 Windows 工作站自动使用 `.tools` 中的项目专用 Node，不改系统 Node 设置。首次完整构建执行 `npm ci`、五项单元测试、TypeScript 检查和 Vite 生产构建；已安装依赖时可用 `--skip-install` 跳过安装。

Windows 上执行 `npm ci` 前先停止本项目的 Vite 开发服务，避免运行中的原生模块被锁定。也可以在依赖未变化时使用下方命令，保留开发服务并跳过重装。

```powershell
.\scripts\build-image.ps1 --skip-install
```

脚本把 `dist`、Dockerfile、Nginx 配置及发布助手上传到服务器，不上传源码、node_modules、`.env.local` 或其他本地配置。服务器使用已缓存的官方 `nginx:latest` 镜像，构建时解析并固定其 digest，执行 `nginx -t`，将应用镜像推送到现有 `192.168.6.162:5000` 仓库，并预加载到 RKE2。

镜像记录保存在 `.tools/image.json`；远端发布文件位于 `/root/tuai/ui/releases/<时间戳>`。本次镜像：

```text
192.168.6.162:5000/tuai/ui@sha256:76685c893456d0b398e6646fffcba91a87ba94bc7ee5facf05c69138606f237f
```

## 同 Pod 结构

```text
namespace: tuai
Deployment: tuai
└── 同一个 Pod
    ├── tuai       :8080  原有 Scala MCP 服务
    └── tuai-ui    :8081  Nginx 静态站点
                          /mcp、/api/ → http://127.0.0.1:8080

Service tuai      NodePort :30881 → :8080
Service tuai-ui   NodePort :30882 → :8081
```

部署脚本通过独立的 server-side apply 字段管理器 `tuai-ui-release` 添加 `tuai-ui` 容器及其临时卷。它不替换 Deployment，且部署后会校验原有 `tuai` 容器配置未变化。数据库、Embedding 和原有 MCP 网络配置沿用运行中的设置。

Nginx 使用 UID/GID 10001，根文件系统只读，`/tmp` 为独立 emptyDir，配置 startup/readiness/liveness 探针。页面静态文件带缓存策略，HTML 不缓存；MCP 同源代理使用固定的 Pod 内地址和匹配的 Origin/Host。

## 对外访问

`tuai-ui` Service 为 NodePort，`externalTrafficPolicy=Cluster`，无来源 IP 白名单。`tuai-ui-allow-all-clients` NetworkPolicy 对 8081 不设置 from 限制，允许所有来源。若节点运行 firewalld，脚本仅添加该 TCP 端口，不关闭防火墙。

```text
页面：http://192.168.6.162:30882
探针：http://192.168.6.162:30882/healthz
同源 MCP：http://192.168.6.162:30882/mcp
```

“允许所有 IP”指服务和节点规则不限来源；访问方仍需有到 `192.168.6.162` 的网络路由。页面和当前 MCP 均公开访问，不设登录、Token 验证或 namespace 白名单。顶栏下拉框从 `/api/namespaces` 加载全部分组和文档数，无需手写名称。左侧调用 `/api/documents` 分页和检索标题、路径、正文；仅含非 Markdown 文档的分组也列出，阅读列表显示空状态。

需要其他 NodePort 时：

```powershell
.\scripts\deploy-k8s.ps1 --node-port 30883
```

部署前检查端口占用。现有前端 Service 的 NodePort 可随重发调整；旧的 firewalld 放行规则不自动删除。

## 验证

```sh
kubectl -n tuai get deployment,pods,svc,networkpolicy
kubectl -n tuai logs deployment/tuai -c tuai-ui --tail=50
kubectl -n tuai logs deployment/tuai -c tuai --tail=50
kubectl -n tuai rollout status deployment/tuai --timeout=50s
```

TuAI Pod 应显示 **2/2 Running**。本次验证包括五项读取单元测试、六项针对线上页面的浏览器测试（真实阅读、手机、错误重试、模拟 namespace 选择、模拟 25 份文档分页/跨页检索、紧凑布局）。从 6.177 验证 Nginx 后的真实 namespace 统计与分页检索，后端验证真实 PostgreSQL 的分页边界和文档定位。测试不写入线上知识库。

复现线上测试：

```powershell
$env:PLAYWRIGHT_BASE_URL = 'http://192.168.6.162:30882'
# 使用已安装的 Chrome；不设置时使用 Playwright 下载的 Chromium。
$env:PLAYWRIGHT_CHANNEL = 'chrome'
.\scripts\npm.ps1 run test:e2e
```

## 重发与回滚

部署可重复执行；无需重新创建数据库或再次导入文档。每次变更前，Deployment、已有前端 Service 和 NetworkPolicy 保存到远端发布目录的 `before-deploy-*` 中。

前端镜像回滚可在节点使用上一版 digest，仅修改前端容器：

```sh
kubectl -n tuai set image deployment/tuai tuai-ui=192.168.6.162:5000/tuai/ui@sha256:<上一版digest>
kubectl -n tuai rollout status deployment/tuai --timeout=50s
```

或在下一次构建前备份 `.tools/image.json`，然后以 `--receipt` 指定旧记录重新发布。由于前后端共享 Pod，任何一个容器的滚动更新都会重建整个 Pod，但本脚本保留后端配置，且使用现有 Deployment 的 `maxUnavailable=0` 滚动策略。
