"""Local SSH and npm helpers. No secrets are sent in command-line arguments."""
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def remote(host, args, **kwargs):
    return run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', host,
                ' '.join(shlex.quote(str(arg)) for arg in args)], **kwargs)


def upload(host, source, destination):
    return run(['scp', '-q', source, host + ':' + destination])


def prepare_release(host, release):
    if not re.fullmatch(r'/[A-Za-z0-9_./-]+', release) or '..' in release.split('/'):
        raise ValueError('Invalid remote release directory')
    remote(host, ['python3', '-c', 'import pathlib,os;p=pathlib.Path('+repr(release)+');p.mkdir(parents=True,exist_ok=True);os.chmod(str(p),0o700)'])


def npm(*args):
    pathfile = ROOT/'.tools/node-path.txt'
    env = os.environ.copy()
    if os.name == 'nt' and pathfile.exists():
        directory = Path(pathfile.read_text().strip())
        command = [directory/'node.exe', directory/'node_modules/npm/bin/npm-cli.js']
        env['PATH'] = str(directory) + os.pathsep + env['PATH']
    else:
        executable = shutil.which('npm.cmd' if os.name=='nt' else 'npm')
        if not executable:
            raise RuntimeError('Install Node.js 22.12+ and npm first')
        command = [executable]
    return run([*command, *args], cwd=ROOT, env=env)
