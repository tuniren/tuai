# Docker 构建和发布到 6.162

日常前后端一起发布统一使用 `python scripts/deploy_all.py`，详见 [一键部署前后端](full-deployment.md)。下文保留单独后端发布、配置和回滚的底层说明。

2026-11-23 已实际部署，Namespace / Deployment / Service 均为 `tuai`，NodePort 为 `30881`。数据库继续使用 `192.168.6.162:30433/tuai`。本机 Windows 和 `192.168.6.177` 均已访问健康检查成功；外部 MCP 初始化、五个工具发现及数据库文档列表调用通过。

本次镜像：

```text
192.168.6.162:5000/tuai/knowledge-base:0.1.0-20261123-195300
192.168.6.162:5000/tuai/knowledge-base@sha256:46c5e9b4a9f6f5cdf7bc8c82d0b8b160e2818b717617b9bfce45d580b726f238
```

本次运行时来源为官方 Temurin Java 25 linux/amd64，manifest digest 为 `sha256:ac9a0b0a5287d5b5950cfb9d197a4c2789adf7622f7741605f70700f35dde3a1`。当前 MCP 无需鉴权。2026-11-23 已补齐集群内部的 Ollama + Qwen3 Embedding 4B 服务，输出 1536 维向量，不依赖外部模型密钥；从 6.177 实测入库、中文语义检索、原文读取和删除通过，见 [本地 Embedding 服务](local-embeddings.md)。

目标：`root@192.168.6.162`，RKE2 单节点 `k8s-6-162`。本机需要 Python 3.10+、OpenSSH、JDK 25 和 Maven；不需要本机 Docker。目标服务器需要 Docker、kubectl、Python 3.7+ 和现有 RKE2 containerd。

## 两条命令

在项目根目录执行：

```powershell
.\scripts\build-image.ps1
.\scripts\deploy-k8s.ps1
```

Linux / 直接使用 Python：

```sh
python scripts/build_image.py
python scripts/deploy_k8s.py
```

构建脚本执行本机 `mvn clean verify`，将可执行 JAR 和 Dockerfile 传到服务器，构建 Linux/amd64 Java 25 镜像，推送现有 `192.168.6.162:5000` 仓库，再以不可变 digest 预加载到 RKE2。构建结果记录在本机 `.tools/image.json`，发布脚本读取它，不需要手工复制镜像名。

服务器构建上下文只包含 JAR、Dockerfile 和相关脚本，不上传源码目录或开发配置。Maven 日志位于 `.tools/docker-build-maven.log`。服务器每次发布文件保存在 `/root/tuai/releases/<时间戳>`。

官方运行时采用 `eclipse-temurin:25-jre-jammy`。远端尚无此镜像时，脚本通过本机网络从 Docker 官方镜像仓库下载 linux/amd64 镜像，校验 manifest、config、压缩层和未压缩层的 SHA-256，然后上传 Docker-load 包。缓存位于 `.tools/temurin-25-jre-amd64.tar`，避免服务器直连 Docker Hub 失败。

## 发布配置

默认读取项目 `AppConfig.scala` 的当前默认配置，并应用同名进程环境变量覆盖。若存在 `deploy/settings.local.json`，发布脚本默认读取其中的覆盖项；也可用 `--settings-file` 显式指定其他文件。因此数据库使用当前已经验证的 `192.168.6.162:30433/tuai` 及现有账号信息；本地 Embedding 安装脚本已保存模型覆盖项，后续普通发布会继续使用集群内模型。发布强制 `SERVER_ADDRESS=0.0.0.0`、`SERVER_PORT=8080`。

需要覆盖数据库或模型配置时：

```powershell
Copy-Item deploy/settings.example.json deploy/settings.local.json
# 编辑 deploy/settings.local.json，填写实际连接信息和 Embedding 密钥
.\scripts\deploy-k8s.ps1 --settings-file deploy/settings.local.json
```

