#!/usr/bin/env python3
"""Run an isolated, pre-staged WAN benchmark fixture (root on the remote host)."""
import argparse,json,os,signal,socket,subprocess,time
from pathlib import Path
import yaml

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('action',choices=['start','stop']);p.add_argument('--root',type=Path,required=True)
p.add_argument('--public-ip',required=True);p.add_argument('--client-ip',required=True)
a=p.parse_args();root=a.root.resolve();root.mkdir(parents=True,exist_ok=True)
assert str(root).startswith(('/opt/conet-benchmarks/','/var/snap/docker/common/conet-benchmarks/')), 'Use a dedicated benchmark directory'
os.chdir(root)
process_file=root/'processes.json'
def command(args):return subprocess.check_output([str(x) for x in args],text=True)
def write(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(yaml.safe_dump(value,allow_unicode=True))
def secret(path,value):path.touch(mode=0o600);path.chmod(0o600);path.write_text(value+'\n')
def start(name,args):
    with (root/(name+'.log')).open('ab') as log:
        proc=subprocess.Popen([str(x) for x in args],stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,start_new_session=True,env={**os.environ,'JAVA_OPTS':f'-Xms64m -Xmx512m -Djava.io.tmpdir={root}/tmp','CONET_PUBLIC_HOST':a.public_ip})
    processes[name]={'pid':proc.pid,'start':Path(f'/proc/{proc.pid}/stat').read_text().split()[21]}
    process_file.write_text(json.dumps(processes))
def wait(port):
    for _ in range(180):
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=.3):return
        except OSError:time.sleep(.5)
    raise RuntimeError('Service failed on port '+str(port))
