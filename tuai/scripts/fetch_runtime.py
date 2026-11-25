#!/usr/bin/env python3
"""Fetch the official Temurin linux/amd64 runtime as a Docker-load archive without local Docker."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import urllib.request


def fetch(output, image="eclipse-temurin:25-jre-jammy"):
    if not image.startswith("eclipse-temurin:"):
        raise ValueError("The fallback downloader supports official eclipse-temurin tags only")
    tag = image.split(":", 1)[1]
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    token_url = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/eclipse-temurin:pull"
    with urllib.request.urlopen(token_url, timeout=30) as response:
        token = json.load(response)["token"]
    base = "https://registry-1.docker.io/v2/library/eclipse-temurin/"

    def request(path):
        return urllib.request.Request(base + path, headers={"Authorization": "Bearer " + token,
            "Accept": "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, "
                      "application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json"})

    def read(path):
        with urllib.request.urlopen(request(path), timeout=60) as response:
            return response.read()

    index = json.loads(read("manifests/" + tag))
    descriptor = next(m for m in index["manifests"]
                      if m.get("platform", {}).get("os") == "linux" and m["platform"].get("architecture") == "amd64")
    raw_manifest = read("manifests/" + descriptor["digest"])
    if "sha256:" + hashlib.sha256(raw_manifest).hexdigest() != descriptor["digest"]:
        raise ValueError("Runtime manifest checksum mismatch")
    manifest = json.loads(raw_manifest)
    config_bytes = read("blobs/" + manifest["config"]["digest"])
    if "sha256:" + hashlib.sha256(config_bytes).hexdigest() != manifest["config"]["digest"]:
        raise ValueError("Runtime config checksum mismatch")
    config = json.loads(config_bytes)
    if config["architecture"] != "amd64" or config["os"] != "linux":
        raise ValueError("Runtime architecture mismatch")

    temporary_output = output.with_suffix(".partial")
    try:
        with tempfile.TemporaryDirectory(prefix="tuai-runtime-") as temp, tarfile.open(temporary_output, "w") as archive:
            def add_bytes(name, data):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))

            config_name = manifest["config"]["digest"].split(":", 1)[1] + ".json"
            add_bytes(config_name, config_bytes)
            layer_names = []
            for i, layer in enumerate(manifest["layers"]):
                print(f"Fetching runtime layer {i + 1}/{len(manifest['layers'])} ({layer['size'] // 1024} KiB)", flush=True)
                compressed = Path(temp) / "layer.gz"
                checksum = hashlib.sha256()
                with urllib.request.urlopen(request("blobs/" + layer["digest"]), timeout=120) as response, compressed.open("wb") as dest:
                    for chunk in iter(lambda: response.read(1024 * 1024), b""):
                        checksum.update(chunk)
                        dest.write(chunk)
                if "sha256:" + checksum.hexdigest() != layer["digest"]:
                    raise ValueError("Runtime layer download checksum mismatch")
                uncompressed = Path(temp) / "layer.tar"
                checksum = hashlib.sha256()
                with gzip.open(compressed, "rb") as source, uncompressed.open("wb") as dest:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        checksum.update(chunk)
                        dest.write(chunk)
                if "sha256:" + checksum.hexdigest() != config["rootfs"]["diff_ids"][i]:
                    raise ValueError("Runtime layer content checksum mismatch")
                name = str(i) + "/layer.tar"
                archive.add(uncompressed, arcname=name)
                layer_names.append(name)
            add_bytes("manifest.json", json.dumps([{"Config": config_name, "RepoTags": [image], "Layers": layer_names}]).encode())
        temporary_output.replace(output)
    finally:
        temporary_output.unlink(missing_ok=True)
    output.with_suffix(".json").write_text(json.dumps({"image": image, "platform": "linux/amd64",
        "manifestDigest": descriptor["digest"], "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}, indent=2), encoding="utf-8")
    print(f"Runtime saved: {output}; source digest: {descriptor['digest']}", flush=True)
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(".tools/temurin-25-jre-amd64.tar"))
    parser.add_argument("--image", default="eclipse-temurin:25-jre-jammy")
    options = parser.parse_args()
    fetch(options.output, options.image)
