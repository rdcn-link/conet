#!/usr/bin/env python3
"""Private-network case UI; the browser never receives service credentials."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
from urllib.parse import urlparse
import uuid
from demo import HERE,STATE,execute
from operator import itemgetter

JOBS={};LOCK=threading.RLock();POOL=ThreadPoolExecutor(max_workers=1)


def now():return datetime.now(timezone.utc).isoformat()


def save(job):
    with LOCK:
        target=STATE/'web-jobs';target.mkdir(exist_ok=True)
        temp=target/(job['id']+'.tmp');temp.write_text(json.dumps(job,indent=2,default=str));temp.replace(target/(job['id']+'.json'))


def run(job):
    def submitted(identifier,variables):
        with LOCK:job.update(job_id=identifier,variables=variables,state='running',started_at=now());save(job)
    def status(value):
        with LOCK:job['execution']=value;job['updated_at']=now();save(job)
    try:
        output=STATE/'results'/job['id']
        summary=execute(job['bbox'],output,submitted,status,job.get('min_confidence',.35))
        result=json.loads((output/'analysis.json').read_text())
        with LOCK:
            job.update(state='succeeded',finished_at=now(),summary=summary,result=result,
              artifacts={key:f"/cases/check-dams/results/{job['id']}/{name}" for key,name in [('map','distribution.svg'),('geojson','sites.geojson'),('analysis','analysis.json'),('variables','variables.yaml')]});save(job)
    except Exception as error:
        with LOCK:job.update(state='failed',finished_at=now(),error=str(error));save(job)


def validate(payload,manifest):
    if not isinstance(payload,dict):raise ValueError('请求必须为 JSON 对象')
    import math
    bbox=payload.get('bbox')
    if not isinstance(bbox,list) or len(bbox)!=4 or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in bbox):raise ValueError('请指定有效的西、南、东、北经纬度边界')
    w,s,e,n=bbox
    if not(-180<=w<e<=180 and -90<=s<n<=90):raise ValueError('WGS84 选择框无效')
    mw,ms,me,mn=manifest['bbox_wgs84']
    if e<=mw or w>=me or n<=ms or s>=mn:raise ValueError('所选区域未与合成研究区相交')
    conf=payload.get('min_confidence',.35)
    if isinstance(conf,bool) or not isinstance(conf,(int,float)) or not .05<=conf<=.95:raise ValueError('置信度必须介于 0.05 和 0.95 之间')
    return bbox,conf


class Handler(BaseHTTPRequestHandler):
    def send(self,code,value,mime='application/json'):
        data=json.dumps(value,default=str).encode() if mime=='application/json' else value
        self.send_response(code);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.end_headers();self.wfile.write(data)
    def do_GET(self):
        path=urlparse(self.path).path
        try:
            if path=='/api/config':
                manifest=json.loads((STATE/'data/manifest.json').read_text());connection=json.loads((STATE/'connection.json').read_text())
                self.send(200,{'manifest':manifest,'web_urls':connection['web_urls'],'job_yaml':(HERE/'job.yaml').read_text()});return
            if path=='/api/overview':self.send(200,json.loads((STATE/'data/overview.geojson').read_text()));return
            if path=='/api/jobs':
                with LOCK:self.send(200,[{k:v for k,v in j.items() if k not in ('result','execution')} for j in sorted(JOBS.values(),key=itemgetter('created_at'),reverse=True)])
                return
            if path.startswith('/api/jobs/'):
                identifier=path.rsplit('/',1)[1]
                with LOCK:
                    if identifier not in JOBS:self.send(404,{'error':'未找到该案例任务'});return
                    self.send(200,JOBS[identifier]);return
            if path.startswith('/results/'):
                pieces=path.split('/')
                if len(pieces)!=4 or pieces[2] not in JOBS or pieces[3] not in ('distribution.svg','sites.geojson','analysis.json','variables.yaml'):self.send(404,{'error':'未找到资源'});return
                file=STATE/'results'/pieces[2]/pieces[3]
                if not file.is_file():self.send(404,{'error':'成果文件不可用'});return
                mime='image/svg+xml' if file.suffix=='.svg' else 'application/geo+json' if file.suffix=='.geojson' else 'application/json' if file.suffix=='.json' else 'application/yaml'
                self.send(200,file.read_bytes(),mime);return
            names={'/':'index.html','/app.js':'app.js','/style.css':'style.css'}
            if path in names:
                file=HERE/'web'/names[path];mime='text/html; charset=utf-8' if file.suffix=='.html' else 'text/javascript; charset=utf-8' if file.suffix=='.js' else 'text/css; charset=utf-8'
                self.send(200,file.read_bytes(),mime);return
            self.send(404,{'error':'未找到资源'})
        except Exception as error:self.send(500,{'error':str(error)})
    def do_POST(self):
        if urlparse(self.path).path!='/api/jobs':self.send(404,{'error':'未找到资源'});return
        try:
            # Prevent cross-site forms from initiating resource-intensive jobs.
            origin=self.headers.get('Origin')
            if origin and urlparse(origin).netloc!=self.headers.get('Host'):raise ValueError('不允许跨站提交')
            size=int(self.headers.get('Content-Length','0'))
            if not 0<size<=16384:raise ValueError('请求大小无效')
            if self.headers.get_content_type()!='application/json':raise ValueError('请求必须采用 JSON 格式')
            payload=json.loads(self.rfile.read(size));bbox,conf=validate(payload,json.loads((STATE/'data/manifest.json').read_text()))
            with LOCK:
                if sum(j['state'] in ('queued','running') for j in JOBS.values())>=4:self.send(429,{'error':'任务队列已满，请等待当前分析完成'});return
                job={'id':uuid.uuid4().hex,'state':'queued','created_at':now(),'bbox':bbox,'min_confidence':conf,'synthetic':True}
                JOBS[job['id']]=job;save(job);POOL.submit(run,job);self.send(202,job)
        except (ValueError,TypeError,json.JSONDecodeError) as error:self.send(400,{'error':str(error)})


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--host',default='0.0.0.0');p.add_argument('--port',type=int,default=8860);a=p.parse_args()
    for file in (STATE/'web-jobs').glob('*.json'):
        job=json.loads(file.read_text())
        if job['state'] in ('queued','running'):job.update(state='interrupted',error='案例服务已重启，请先在 Union 看板核对原任务再决定是否重新提交')
        JOBS[job['id']]=job
    print(f'Check-dam demo listening on {a.host}:{a.port}',flush=True)
    ThreadingHTTPServer((a.host,a.port),Handler).serve_forever()

if __name__=='__main__':main()