`settings.local.json` 被 Git 忽略。字段与 AppConfig 环境变量一致，值均写成字符串；只需填写要覆盖的字段。密码、Embedding key 和 TuAI key 写入 Kubernetes Secret，其余参数写入 ConfigMap。敏感参数通过 SSH 标准输入传输，不放在命令行参数中；服务器的中转配置文件在发布结束后删除。不会更改 PostgreSQL 的账号、数据卷或网络配置。

若当前 Embedding key 为空，服务仍可启动、连接 MCP、列出/读取/删除已有文档；向量入库与检索需要配置可用的 Embedding 服务。`/health` 检查数据库，不验证模型接口。

## Kubernetes 资源和网络

[kubernetes.json](../deploy/kubernetes.json) 是标准 Kubernetes JSON 清单，可由 kubectl 读取。发布脚本填写实际镜像 digest 并创建：

- Namespace `tuai`。
- Deployment `tuai`，1 副本，滚动更新、启动/就绪/存活探针。
- ConfigMap `tuai-config` 和 Secret `tuai-credentials`。
- NodePort Service `tuai`，`30881 → 8080`。
- NetworkPolicy `tuai-allow-all-clients`，允许所有来源访问应用 TCP 8080。

访问地址：

```text
MCP:    http://192.168.6.162:30881/mcp
Health: http://192.168.6.162:30881/health
```

资料批量导入（当前集群内 Embedding 已配置）：

```powershell
python scripts/ingest.py C:\path\to\docs --namespace engineering --url http://192.168.6.162:30881/mcp
```

应用监听 `0.0.0.0`，NodePort 没有来源 IP 白名单，NetworkPolicy 的 ingress 未限制 from。若服务器启用 firewalld，脚本仅为该 NodePort 增加 TCP 放行，不关闭防火墙。客户端还需要具备到该内网地址的路由；是否能从公网访问取决于现有网络路由/NAT。

`TUAI_API_KEY` 沿用当前配置。配置非空 key 时，所有 IP 都可以建立网络连接，但 MCP 请求仍需 `Authorization: Bearer <key>`；`/health` 公开。空 key 表示 MCP 无需鉴权，客户端可调用包括修改/删除在内的五个工具。

容器以 UID 10001 运行，根文件系统只读，不挂载 Kubernetes API token；数据存储在现有 PostgreSQL 中，无需给应用 Pod 分配 PVC。Deployment 固定在 `k8s-6-162`，便于使用已预加载的镜像。镜像也已保存在内网仓库；节点缓存清空后重新运行发布脚本会再次预加载，无需更改运行时全局仓库配置。

## 参数和重发

```powershell
# 已手工验证过 JAR 时，跳过本机构建
.\scripts\build-image.ps1 --skip-build

# 自定义镜像 tag
.\scripts\build-image.ps1 --image 192.168.6.162:5000/tuai/knowledge-base:my-version

# 使用指定构建记录，或更换空闲 NodePort
.\scripts\deploy-k8s.ps1 --receipt .tools/image.json --node-port 30881
```

发布脚本在变更前检查 NodePort 占用，并将旧 Deployment、Service、ConfigMap、Secret 和网络策略保存在远端发布目录的 `before-deploy-*` 下。配置变化会更新 Pod 模板指纹，使相同镜像的配置重发也能生效。

## 查看与回滚

在 6.162 上：

```sh
kubectl -n tuai get deployment,pods,svc,networkpolicy
kubectl -n tuai logs deployment/tuai --tail=100
kubectl -n tuai rollout status deployment/tuai --timeout=50s
```

应用镜像回滚：

```sh
kubectl -n tuai rollout undo deployment/tuai
kubectl -n tuai rollout status deployment/tuai --timeout=50s
```

`rollout undo` 仅恢复 Deployment 模板，不恢复 ConfigMap/Secret 内容。若本次修改了配置，应使用之前保存的本机设置和镜像构建记录重新发布相同配置。数据库迁移不随 Deployment 回滚，已有 V1 迁移保持原样。

发布脚本等待就绪后从节点验证数据库健康检查和 MCP 初始化；若等待超时可先查看 Pod 和日志，修复后重新运行发布脚本，已有配置和资源会更新。
