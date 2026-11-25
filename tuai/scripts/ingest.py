#!/usr/bin/env python3
"""Import UTF-8 documents through the knowledge base MCP, using Python's standard library only."""
import argparse
import fnmatch
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

TEXT_SUFFIXES = {
    ".md", ".txt", ".rst", ".adoc", ".java", ".scala", ".sbt", ".kt", ".kts", ".py", ".go", ".rs",
    ".js", ".jsx", ".ts", ".tsx", ".vue", ".svelte", ".html", ".css", ".scss",
    ".xml", ".json", ".yaml", ".yml", ".toml", ".properties", ".sql", ".sh", ".ps1",
    ".c", ".h", ".cpp", ".hpp", ".cs", ".proto", ".graphql",
}
SKIP_DIRS = {".git", ".idea", ".tools", ".venv", "node_modules", "target", "dist", "build", "__pycache__"}


class McpClient:
    def __init__(self, url, token=""):
        self.url = url.rstrip("/")
        if not self.url.endswith("/mcp"):
            self.url += "/mcp"
        self.headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                        "MCP-Protocol-Version": "2025-06-18"}
        if token:
            self.headers["Authorization"] = "Bearer " + token
        self.next_id = 0

    def request(self, method, params, notification=False):
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            self.next_id += 1
            message["id"] = self.next_id
        body = json.dumps(message, ensure_ascii=False).encode("utf-8")
        with urlopen(Request(self.url, data=body, headers=self.headers), timeout=600) as response:
            if notification:
                return None
            result = json.load(response)
        if result.get("id") != message["id"]:
            raise ValueError("MCP response ID does not match request")
        if "error" in result:
            raise ValueError(result["error"].get("message", "MCP protocol error"))
        return result["result"]

    def initialize(self):
        result = self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                              "clientInfo": {"name": "tuai-import", "version": "0.1.0"}})
        self.headers["MCP-Protocol-Version"] = result["protocolVersion"]
        self.request("notifications/initialized", {}, notification=True)

    def upsert(self, document):
        result = self.request("tools/call", {"name": "kb_upsert_document", "arguments": document})
        text = "\n".join(item.get("text", "") for item in result.get("content", []) if item.get("type") == "text")
        if result.get("isError"):
            raise ValueError(text or "Document import failed")
        return json.loads(text)


def candidates(root):
    if root.is_file():
        return [root]
    # Respect .gitignore when Git is available; -z preserves spaces and non-ASCII filenames.
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "."],
            check=True, capture_output=True,
        )
        return [root / item.decode("utf-8") for item in result.stdout.split(b"\0") if item]
    except (OSError, subprocess.CalledProcessError):
        files = []
        for directory, dirs, names in os.walk(root, followlinks=False):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not (Path(directory) / d).is_symlink()]
            files.extend(Path(directory) / name for name in names)
        return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:8080/mcp")
    parser.add_argument("--source-prefix", default="")
    parser.add_argument("--exclude", action="append", default=[], help="Relative path glob; repeatable")
    parser.add_argument("--max-files", type=int, default=500)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    root = args.path.resolve(strict=True)
    base = root if root.is_dir() else root.parent
    token = os.environ.get("TUAI_API_KEY", "")
    selected = []
    for path in sorted(set(candidates(root))):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            relative = path.resolve().relative_to(base).as_posix()
        except ValueError:
            continue
        if any(part in SKIP_DIRS for part in path.relative_to(base).parts):
            continue
        name = path.name.lower()
        if (name.startswith(".env") or "private-seed" in name or name in {"application-local.yml", "application-local.yaml"}
                or path.suffix.lower() not in TEXT_SUFFIXES):
            continue
        if any(fnmatch.fnmatch(relative, pattern) for pattern in args.exclude):
            continue
        selected.append((path, relative))
    if args.max_files < 1 or len(selected) > args.max_files:
        parser.error(f"Selected {len(selected)} files; increase --max-files or add --exclude")
    client = McpClient(args.url, token)
    if selected and not args.dry_run:
        try:
            client.initialize()
        except (OSError, ValueError, KeyError) as ex:
            print(f"MCP initialization failed: {ex}", file=sys.stderr)
            return 1
    succeeded = failed = skipped = 0
    for path, relative in selected:
        if args.dry_run:
            print(relative)
            continue
        try:
            if path.stat().st_size > 1_600_000:
                raise ValueError("File too large (max 400000 UTF-8 characters)")
            content = path.read_text(encoding="utf-8-sig")
            if not content.strip():
                skipped += 1
                continue
            if len(content) > 400_000 or "\0" in content:
                raise ValueError("File too large or contains NUL")
            source = "/".join(p for p in [args.source_prefix.strip("/"), relative] if p)
            result = client.upsert({"namespace": args.namespace, "source": source, "title": relative,
                                    "content": content, "metadata": {"path": relative}})
            print(f"{'unchanged' if result['unchanged'] else 'indexed'} {relative} ({result['chunks']} chunks)")
            succeeded += 1
        except (OSError, UnicodeError, ValueError, KeyError, HTTPError, URLError) as ex:
            print(f"FAILED {relative}: {ex}", file=sys.stderr)
            failed += 1
    print(f"selected={len(selected)} succeeded={succeeded} skipped={skipped} failed={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
