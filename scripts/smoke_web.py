#!/usr/bin/env python3
"""Submit two real analyses and verify shared-Union completion and artifacts."""
import argparse
import json
import time
from urllib.request import Request,urlopen
from urllib.error import HTTPError

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--url',default='http://127.0.0.1:8870');a=p.parse_args();base=a.url.rstrip('/')
    def request(path,payload=None,headers=None):
        data=None if payload is None else json.dumps(payload).encode()
        with urlopen(Request(base+path,data,{'Content-Type':'application/json',**(headers or {})}),timeout=15) as response:
            value=response.read();mime=response.headers.get('Content-Type','');return json.loads(value) if 'json' in mime else value
    config=request('/api/config');assert len(config['centers'])==3
    test_jobs=[]
    for case in ['check-dams','observatory']:
        prefix='/cases/'+case
        cfg=request(prefix+'/api/config')
        try:request(prefix+'/api/jobs',[])
        except HTTPError as e:assert e.code==400
        else:raise AssertionError('Non-object input accepted')
        try:request(prefix+'/api/jobs',{},headers={'Origin':'https://other.example'})
        except HTTPError as e:assert e.code==403
        else:raise AssertionError('Cross-origin input accepted')
        w,s,e,n=cfg['manifest']['tiles'][0]['bbox_wgs84'] if case=='check-dams' else [0,0,0,0]
        payload={'region':{'type':'Polygon','coordinates':[[[w,s],[e,s],[e,n],[w,n],[w,s]]]},'min_confidence':.35} if case=='check-dams' else {'cone':[150,2,.15],'match_radius_arcsec':.8,'min_snr':5}
        job=request(prefix+'/api/jobs',payload);test_jobs.append((case,job['id']))
    deadline=time.monotonic()+360
    summaries=[]
    for case,identifier in test_jobs:
        while True:
            job=request('/cases/'+case+'/api/jobs/'+identifier)
            if job['state'] not in ['queued','running']:break
            if time.monotonic()>deadline:raise TimeoutError('Union analysis timed out')
            time.sleep(1)
        assert job['state']=='succeeded',job.get('error')
        assert job.get('job_id') and job.get('summary')
        for key,path in job['artifacts'].items():
            assert path.startswith('/cases/'+case+'/results/');assert request(path),key
        assert len(job['result']['features'] if case=='check-dams' else job['result']['rows'])>0
        summaries.append({'case':case,'application_id':identifier,**job['summary']})
    snap=request('/api/snapshot');assert snap['available']
    assert len(snap['overview']['members'])==3
    assert all(m['state']=='approved' and m.get('center_id') in ['a','b','c'] for m in snap['overview']['members'])
    assert all(n>=2 for n in snap['topology']['participation'].values())
    print(json.dumps(summaries,indent=2,ensure_ascii=False))
if __name__=='__main__':main()
