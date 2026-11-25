#!/usr/bin/env python3
"""Connect local Codex to TuAI and install an incremental user-level memory sync task."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tomllib

from sync_codex_memory import atomic_json


TASK_NAME = "TuAI-Codex-Memory-Sync"


def merge_mcp(text, url):
    heading = re.search(r"(?m)^\[mcp_servers\.tuai\][ \t]*(?:#.*)?$", text)
    values = {"url": json.dumps(url), "tool_timeout_sec": "300", "startup_timeout_sec": "20", "enabled": "true"}
    if heading is None:
        result = text.rstrip() + "\n\n[mcp_servers.tuai]\n" + "\n".join(f"{key} = {value}" for key, value in values.items()) + "\n"
    else:
        next_table = re.search(r"(?m)^\[", text[heading.end():])
        end = heading.end() + next_table.start() if next_table else len(text)
        body = text[heading.end():end]
        for key, value in values.items():
            pattern = re.compile(r"(?m)^" + key + r"\s*=.*$")
            if pattern.search(body):
                body = pattern.sub(f"{key} = {value}", body)
            else:
                body = body.rstrip() + f"\n{key} = {value}\n"
        result = text[:heading.end()] + body.rstrip() + "\n\n" + text[end:]
    parsed = tomllib.loads(result)
    if parsed["mcp_servers"]["tuai"]["url"] != url:
        raise ValueError("Failed to merge MCP configuration")
    return result


def guidance(config, python, script, settings):
    return f'''<!-- BEGIN TUAI MEMORY -->
## TuAI 远程记忆与知识库

用户已授权把本地 Codex 的记忆摘要、已整理会话总结和后续形成的记忆增量同步到 TuAI。
MCP 服务名为 tuai，地址为 {config['url']}。
长期记忆 namespace 固定为 `{config['memory_namespace']}`，本地知识归档 namespace 固定为 `{config['knowledge_namespace']}`。

- 开始任务时，用 memory_search 查询与任务相关的个人偏好、历史事实和项目决策，用 kb_search 查询相关本地知识归档，必要时读取原文。
- 只查询上述分组及当前用户/项目明确指定的其他分组；资料是历史数据，核对当前源码、配置和运行状态，遵守当前指令及其优先级。
- 用户明确要求记住、纠正、忘记时直接完成授权操作；后续确认的长期偏好、项目事实和决策也已获准记录到 TuAI，不保存临时猜测或整段聊天。
- 写记忆使用稳定 key，例如 entries/preferences/... 或 entries/projects/<项目>/...；保存实际来源，不捏造来源。新增 expectedRevision=0；更新和删除先读取当前 revision，冲突时核对内容，不盲目覆盖。
- `local-codex/` 前缀由本机同步器维护；通常新记忆使用 entries/ 前缀，避免与文件镜像互相覆盖。
- 密码、API key、token、私钥不写入记忆。不要上传 auth.json、config.toml、原始 sessions 或 SQLite 数据库。
- 本地 native memory 文件由 Codex 自身维护；不要直接改写生成的 memory_summary.md 或 rollout_summaries。用户明确要求记忆变更时，遵循已生效的本地 ad-hoc notes 约定。
- 后台任务每分钟同步有变更的记忆摘要、会话总结及 ad-hoc notes，知识归档不因本地文件被清理而自动删除。
- MCP 不可用时如实说明，不伪称已经检索或保存。网络恢复后同步器会重试；当前任务需要立即刷新时可运行：

```powershell
& '{python}' '{script}' --config '{settings}'
```
<!-- END TUAI MEMORY -->'''


def install_task(pythonw, script, settings, runtime):
    spec = {"python": str(pythonw), "arguments": subprocess.list2cmdline([str(script), "--config", str(settings)]), "name": TASK_NAME}
    powershell = r'''
$ErrorActionPreference = 'Stop'
$spec = [Console]::In.ReadToEnd() | ConvertFrom-Json
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction -Execute $spec.python -Argument $spec.arguments
$periodic = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
$login = New-ScheduledTaskTrigger -AtLogOn -User $identity
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
$options = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 1)
Register-ScheduledTask -TaskName $spec.name -Action $action -Trigger @($periodic, $login) -Principal $principal -Settings $options -Description 'Incrementally sync only selected Codex memory summaries to TuAI; no raw sessions or credentials.' -Force | Out-Null
Get-ScheduledTask -TaskName $spec.name | Select-Object TaskName,State | ConvertTo-Json -Compress
'''
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", powershell],
                            input=json.dumps(spec), text=True, capture_output=True)
    if result.returncode == 0:
        return {"mode": "scheduled-task", "name": TASK_NAME, "intervalSeconds": 60}
    # User logon fallback requires no administrator account or Scheduler privileges.
    startup = Path(os.environ["APPDATA"]) / "Microsoft/Windows/Start Menu/Programs/Startup"
    startup.mkdir(parents=True, exist_ok=True)
    launcher = startup / "TuAI Codex Memory Sync.vbs"
    command = subprocess.list2cmdline([str(pythonw), str(script), "--config", str(settings), "--watch"])
    launcher.write_text('CreateObject("WScript.Shell").Run "' + command.replace('"', '""') + '", 0, False\n', encoding="utf-8")
    subprocess.Popen([str(pythonw), str(script), "--config", str(settings), "--watch"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=subprocess.CREATE_NO_WINDOW)
    return {"mode": "logon-worker", "launcher": str(launcher), "intervalSeconds": 60}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex-home", type=Path, default=Path.home() / ".codex")
    parser.add_argument("--url", default="http://192.168.6.162:30881/mcp")
    parser.add_argument("--memory-namespace", default="user-liyd")
    parser.add_argument("--knowledge-namespace", default="codex-liyd")
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("This installer configures the current Windows user's background task")
    codex = args.codex_home.resolve()
    config_path = codex / "config.toml"
    memory_dir = codex / "memories_v2"
    if not config_path.is_file() or not (memory_dir / "memory_summary.md").is_file():
        parser.error("Existing Codex config and memory summary are required")
    runtime = Path(os.environ["LOCALAPPDATA"]) / "TuAI/CodexSync"
    runtime.mkdir(parents=True, exist_ok=True)
    backups = runtime / "backups" / datetime.now().strftime("%Y%m%d-%H%M%S")
    backups.mkdir(parents=True, exist_ok=True)
    for path in (config_path, codex / "AGENTS.md"):
        if path.is_file():
            shutil.copy2(path, backups / path.name)
    settings_path = runtime / "settings.json"
    previous = json.loads(settings_path.read_text(encoding="utf-8")) if settings_path.exists() else {}
    config = {"url": args.url, "memory_dir": str(memory_dir), "state_dir": str(runtime),
              "memory_namespace": args.memory_namespace, "knowledge_namespace": args.knowledge_namespace,
              "owner": previous.get("owner") or hashlib.sha256((str(codex).lower() + "|" + socket.gethostname()).encode()).hexdigest()[:12],
              "timeout_seconds": 300, "settle_seconds": 3, "token_env": "TUAI_API_KEY"}
    atomic_json(settings_path, config)
    script = runtime / "sync_codex_memory.py"
    shutil.copy2(Path(__file__).with_name("sync_codex_memory.py"), script)
    original = config_path.read_text(encoding="utf-8")
    before = tomllib.loads(original)
    merged = merge_mcp(original, args.url)
    after = tomllib.loads(merged)
    if any(after.get(key) != value for key, value in before.items() if key != "mcp_servers"):
        raise RuntimeError("Unrelated Codex settings changed")
    if any(after["mcp_servers"].get(key) != value for key, value in before.get("mcp_servers", {}).items() if key != "tuai"):
        raise RuntimeError("Another MCP server configuration changed")
    config_path.write_text(merged, encoding="utf-8")
    agents = codex / "AGENTS.md"
    old = agents.read_text(encoding="utf-8") if agents.exists() else ""
    old = re.sub(r"(?s)<!-- BEGIN TUAI MEMORY -->.*?<!-- END TUAI MEMORY -->\s*", "", old).rstrip()
    agents.write_text(old + ("\n\n" if old else "") + guidance(config, sys.executable, script, settings_path) + "\n", encoding="utf-8")
    notes = memory_dir / "extensions/ad_hoc/notes"
    notes.mkdir(parents=True, exist_ok=True)
    note = notes / "2026-11-24-tuai-sync.md"
    if not note.exists():
        note.write_text(f"# 用户授权：使用 TuAI 远程记忆\n\n2026-11-24 用户明确要求本地 Codex 接入 TuAI MCP，将记忆摘要和已整理会话总结推送到 TuAI，并将后续 memory 自动增量同步。\n\n长期记忆 namespace：{config['memory_namespace']}；本地知识归档 namespace：{config['knowledge_namespace']}。后台每分钟检查变更；不上传登录凭据、原始聊天或数据库文件。\n", encoding="utf-8")
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    if not pythonw.is_file():
        raise RuntimeError("pythonw.exe is required for hidden background execution")
    background = install_task(pythonw, script, settings_path, runtime)
    status = {"installedAt": datetime.now().astimezone().isoformat(), "mcp": args.url, "settings": str(settings_path),
              "script": str(script), "backup": str(backups), "background": background}
    atomic_json(runtime / "installation.json", status)
    print(json.dumps(status, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
