#!/usr/bin/env python3
"""Compare the live CONET check-dam app with download-first local XStream."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import ftplib
import json
import math
import os
from pathlib import Path
import platform
import random
import shutil
import signal
import socket
import subprocess
import sys
import time
from urllib.request import Request, urlopen, build_opener, ProxyHandler
import xml.etree.ElementTree as ET
import yaml

ROOT=Path(__file__).resolve().parents[2]


def write_json(path,value):
    tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');tmp.replace(path)


def parse_result(raw):
    tree=ET.fromstring(raw)
    node=next((n for n in tree.iter() if n.tag.rsplit('}',1)[-1]=='metadata' and n.get('id')=='analysis-result'),None)
    if node is None or not node.text:raise ValueError('Missing analysis-result metadata')
    result=json.loads(node.text)
    canonical=json.dumps(result,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()
    return dict(result_sha256=hashlib.sha256(canonical).hexdigest(),svg_sha256=hashlib.sha256(raw).hexdigest(),
        result_bytes=len(raw),valid_sites=len(result['features']),rejected=len(result['rejections']),
        candidate_count=result['candidate_count'],windows_inferred=result['windows_inferred']),result



def compare_analysis(reference, actual):
    """Full structural comparison; narrow tolerances for cross-CPU float kernels.

    Integer IDs, booleans, text, keys, lengths and order remain exact.
    Geographic coordinates: 1e-9 degrees; projected/pixel positions: 1e-4
    native units; confidence: 1e-6; other floating values: 1e-8.
    """
    differences=[]
    def visit(a,b,path):
        if type(a)!=type(b):raise ValueError('Analysis type mismatch at '+path)
        if isinstance(a,dict):
            if a.keys()!=b.keys():raise ValueError('Analysis keys mismatch at '+path)
            for key in a:visit(a[key],b[key],path+'/'+key)
        elif isinstance(a,list):
            if len(a)!=len(b):raise ValueError('Analysis length mismatch at '+path)
            for i,(x,y) in enumerate(zip(a,b)):visit(x,y,path+'/'+str(i))
        elif isinstance(a,float):
            geographic=any(x in path for x in ['/longitude','/latitude']) or ('/geojson/' in path and '/geometry/coordinates/' in path)
            spatial=any(x in path for x in ['/coordinates/','/pixel_box/']) or path.endswith(('/x','/y'))
            tolerance=1e-9 if geographic else 1e-4 if spatial else 1e-6 if '/confidence' in path else 1e-8
            if not math.isfinite(a) or not math.isfinite(b) or abs(a-b)>tolerance:raise ValueError('Analysis numeric mismatch at '+path)
            if a!=b:differences.append(dict(path=path,absolute_difference=abs(a-b),tolerance=tolerance))
        elif a!=b:raise ValueError('Analysis value mismatch at '+path)
    visit(reference,actual,'')
    return dict(policy='cross-cpu-full-structure-v1',different_floats=len(differences),differences=differences)


def plan_observations(plan,node_urls):
    centers={url:center for center,url in node_urls.items()}
    placements=[dict(center=centers.get(sub['node'],'unknown'),steps=sub['steps'],state=sub['state']) for sub in plan['sub_jobs']]
    if {p['center'] for p in placements} != {'a','b','c'}:raise ValueError('Expected the three source centers to participate')
    if any(p['state']!='succeeded' for p in placements):raise ValueError('Incomplete Union subjob')
    outputs=[]
    for snapshot in plan['nodes'].values():
        for port,metrics in snapshot.get('ports',{}).items():
            if '/outputs/' in port:outputs.append(dict(port=port,remote_payload_bytes=metrics.get('remote_payload_bytes',0)))
    if not outputs:raise ValueError('No observed output-port statistics')
    return dict(placements=placements,observed_remote_output_payload_bytes=sum(o['remote_payload_bytes'] for o in outputs),output_ports=outputs,
                union_elapsed_s=plan['elapsed_ms']/1000)


class FtpServers:
    """Read-only isolated loopback FTP endpoints for the existing source directories."""
    def __init__(self,out,binary,sources):
        self.processes=[];self.logs=[];self.ports={};self.out=out
        empty=out/'ftp-empty';empty.mkdir()
        subprocess.run(['sudo','-n','chown','root:root',str(empty)],check=True)
        try:
            for center,root in sources.items():
                with socket.socket() as sock:
                    sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
                self.ports[center]=port
                options=dict(listen='YES',listen_ipv6='NO',listen_address='127.0.0.1',listen_port=port,
                    background='NO',anonymous_enable='YES',no_anon_password='YES',local_enable='NO',
                    write_enable='NO',ftp_username='bin',nopriv_user='nobody',anon_root=str(root),
                    secure_chroot_dir=str(empty),pasv_enable='YES',port_enable='NO',
                    anon_world_readable_only='YES',xferlog_enable='NO',syslog_enable='NO',ssl_enable='NO')
                config=out/('ftp-'+center+'.conf');config.write_text(''.join(f'{k}={v}\n' for k,v in options.items()))
                subprocess.run(['sudo','-n','chown','root:root',str(config)],check=True)
                log=(out/('ftp-'+center+'.log')).open('wb');self.logs.append(log)
                process=subprocess.Popen(['sudo','-n',str(binary),str(config)],stdin=subprocess.DEVNULL,
                        stdout=log,stderr=subprocess.STDOUT,start_new_session=True);self.processes.append(process)
                for _ in range(100):
                    if process.poll() is not None:raise RuntimeError('FTP fixture exited: '+center)
                    try:
                        with socket.create_connection(('127.0.0.1',port),timeout=.2):break
                    except OSError:time.sleep(.1)
                else:raise TimeoutError('FTP startup: '+center)
        except BaseException:self.close();raise
    def close(self):
        for process in self.processes:
            if process.poll() is None:
                subprocess.run(['sudo','-n','kill','-TERM','--','-'+str(process.pid)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                try:process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    subprocess.run(['sudo','-n','kill','-KILL','--','-'+str(process.pid)],check=False);process.wait()
        for log in self.logs:log.close()


class Benchmark:
    def __init__(self,args):
        self.args=args;self.out=args.output.resolve();self.out.mkdir(parents=True,exist_ok=False)
        self.remote_config=json.loads(args.remote_config.read_text()) if getattr(args,'remote_config',None) else None
        self.connection=self.remote_config['connection'] if self.remote_config else json.loads((ROOT/'.state/network/connection.json').read_text())
        self.http=build_opener(ProxyHandler({}))
        self.config=self.api('/apps/check-dams/api/config')
        self.manifest=self.config['manifest'];self.job=self.config['job_yaml']
        self.inputs=self.inputs_for(self.manifest)
        self.sources={}
        for center,dataset,item,key in [('a','terrain','terrain.bundle','TERRAIN_URI'),('b','imagery','imagery.bundle','IMAGERY_URI'),('c','channels','channels.bundle','CHANNEL_GRIDS_URI')]:
            self.sources[key]=dict(center=center,dataset=dataset,item=item,
                **self.manifest['files'][center+'/'+item])
        self.runner_config=dict(local_operator_library=str(ROOT/'.state/network/operators'),
            operator_cache=str(self.out/'operator-cache'),remote_operator_library_urls=[],max_concurrent_tasks=16,
            execution_directory=str(self.out/'executions'),datasets={s['dataset']:dict(provider='directory',source={'root':str(self.out/'downloads'/s['dataset'])}) for s in self.sources.values()})
        # The runner uses exactly the server distribution's engine/executor jars.
        self.lib=args.dacp_home/'dacp-server/target/universal/stage/lib'
        if not self.lib.is_dir():raise ValueError('Build/stage DACP Server before running')
        self.command=['sudo','-n','java','-Xms64m','-Xmx512m','--add-opens=java.base/java.nio=ALL-UNNAMED',
                      '-Dio.netty.tryReflectionSetAccessible=true','-cp',str(self.lib/'*'),'xstream.runner.Main']
        self.baseline_job=self.out/'job.yaml';self.baseline_job.write_text(self.job)
        self.engine_config=self.out/'engine.yaml';self.engine_config.write_text(yaml.safe_dump(self.runner_config))
        variables=dict(REGION=self.inputs['region'],REGION_BBOX=self.inputs['region']['coordinates'][0],MIN_CONFIDENCE=self.inputs['min_confidence'])
        ring=variables['REGION_BBOX'];variables['REGION_BBOX']=[min(p[0] for p in ring),min(p[1] for p in ring),max(p[0] for p in ring),max(p[1] for p in ring)]
        variables.update({k:'context:/'+s['dataset']+'/'+s['item'] for k,s in self.sources.items()})
        self.variables_path=self.out/'variables.yaml';self.variables_path.write_text(yaml.safe_dump(variables))
        self.operator_fingerprints={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'.state/network/operators').glob('check_dam_*/*/*')) if p.is_file()}
        self.source_hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__),ROOT/'cases/check-dams/job.yaml',*sorted((ROOT/'cases/check-dams/operator').glob('*.py'))]}
    @staticmethod
    def inputs_for(manifest):
        w,s,e,n=manifest['tiles'][0]['bbox_wgs84']
        return dict(region={'type':'Polygon','coordinates':[[[w,s],[e,s],[e,n],[w,n],[w,s]]]},min_confidence=.35)
    def api(self,path,payload=None):
        data=None if payload is None else json.dumps(payload).encode()
        with self.http.open(Request(self.args.url.rstrip('/')+path,data,{'Content-Type':'application/json'}),timeout=30) as response:
            raw=response.read()
            return json.loads(raw) if 'json' in response.headers.get('Content-Type','') else raw
    def download(self,key,s):
        path=self.out/'downloads'/s['dataset']/s['item'];path.parent.mkdir(parents=True,exist_ok=True)
        digest=hashlib.sha256();size=0;start=time.perf_counter()
        with ftplib.FTP() as ftp:
            ftp.connect(getattr(self.ftp,'host','127.0.0.1'),self.ftp.ports[s['center']],timeout=self.args.timeout)
            ftp.login('anonymous','benchmark@example.invalid');ftp.voidcmd('TYPE I')
            with path.open('wb') as target:
                def consume(block):
                    nonlocal size
                    target.write(block);digest.update(block);size+=len(block)
                ftp.retrbinary('RETR '+s['item'],consume,blocksize=65536)
                target.flush()
        if size!=s['bytes'] or digest.hexdigest()!=s['sha256']:raise ValueError('Source integrity mismatch: '+key)
        return dict(source=key,protocol='ftp',bytes=size,sha256=digest.hexdigest(),wall_s=time.perf_counter()-start)
    def centralized(self,label):
        directory=self.out/'downloads'
        if directory.exists():shutil.rmtree(directory)
        start=time.perf_counter()
        with ThreadPoolExecutor(max_workers=3) as pool:
            futures=[pool.submit(self.download,k,s) for k,s in self.sources.items()]
            downloads=[f.result() for f in futures]
        download_done=time.perf_counter()
        output=self.out/(label+'-local.svg');log=self.out/(label+'-local.log')
        process=None
        with output.open('wb') as target,log.open('wb') as stderr:
            process=subprocess.Popen(self.command+[str(self.engine_config),str(self.baseline_job),str(self.variables_path)],
                 cwd=ROOT,stdin=subprocess.DEVNULL,stdout=target,stderr=stderr,start_new_session=True)
            try:
                code=process.wait(timeout=self.args.timeout)
                if code:raise RuntimeError('Local engine failed; inspect '+log.name)
                target.flush()
            except BaseException:
                # Target only this run's isolated launcher group; cancellation reaches its engine.
                subprocess.run(['sudo','-n','kill','-TERM','--','-'+str(process.pid)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                try:process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    subprocess.run(['sudo','-n','kill','-KILL','--','-'+str(process.pid)],check=False);process.wait()
                raise
        compute_done=time.perf_counter();raw=output.read_bytes();summary,result=parse_result(raw)
        finished=time.perf_counter();write_json(self.out/(label+'-local-result.json'),result)
        return dict(state='succeeded',method='ftp-download-local',wall_s=finished-start,
                    download_s=download_done-start,compute_s=compute_done-download_done,validation_s=finished-compute_done,
                    downloaded_bytes=sum(d['bytes'] for d in downloads),downloads=downloads,**summary)
    def conet(self,label):
        start=time.perf_counter();job=self.api('/apps/check-dams/api/jobs',self.inputs);run=job['id']
        deadline=time.monotonic()+self.args.timeout
        try:
            while job['state'] in ['queued','running']:
                if time.monotonic()>deadline:raise TimeoutError('CONET analysis timeout')
                time.sleep(.2);job=self.api('/apps/check-dams/api/jobs/'+run)
            completed=time.perf_counter()
            if job['state']!='succeeded':raise RuntimeError('CONET analysis failed: '+job.get('error','unknown'))
            raw=self.api(job['artifacts']['map']);summary,result=parse_result(raw)
            finished=time.perf_counter()
            (self.out/(label+'-conet.svg')).write_bytes(raw);write_json(self.out/(label+'-conet-result.json'),result)
            write_json(self.out/(label+'-conet-run.json'),job)
            job_id=job['job_id']
            try:
                plan=self.api('/api/union-jobs/'+job_id)
                write_json(self.out/(label+'-physical-plan.json'),plan)
                observations=plan_observations(plan,self.connection['node_urls'])
            except Exception as error:raise RuntimeError('Cannot retain actual Union physical plan') from error
            return dict(state='succeeded',method='conet-union',wall_s=finished-start,
                app_completion_s=completed-start,result_fetch_validation_s=finished-completed,
                run_id=run,job_id=job_id,created_at=job['created_at'],started_at=job.get('started_at'),finished_at=job.get('finished_at'),**observations,**summary)
        except BaseException:
            if job['state'] in ['queued','running']:
                try:self.api('/apps/check-dams/api/jobs/'+run+'/cancel',{})
                except Exception:pass
            raise
    def main(self):
        if self.remote_config:
            from types import SimpleNamespace
            self.ftp=SimpleNamespace(**self.remote_config['ftp'])
            return self.run_trials()
        sources={}
        for source in self.sources.values():
            config=yaml.safe_load((ROOT/'.state/network'/(source['center']+'.yaml')).read_text())
            sources[source['center']]=Path(config['datasets'][source['dataset']]['source']['root']).resolve()
        self.ftp=FtpServers(self.out,self.args.vsftpd.resolve(),sources)
        try:return self.run_trials()
        finally:self.ftp.close()
    def run_trials(self):
        jobs=self.api('/apps/check-dams/api/jobs')
        if any(j['state'] in ['queued','running'] for j in jobs):raise RuntimeError('Wait for existing app analyses to finish')
        report=dict(timestamp=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
            config=dict(download_protocol='FTP (unencrypted)',disk_sink='write-and-close; no fsync',repeats=self.args.repeats,warmups=self.args.warmups,seed=20261108,download_workers=3,timeout=self.args.timeout),
            host=dict(platform=platform.platform(),logical_cpus=os.cpu_count(),network=self.remote_config['network'] if self.remote_config else 'Existing CONET loopback nodes; shared physical host; no bandwidth shaping'),
            sources={k:{kk:vv for kk,vv in s.items() if kk!='uri'} for k,s in self.sources.items()},
            inputs=self.inputs,source_sha256=self.source_hashes,operator_sha256=self.operator_fingerprints,job_yaml=self.job,
            engine_jar_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in self.lib.glob('*xstream*.jar')},samples=[])
        if self.remote_config:
            report['remote_environment']=self.remote_config['environment']
            report['config']['result_comparison']='cross-cpu-full-structure-v1'
        write_json(self.out/'results.json',report)
        reference=None;reference_result=None;rng=random.Random(20261108)
        for repeat in range(-self.args.warmups,self.args.repeats):
            methods=['conet-union','ftp-download-local'];rng.shuffle(methods)
            for method in methods:
                label=f'trial-{repeat+1:02d}';sample=dict(repeat=repeat,warmup=repeat<0,method=method)
                try:
                    sample.update(self.conet(label) if method=='conet-union' else self.centralized(label))
                    result=json.loads((self.out/(label+('-conet-result.json' if method=='conet-union' else '-local-result.json'))).read_text())
                    if reference is None:reference=sample['result_sha256'];reference_result=result
                    if self.remote_config:
                        sample['comparison_to_reference']=compare_analysis(reference_result,result)
                    elif sample['result_sha256']!=reference:raise ValueError('Analysis results differ between runs or methods')
                except Exception as error:sample.update(state='failed',error=str(error))
                report['samples'].append(sample);write_json(self.out/'results.json',report);print(json.dumps(sample,ensure_ascii=False),flush=True)
                if sample['state']!='succeeded':raise RuntimeError('Benchmark failed; see retained trial logs')
        return report

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--url',default='http://127.0.0.1:8870')
    p.add_argument('--dacp-home',type=Path,default=Path('/home/cnic/dacp-codex'))
    p.add_argument('--output',type=Path,required=True);p.add_argument('--repeats',type=int,default=3);p.add_argument('--warmups',type=int,default=1)
    p.add_argument('--remote-config',type=Path,help='Existing remote FTP endpoints and CONET node mapping; no local fixture servers')
    p.add_argument('--vsftpd',type=Path,default=Path.home()/'.local/share/dacp-transfer-tools/extracted/usr/sbin/vsftpd')
    p.add_argument('--timeout',type=int,default=600);a=p.parse_args()
    if a.repeats<1 or a.warmups<0 or a.timeout<1:p.error('Invalid repeats/warmups/timeout')
    Benchmark(a).main()

if __name__=='__main__':main()
