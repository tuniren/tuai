#!/usr/bin/env python3
"""Deploy downloaded Qwen3/Ollama assets on 6.162 and configure the current TuAI service."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path

from deployment import ROOT, prepare_release, remote, upload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='root@192.168.6.162')
    parser.add_argument('--assets', type=Path, default=ROOT/'.tools/embedding-assets')
    parser.add_argument('--resume-release', help='Reuse assets already uploaded to this remote release directory')
    args = parser.parse_args()
    assets = args.assets.resolve()
    if not (assets/'receipt.json').is_file():
        parser.error('Run python scripts/fetch_embeddings.py first (Python 3.14+)')
    release = args.resume_release or '/root/tuai/embeddings/' + datetime.now().strftime('%Y%m%d-%H%M%S')
    prepare_release(args.host, release)
    # Models persist independently of Pod/container lifetime. Blobs are content-addressed.
    model_path = '/var/lib/tuai/ollama/models'
    for path in ([] if args.resume_release else sorted((assets/'models').rglob('*'))):
        if path.is_file():
            destination = model_path + '/' + path.relative_to(assets/'models').as_posix()
            prepare_release(args.host, str(Path(destination).parent).replace('\\','/'))
            upload(args.host, path, destination)
    runtime = assets/'runtime-cpu.tar'
    checksum = hashlib.sha256()
    with runtime.open('rb') as source:
        for chunk in iter(lambda: source.read(2**20), b''):
            checksum.update(chunk)
    (assets/'runtime-cpu.sha256').write_text(checksum.hexdigest())
    if not args.resume_release:
        for source in [runtime, assets/'runtime-cpu.sha256', assets/'receipt.json']:
            upload(args.host, source, release+'/'+source.name)
    for source, name in [(ROOT/'deploy/embeddings.Dockerfile','embeddings.Dockerfile'),
                         (ROOT/'deploy/embeddings.kubernetes.json','embeddings.kubernetes.json'),
                         (ROOT/'scripts/setup_embeddings_remote.py','setup_embeddings_remote.py'),
                         (ROOT/'scripts/remote_release.py','remote_release.py')]:
        upload(args.host, source, release+'/'+name)
    remote(args.host, ['python3', '-u', release+'/setup_embeddings_remote.py', '--release', release])
    # Keep future ordinary releases from accidentally restoring the unavailable external provider.
    path = ROOT/'deploy/settings.local.json'
    settings = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    settings.update({'EMBEDDING_BASE_URL':'http://tuai-embeddings.tuai.svc.cluster.local:11434/v1',
                     'EMBEDDING_MODEL':'qwen3-embedding:4b', 'EMBEDDING_DIMENSIONS':'1536',
                     'EMBEDDING_SEND_DIMENSIONS':'true', 'EMBEDDING_BATCH_SIZE':'2',
                     'EMBEDDING_TIMEOUT_SECONDS':'60', 'EMBEDDING_API_KEY':''})
    path.write_text(json.dumps(settings, indent=2)+'\n', encoding='utf-8')
    print('Saved deployment overrides:', path)
    print('Remote release:', release)


if __name__ == '__main__':
    main()
