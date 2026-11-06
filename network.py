#!/usr/bin/env python3
"""Create and manage CONET's configured multi-center DACP/Union network."""
import json
import os
import re
from pathlib import Path
import socket
import subprocess
import sys
import time
import yaml

HERE=Path(__file__).resolve().parent
ROOT=HERE
DACP_HOME=Path(os.environ.get('DACP_HOME', str(HERE.parent/'dacp-codex'))).resolve()
from dacp import Source

STATE=ROOT/'.state/network'
NODE=DACP_HOME/'dacp-server/target/universal/stage/bin/dacp-server'
ADMIN=NODE.with_name('dacp-admin')
UNION=DACP_HOME/'dacp-union-server/target/universal/stage/bin/dacp-union-server'
UNION_ADMIN=UNION.with_name('union-admin')


def local(program,*args):
    return json.loads(subprocess.check_output([str(program),*map(str,args)],cwd=ROOT,text=True))


def secret(path,text):
    path.write_text(text+'\n');path.chmod(0o600)


def free_port():
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));return sock.getsockname()[1]


def write_yaml(path,data):path.write_text(yaml.safe_dump(data,sort_keys=False))


CONFIG=yaml.safe_load((ROOT/'conet.yaml').read_text())

def case_state(case):return ROOT/'.state/cases'/case['id']

def build_operators():
    library=STATE/'operators';library.mkdir(parents=True,exist_ok=True)
    for case in CONFIG['cases']:
        env=dict(os.environ,CONET_CASE_STATE=str(case_state(case)))
        subprocess.run([sys.executable,'-c','import demo;demo.build_operators()'],cwd=ROOT/case['directory'],env=env,check=True)
        for operator in (case_state(case)/'operators').iterdir():
            link=library/operator.name
            if not link.exists():link.symlink_to(operator,target_is_directory=True)
    return library

def datasets_for(center):
    node=next(c for c in CONFIG['centers'] if c['id']==center)
    result={}
    for entry in node['datasets']:
        case=next(c for c in CONFIG['cases'] if c['id']==entry['case'])
        directory=case_state(case)/'data'/entry.get('directory',center)
        if not directory.resolve().is_relative_to((case_state(case)/'data').resolve()):raise ValueError('Dataset directory must remain inside case data')
        if not directory.is_dir():raise ValueError(f'Missing data: {directory}; see generation instructions')
        result[entry['name']]={'provider':'directory','description':json.dumps({'title':entry['title'],'description':entry['description'],'case':case['id'],'center':center,'synthetic':True}),'source':{'root':str(directory)}}
    return result

def save_processes(processes):(STATE/'processes.json').write_text(json.dumps(processes,indent=2))


