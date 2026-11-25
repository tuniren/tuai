#!/usr/bin/env python3
"""Verify the JAR locally, build/push a Linux Docker image on 6.162, and preload it into RKE2."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

from deployment import ROOT, prepare_release, remote, run, upload, verify_jar
from fetch_runtime import fetch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="root@192.168.6.162")
    parser.add_argument("--image", help="Full image tag; default uses the existing :5000 registry and a timestamp")
    parser.add_argument("--base-image", default="eclipse-temurin:25-jre-jammy")
    parser.add_argument("--runtime-archive", type=Path, help="Existing docker-load archive for the runtime image")
    parser.add_argument("--skip-build", action="store_true", help="Reuse an already verified executable JAR")
    args = parser.parse_args()
    jar = verify_jar(args.skip_build)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    image = args.image or "192.168.6.162:5000/tuai/knowledge-base:0.1.0-" + stamp
    if not re.fullmatch(r"[A-Za-z0-9._:/-]+", image) or "@" in image:
        raise ValueError("Supply a full Docker image tag")
    release = "/root/tuai/releases/" + stamp
    prepare_release(args.host, release + "/target")
    for source, name in [(jar, "target/tuai-0.1.0-SNAPSHOT.jar"), (ROOT / "Dockerfile", "Dockerfile"),
                         (ROOT / ".dockerignore", ".dockerignore"), (ROOT / "scripts/remote_release.py", "remote_release.py")]:
        upload(args.host, source, release + "/" + name)

    archive = args.runtime_archive
    if archive is None:
        try:
            remote(args.host, ["docker", "image", "inspect", args.base_image], capture_output=True)
        except subprocess.CalledProcessError:
            if args.base_image.startswith("eclipse-temurin:"):
                archive = ROOT / ".tools/temurin-25-jre-amd64.tar"
                metadata = archive.with_suffix(".json")
                reusable = archive.is_file() and metadata.is_file()
                if reusable:
                    info = json.loads(metadata.read_text(encoding="utf-8"))
                    reusable = (info.get("image") == args.base_image and
                                info.get("sha256") == hashlib.sha256(archive.read_bytes()).hexdigest())
                if not reusable:
                    fetch(archive, args.base_image)
            else:
                remote(args.host, ["docker", "pull", args.base_image])
    if archive:
        upload(args.host, archive, release + "/runtime.tar")
        remote(args.host, ["docker", "load", "-i", release + "/runtime.tar"])
    remote(args.host, ["python3", release + "/remote_release.py", "build", "--release", release,
                      "--image", image, "--base-image", args.base_image])
    receipt = ROOT / ".tools/image.json"
    receipt.parent.mkdir(exist_ok=True)
    run(["scp", "-q", args.host + ":" + release + "/image.json", receipt])
    result = json.loads(receipt.read_text(encoding="utf-8"))
    result.update({"host": args.host, "release": release, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest()})
    receipt.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Image ready: " + result["digest"])
    print("Receipt: " + str(receipt))


if __name__ == "__main__":
    main()
