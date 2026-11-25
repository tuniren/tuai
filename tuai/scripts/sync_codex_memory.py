#!/usr/bin/env python3
"""Push explicitly selected Codex memory files to TuAI; no chat/auth/database files are read."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request


class SyncError(RuntimeError):
    pass


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def atomic_json(path, value):
    temporary = path.with_name(path.name + f".{os.getpid()}.tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def redact(text):
    text = re.sub(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
                  "[REDACTED PRIVATE KEY]", text, flags=re.S)
    text = re.sub(r"\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})", "[REDACTED TOKEN]", text)
    text = re.sub(r"(?i)(Bearer\s+)[A-Za-z0-9_.-]{16,}", r"\1[REDACTED]", text)
    assignment = re.compile(r"(?i)((?:[A-Za-z0-9_.-]*(?:password|passwd|api[_-]?key|access[_-]?token|secret)|密码|密钥)\s*[:=]\s*)(['\"`]?)([^\s'\"`<>{}\[\],;]+)\2")
    def replace(match):
        value = match[3]
        if len(value) < 6 or value.startswith(("$", "os.environ", "process.env")) or value.lower() in {"none", "false", "true", "null"}:
            return match[0]
        return match[1] + match[2] + "[REDACTED]" + match[2]
    return assignment.sub(replace, text)


def split_text(text, maximum=6000):
    """Respect TuAI's UTF-16 limit and never split a supplementary Unicode character."""
    parts, current, length = [], [], 0
    for line in text.splitlines(keepends=True):
        size = len(line.encode("utf-16-le")) // 2
        if current and length + size > maximum:
            parts.append("".join(current)); current, length = [], 0
        if size > maximum:
            for character in line:
                width = 2 if ord(character) > 0xFFFF else 1
                if length + width > maximum:
                    parts.append("".join(current)); current, length = [], 0
                current.append(character); length += width
        else:
            current.append(line); length += size
    if current:
        parts.append("".join(current))
    return [part for part in parts if part.strip()]


def sections(text, fallback):
    headings, body, output = [], [], []
    def flush():
        value = "".join(body).strip()
        meaningful = re.sub(r"(?m)^#{1,6}\s+.*$", "", value).strip()
        if meaningful and meaningful not in {"v1", "v2"}:
            title = " / ".join(label for _, label in headings) or fallback
            output.append((title, value))
    for line in text.splitlines(keepends=True):
        match = re.match(r"^(#{1,3})\s+(.+?)\s*$", line)
        if match:
            flush(); body = []
            level = len(match[1])
            headings = [(depth, label) for depth, label in headings if depth < level] + [(level, match[2])]
        body.append(line)
    flush()
    return output


def collect(config):
    root = Path(config["memory_dir"]).resolve()
    selected = [root / "memory_summary.md"]
    selected += sorted((root / "rollout_summaries").glob("*.md"))
    selected += sorted((root / "extensions/ad_hoc/notes").glob("*.md"))
    documents, memories, seen = {}, {}, {}
    for path in selected:
        if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(root):
            continue
        before = path.stat()
        if time.time() - before.st_mtime < config.get("settle_seconds", 3):
            continue
        text = path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")
        after = path.stat()
        if (before.st_mtime_ns, before.st_size) != (after.st_mtime_ns, after.st_size):
            continue
        relative = path.relative_to(root).as_posix()
        text = redact(text)
        if not text.strip() or "\0" in text:
            continue
        if len(text.encode("utf-16-le")) // 2 > 400000:
            raise SyncError("A selected knowledge file exceeds the document limit: " + relative)
        source = f"codex-local/{config['owner']}/memories_v2/{relative}"
        first_heading = re.search(r"(?m)^#\s+(.+)$", text)
        title = (first_heading[1] if first_heading else path.stem)[:200]
        document = {"namespace": config["knowledge_namespace"], "source": source, "title": title, "content": text,
                    "metadata": {"origin": "codex-local-sync", "owner": config["owner"], "relativePath": relative, "sha256": digest(text)}}
        documents[source] = document
        if relative.startswith("rollout_summaries/"):
            continue
        seen[relative] = True
        occurrences = {}
        for title, body in sections(text, path.stem):
            occurrences[title] = occurrences.get(title, 0) + 1
            section_id = digest(relative + "\0" + title + "\0" + str(occurrences[title]))[:20]
            kind = "preference" if re.search(r"(?i)preferences?|偏好", title) else "context"
            for number, part in enumerate(split_text(body), 1):
                key = f"local-codex/{config['owner']}/{section_id}/{number}"
                memories[key] = {"namespace": config["memory_namespace"], "key": key,
                                 "title": split_text(title, 220)[0] + (f" ({number})" if number > 1 else ""),
                                 "content": part, "kind": kind, "source": source + "#" + section_id,
                                 "localFile": relative}
    return documents, memories, seen


