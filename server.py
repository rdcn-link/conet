#!/usr/bin/env python3
"""CONET portal, case gateway and read-only Union observability."""
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
from urllib.parse import urlsplit
from urllib.request import Request,urlopen
from urllib.error import HTTPError
import yaml

ROOT=Path(__file__).resolve().parent
CONFIG=yaml.safe_load((ROOT/'conet.yaml').read_text())
CACHE={};LOCK=threading.Lock()

def get_json(url):
    with urlopen(url,timeout=3) as response:return json.load(response)

def snapshot():
    connection_path=ROOT/'.state/network/connection.json'
    result={'at_ms':int(time.time()*1000),'synthetic':True,'cases':[],'available':False}
    total=0;rows=0
    for case in CONFIG['cases']:
        file=ROOT/'.state/cases'/case['id']/'data/manifest.json'
        if file.exists():
            manifest=json.loads(file.read_text());total+=manifest.get('total_data_bytes',0);rows+=manifest.get('total_rows',0)
    result['data_bytes']=total;result['catalog_rows']=rows
    if not connection_path.exists():return result
    connection=json.loads(connection_path.read_text());base=connection['web_urls']['union'].replace(urlsplit(connection['web_urls']['union']).hostname,'127.0.0.1')
    urls={'overview':base+'/api/overview','topology':base+'/api/topology'}
    urls.update({c['id']:f"http://127.0.0.1:{c['port']}/api/jobs" for c in CONFIG['cases']})
    def read(pair):
        key,url=pair
        try:return key,get_json(url),None
        except Exception as error:return key,None,str(error)
    with ThreadPoolExecutor(max_workers=len(urls)) as pool:
        for key,value,error in pool.map(read,urls.items()):
            if key in ('overview','topology'):
                result[key]=value
                if error:result[key+'_error']='服务不可用'
            else:
                case=next(c for c in CONFIG['cases'] if c['id']==key)
                result['cases'].append({'id':key,'title':case['title'],'jobs':value or [],'available':error is None})
    for key in ['overview','topology']:
        if result.get(key):
            for member in result[key].get('members',[]):
                member['center_id']=next((k for k,v in connection['node_urls'].items() if v==member.get('url')),None)
    result['available']=result.get('overview') is not None
    return result

def refresh():
    while True:
        try:
            value=snapshot()
            with LOCK:CACHE.clear();CACHE.update(value)
        except Exception:
            with LOCK:CACHE.update(available=False,error='状态快照不可用')
        time.sleep(3)

class Handler(BaseHTTPRequestHandler):
    def send(self,code,data,mime='application/json'):
        if mime=='application/json' and not isinstance(data,bytes):data=json.dumps(data,ensure_ascii=False).encode()
        self.send_response(code);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.end_headers();self.wfile.write(data)
    def gateway(self):
        path=urlsplit(self.path).path
        for case in CONFIG['cases']:
            prefix='/cases/'+case['id']
            if path==prefix:self.send_response(302);self.send_header('Location',prefix+'/');self.end_headers();return True
            if not path.startswith(prefix+'/'):continue
            suffix=self.path[len(prefix):]
            body=None
            headers={}
            if self.command=='POST':
                origin=self.headers.get('Origin')
                if origin and urlsplit(origin).netloc!=self.headers.get('Host'):self.send(403,{'error':'不允许跨站提交'});return True
                size=int(self.headers.get('Content-Length','0'))
                if not 0<size<=16384:self.send(400,{'error':'请求大小无效'});return True
                body=self.rfile.read(size);headers['Content-Type']=self.headers.get('Content-Type','')
            try:
                req=Request(f"http://127.0.0.1:{case['port']}"+suffix,data=body,headers=headers,method=self.command)
                with urlopen(req,timeout=10) as response:self.send(response.status,response.read(),response.headers.get('Content-Type','application/octet-stream'))
            except HTTPError as error:self.send(error.code,error.read(),error.headers.get('Content-Type','application/json'))
            except OSError:self.send(503,{'error':'案例服务不可用'})
            return True
        return False
    def do_GET(self):
        if self.gateway():return
        path=urlsplit(self.path).path
        if path=='/api/config':self.send(200,{'name':CONFIG['name'],'cases':[{k:c[k] for k in ['id','title','title_en','description']} for c in CONFIG['cases']],'centers':CONFIG['centers']});return
        if path=='/api/snapshot':
            with LOCK:value=dict(CACHE)
            self.send(200,value);return
        files={'/':'index.html','/screen':'screen.html','/screen/':'screen.html','/style.css':'style.css','/app.js':'app.js'}
        if path in files:
            file=ROOT/'web'/files[path];mime={'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8'}[file.suffix]
            self.send(200,file.read_bytes(),mime);return
        self.send(404,{'error':'未找到资源'})
    def do_POST(self):
        try:
            if not self.gateway():self.send(404,{'error':'未找到资源'})
        except (ValueError,TypeError):self.send(400,{'error':'请求格式无效'})

if __name__=='__main__':
    threading.Thread(target=refresh,daemon=True).start()
    ThreadingHTTPServer((CONFIG['host'],CONFIG['port']),Handler).serve_forever()
