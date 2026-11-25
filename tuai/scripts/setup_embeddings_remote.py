#!/usr/bin/env python3
"""Install staged CPU embeddings on the existing 6.162 RKE2 node."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

from remote_release import apply, preload


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def read_resource(kind, name):
    return json.loads(run(['kubectl', '-n', 'tuai', 'get', kind, name, '-o', 'json'],
                          capture_output=True, text=True).stdout)


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(2**20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    args = parser.parse_args()
    release = Path(args.release).resolve()
    receipt = json.loads((release/'receipt.json').read_text())
    expected_runtime = (release/'runtime-cpu.sha256').read_text().strip()
    if sha256(release/'runtime-cpu.tar') != expected_runtime:
        raise ValueError('CPU runtime archive checksum mismatch')
    model_path = Path('/var/lib/tuai/ollama/models')
    manifest_path = model_path/'manifests/registry.ollama.ai/library/qwen3-embedding/4b'
    if sha256(manifest_path) != receipt['modelManifestSha256']:
        raise ValueError('Model manifest checksum mismatch')
    manifest = json.loads(manifest_path.read_text())
    for descriptor in [manifest['config'], *manifest['layers']]:
        path = model_path/'blobs'/descriptor['digest'].replace(':', '-')
        if sha256(path) != descriptor['digest'].split(':')[1]:
            raise ValueError('Model blob checksum mismatch')
    run(['tar', '-xf', release/'runtime-cpu.tar', '-C', release])
    for path in model_path.rglob('*'):
        path.chmod(0o755 if path.is_dir() else 0o644)
        os.chown(str(path), 10001, 10001)
    model_path.chmod(0o755)
    os.chown(str(model_path), 10001, 10001)

    image = '192.168.6.162:5000/tuai/embeddings:cpu-' + release.name
    env = os.environ.copy()
    env['DOCKER_BUILDKIT'] = '0'
    run(['docker', 'build', '--pull=false', '-f', release/'embeddings.Dockerfile', '-t', image, release], env=env)
    # Verify the CPU runtime and its glibc compatibility before changing Kubernetes.
    run(['docker', 'run', '--rm', '--network', 'none', image, '--version'])
    run(['docker', 'push', image])
    inspection = json.loads(run(['docker', 'image', 'inspect', image], capture_output=True, text=True).stdout)[0]
    digest = next(d for d in inspection['RepoDigests'] if d.startswith('192.168.6.162:5000/tuai/embeddings@'))
    preload(digest)
    resources = json.loads((release/'embeddings.kubernetes.json').read_text())['items']
    deployment = next(item for item in resources if item['kind']=='Deployment')
    deployment['spec']['template']['spec']['containers'][0]['image'] = digest
    for resource in resources:
        apply(resource)
    run(['kubectl', '-n', 'tuai', 'rollout', 'status', 'deployment/tuai-embeddings', '--timeout=50s'])

    # Probe through the service from the node; do not expose a second public port.
    service = read_resource('service', 'tuai-embeddings')
    base = 'http://' + service['spec']['clusterIP'] + ':11434'
    body = json.dumps({'model':'qwen3-embedding:4b', 'input':['TuAI knowledge base readiness check'],
                       'dimensions':1536, 'encoding_format':'float'}).encode()
    started = time.monotonic()
    request = urllib.request.Request(base+'/v1/embeddings', data=body, headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request, timeout=120) as response:
        vectors = json.load(response)['data']
    if len(vectors)!=1 or len(vectors[0]['embedding'])!=1536:
        raise ValueError('Embedding service did not return 1536 dimensions')
    print('Embedding verified: 1536 dimensions, %.2fs cold request' % (time.monotonic()-started), flush=True)

    # Preserve the running database, credentials and all unrelated configuration.
    backup = release/'before-config'
    backup.mkdir(mode=0o700, exist_ok=True)
    for kind, name in [('configmap','tuai-config'), ('deployment','tuai')]:
        path = backup/(kind+'.json')
        path.write_text(json.dumps(read_resource(kind,name), indent=2))
        path.chmod(0o600)
    overrides = {'EMBEDDING_BASE_URL':'http://tuai-embeddings.tuai.svc.cluster.local:11434/v1',
                 'EMBEDDING_MODEL':'qwen3-embedding:4b', 'EMBEDDING_DIMENSIONS':'1536',
                 'EMBEDDING_SEND_DIMENSIONS':'true', 'EMBEDDING_BATCH_SIZE':'2', 'EMBEDDING_TIMEOUT_SECONDS':'60'}
    run(['kubectl', '-n', 'tuai', 'patch', 'configmap', 'tuai-config', '--type=merge', '--patch-file=/dev/stdin'],
        input=json.dumps({'data':overrides}), text=True)
    run(['kubectl', '-n', 'tuai', 'rollout', 'restart', 'deployment/tuai'])
    run(['kubectl', '-n', 'tuai', 'rollout', 'status', 'deployment/tuai', '--timeout=50s'])
    receipt.update({'image':digest, 'overrides':overrides})
    (release/'installed.json').write_text(json.dumps(receipt, indent=2))
    print('Installed local embeddings; MCP endpoint unchanged.', flush=True)


if __name__ == '__main__':
    main()