class McpClient:
    def __init__(self, config):
        self.url = config["url"]
        self.timeout = config.get("timeout_seconds", 300)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-06-18"}
        token = os.environ.get(config.get("token_env", "TUAI_API_KEY"), "")
        if token:
            self.headers["Authorization"] = "Bearer " + token
        self.serial = 0

    def request(self, method, params, notification=False):
        self.serial += 1
        payload = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            payload["id"] = self.serial
        request = urllib.request.Request(self.url, data=json.dumps(payload, ensure_ascii=False).encode(), headers=self.headers)
        with self.opener.open(request, timeout=self.timeout) as response:
            if notification:
                return None
            result = json.load(response)
        if result.get("id") != payload["id"] or result.get("jsonrpc") != "2.0":
            raise SyncError("Invalid MCP response")
        if "error" in result:
            raise SyncError("MCP protocol error")
        return result["result"]

    def initialize(self):
        result = self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                              "clientInfo": {"name": "tuai-codex-local-sync", "version": "1"}})
        self.headers["MCP-Protocol-Version"] = result["protocolVersion"]
        self.request("notifications/initialized", {}, notification=True)

    def call(self, name, arguments):
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        text = "\n".join(item.get("text", "") for item in result.get("content", []) if item.get("type") == "text")
        if result.get("isError"):
            raise SyncError(text)
        return json.loads(text)


def fingerprint(value):
    return digest(json.dumps(value, sort_keys=True, ensure_ascii=False))


def memory_payload(value):
    return {key: value[key] for key in ("namespace", "key", "title", "content", "kind", "source")}


def matches(remote, desired):
    return remote is not None and remote.get("content") == desired["content"] and all(
        remote["summary"].get(key) == desired[key] for key in ("namespace", "key", "title", "kind", "source"))


def read_memory(client, namespace, key):
    try:
        return client.call("memory_read", {"namespace": namespace, "key": key})
    except SyncError as error:
        if str(error) == "Memory not found in this namespace":
            return None
        raise


@contextmanager
def exclusive(path):
    handle = path.open("a+b")
    try:
        if handle.tell() == 0:
            handle.write(b"0"); handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            yield False
            return
        yield True
    finally:
        handle.close()


