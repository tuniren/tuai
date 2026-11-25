#!/usr/bin/env python3
"""Build, deploy and verify both TuAI services with one command (Python 3.10+)."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from deployment import ROOT, remote, settings


def timestamp():
    try:
        return datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(timespec="seconds")
    except ZoneInfoNotFoundError:
        from datetime import timedelta, timezone
        return datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds")


def npm_runtime(ui):
    env = os.environ.copy()
    pathfile = ui / ".tools/node-path.txt"
    if os.name == "nt" and pathfile.is_file():
        directory = Path(pathfile.read_text(encoding="utf-8").strip())
        env["PATH"] = str(directory) + os.pathsep + env.get("PATH", "")
        command = [str(directory / "node.exe"), str(directory / "node_modules/npm/bin/npm-cli.js")]
    else:
        executable = shutil.which("npm.cmd" if os.name == "nt" else "npm")
        if not executable:
            raise RuntimeError("Install Node.js 22.12+ and npm first")
        command = [executable]
    if not env.get("PLAYWRIGHT_CHANNEL") and os.name == "nt":
        candidates = [Path(os.environ.get("PROGRAMFILES", "C:/Program Files")) / "Google/Chrome/Application/chrome.exe",
                      Path(os.environ.get("LOCALAPPDATA", "")) / "Google/Chrome/Application/chrome.exe"]
        if any(path.is_file() for path in candidates):
            env["PLAYWRIGHT_CHANNEL"] = "chrome"
    return command, env


def run_step(name, command, cwd, directory, env=None):
    print(f"\n[{name}]", flush=True)
    path = directory / (name + ".log")
    environment = (env or os.environ).copy()
    environment["PYTHONUNBUFFERED"] = "1"
    environment["PYTHONIOENCODING"] = "utf-8"
    with path.open("wb") as log:
        with subprocess.Popen([str(arg) for arg in command], cwd=cwd, env=environment,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
            for line in process.stdout:
                log.write(line)
                log.flush()
                print(line.decode("utf-8", errors="replace").rstrip(), flush=True)
            code = process.wait()
    if code:
        raise RuntimeError(f"{name} failed (exit {code}); log: {path}")


def current_images(host):
    response = remote(host, ["kubectl", "-n", "tuai", "get", "deployment", "tuai", "-o", "json"],
                      capture_output=True, text=True, timeout=30)
    deployment = json.loads(response.stdout)
    return {container["name"]: container["image"]
            for container in deployment["spec"]["template"]["spec"]["containers"]}


def verify_endpoints(ui_url, backend_url, ui, values, namespace):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    headers = {}
    if values.get("TUAI_API_KEY"):
        headers["Authorization"] = "Bearer " + values["TUAI_API_KEY"]

    def fetch(url, body=None, json_response=True):
        request_headers = dict(headers)
        if body is not None:
            request_headers.update({"Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
        request = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None,
                                         headers=request_headers)
        for attempt in range(4):
            try:
                with opener.open(request, timeout=100) as response:
                    content = response.read()
                    return json.loads(content) if json_response else content
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if attempt == 3:
                    raise RuntimeError("Public endpoint verification failed: " + urllib.parse.urlsplit(url).path) from None
                print("Waiting for the public endpoint...", flush=True)
                time.sleep(2)

    if fetch(backend_url + "/health").get("status") != "UP":
        raise RuntimeError("Database health is not UP")
    index = (ui / "dist/index.html").read_bytes()
    for route in ("/", "/search"):
        if fetch(ui_url + route, json_response=False) != index:
            raise RuntimeError("Served UI differs from this build: " + route)
    # Check the immutable JavaScript asset too, not just SPA fallback HTML.
    asset = re.search(rb'<script[^>]+src="([^"]+)"', index)
    if asset is None:
        raise RuntimeError("UI entry script not found")
    asset_path = asset.group(1).decode()
    local_asset = (ui / "dist" / asset_path.lstrip("/")).read_bytes()
    if hashlib.sha256(fetch(ui_url + asset_path, json_response=False)).digest() != hashlib.sha256(local_asset).digest():
        raise RuntimeError("Served UI entry asset differs from this build")

    initialized = fetch(ui_url + "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "tuai-deploy-all", "version": "1"}}})
    if initialized.get("result", {}).get("serverInfo", {}).get("name") != "tuai-knowledge-base":
        raise RuntimeError("MCP initialization failed")
    listed = fetch(ui_url + "/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    tools = {tool["name"] for tool in listed.get("result", {}).get("tools", [])}
    required = {"kb_upsert_document", "kb_search", "kb_list_documents", "kb_read_document", "kb_delete_document",
                "memory_upsert", "memory_search", "memory_list", "memory_read", "memory_delete"}
    if not required.issubset(tools):
        raise RuntimeError("The deployed MCP is missing knowledge or memory tools")
    namespaces = fetch(ui_url + "/api/namespaces")
    if not isinstance(namespaces, list):
        raise RuntimeError("Namespace proxy validation failed")
    search_namespaces = fetch(ui_url + "/api/search/namespaces")
    if not isinstance(search_namespaces, list) or any(not isinstance(value, str) for value in search_namespaces):
        raise RuntimeError("Search namespace selector validation failed")
    counts = {}
    for scope in (namespace, None):
        for category, mode in (("documents", "keyword"), ("documents", "semantic"), ("memories", "keyword")):
            params = {"q": "TuAI 数据库部署" if mode == "semantic" else "TuAI", "type": category, "mode": mode, "limit": 3}
            if scope is not None:
                params["namespace"] = scope
            result = fetch(ui_url + "/api/search?" + urllib.parse.urlencode(params))
            if not isinstance(result.get("items"), list) or result.get("type") != category or result.get("mode") != mode:
                raise RuntimeError("Search response validation failed")
            if result.get("namespace") != scope or any(not item.get("namespace") or
                    (scope is not None and item["namespace"] != scope) for item in result["items"]):
                raise RuntimeError("Search result namespace does not match the selected scope")
            if mode == "semantic" and any(not isinstance(item.get("score"), (int, float)) for item in result["items"]):
                raise RuntimeError("Semantic search scores missing")
            if mode == "semantic" and not result["items"] and any(
                    (scope is None or item.get("namespace") == scope) and item.get("documents", 0) > 0 for item in namespaces):
                raise RuntimeError("Semantic search returned no evidence for the populated smoke-test scope")
            key = category + "/" + mode + ("/all" if scope is None else "/selected")
            counts[key] = len(result["items"])
            print(f"Verified {key}: {len(result['items'])} results", flush=True)
    # Reads only: do not insert or delete production documents/memories for a deployment probe.
    return {"health": "UP", "mcpTools": len(tools), "uiAssets": "matched", "searchResults": counts}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="root@192.168.6.162")
    parser.add_argument("--public-host", default="192.168.6.162")
    parser.add_argument("--ui-dir", type=Path, default=ROOT.parent / "tuai-ui")
    parser.add_argument("--settings-file", type=Path)
    parser.add_argument("--backend-port", type=int, default=30881)
    parser.add_argument("--ui-port", type=int, default=30882)
    parser.add_argument("--namespace", default="tuai", help="Explicit namespace for read-only deployment probes")
    parser.add_argument("--skip-install", action="store_true", help="Skip npm ci when lockfile dependencies are already installed")
    parser.add_argument("--skip-browser-tests", action="store_true", help="Skip the final Playwright tests; HTTP probes still run")
    parser.add_argument("--dry-run", action="store_true", help="Print the plan without building or changing remote state")
    args = parser.parse_args(argv)
    if not all(30000 <= value <= 32767 for value in (args.backend_port, args.ui_port)) or args.backend_port == args.ui_port:
        parser.error("Supply two distinct NodePorts in 30000..32767")
    ui = args.ui_dir.resolve()
    for path in (ROOT / "scripts/build_image.py", ui / "scripts/build_image.py", ui / "package-lock.json"):
        if not path.is_file():
            parser.error("Required project file missing: " + str(path))
    ui_url = f"http://{args.public_host}:{args.ui_port}"
    backend_url = f"http://{args.public_host}:{args.backend_port}"
    print("TuAI full deployment: " + args.host, flush=True)
    print("Plan: build/test backend -> build/test frontend -> deploy backend -> deploy frontend -> verify HTTP/MCP/search -> browser tests", flush=True)
    print("UI: " + ui_url + "/search", flush=True)
    if args.dry_run:
        return 0

    stamp = timestamp().replace("-", "").replace(":", "").replace("T", "-").split("+")[0]
    directory = ROOT / ".tools/releases" / (stamp + f"-{os.getpid()}")
    directory.mkdir(parents=True)
    receipt = {"startedAt": timestamp(), "status": "running", "host": args.host, "uiUrl": ui_url, "backendUrl": backend_url}
    record = directory / "deployment.json"
    def save():
        record.write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    save()
    try:
        receipt["beforeImages"] = current_images(args.host)
        settings_file = args.settings_file or (ROOT / "deploy/settings.local.json" if (ROOT / "deploy/settings.local.json").is_file() else None)
        values = settings(settings_file)
        npm, browser_env = npm_runtime(ui)
        python = sys.executable
        # Finish both builds before the first production mutation.
        run_step("01-backend-build", [python, ROOT / "scripts/build_image.py", "--host", args.host], ROOT, directory)
        frontend_build = [python, ui / "scripts/build_image.py", "--host", args.host]
        if args.skip_install:
            frontend_build.append("--skip-install")
        run_step("02-frontend-build", frontend_build, ui, directory)
        for name, source in (("backend", ROOT), ("frontend", ui)):
            target = directory / (name + "-image.json")
            shutil.copyfile(source / ".tools/image.json", target)
            receipt[name] = json.loads(target.read_text(encoding="utf-8"))
        save()
        backend_deploy = [python, ROOT / "scripts/deploy_k8s.py", "--receipt", directory / "backend-image.json", "--node-port", args.backend_port]
        if settings_file:
            backend_deploy.extend(["--settings-file", settings_file.resolve()])
        run_step("03-backend-deploy", backend_deploy, ROOT, directory)
        run_step("04-frontend-deploy", [python, ui / "scripts/deploy_k8s.py", "--receipt", directory / "frontend-image.json",
                                       "--node-port", args.ui_port], ui, directory)
        images = current_images(args.host)
        expected = {"tuai": receipt["backend"]["digest"], "tuai-ui": receipt["frontend"]["digest"]}
        if any(images.get(name) != digest for name, digest in expected.items()):
            raise RuntimeError("Running images do not match both release receipts")
        print("\n[05-public-verification]", flush=True)
        receipt["verification"] = verify_endpoints(ui_url, backend_url, ui, values, args.namespace)
        receipt["afterImages"] = images
        save()
        if not args.skip_browser_tests:
            browser_env["PLAYWRIGHT_BASE_URL"] = ui_url
            run_step("06-browser-tests", [*npm, "run", "test:e2e"], ui, directory, browser_env)
        receipt.update({"status": "success", "completedAt": timestamp(), "browserTests": "skipped" if args.skip_browser_tests else "passed"})
        save()
        shutil.copyfile(record, ROOT / ".tools/deployment-latest.json")
        print("\nDeployment complete: " + ui_url + "/search", flush=True)
        print("MCP: " + backend_url + "/mcp", flush=True)
        print("Receipt and step logs: " + str(directory), flush=True)
        return 0
    except Exception as error:
        receipt.update({"status": "failed", "failedAt": timestamp(), "error": str(error)})
        save()
        print("\nDeployment stopped: " + str(error), file=sys.stderr, flush=True)
        print("Receipt and logs: " + str(directory), file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    raise SystemExit(main())
