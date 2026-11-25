# 本地 Embedding 服务

TuAI 的向量生成使用 6.162 上的 **Ollama + Qwen3 Embedding 4B**，通过 OpenAI 兼容的 `/v1/embeddings` 接口提供服务。模型支持中英文，使用其可变维度能力输出 1536 维向量，与当前 PostgreSQL 表结构一致。

本地及 6.177 的 Codex 都使用 `http://192.168.6.162:3000/v1` 网关。已验证该网关只提供聊天模型，没有可用的 Embedding 通道，因此不把聊天模型或 Codex 密钥当作向量服务。

## 访问与数据位置

| 项目 | 配置 |
| --- | --- |
| Codex 使用的 MCP 地址 | `http://192.168.6.162:30881/mcp` |
| 集群内 Embedding 地址 | `http://tuai-embeddings.tuai.svc.cluster.local:11434/v1` |
| 模型 | `qwen3-embedding:4b` |
| 输出维度 | `1536` |
| 模型 API key | 不需要 |
| K8s Deployment / Service | `tuai/tuai-embeddings` |
| 节点上的持久化模型目录 | `/var/lib/tuai/ollama/models` |
| CPU / 内存限制 | 12 核 / 8 GiB |

模型离线存储在节点上，Pod 重启不重新下载。服务采用 ClusterIP，网络策略仅允许同 namespace 的 TuAI Pod 访问，不额外暴露 NodePort。模型目录允许服务维护本地 manifest 格式，容器根文件系统只读，不使用 GPU。

## 首次准备或重建

下载需要 Python 3.14+（标准库 Zstandard 解压）、约 4 GB 网络流量及额外解压空间；部署需要现有 SSH 权限和目标节点已缓存的 `eclipse-temurin:25-jre-jammy` 基础镜像。

```powershell
python scripts/fetch_embeddings.py
python scripts/setup_embeddings.py
```

下载脚本从 Ollama 官方来源获取 Linux/amd64 运行时与模型，校验模型 manifest 和 blob，缓存到 `.tools/embedding-assets`。部署脚本提取 CPU 运行库、构建镜像、推送现有内网仓库并按 digest 加载到 RKE2；先测试真实向量输出维度，再更新运行中 TuAI 的模型配置。原 ConfigMap 与 Deployment 备份在远端 `/root/tuai/embeddings/<时间>/before-config`。

安装成功后将 Embedding 覆盖项保存到 Git 忽略的 `deploy/settings.local.json`。普通发布脚本默认读取此文件，也可用 `--settings-file` 显式指定其他配置，避免下次发布恢复到尚不可用的外部模型。

```powershell
.\scripts\deploy-k8s.ps1
```

应用和模型服务分别发布；只修改 Scala 应用时，不必重新下载或部署模型。

## 运维与验证

```sh
kubectl -n tuai get deployment,pods,svc
kubectl -n tuai logs deployment/tuai-embeddings --tail=50
kubectl -n tuai logs deployment/tuai --tail=50
```

`/health` 和模型服务探针只用于基础可用性检查。完整验证应通过 MCP 入库一份明确授权的文档，再调用语义检索和原文读取。模型首次加载会比后续查询慢；模型保持驻留以减少加载开销。

入库和检索仍使用 [Codex 指南](codex-mcp-guide.md) 中的五个工具。数据库与原有 MCP 地址不变；更换其他 Embedding 模型或地址后，要重新入库原文才能参与新模型配置下的检索。

2026-11-23 从 6.177 验证：中文语义查询约 0.38 秒，临时文档首次写入约 0.84 秒，重复输入跳过；读取、列表与删除均通过，测试文档已清理。README 与 Codex 指南等六份项目文档已导入 `tuai` 分组。CPU 模型索引长文档耗时约十秒到一分钟，6.177 的 Codex 已设置 `tool_timeout_sec=300`。