def synchronize(config, client=None, force=False, dry_run=False):
    directory = Path(config["state_dir"])
    directory.mkdir(parents=True, exist_ok=True)
    documents, memories, seen = collect(config)
    stats = {"documents": len(documents), "memories": len(memories), "documentsUploaded": 0, "memoriesUploaded": 0,
             "unchanged": 0, "retired": 0, "conflicts": 0, "errors": 0}
    if dry_run:
        return stats
    with exclusive(directory / "sync.lock") as acquired:
        if not acquired:
            return {"status": "already-running"}
        state_path = directory / "state.json"
        state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {"documents": {}, "memories": {}}
        initialized = False
        client = client or McpClient(config)
        def connect():
            nonlocal initialized
            if not initialized:
                client.initialize(); initialized = True
        def checkpoint():
            atomic_json(state_path, state)
        # Mirror topical memory first. Knowledge documents remain a separate durable archive.
        for key, memory in memories.items():
            wanted = memory_payload(memory)
            signature = fingerprint(wanted)
            previous = state["memories"].get(key)
            if previous and previous["hash"] == signature and not force:
                stats["unchanged"] += 1
                continue
            try:
                connect()
                remote = read_memory(client, wanted["namespace"], key)
                if not matches(remote, wanted):
                    if previous and (remote is None or remote["summary"]["revision"] != previous["revision"]):
                        stats["conflicts"] += 1; logging.warning("Memory changed remotely; preserved %s", key)
                        continue
                    if remote and remote["summary"]["source"] != wanted["source"]:
                        stats["conflicts"] += 1; continue
                    payload = {**wanted, "expectedRevision": remote["summary"]["revision"] if remote else 0}
                    try:
                        remote = client.call("memory_upsert", payload)
                    except (OSError, TimeoutError, SyncError):
                        # A timeout might have committed. Read back instead of blindly retrying a mutation.
                        remote = read_memory(client, wanted["namespace"], key)
                        if not matches(remote, wanted):
                            raise SyncError("Memory write could not be verified") from None
                    stats["memoriesUploaded"] += 1
                    logging.info("Memory synchronized: %s", key)
                state["memories"][key] = {"hash": signature, "revision": remote["summary"]["revision"],
                                           "localFile": memory["localFile"], "source": wanted["source"]}
                checkpoint()
            except Exception as error:
                stats["errors"] += 1; logging.error("Memory sync failed: %s (%s)", key, type(error).__name__)
        # Only retire this syncer's superseded sections of files successfully read this time.
        # A deleted local file does not cause archive deletion or mass removal of remote memories.
        for key, previous in list(state["memories"].items()):
            if key in memories or previous["localFile"] not in seen:
                continue
            try:
                connect()
                remote = read_memory(client, config["memory_namespace"], key)
                if remote is not None:
                    if remote["summary"]["revision"] != previous["revision"] or remote["summary"]["source"] != previous["source"]:
                        stats["conflicts"] += 1; continue
                    client.call("memory_delete", {"namespace": config["memory_namespace"], "key": key, "expectedRevision": previous["revision"]})
                del state["memories"][key]; stats["retired"] += 1; checkpoint()
            except Exception as error:
                stats["errors"] += 1; logging.error("Retiring superseded memory failed: %s (%s)", key, type(error).__name__)
        for source, document in documents.items():
            signature = fingerprint(document)
            if state["documents"].get(source, {}).get("hash") == signature and not force:
                stats["unchanged"] += 1
                continue
            try:
                connect()
                result = client.call("kb_upsert_document", document)
                state["documents"][source] = {"hash": signature, "documentId": result["documentId"]}
                stats["documentsUploaded"] += 1; checkpoint()
                logging.info("Knowledge synchronized: %s", source)
            except Exception as error:
                stats["errors"] += 1; logging.error("Knowledge sync failed: %s (%s)", source, type(error).__name__)
        status = {"checkedAt": datetime.now(timezone.utc).isoformat(), "status": "success" if not stats["errors"] and not stats["conflicts"] else "incomplete", **stats}
        atomic_json(directory / "status.json", status)
        return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "TuAI/CodexSync/settings.json")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--force", action="store_true", help="Read back unchanged remote items too; revision conflicts remain protected")
    parser.add_argument("--watch", action="store_true", help="Fallback logon worker when Task Scheduler is unavailable")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    directory = Path(config["state_dir"]); directory.mkdir(parents=True, exist_ok=True)
    handlers = [RotatingFileHandler(directory / "sync.log", maxBytes=2_000_000, backupCount=2, encoding="utf-8")]
    if sys.stdout is not None:
        handlers.append(logging.StreamHandler(sys.stdout))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", handlers=handlers)
    def once():
        try:
            result = synchronize(config, force=args.force, dry_run=args.dry_run)
            logging.info("Sync result: %s", json.dumps(result, ensure_ascii=True))
            return 0 if result.get("status", "success") in {"success", "already-running"} else 1
        except Exception as error:
            logging.error("Sync unavailable (%s); next scheduled run will retry", type(error).__name__)
            atomic_json(directory / "status.json", {"status": "unavailable", "checkedAt": datetime.now(timezone.utc).isoformat(), "errorType": type(error).__name__})
            return 1
    if not args.watch:
        return once()
    with exclusive(directory / "watch.lock") as acquired:
        if not acquired:
            return 0
        while True:
            once(); time.sleep(60)


if __name__ == "__main__":
    raise SystemExit(main())