def start(program,config,processes,name):
    with (STATE/(name+'.log')).open('ab') as log:
        p=subprocess.Popen(['sudo','-n',str(program)]+(['serve'] if program==UNION else [])+[str(config)],cwd=ROOT,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    processes[name]=p.pid;save_processes(processes)


def stop():
    path=STATE/'processes.json'
    if not path.exists():return
    processes=json.loads(path.read_text());remaining={}
    for name,pid in processes.items():
        proc=Path(f'/proc/{pid}/cmdline')
        if not proc.exists():continue
        command=proc.read_bytes().replace(b'\0',b' ').decode()
        if str(STATE) not in command:
            print('Refusing to stop unrelated PID',pid,file=sys.stderr);remaining[name]=pid;continue
        subprocess.run(['sudo','-n','kill','-TERM','--','-'+str(pid)],check=False)
        for _ in range(100):
            if not proc.exists() or Path(f'/proc/{pid}/stat').read_text().split()[2]=='Z':break
            time.sleep(.1)
        else:
            print('Process still stopping:',name,file=sys.stderr);remaining[name]=pid
    save_processes(remaining)


def up():
    if (STATE/'processes.json').exists() and json.loads((STATE/'processes.json').read_text()):raise RuntimeError('Case already has processes; run down first')
    STATE.mkdir(parents=True,exist_ok=True)
    subprocess.run([sys.executable,str(ROOT/'scripts/generate_reference_data.py')],check=True)
    library=build_operators()
    for image in ['xstream/check-dams-demo:1.0','xstream/observatory-demo:1.0']:
        subprocess.run(['sudo','-n','docker','image','inspect',image],check=True,stdout=subprocess.DEVNULL)
    tls=STATE/'tls';tls.mkdir(exist_ok=True)
    def openssl(*args):
        subprocess.run(['openssl',*map(str,args)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if not (tls/'ca.pem').exists():
        openssl('req','-x509','-newkey','rsa:2048','-nodes','-days','365','-keyout',tls/'ca.key','-out',tls/'ca.pem','-subj','/CN=DACP synthetic case CA')
    (tls/'extensions.cnf').write_text('subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth\n')
    center_ids=[c['id'] for c in CONFIG['centers']]
    if not center_ids or len(set(center_ids))!=len(center_ids) or any(not re.fullmatch(r'[a-z][a-z0-9-]*',c) or c=='union' for c in center_ids):
        raise ValueError('Centers require unique lowercase identifiers; union is reserved')
    for name in [*center_ids,'union']:
        if not (tls/(name+'.pem')).exists():
            openssl('req','-newkey','rsa:2048','-nodes','-keyout',tls/(name+'.key'),'-out',tls/(name+'.csr'),'-subj','/CN=synthetic-'+name)
            openssl('x509','-req','-in',tls/(name+'.csr'),'-CA',tls/'ca.pem','-CAkey',tls/'ca.key','-CAcreateserial','-days','365','-extfile',tls/'extensions.cnf','-out',tls/(name+'.pem'))
    for key in tls.glob('*.key'):key.chmod(0o600)
    ports={name:free_port() for name in [*center_ids,'union']};web={name:free_port() for name in ports}
    identity_config=dict(host='127.0.0.1',port=ports['union'],public_url=f"dacp://127.0.0.1:{ports['union']}",
        tls_cert=str(tls/'union.pem'),tls_key=str(tls/'union.key'),tls_ca=str(tls/'ca.pem'),state_directory=str(STATE/'union'),
        credential_directory=str(STATE/'union-credentials'),poll_interval_ms=500,execution_timeout_seconds=300,retention_seconds=3600,
        web={'host':'0.0.0.0','port':web['union']},members=[],s3={'enabled':False})
    write_yaml(STATE/'union.yaml',identity_config);identity=local(UNION_ADMIN,'identity',STATE/'union.yaml')
    for center in CONFIG['centers']:
        title=center['title'];center=center['id']
        store=STATE/(center+'-credentials')
        grant=local(ADMIN,'grant-union',store,'--subject','conet-union','--union-id',identity['union_id'],'--fingerprint',identity['scheduler_identity'])
        tokenfile=STATE/(center+'-union.token');secret(tokenfile,grant['token'])
        identity_config['members'].append({'url':f"dacp://127.0.0.1:{ports[center]}",'credential_file':str(tokenfile)})
        node=dict(host='127.0.0.1',public_host='127.0.0.1',port=ports[center],tls_cert=str(tls/(center+'.pem')),tls_key=str(tls/(center+'.key')),tls_ca=str(tls/'ca.pem'),
          credential_directory=str(store),datasets=datasets_for(center),
          local_operator_library=str(library),remote_operator_library_urls=[],operator_cache=str(STATE/(center+'-cache')),
          max_concurrent_tasks=16,web={'host':'0.0.0.0','port':web[center]},s3={'enabled':False},
          execution_store={'provider':'file','settings':{'directory':str(STATE/(center+'-executions'))}},
          union={'state_directory':str(STATE/(center+'-union')),'execution_timeout_seconds':300},access_log={'directory':str(STATE/(center+'-access'))})
        write_yaml(STATE/(center+'.yaml'),node)
    write_yaml(STATE/'union.yaml',identity_config)
    if not (STATE/'app.token').exists():
        account=local(UNION_ADMIN,'create-account',STATE/'union-credentials','--subject','conet-app','--role','runner')
        secret(STATE/'app.token',account['token'])
    processes={}
    try:
        for center in center_ids:start(NODE,STATE/(center+'.yaml'),processes,center)
        for center in center_ids:
            for _ in range(200):
                try:
                    with socket.create_connection(('127.0.0.1',ports[center]),timeout=.2):break
                except OSError:time.sleep(.1)
            else:raise RuntimeError('Node startup failed: '+center)
        start(UNION,STATE/'union.yaml',processes,'union')
        connection={'union_url':identity_config['public_url'],'ca':str(tls/'ca.pem'),'token_file':str(STATE/'app.token'),
                    'node_urls':{k:f'dacp://127.0.0.1:{v}' for k,v in ports.items() if k!='union'},
                    'web_urls':{k:f'http://{public_host()}:{v}' for k,v in web.items()},'platform':'CONET'}
        (STATE/'connection.json').write_text(json.dumps(connection,indent=2)+'\n')
        for _ in range(100):
            try:
                if len(catalog(connection))==sum(len(c['datasets']) for c in CONFIG['centers']):break
            except Exception:pass
            time.sleep(.5)
        else:raise RuntimeError('Union members not ready; see isolated service logs')
        print(json.dumps(connection,indent=2))
    except BaseException:stop();raise


def catalog(connection):
    return Source.datasets(connection['union_url'],tls_root_certs=Path(connection['ca']).read_bytes(),token=Path(connection['token_file']).read_text().strip())


def public_host():
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        sock.connect(('10.0.0.1',80));return sock.getsockname()[0]
