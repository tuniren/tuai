#!/usr/bin/env python3
"""Remote Docker/RKE2 release helper. Python 3.7+; preserves the running TuAI backend."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time
import urllib.error
import urllib.request


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def output(args):
    return run(args, capture_output=True, text=True).stdout


def preload(image):
    result = run(['/hci/platform/rke2/data/rke2/bin/ctr', '--address', '/run/k3s/containerd/containerd.sock',
                  '--namespace', 'k8s.io', 'images', 'pull', '--plain-http', '--platform', 'linux/amd64', image],
                 capture_output=True, text=True)
    print('Immutable image preloaded into RKE2.', flush=True)


def build(args):
    release = Path(args.release)
    with tarfile.open(release/'ui-dist.tar.gz') as archive:
        for member in archive.getmembers():
            if not member.name.startswith('dist') or '..' in Path(member.name).parts or member.name.startswith('/') or member.issym() or member.islnk():
                raise ValueError('Invalid static archive member')
        archive.extractall(release)
    base = json.loads(output(['docker', 'image', 'inspect', args.base_image]))[0]
    base_digest = base['RepoDigests'][0]
    env = os.environ.copy()
    env['DOCKER_BUILDKIT'] = '0'
    run(['docker', 'build', '--pull=false', '--build-arg', 'NGINX_IMAGE='+base_digest,
         '-t', args.image, release], env=env)
    run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp:uid=10001,gid=10001', args.image, '-t'])
    run(['docker', 'push', args.image])
    inspection = json.loads(output(['docker', 'image', 'inspect', args.image]))[0]
    digest = next(d for d in inspection['RepoDigests'] if d.startswith('192.168.6.162:5000/tuai/ui@'))
    preload(digest)
    (release/'image.json').write_text(json.dumps({'image':args.image, 'digest':digest, 'baseImage':base_digest,
                                                'builtAt':datetime.now().isoformat()}, indent=2)+'\n')


def apply(resource):
    # A distinct manager owns the sidecar, leaving backend release fields intact.
    result = subprocess.run(['kubectl', 'apply', '--server-side', '--field-manager=tuai-ui-release', '-f', '-'],
                            input=json.dumps(resource), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Kubernetes apply failed: '+result.stderr[-2000:])
    print(result.stdout.strip(), flush=True)


def get(kind, name):
    return json.loads(output(['kubectl', '-n', 'tuai', 'get', kind, name, '-o', 'json']))


def verify_public_endpoint(base):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    message={'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':'kb_list_documents','arguments':{'namespace':'tuai','limit':1}}}
    # A ready Pod may precede the NodePort endpoint update by a few seconds.
    for attempt in range(6):
        try:
            with opener.open(base,timeout=5) as response:
                if '<title>TuAI' not in response.read().decode(): raise RuntimeError('Unexpected UI response')
            request=urllib.request.Request(base+'/mcp',data=json.dumps(message).encode(),headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream','Origin':base})
            with opener.open(request,timeout=5) as response:
                result=json.load(response)
                if 'error' in result or result['result'].get('isError'):raise RuntimeError('MCP proxy validation failed')
            with opener.open(base+'/api/namespaces',timeout=5) as response:
                if not isinstance(json.load(response),list):raise RuntimeError('Namespace API proxy validation failed')
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt==5: raise
            print('Waiting for the public NodePort endpoint...',flush=True)
            time.sleep(2)


def deploy(args):
    release = Path(args.release)
    existing = get('deployment', 'tuai')
    if not any(c['name']=='tuai' for c in existing['spec']['template']['spec']['containers']):
        raise RuntimeError('Expected backend container not found')
    services = json.loads(output(['kubectl', 'get', 'services', '-A', '-o', 'json']))['items']
    for service in services:
        if service['metadata']['namespace']=='tuai' and service['metadata']['name']=='tuai-ui':
            continue
        if any(p.get('nodePort')==args.node_port for p in service['spec'].get('ports',[])):
            raise RuntimeError('Requested NodePort is already allocated')
    preload(args.image)
    backup = release/('before-deploy-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
    backup.mkdir(mode=0o700)
    for kind, name in [('deployment','tuai'), ('service','tuai-ui'), ('networkpolicy','tuai-ui-allow-all-clients')]:
        result = subprocess.run(['kubectl','-n','tuai','get',kind,name,'-o','json'],capture_output=True,text=True)
        if result.returncode==0:
            path=backup/(kind+'.json'); path.write_text(result.stdout); path.chmod(0o600)
    sidecar = {
        'name':'tuai-ui', 'image':args.image, 'imagePullPolicy':'IfNotPresent',
        'ports':[{'name':'ui-http','containerPort':8081}],
        'resources':{'requests':{'cpu':'50m','memory':'32Mi'},'limits':{'cpu':'1','memory':'128Mi'}},
        'startupProbe':{'httpGet':{'path':'/healthz','port':'ui-http'},'periodSeconds':5,'failureThreshold':12},
        'readinessProbe':{'httpGet':{'path':'/healthz','port':'ui-http'},'periodSeconds':10},
        'livenessProbe':{'httpGet':{'path':'/healthz','port':'ui-http'},'periodSeconds':20},
        'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001,'allowPrivilegeEscalation':False,
                           'readOnlyRootFilesystem':True,'capabilities':{'drop':['ALL']}},
        'volumeMounts':[{'name':'ui-tmp','mountPath':'/tmp'}],
    }
    # A partial apply merges the associative container/volume lists by name. It neither
    # replaces the Deployment nor takes ownership of the backend container or its image.
    apply({'apiVersion':'apps/v1','kind':'Deployment','metadata':{'name':'tuai','namespace':'tuai'},
           'spec':{'template':{'spec':{'containers':[sidecar], 'volumes':[{'name':'ui-tmp','emptyDir':{'sizeLimit':'64Mi'}}]}}}})
    apply({'apiVersion':'v1','kind':'Service','metadata':{'name':'tuai-ui','namespace':'tuai'},
           'spec':{'type':'NodePort','externalTrafficPolicy':'Cluster','selector':{'app':'tuai'},
                   'ports':[{'name':'http','port':8081,'targetPort':'ui-http','nodePort':args.node_port,'protocol':'TCP'}]}})
    apply({'apiVersion':'networking.k8s.io/v1','kind':'NetworkPolicy','metadata':{'name':'tuai-ui-allow-all-clients','namespace':'tuai'},
           'spec':{'podSelector':{'matchLabels':{'app':'tuai'}},'policyTypes':['Ingress'],
                   'ingress':[{'ports':[{'protocol':'TCP','port':8081}]}]}})
    if shutil.which('firewall-cmd'):
        active=subprocess.run(['firewall-cmd','--state'],capture_output=True)
        if active.returncode==0:
            run(['firewall-cmd','--add-port='+str(args.node_port)+'/tcp'])
            run(['firewall-cmd','--permanent','--add-port='+str(args.node_port)+'/tcp'])
    run(['kubectl','-n','tuai','rollout','status','deployment/tuai','--timeout=50s'])
    after = get('deployment','tuai')
    before_backend=next(c for c in existing['spec']['template']['spec']['containers'] if c['name']=='tuai')
    after_backend=next(c for c in after['spec']['template']['spec']['containers'] if c['name']=='tuai')
    if before_backend!=after_backend:
        raise RuntimeError('Backend configuration unexpectedly changed')
    base='http://192.168.6.162:'+str(args.node_port)
    verify_public_endpoint(base)
    (release/'deployed.json').write_text(json.dumps({'image':args.image,'nodePort':args.node_port,'deployedAt':datetime.now().isoformat(),'backup':str(backup)},indent=2))
    print('Deployed Nginx in the TuAI Pod; backend unchanged; UI and MCP proxy verified.',flush=True)
    print('Backup:',backup,flush=True)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['build','deploy'])
    parser.add_argument('--release',required=True)
    parser.add_argument('--image',required=True)
    parser.add_argument('--base-image',default='nginx:latest')
    parser.add_argument('--node-port',type=int,default=30882)
    args=parser.parse_args()
    (build if args.action=='build' else deploy)(args)
