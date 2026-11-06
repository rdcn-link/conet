#!/usr/bin/env python3
"""Submit CONET sources for repository review. Does not approve submissions."""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.request import Request,build_opener,HTTPCookieProcessor
from urllib.error import HTTPError
from urllib.parse import urlsplit
import zipfile
import yaml

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.state/publication'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',required=True)
    parser.add_argument('--admin-password-file',type=Path,required=True,help='For runtime registration only')
    parser.add_argument('--token-file',type=Path,required=True)
    args=parser.parse_args()
    url=args.url.rstrip('/');parsed=urlsplit(url)
    if parsed.scheme!='https' and not(parsed.scheme=='http' and parsed.hostname in ['localhost','127.0.0.1']):raise ValueError('HTTPS required outside localhost')
    WORK.mkdir(parents=True,exist_ok=True)
    opener=build_opener(HTTPCookieProcessor());csrf=''
    def request(path,method='GET',value=None,token=False,file=None):
        headers={}
        if token:headers['Authorization']='Bearer '+args.token_file.read_text().strip()
        elif method!='GET' and csrf:headers['X-CSRF-Token']=csrf
        if file:
            body=file.open('rb');headers.update({'Content-Type':'application/octet-stream','Content-Length':str(file.stat().st_size),'X-File-Name':file.name})
        else:body=None if value is None else json.dumps(value).encode();headers['Content-Type']='application/json'
        try:
            with opener.open(Request(url+path,data=body,headers=headers,method=method),timeout=300) as r:return json.load(r)
        except HTTPError as e:raise RuntimeError(f'{path}: HTTP {e.code}: {e.read().decode()}') from None
        finally:
            if file:body.close()
    csrf=request('/api/admin/login','POST',{'password':args.admin_password_file.read_text().strip()})['csrf']
    runtimes=request('/v1/runtimes');builder=next(r['build'] for r in runtimes if r['name']=='python-arrow' and r['version']=='2.0')
    # The trained demonstration weights are an immutable runtime asset, never source.
    model=ROOT/'.state/cases/check-dams/model/model.pt'
    if not model.is_file():raise ValueError('Train the synthetic check-dam model before publishing')
    image='xstream/conet-check-dams:1.0'
    dockerfile=WORK/'Dockerfile.model';dockerfile.write_text('FROM xstream/check-dams-demo:1.0\nCOPY model.pt /opt/models/check-dam.pt\n')
    import shutil
    shutil.copyfile(model,WORK/'model.pt')
    subprocess.run(['sudo','-n','docker','build','-t',image,'-f',str(dockerfile),str(WORK)],check=True)
    report=[]
    for case,image,runtime_name in [('check-dams',image,'conet-geospatial'),('observatory','xstream/observatory-demo:1.0','conet-astronomy')]:
        runtime=next((r for r in runtimes if r['name']==runtime_name and r['version']=='1.0'),None)
        if runtime is None:
            archive=WORK/(runtime_name+'.tar.gz')
            with archive.open('wb') as target:
                process=subprocess.Popen(['sudo','-n','docker','save',image],stdout=subprocess.PIPE)
                with gzip.GzipFile(fileobj=target,mode='wb',compresslevel=2,mtime=0) as compressed:shutil.copyfileobj(process.stdout,compressed)
                if process.wait()!=0:raise RuntimeError('Runtime export failed')
            descriptor=request('/api/admin/blobs','POST',file=archive)
            identifier=subprocess.check_output(['sudo','-n','docker','image','inspect',image,'--format','{{.Id}}'],text=True).strip()
            runtime={'name':runtime_name,'version':'1.0','description':'CONET synthetic science case environment; offline execution','image':identifier,'archive':descriptor,'executors':['python'],'dependencies':{'python':'3.12','pyarrow':'26.0.1','synthetic_demo':'true'},'build':builder}
            request('/api/admin/runtimes','POST',runtime)
        # Build versioned local manifests to keep the public/source declarations identical.
        env=dict(os.environ,CONET_CASE_STATE=str(ROOT/'.state/cases'/case))
        subprocess.run([sys.executable,'-c','import demo;demo.build_operators()'],cwd=ROOT/'cases'/case,env=env,check=True)
        source=WORK/(case+'-sources.zip')
        with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as archive:
            for file in sorted((ROOT/'cases'/case/'operator').glob('*.py')):archive.write(file,'src/'+file.name)
        digest=hashlib.sha256(source.read_bytes()).hexdigest()
        request('/v1/blobs/sha256/'+digest,'PUT',token=True,file=source)
        used={step['operator'] for step in yaml.safe_load((ROOT/'cases'/case/'job.yaml').read_text())['steps']}
        for file in sorted((ROOT/'.state/cases'/case/'operators').glob('*/*/operator.yaml')):
            local=yaml.safe_load(file.read_text())
            if local['name']+'@'+local['version'] not in used:continue
            previous=request('/v1/submissions',token=True)
            previous=previous if isinstance(previous,list) else previous.get('submissions',[])
            existing=next((s for s in previous if s.get('manifest',{}).get('name')==local['name'] and s.get('manifest',{}).get('version')==local['version'] and s.get('status')!='rejected'),None)
            if existing:report.append({'name':local['name'],'existing':True,'submission_id':existing.get('id',existing.get('submission_id'))});continue
            manifest={'api_version':2,'name':local['name'],'version':local['version'],'description':local['description'],'icon':local['icon'],'inputs':local['inputs'],'parameters':local['parameters'],'execution':{'executor':'python','entrypoint':'analysis','config':{}},'runtime':{k:runtime[k] for k in ['image','archive']},'build':{'language':'python'},'source_public':True,'provenance':{'source_archive':{'sha256':digest,'size':source.stat().st_size,'filename':source.name},'license':'Apache-2.0'}}
            result=request('/v1/submissions','POST',manifest,token=True);report.append({'name':local['name'],**result})
            (WORK/'submissions.json').write_text(json.dumps(report,indent=2))
            print(json.dumps(report[-1]),flush=True)
    (WORK/'submissions.json').write_text(json.dumps(report,indent=2))
    print('Submitted for review; administrator approval is required for public download.')

if __name__=='__main__':main()
