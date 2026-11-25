#!/usr/bin/env python3
"""Compile React, build an Nginx static image on 6.162, push and preload into RKE2."""
import argparse
from datetime import datetime
import hashlib
import json
import tarfile

from deployment import ROOT, npm, prepare_release, remote, run, upload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='root@192.168.6.162')
    parser.add_argument('--base-image', default='nginx:latest', help='Existing server-side image; resolved to an immutable digest')
    parser.add_argument('--skip-install', action='store_true', help='Use already installed lockfile dependencies')
    args = parser.parse_args()
    if not args.skip_install:
        npm('ci', '--prefer-offline', '--no-audit', '--no-fund')
    npm('test')
    npm('run', 'build')
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
    release = '/root/tuai/ui/releases/' + stamp
    prepare_release(args.host, release)
    tools = ROOT/'.tools'
    tools.mkdir(exist_ok=True)
    archive = tools/'ui-dist.tar.gz'
    with tarfile.open(archive, 'w:gz') as bundle:
        bundle.add(ROOT/'dist', arcname='dist')
    for source, name in [(archive,'ui-dist.tar.gz'), (ROOT/'Dockerfile','Dockerfile'),
                         (ROOT/'nginx.conf','nginx.conf'), (ROOT/'scripts/remote_ui.py','remote_ui.py')]:
        upload(args.host, source, release+'/'+name)
    remote(args.host, ['python3', '-u', release+'/remote_ui.py', 'build', '--release', release,
                      '--base-image', args.base_image, '--image', '192.168.6.162:5000/tuai/ui:0.1.0-'+stamp])
    receipt = tools/'image.json'
    run(['scp', '-q', args.host+':'+release+'/image.json', receipt])
    data = json.loads(receipt.read_text())
    data.update({'host':args.host, 'release':release, 'distArchiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest()})
    receipt.write_text(json.dumps(data, indent=2)+'\n', encoding='utf-8')
    print('Image ready:', data['digest'])
    print('Receipt:', receipt)


if __name__ == '__main__':
    main()