# Rules affect only this fixture's new public ports; retain explicit rule list for removal.
rules=[['-i','lo','-p','tcp','-m','multiport','--dports','28720,28731:28733,28740:28759','-m','comment','--comment','conet-check-dams-benchmark','-j','ACCEPT'],['-p','tcp','-m','multiport','--dports','28720,28731:28733,28740:28759','-s',a.client_ip,'-m','comment','--comment','conet-check-dams-benchmark','-j','ACCEPT'],['-p','tcp','-m','multiport','--dports','28720,28731:28733,28740:28759','-m','comment','--comment','conet-check-dams-benchmark','-j','DROP']]
if a.action=='stop':
    records=list(json.loads(process_file.read_text()).values()) if process_file.exists() else []
    for rec in records:
        pid=rec['pid'];stat=Path(f'/proc/{pid}/stat')
        if stat.exists() and stat.read_text().split()[21]==rec['start']:os.killpg(pid,signal.SIGTERM)
    deadline=time.monotonic()+30
    for rec in records:
        stat=Path(f"/proc/{rec['pid']}/stat")
        while stat.exists() and stat.read_text().split()[2]!='Z' and stat.read_text().split()[21]==rec['start']:
            if time.monotonic()>deadline:raise RuntimeError('A benchmark process is still stopping; registry retained')
            time.sleep(.1)
    for rule in rules:subprocess.run(['iptables','-D','INPUT',*rule],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    process_file.unlink(missing_ok=True);print('Stopped only registered benchmark processes and rules');raise SystemExit
assert not process_file.exists(),'Stop the existing fixture first'
for port in [28701,28702,28703,28710,28711,28712,28713,28714,28720,28731,28732,28733]:
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);sock.bind(('0.0.0.0',port))
# Insert DROP first, then ACCEPT ahead of it. Existing services have disjoint ports.
for rule in reversed(rules):subprocess.check_call(['iptables','-I','INPUT','1',*rule])
tls=root/'tls';tls.mkdir(exist_ok=True)
def openssl(*args):subprocess.run(['openssl',*map(str,args)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
if not (tls/'ca.pem').exists():openssl('req','-x509','-newkey','rsa:2048','-nodes','-days','30','-keyout',tls/'ca.key','-out',tls/'ca.pem','-subj','/CN=CONET benchmark CA')
(tls/'extensions.cnf').write_text('subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth\n')
for name in ['a','b','c','union']:
    if not (tls/(name+'.pem')).exists():
        openssl('req','-newkey','rsa:2048','-nodes','-keyout',tls/(name+'.key'),'-out',tls/(name+'.csr'),'-subj','/CN=benchmark-'+name)
        openssl('x509','-req','-in',tls/(name+'.csr'),'-CA',tls/'ca.pem','-CAkey',tls/'ca.key','-CAcreateserial','-days','30','-extfile',tls/'extensions.cnf','-out',tls/(name+'.pem'))
for path in tls.glob('*.key'):path.chmod(0o600)
ports=dict(a=28701,b=28702,c=28703,union=28710);webs=dict(a=28711,b=28712,c=28713,union=28714)
def base(name):return dict(host='127.0.0.1',port=ports[name],tls_cert=str(tls/(name+'.pem')),tls_key=str(tls/(name+'.key')),tls_ca=str(tls/'ca.pem'),credential_directory=str(root/(name+'-credentials')),web=dict(host='127.0.0.1',port=webs[name]),s3=dict(enabled=False))
u=base('union');u.update(public_url='dacp://127.0.0.1:28710',state_directory=str(root/'union-state'),poll_interval_ms=500,execution_timeout_seconds=1200,retention_seconds=3600,members=[])
write(root/'union.yaml',u)
identity=json.loads(command([root/'union/bin/union-admin','identity',root/'union.yaml']))
centers=[]
for name,dataset in [('a','terrain'),('b','imagery'),('c','channels')]:
    token=root/(name+'-union.token')
    if not token.exists():
        grant=json.loads(command([root/'node/bin/dacp-admin','grant-union',root/(name+'-credentials'),'--subject','conet-union','--union-id',identity['union_id'],'--fingerprint',identity['scheduler_identity']]))
        secret(token,grant['token'])
    u['members'].append(dict(url=f'dacp://127.0.0.1:{ports[name]}',credential_file=str(token)))
    desc=dict(title=dataset,description='Synthetic check-dam benchmark source',case='check-dams',center=name,dataset=dataset,synthetic=True,publication=dict(policy='public'))
    n=base(name);n.update(public_host='127.0.0.1',local_operator_library=str(root/'operators'),operator_cache=str(root/(name+'-cache')),max_concurrent_tasks=16,datasets={dataset:dict(provider='directory',description=json.dumps(desc),source=dict(root=str(root/'data'/name)))},remote_operator_library_urls=[],execution_store=dict(provider='file',settings=dict(directory=str(root/(name+'-executions')))),union=dict(state_directory=str(root/(name+'-union')),execution_timeout_seconds=1200),access_log=dict(directory=str(root/(name+'-access'))))
    write(root/(name+'.yaml'),n)
    centers.append(dict(id=name,title=dataset,datasets=[dict(case='check-dams',name=dataset,title=dataset,description=desc['description'])]))
write(root/'union.yaml',u)
app_token=root/'app.token'
if not app_token.exists():secret(app_token,json.loads(command([root/'union/bin/union-admin','create-account',root/'union-credentials','--subject','conet-benchmark','--role','runner']))['token'])
connection=dict(union_url=u['public_url'],ca=str(tls/'ca.pem'),token_file=str(app_token),platform='CONET',node_urls={n:f'dacp://127.0.0.1:{ports[n]}' for n in 'abc'},web_urls={n:f'http://127.0.0.1:{webs[n]}' for n in webs})
conet=root/'conet';network=conet/'.state/network';network.mkdir(parents=True,exist_ok=True)
(network/'connection.json').write_text(json.dumps(connection))
case=conet/'.state/cases/check-dams';(case/'data').mkdir(parents=True,exist_ok=True)
(case/'data/manifest.json').write_bytes((root/'data/manifest.json').read_bytes());(case/'connection.json').write_text(json.dumps(connection))
write(conet/'conet.yaml',dict(name='CONET benchmark',host='0.0.0.0',port=28720,cases=[dict(id='check-dams',title='山洪沟道淤地坝识别',description='Remote benchmark',directory='check-dams')],centers=centers,hosting=dict(enabled=False),appbuilder=dict(execution=dict(max_running=1,max_queued=2,min_free_bytes=1073741824))))
(root/'tmp').mkdir(exist_ok=True)
processes={}
for name in 'abc':start(name,[root/'node/bin/dacp-server',root/(name+'.yaml')])
for name in 'abc':wait(ports[name])
start('union',[root/'union/bin/dacp-union-server','serve',root/'union.yaml']);wait(28710)
start('conet',[conet/'target/universal/stage/bin/conet','--root',conet,'serve']);wait(28720)
empty=root/'ftp-empty';empty.mkdir(exist_ok=True)
for i,name in enumerate('abc'):
    options=dict(listen='YES',listen_ipv6='NO',listen_address='0.0.0.0',listen_port=28731+i,background='NO',anonymous_enable='YES',no_anon_password='YES',local_enable='NO',write_enable='NO',ftp_username='bin',nopriv_user='nobody',anon_root=str(root/'data'/name),secure_chroot_dir=str(empty),pasv_enable='YES',port_enable='NO',anon_world_readable_only='YES',xferlog_enable='NO',syslog_enable='NO',ssl_enable='NO',pasv_address=a.public_ip,pasv_min_port=28740+i*6,pasv_max_port=28745+i*6)
    config=root/('ftp-'+name+'.conf');config.write_text(''.join(f'{k}={v}\n' for k,v in options.items()))
    start('ftp-'+name,[root/'vsftpd',config]);wait(28731+i)
print('Fixture ready: HTTP 28720; read-only FTP 28731–28733 (client IP restricted)')
