#!/usr/bin/env python3
"""Add/update Nginx in the existing TuAI Pod and expose it on an all-source NodePort."""
import argparse
import json
from pathlib import Path

from deployment import ROOT, remote, upload


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--receipt', type=Path, default=ROOT/'.tools/image.json')
    parser.add_argument('--node-port', type=int, default=30882)
    args = parser.parse_args()
    if not 30000 <= args.node_port <= 32767:
        parser.error('--node-port must be 30000..32767')
    receipt = json.loads(args.receipt.read_text(encoding='utf-8'))
    host, release = receipt['host'], receipt['release']
    upload(host, ROOT/'scripts/remote_ui.py', release+'/remote_ui.py')
    remote(host, ['python3', '-u', release+'/remote_ui.py', 'deploy', '--release', release,
                  '--image', receipt['digest'], '--node-port', args.node_port])
    print('UI: http://192.168.6.162:'+str(args.node_port))


if __name__ == '__main__':
    main()
