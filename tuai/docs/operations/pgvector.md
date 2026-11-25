# PostgreSQL pgvector 安装记录

2026-11-23，在 `192.168.6.162` 上完成。

## 当前状态

- Kubernetes namespace / StatefulSet / Pod：`postgresql` / `postgresql` / `postgresql-0`。
- NodePort：`192.168.6.162:30433` → PostgreSQL `5432`。
- PostgreSQL：`18.6 (Debian 18.6-1.pgdg13+2)`，本次未升级数据库版本。
- 扩展：`pgvector 0.8.6`，已在 `tuai` 库的 `public` schema 启用。
- PVC：`postgresql-data`；PV：`dev-env-postgresql-data`；挂载目录 `/var/lib/postgresql`；PGDATA `/var/lib/postgresql/18/docker`。
- StatefulSet 继续固定在节点 `k8s-6-162`；PVC 身份、挂载、环境参数和 Service 均已比对保持原样。

镜像基于原正在运行的 PostgreSQL 镜像，安装匹配的 PGDG 软件包 `postgresql-18-pgvector=0.8.6-1.pgdg13+2`。包 SHA-256：

```text
fbe50445236a15de9d1aef6d2988510b62e29b16fcfa5b5eca31272ce09b3320
```

已部署的不可变镜像引用：

```text
192.168.6.162:5000/dev-env/postgresql-pgvector@sha256:b570e45939de4d0c9200bc1cd6c41fe3328d8a562e07a4083411839f805c4bd9
```

镜像已推送现有内网仓库 `:5000` 并预加载到节点的 RKE2 containerd，StatefulSet 使用 `IfNotPresent`。插件在镜像内，Pod 重建后仍然存在。若将来节点镜像缓存被清空，需在该节点预拉取这个使用 HTTP 的仓库镜像，或为运行时配置该仓库。原 `:8087` 仓库推送返回 500，本次未依赖它完成部署，也未修改全局仓库配置。

## 备份与复现资料

服务器 `192.168.6.162` 的 root 私有目录：

```text
/root/tuai-pgvector-20261123-181401
```

其中包含全库逻辑备份 `all-databases.sql`、更新前后的 StatefulSet、Service/PVC 快照、Dockerfile、校验后的软件包、基础镜像导出包和最终镜像导出包。备份含数据库数据及角色信息，仅保存在该服务器，未加入代码仓库。

节点缓存恢复命令（在 `192.168.6.162` 执行）：

```sh
/hci/platform/rke2/data/rke2/bin/ctr \
  --address /run/k3s/containerd/containerd.sock --namespace k8s.io \
  images pull --plain-http --platform linux/amd64 \
  192.168.6.162:5000/dev-env/postgresql-pgvector@sha256:b570e45939de4d0c9200bc1cd6c41fe3328d8a562e07a4083411839f805c4bd9
```

重建基础镜像时，以保存的原始镜像为准；不要用浮动的 `postgres:18` 替代已验证的 18.6。不要直接切回不含 pgvector 的旧镜像运行已使用 vector 类型的数据库。

## 已执行验证

1. 隔离容器内创建 vector 扩展、向量表和 HNSW 索引，余弦距离结果正确。
2. StatefulSet 更新完成，`postgresql-0` 恢复 `1/1 Ready`。
3. 在 `tuai` 库执行 `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public`，查询扩展版本为 0.8.6。
4. 从本机连接 NodePort，执行项目原 Flyway 迁移成功；`tuai.rag_document`、`tuai.rag_chunk` 已创建，版本 1 标记成功。
5. 启动实际可执行 JAR，数据库健康检查返回 `UP`，MCP 初始化成功；验证进程随后已停止。

本次未修改项目的迁移 SQL 或配置代码。
