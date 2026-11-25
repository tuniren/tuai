#!/usr/bin/env python3
"""Deploy the built image to 6.162 Kubernetes with the current PostgreSQL settings and all-source NodePort access."""
import argparse
import json
from pathlib import Path

from deployment import ROOT, remote, settings, upload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path, default=ROOT / ".tools/image.json")
    local_settings = ROOT / "deploy/settings.local.json"
    parser.add_argument("--settings-file", type=Path, default=local_settings if local_settings.is_file() else None,
                        help="Private JSON with AppConfig overrides; defaults to deploy/settings.local.json when present")
    parser.add_argument("--node-port", type=int, default=30881)
    args = parser.parse_args()
    if not 30000 <= args.node_port <= 32767:
        parser.error("--node-port must be 30000..32767")
    receipt = json.loads(args.receipt.read_text(encoding="utf-8"))
    values = settings(args.settings_file)
    host, release = receipt["host"], receipt["release"]
    upload(host, ROOT / "scripts/remote_release.py", release + "/remote_release.py")
    upload(host, ROOT / "deploy/kubernetes.json", release + "/kubernetes.json")
    # Credentials travel over SSH stdin; no secret appears in shell arguments or committed manifests.
    remote(host, ["python3", "-c", "import os,sys; p=" + repr(release + "/settings.json") +
                  "; fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600); "
                  "os.fchmod(fd,0o600); f=os.fdopen(fd,'w'); f.write(sys.stdin.read()); f.close()"],
           input=json.dumps(values), text=True)
    remote(host, ["python3", release + "/remote_release.py", "deploy", "--release", release,
                  "--image", receipt["digest"], "--node-port", str(args.node_port)])
    if not values.get("EMBEDDING_API_KEY") and "api.openai.com" in values.get("EMBEDDING_BASE_URL", ""):
        print("Embedding API key is not configured: MCP/list/read/delete work; vector import/search require a model key.")
    print("MCP: http://192.168.6.162:" + str(args.node_port) + "/mcp")
    print("Health: http://192.168.6.162:" + str(args.node_port) + "/health")


if __name__ == "__main__":
    main()
