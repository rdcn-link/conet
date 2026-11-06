#!/usr/bin/env python3
"""Compare rectangle and triangle jobs on the shared Union, checking exact membership."""
import argparse,json,time
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from shapely.geometry import shape,Point


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--url',default='http://127.0.0.1:8870');args=parser.parse_args()
    base=args.url.rstrip('/')+'/cases/check-dams'
    def api(path,payload=None):
        data=None if payload is None else json.dumps(payload).encode()
        with urlopen(Request(base+path,data,{'Content-Type':'application/json'}),timeout=20) as response:return json.load(response)
    w,s,e,n=api('/api/config')['manifest']['tiles'][0]['bbox_wgs84']
    regions={'rectangle':{'type':'Polygon','coordinates':[[[w,s],[e,s],[e,n],[w,n],[w,s]]]},
             'triangle':{'type':'Polygon','coordinates':[[[w,s],[e,s],[w,n],[w,s]]]}}
    invalid={'type':'Polygon','coordinates':[[[w,s],[e,n],[e,s],[w,n],[w,s]]]}
    try:api('/api/jobs',{'region':invalid})
    except HTTPError as error:assert error.code==400
    else:raise AssertionError('Self-intersecting polygon accepted')
    results={}
    for name,region in regions.items():
        job=api('/api/jobs',{'region':region,'min_confidence':.35});deadline=time.monotonic()+360
        while job['state'] in ('queued','running'):
            if time.monotonic()>deadline:raise TimeoutError(name)
            time.sleep(1);job=api('/api/jobs/'+job['id'])
        assert job['state']=='succeeded',job.get('error',job['state'])
        result=job['result'];assert result['region']==region
        assert job['variables']['REGION']==region
        assert all(shape(region).covers(Point(f['properties']['longitude'],f['properties']['latitude'])) for f in result['features'])
        assert result['features'],'Expected nonempty synthetic first-tile result'
        results[name]=result
        print(json.dumps({'selection':name,'job_id':job['job_id'],'valid_sites':len(result['features'])}),flush=True)
    outside=[f for f in results['rectangle']['features'] if not shape(regions['triangle']).covers(Point(f['properties']['longitude'],f['properties']['latitude']))]
    assert outside,'Fixture must distinguish polygon analysis from bounding-box analysis'
    assert len(results['triangle']['features'])<len(results['rectangle']['features'])
    print('Exact polygon filtering verified against rectangle baseline.')

if __name__=='__main__':main()
