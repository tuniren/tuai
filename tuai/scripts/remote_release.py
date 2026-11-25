#!/usr/bin/env python3
"""Remote build/deploy implementation. Python 3.7+, Docker, kubectl and RKE2 ctr on the target host."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def output(args):
    return run(args, text=True, stdout=subprocess.PIPE).stdout.strip()


def read_json(opener, request):
    # Endpoint rules can lag Pod readiness during a rolling update.
    for attempt in range(6):
        try:
            with opener.open(request, timeout=5) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 5:
                raise
            print("Waiting for the NodePort endpoint...", flush=True)
            time.sleep(2)


def preload(image):
    ctr = Path("/hci/platform/rke2/data/rke2/bin/ctr")
    socket = Path("/run/k3s/containerd/containerd.sock")
    if not ctr.is_file() or not socket.exists():
        raise RuntimeError("This deployment targets the RKE2 node 6.162; ctr/socket not found")
    command = [ctr, "--address", socket, "--namespace", "k8s.io", "images", "pull", "--platform", "linux/amd64"]
    if image.startswith("192.168.6.162:5000/"):
        command += ["--plain-http"]
    # Avoid progress escape sequences in caller output; full errors remain available when pull fails.
    result = subprocess.run([str(a) for a in command + [image]], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("RKE2 image preload failed: " + result.stderr[-2000:])
    print("RKE2 immutable image preloaded.", flush=True)


def build(args):
    env = os.environ.copy()
    # Legacy builder uses the locally loaded official runtime, including on nodes without Docker Hub access.
    env["DOCKER_BUILDKIT"] = "0"
    run(["docker", "build", "--network=host", "--pull=false", "--build-arg", "RUNTIME_IMAGE=" + args.base_image,
         "-t", args.image, args.release], env=env)
    run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "java", args.image, "-version"])
    run(["docker", "push", args.image])
    inspection = json.loads(output(["docker", "image", "inspect", args.image]))[0]
    repository = args.image.rsplit(":", 1)[0]
    digest = next(d for d in inspection["RepoDigests"] if d.startswith(repository + "@"))
    preload(digest)
    receipt = {"image": args.image, "digest": digest, "baseImage": args.base_image,
               "baseImageId": json.loads(output(["docker", "image", "inspect", args.base_image]))[0]["Id"],
               "imageId": inspection["Id"], "builtAt": datetime.now().isoformat()}
    Path(args.release, "image.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print("Built and published " + digest, flush=True)


def apply(manifest):
    # server-side apply avoids an annotation containing a second plaintext copy of Secret stringData.
    result = subprocess.run(["kubectl", "apply", "--server-side", "--field-manager=tuai-release", "-f", "-"],
                            input=json.dumps(manifest), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Do not print a kubectl diagnostic that might include the secret manifest.
        raise RuntimeError("kubectl apply failed for " + manifest.get("kind", "resource") + "; inspect cluster events")
    print(result.stdout.strip(), flush=True)


def deploy(args):
    release = Path(args.release)
    settings_file = release / "settings.json"
    try:
        settings = json.loads(settings_file.read_text())
        document = json.loads((release / "kubernetes.json").read_text())
        resources = document["items"]
        deployment = next(r for r in resources if r["kind"] == "Deployment")
        service = next(r for r in resources if r["kind"] == "Service")
        deployment["spec"]["template"]["spec"]["containers"][0]["image"] = args.image
        # Replacing settings must roll the Pod even if the same image is redeployed.
        fingerprint = hashlib.sha256(json.dumps(settings, sort_keys=True).encode()).hexdigest()
        deployment["spec"]["template"]["metadata"]["annotations"] = {"tuai/config-sha256": fingerprint}
        service["spec"]["ports"][0]["nodePort"] = args.node_port
        allocated = json.loads(output(["kubectl", "get", "services", "-A", "-o", "json"]))
        for existing in allocated["items"]:
            if existing["metadata"].get("namespace") == "tuai" and existing["metadata"]["name"] == "tuai":
                continue
            if any(p.get("nodePort") == args.node_port for p in existing["spec"].get("ports", [])):
                raise RuntimeError("Requested NodePort is already allocated")
        # Preflight the exact image and the SSH-target node before changing the Deployment.
        preload(args.image)
        node = json.loads(output(["kubectl", "get", "node", "k8s-6-162", "-o", "json"]))
        if not any(a.get("address") == "192.168.6.162" for a in node["status"]["addresses"]):
            raise RuntimeError("Pinned node address differs from the requested target")
        backup = release / ("before-deploy-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
        backup.mkdir(mode=0o700)
        for kind, name in [("deployment", "tuai"), ("service", "tuai"), ("configmap", "tuai-config"),
                           ("secret", "tuai-credentials"), ("networkpolicy", "tuai-allow-all-clients")]:
            response = subprocess.run(["kubectl", "-n", "tuai", "get", kind, name, "-o", "json"],
                                      text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if response.returncode == 0:
                path = backup / (kind + ".json")
                path.write_text(response.stdout)
                path.chmod(0o600)
        apply(next(r for r in resources if r["kind"] == "Namespace"))
        secret_keys = {"DB_PASSWORD", "EMBEDDING_API_KEY", "TUAI_API_KEY"}
        apply({"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "tuai-config", "namespace": "tuai"},
               "data": {k: v for k, v in settings.items() if k not in secret_keys}})
        apply({"apiVersion": "v1", "kind": "Secret", "type": "Opaque",
               "metadata": {"name": "tuai-credentials", "namespace": "tuai"},
               "stringData": {k: v for k, v in settings.items() if k in secret_keys}})
        for resource in resources:
            if resource["kind"] != "Namespace":
                apply(resource)
        # Open only this service port if firewalld is active; do not disable the firewall.
        if shutil.which("firewall-cmd"):
            active = subprocess.run(["firewall-cmd", "--state"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if active.returncode == 0:
                run(["firewall-cmd", "--add-port=" + str(args.node_port) + "/tcp"])
                run(["firewall-cmd", "--permanent", "--add-port=" + str(args.node_port) + "/tcp"])
        run(["kubectl", "-n", "tuai", "rollout", "status", "deployment/tuai", "--timeout=50s"])
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        if read_json(opener, "http://192.168.6.162:" + str(args.node_port) + "/health").get("status") != "UP":
            raise RuntimeError("Deployment is ready but database health is not UP")
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "tuai-deploy", "version": "1"}}}).encode()
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
        if settings.get("TUAI_API_KEY"):
            headers["Authorization"] = "Bearer " + settings["TUAI_API_KEY"]
        initialized = read_json(opener, urllib.request.Request("http://192.168.6.162:" + str(args.node_port) + "/mcp", data=body, headers=headers))
        if initialized["result"]["serverInfo"]["name"] != "tuai-knowledge-base":
            raise RuntimeError("Unexpected MCP server identity")
        (release / "deployed.json").write_text(json.dumps({"image": args.image, "nodePort": args.node_port,
            "deployedAt": datetime.now().isoformat(), "health": "UP", "mcp": "initialized"}, indent=2))
        print("Deployment ready. Database health UP; MCP initialized; all-source TCP access on " + str(args.node_port), flush=True)
        print("Prior resources backed up at " + str(backup), flush=True)
    finally:
        if settings_file.exists():
            settings_file.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["build", "deploy"])
    parser.add_argument("--release", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--base-image", default="eclipse-temurin:25-jre-jammy")
    parser.add_argument("--node-port", type=int, default=30881)
    args = parser.parse_args()
    if args.action == "build":
        build(args)
    else:
        deploy(args)


if __name__ == "__main__":
    main()
