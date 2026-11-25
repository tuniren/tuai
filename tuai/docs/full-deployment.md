# 一键部署前后端

后端项目和前端项目默认并列放置：

```text
tuai/
  AGENTS.md
  tuai/          # Scala 后端
  tuai-ui/       # React 前端
```

在后端目录执行一条命令：

```powershell
python scripts/deploy_all.py
```

也可在前端目录执行：

```powershell
python ../tuai/scripts/deploy_all.py
```

项目约定：完成代码修改和验证后直接执行此脚本发布，不再额外询问部署确认；用户当次明确要求不部署时除外。该约定记录在两个项目上级的 `AGENTS.md`。

## 默认流程

1. 检查目标集群当前前后端镜像，记录发布前状态。
2. 后端 `mvn clean verify`，构建可执行 JAR、远端 Docker 镜像，推送内网仓库并预加载到 RKE2。
3. 前端 `npm ci`、单元测试、TypeScript 与 Vite 构建，构建 Nginx 镜像并推送、预加载。
4. 两个构建都成功后，使用本次固定的镜像记录依次发布后端和前端，等待滚动更新。
5. 校验集群内两个镜像 digest 都与本次发布一致。
6. 验证数据库健康、首页与 `/search` 的 HTML/JS 与本地构建一致、MCP 十个工具、namespace 选项接口，以及指定分组和全部分组下的真实文档关键词/语义检索、记忆关键词检索及结果所属分组。
7. 对实际线上地址执行完整 Playwright 浏览器测试，包括原阅读页和新搜索页。

部署探针只读，不为验证写入生产文档或记忆。搜索浏览器用例使用拦截的固定测试数据，真实语义搜索由 HTTP 部署探针单独检查。

页面：<http://192.168.6.162:30882/search>；阅读页：<http://192.168.6.162:30882>；MCP：<http://192.168.6.162:30881/mcp>。

2026-11-24 已首次通过该脚本完成前后端部署。Pod 两个容器就绪、无重启；健康检查 UP，MCP 暴露十个工具，真实语义/关键词搜索与记忆查询通过，线上 12 项浏览器测试全部通过。另从实际页面完成关键词搜索、切换语义搜索并打开文档全文，未发生页面错误。发布记录位于 `.tools/releases/20261124-003511-4552/deployment.json`。

## 环境和配置

需要 Python 3.10+、OpenSSH、JDK 25、Maven 3.9+、Node.js 22.12+ 及 npm。Windows 自动复用 IDEA 自带 Java/Maven 和前端 `.tools/node-path.txt` 中的 Node。当前工作站默认选择已安装的 Chrome 运行浏览器测试；也可通过 `PLAYWRIGHT_CHANNEL` 指定浏览器。

默认 SSH 目标为 `root@192.168.6.162`，需要已配置无交互 SSH。目标机沿用现有 Docker、RKE2、内网镜像仓库和已缓存的 Java/Nginx 基础镜像，本机无需 Docker。

后端沿用 `deploy/settings.local.json` 和现有配置规则，不在一键脚本中重复保存数据库密码或模型密钥。敏感配置由既有后端发布助手通过 SSH 标准输入传输。前端继续在同一 Pod 中通过 Nginx 代理后端；两个独立字段管理器分别管理容器。

## 常用参数

```powershell
# 仅查看流程，不构建、不访问集群、不部署
python scripts/deploy_all.py --dry-run

# node_modules 已按当前 lockfile 安装时，跳过 npm ci
python scripts/deploy_all.py --skip-install

# 非默认前端位置或私有发布配置
python scripts/deploy_all.py --ui-dir C:/source/tuniren/tuai/tuai-ui --settings-file deploy/settings.local.json

# 指定线上只读验证的 namespace
python scripts/deploy_all.py --namespace tuai
```

其他参数：`--host`、`--public-host`、`--backend-port`（默认 30881）、`--ui-port`（默认 30882）。两个 NodePort 必须不同，且在 30000..32767 内。`--skip-browser-tests` 仅跳过最后的浏览器用例，HTTP、MCP 和真实搜索验证仍执行。

Windows 上若本项目 Vite 开发服务正在使用原生模块，应先停止开发服务再运行默认 `npm ci`；依赖未变时也可使用 `--skip-install`。

## 日志和回滚

每次执行生成后端 `.tools/releases/<时间戳>-<进程号>/`，包含逐步骤日志、固定的前后端镜像记录和 `deployment.json`。记录只包含镜像、发布路径、验证结果及状态，不包含私有配置或凭据。全部验证通过后更新 `.tools/deployment-latest.json`；失败时脚本返回非零，保留已完成阶段的信息，不把部分发布报告为成功。

后端 Maven 完整日志仍位于 `.tools/docker-build-maven.log`。底层发布助手会在远端各自的 `before-deploy-*` 目录备份资源。回滚镜像时使用部署记录中的 `beforeImages`，按 [后端发布文档](deployment.md) 和前端发布文档恢复对应容器。

Flyway 迁移由后端启动时执行。V2 是新增记忆表和索引的兼容迁移，镜像回滚不自动删除记忆表或回滚数据库。
