#!/usr/bin/env python3
"""Fetch Ollama CPU runtime and Qwen3 Embedding 4B assets (Python 3.14+)."""
import concurrent.futures
import copy
import compression.zstd
import hashlib
import json
from pathlib import Path
import tarfile
import time
import urllib.request

root = Path(__file__).resolve().parent.parent / '.tools/embedding-assets'
root.mkdir(exist_ok=True)
model_base = 'https://ollama.com/v2/library/qwen3-embedding/'
with urllib.request.urlopen(model_base + 'manifests/4b', timeout=30) as response:
    manifest_bytes = response.read()
manifest = json.loads(manifest_bytes)
manifest_path = root / 'models/manifests/registry.ollama.ai/library/qwen3-embedding/4b'
manifest_path.parent.mkdir(parents=True, exist_ok=True)
manifest_path.write_bytes(manifest_bytes)

def download(label, url, path, expected=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        with path.open('rb') as source:
            actual = hashlib.file_digest(source, 'sha256').hexdigest()
        if not expected or actual == expected.removeprefix('sha256:'):
            print(label, 'verified cache', flush=True)
            return actual
    partial = path.with_suffix('.partial')
    checksum = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=90) as response, partial.open('wb') as output:
        size = int(response.headers.get('Content-Length', '0'))
        count = 0
        started = last = time.monotonic()
        while chunk := response.read(2**20):
            checksum.update(chunk)
            output.write(chunk)
            count += len(chunk)
            if time.monotonic() - last > 20:
                print(label, f'{count/2**20:.0f}/{size/2**20:.0f} MiB; {count/2**20/(time.monotonic()-started):.1f} MiB/s', flush=True)
                last = time.monotonic()
    actual = checksum.hexdigest()
    if expected and actual != expected.removeprefix('sha256:'):
        raise ValueError(label + ' checksum mismatch')
    partial.replace(path)
    print(label, 'download complete; sha256=' + actual, flush=True)
    return actual

prior = json.loads((root/'receipt.json').read_text()) if (root/'receipt.json').exists() else {}
jobs = [('ollama', 'https://ollama.com/download/ollama-linux-amd64.tar.zst', root/'ollama-linux-amd64.tar.zst', prior.get('runtimeArchiveSha256'))]
for descriptor in [manifest['config'], *manifest['layers']]:
    digest = descriptor['digest']
    jobs.append(('model-' + digest[7:19], model_base + 'blobs/' + digest,
                 root/'models/blobs'/digest.replace(':', '-'), digest))
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
    results = list(executor.map(lambda args: download(*args), jobs))

receipt = {'runtimeArchiveSha256': results[0], 'modelManifestSha256': hashlib.sha256(manifest_bytes).hexdigest(),
           'model': 'qwen3-embedding:4b', 'modelLayers': manifest['layers']}
(root/'receipt.json').write_text(json.dumps(receipt, indent=2))
# Preserve Linux permissions and symlinks in a tar rather than extracting them on Windows.
with compression.zstd.ZstdFile(root/'ollama-linux-amd64.tar.zst') as stream, \
        tarfile.open(fileobj=stream, mode='r|') as archive, tarfile.open(root/'runtime-cpu.tar', 'w') as output:
    for member in archive:
        parts = Path(member.name).parts
        allowed = member.name == 'bin/ollama' or (parts[:2] == ('lib', 'ollama') and len(parts) == 3 and not member.isdir())
        if allowed:
            item = copy.copy(member)
            item.name = 'runtime/' + member.name
            if member.islnk():
                item.linkname = 'runtime/' + member.linkname
            output.addfile(item, archive.extractfile(member) if member.isfile() else None)
            print('runtime file:', member.name, flush=True)
print('READY:', root, flush=True)
