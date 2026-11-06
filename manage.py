#!/usr/bin/env python3
"""Manage only CONET's processes. Use the Python environment with dacp-client installed."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import urllib.request
import network

ROOT=Path(__file__).resolve().parent
STATE=ROOT/'.state';STATE.mkdir(exist_ok=True)
PIDFILE=STATE/'apps.json'

def alive(pid):
    try:return Path(f'/proc/{pid}/stat').read_text().split()[2]!='Z'
    except FileNotFoundError:return False

def stop_apps():
    records=json.loads(PIDFILE.read_text()) if PIDFILE.exists() else []
    remaining=[]
    for record in reversed(records):
        pid=record['pid']
        if not alive(pid):continue
        # Check both process start time and cwd before touching a saved PID.
        if Path(f'/proc/{pid}/stat').read_text().split()[21]!=record['start'] or Path(f'/proc/{pid}/cwd').resolve()!=Path(record['cwd']):
            raise RuntimeError(f'Refusing to stop unrelated PID {pid}')
        os.killpg(pid,signal.SIGTERM)
        for _ in range(50):
            if not alive(pid):break
            time.sleep(.1)
        if alive(pid):remaining.append(record)
    PIDFILE.write_text(json.dumps(remaining,indent=2))
    if remaining:raise RuntimeError('Some application processes are still stopping')

def start_app(name,directory,args,env,records):
    with (STATE/(name+'.log')).open('ab') as log:
        proc=subprocess.Popen([sys.executable,*args],cwd=directory,env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    records.append({'name':name,'pid':proc.pid,'cwd':str(directory),'start':Path(f'/proc/{proc.pid}/stat').read_text().split()[21]})
    PIDFILE.write_text(json.dumps(records,indent=2))

def start_web(records):
    connection=(network.STATE/'connection.json').read_text()
    for case in network.CONFIG['cases']:
        state=network.case_state(case);state.mkdir(parents=True,exist_ok=True)
        (state/'connection.json').write_text(connection)
        env=dict(os.environ,CONET_CASE_STATE=str(state))
        start_app(case['id'],ROOT/case['directory'],['app.py','--host','127.0.0.1','--port',str(case['port'])],env,records)
    start_app('portal',ROOT,['server.py'],dict(os.environ),records)
    for port in [network.CONFIG['port'],*[c['port'] for c in network.CONFIG['cases']]]:
        for _ in range(600):
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/config',timeout=1) as response:
                    if response.status==200:break
            except OSError:time.sleep(.1)
        else:raise RuntimeError(f'Web service on port {port} did not become ready')

def up():
    if PIDFILE.exists() and any(alive(p['pid']) for p in json.loads(PIDFILE.read_text())):raise RuntimeError('CONET applications already running')
    network_records=network.STATE/'processes.json'
    if network_records.exists() and json.loads(network_records.read_text()):raise RuntimeError('CONET network already has processes; use restart-web or down first')
    records=[]
    try:
        network.up()
        start_web(records)
        print(f"CONET: http://{network.public_host()}:{network.CONFIG['port']}/")
    except BaseException:
        stop_apps();network.stop();raise

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['up','down','status','restart-web']);a=parser.parse_args()
    if a.command=='up':up()
    elif a.command=='down':stop_apps();network.stop()
    elif a.command=='restart-web':
        stop_apps()
        try:start_web([])
        except BaseException:stop_apps();raise
    else:
        apps=json.loads(PIDFILE.read_text()) if PIDFILE.exists() else []
        print(json.dumps([{**p,'running':alive(p['pid'])} for p in apps],indent=2))

if __name__=='__main__':main()
