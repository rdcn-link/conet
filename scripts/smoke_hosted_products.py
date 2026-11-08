#!/usr/bin/env python3
"""Verify a real result's DACP bytes/records views and synchronized share/catalog withdrawal."""
import argparse,hashlib,json,subprocess,time
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from dacp import Source,Union

def main(args):
 base=args.url.rstrip('/');root=Path(args.root).resolve();
 def api(path,body=None):
  data=None if body is None else json.dumps(body).encode()
  with urlopen(Request(base+path,data,{'Content-Type':'application/json'}),timeout=60) as r:return json.load(r)
 report=json.loads(Path(args.product).read_text());m=api(report['product_url']+'api');run_id=report['run_id'];app=report['app']
 new=api('/apps/'+app+'/api/jobs/'+run_id+'/publication',{'rights':m['rights'],'confirmed':True,'downloads':[a['id'] for a in m['artifacts'] if a['download_allowed']]});product_id=new['id'];dataset='result-'+product_id
 assert new['dataset_uri'].startswith('dacp://');assert new['dataset_id']==dataset
 node=json.loads((root/'.state/hosted-node/process.json').read_text())['url'];connection=json.loads((root/'.state/network/connection.json').read_text());ca=Path(connection['ca']).read_bytes()
 tool=Path(args.dacp_home)/'dacp-server/target/universal/stage/bin/dacp-admin';credentials=root/'.state/hosted-node/credentials';subject='conet-stream-verification-'+str(int(time.time()))
 grant=json.loads(subprocess.run([str(tool),'create-account',str(credentials),'--subject',subject,'--role','reader','--expires-in','300'],check=True,capture_output=True,text=True).stdout)
 try:
  assert dataset in [d.name for d in Source.datasets(node,tls_root_certs=ca)]
  with Source.open(node+'/'+dataset,tls_root_certs=ca,token=grant['token']) as source:
   paths={i.path for i in source.list()};assert '/matches.csv' in paths and '/metadata.json' in paths and '/analysis.json' not in paths
   with source.open('/matches.csv') as stream:
    with stream.as_bytes() as data:raw=data.read()
   artifact=next(a for a in m['artifacts'] if a['file']=='matches.csv');assert hashlib.sha256(raw).hexdigest()==artifact['sha256']
   with source.open('/matches.csv') as stream:rows=sum(batch.num_rows for batch in stream.as_records())
   assert rows==report['matched_sources']
  union_entries=Source.datasets(connection['union_url'],tls_root_certs=ca);assert any(json.loads(d.description).get('title')==m['title'] for d in union_entries)
  job='name: Hosted result read verification\nsteps:\n  - id: source\n    operator: read_item@1.0\n    inputs: {}\n    params: {uri: '+node+'/'+dataset+'/matches.csv, view: records}\noutputs: {result: source}\n'
  with Union(connection['union_url'],ca=connection['ca'],token=Path(connection['token_file']).read_text().strip(),timeout=120) as union:
   with union.submit(job) as execution:
    job_rows=sum(event['batch'].num_rows for event in execution.results() if event['type']=='data')
    assert job_rows==rows
    report['hosted_read_job_id']=execution.job_id
  api(new['preview_url']+'revoke',{})
  assert dataset not in [d.name for d in Source.datasets(node,tls_root_certs=ca)]
  try:api(new['preview_url']+'api')
  except HTTPError as error:assert error.code==410
  else:raise AssertionError('Revoked share remains public')
  print(json.dumps({'hosted_read_job_id':report['hosted_read_job_id'],'union_read_job_rows':job_rows,'verified_rows':rows,'bytes':len(raw),'bytes_sha256_matches':True,'non_downloadable_artifact_excluded':True,'share_and_catalog_withdrawal':True},ensure_ascii=False,indent=2))
 finally:
  subprocess.run([str(tool),'set-roles',str(credentials),subject,'none'],check=True,capture_output=True,text=True)
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--url',default='http://10.0.82.103:8870');p.add_argument('--root',default='.');p.add_argument('--dacp-home',default='/home/cnic/dacp-codex');p.add_argument('--product',required=True);main(p.parse_args())
