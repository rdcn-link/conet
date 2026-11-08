#!/usr/bin/env python3
"""Execute a synthetic astronomy analysis and verify generated metadata; publication remains an explicit browser action."""
import argparse,json,time
from urllib.request import Request,urlopen

def run(args):
 base=args.url.rstrip('/');
 def api(path,payload=None):
  body=None if payload is None else json.dumps(payload).encode()
  with urlopen(Request(base+path,body,{'Content-Type':'application/json'}),timeout=30) as response:return json.load(response)
 prefix='/apps/observatory'
 config=api(prefix+'/api/config');manifest=config['manifest']
 request={'cone':manifest['default_cone'],'match_radius_arcsec':.8,'min_snr':5}
 job=api(prefix+'/api/jobs',request);run_id=job['id'];deadline=time.monotonic()+360
 while job['state'] in ['queued','running']:
  if time.monotonic()>deadline:raise RuntimeError('Analysis timed out')
  time.sleep(1);job=api(prefix+'/api/jobs/'+run_id)
 assert job['state']=='succeeded',job.get('error')
 metadata=api(job['artifacts']['metadata'])
 assert metadata['run_id']==run_id and metadata['state']=='succeeded'
 assert len(metadata['sources'])==3 and len(metadata['operators'])==7
 assert metadata['abstract'] and metadata['job']['definition_sha256']
 preview=api(prefix+'/api/jobs/'+run_id+'/publication')
 assert all(p['view']=='unconfirmed' for p in preview['rights']['sources'])
 report={'app':'observatory','run_id':run_id,'matched_sources':len(job['result']['rows']),'sources':len(metadata['sources']),'operators':len(metadata['operators']),'url':base+prefix+'/'}
 if args.output:
  with open(args.output,'w') as f:json.dump(report,f,ensure_ascii=False,indent=2)
 print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--url',default='http://10.0.82.103:8870');parser.add_argument('--output');run(parser.parse_args())
