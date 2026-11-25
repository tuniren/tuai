# 本地 Codex 接入与自动同步

本机已将 Codex 接到 `http://192.168.6.162:30881/mcp`。全局配置在 `~/.codex/config.toml` 的 `[mcp_servers.tuai]`，调用超时 300 秒；原来的模型、MCP 和通知配置保留。

2026-11-24 首次迁移已完成：10 条主题记忆写入 `user-liyd`，22 份文档写入 `codex-liyd`（记忆摘要、20 份会话总结、1 份同步授权说明），全部逐条读回全文核对一致，Memory 与知识库语义查询均返回结果。Windows 计划任务已经自动执行下一轮检查，32 项未变化，未重复上传，LastTaskResult=0。

连接后，Codex 可直接使用 `memory_*` 和 `kb_*`。已打开的客户端若未发现工具，需要重新连接 MCP 或启动新会话；全局使用约定在 `~/.codex/AGENTS.md`。

## 数据范围

用户明确选择的是 Codex 已整理的记忆摘要和会话总结，不是原始聊天：

| 本地文件 | TuAI 目标 |
| --- | --- |
| `memories_v2/memory_summary.md` | 完整文档写入 `codex-liyd`，按主题拆分后写入 `user-liyd` Memory |
| `memories_v2/rollout_summaries/*.md` | 知识归档 `codex-liyd`，供按来源和行号检索 |
| `memories_v2/extensions/ad_hoc/notes/*.md` | 知识归档 `codex-liyd`，按主题写入 `user-liyd` Memory |

仅允许上述 Markdown 文件，不遍历 `.codex` 其他内容，不读取上传 `auth.json`、`config.toml`、原始 sessions、SQLite 数据库、插件、Git 历史或缓存。程序会脱敏常见 token、私钥和密码赋值；脱敏是补充保护，不能替代写记忆时避免保存凭据。

Memory 使用稳定的 `local-codex/<本机标识>/<主题标识>/<分片>` key，超过长度上限时按 UTF-16 拆分。知识 source 为 `codex-local/<本机标识>/memories_v2/<相对路径>`，方便追溯。标题和正文按本地已整理资料导入，历史事实仍需核对当前状态。

这些分组是逻辑分类，权限沿用现有服务：当前局域网知识界面可以看到全部 namespace，不具备独立的个人数据权限隔离。

## Windows 后台同步

安装入口：

```powershell
python scripts/install_codex_sync.py
```

安装器为当前 Windows 用户注册 `TuAI-Codex-Memory-Sync` 计划任务，每分钟运行一次，并在登录时触发。任务使用 `pythonw.exe`，不弹出控制台；使用当前用户的交互会话，不保存 Windows 密码。注销或关机期间不执行，登录后补跑。若当前系统不允许注册任务，安装器使用登录启动目录中的隐藏后台 worker，仍每分钟检查。

部署文件和状态位于 `%LOCALAPPDATA%/TuAI/CodexSync/`：

- `settings.json`：白名单目录、MCP 地址、分组和本机标识，不保存凭据。
- `sync_codex_memory.py`：安装后的独立同步器副本，不依赖源码目录。
- `state.json`：已经成功同步的 hash、远端 ID/revision，仅增量上传。
- `status.json`：最近检查时间、导入/跳过/冲突/错误统计。
- `sync.log`：轮转日志，不记录正文、密码或原始服务端错误。
- `installation.json`、`backups/`：安装方式与修改前配置备份；备份可能包含原有私有配置，不应提交或上传。

同一时间只执行一个同步实例。网络不可用时保留本地数据，下一次运行重试；服务端已经写入但响应丢失时，Memory 会先读回核对，不盲目重试更新。第一次导入需要生成向量，可能持续数分钟；之后无变化的检查不调用服务。

手动运行或检查：

```powershell
python scripts/sync_codex_memory.py --dry-run
python scripts/sync_codex_memory.py
Get-ScheduledTask -TaskName TuAI-Codex-Memory-Sync
Get-ScheduledTaskInfo -TaskName TuAI-Codex-Memory-Sync
Get-Content "$env:LOCALAPPDATA/TuAI/CodexSync/status.json"
```

`--force` 会重新读回并核对远端 Memory；即使 force 也不会跳过版本冲突保护。修改同步器代码后重新运行安装器更新本机副本。

## 写入与冲突规则

全局 AGENTS 已记录用户授权：后续确定的长期偏好、事实、项目决策可直接写入 TuAI。新产生的直接 Memory 推荐使用 `entries/...` key，避免与文件同步器维护的 `local-codex/` 范围混合。

本地文件更新会替换对应镜像记录；Memory 更新使用上次成功同步的 revision。若远端被其他会话修改或删除，同步器保留远端并记录 conflict，不强制覆盖。故意远端编辑文件镜像后，应先核对本地与远端再处理冲突。

一次成功读取的本地摘要中被合并或移除的旧主题，仅在远端 revision/source 仍属于同步器时删除对应旧 Memory，避免残留过时主题。整个本地文件被清理时不会触发远端删除；知识文档也不会因本地清理自动删除，归档保留。

生成的 `memory_summary.md` 和会话总结仍由 Codex 自身维护，安装器不直接修改它们。用户明确请求记忆更改时，仅按现有规范在 `extensions/ad_hoc/notes/` 追加说明，由 Codex 后续整合。

停用后台任务：

```powershell
Disable-ScheduledTask -TaskName TuAI-Codex-Memory-Sync
```

如需撤销接入，先停用任务，再移除全局 AGENTS 中的 `BEGIN TUAI MEMORY` 块及 config 的 `[mcp_servers.tuai]` 段；配置备份位于上述 backups 目录。此操作不会自动删除 TuAI 已归档内容。
