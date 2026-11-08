#!/usr/bin/env python3
"""Download locked dependency wheels, then build both offline execution images."""
import argparse
from pathlib import Path
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--case',choices=['all','check-dams','observatory','emergency'],default='all');a=p.parse_args()
    for case in ['check-dams','observatory'] if a.case=='all' else ['check-dams' if a.case=='emergency' else a.case]:
        root=ROOT/'cases'/case;wheels=root/'runtime/wheels';wheels.mkdir(parents=True,exist_ok=True)
        command=[sys.executable,'-m','pip','download','--only-binary=:all:','--no-deps','--dest',str(wheels)]
        if case=='check-dams':
            subprocess.run(command+['--index-url','https://download.pytorch.org/whl/cpu','torch==2.7.1+cpu','torchvision==0.22.1+cpu'],check=True)
            lock='other-requirements.txt'
        else:lock='requirements-lock.txt'
        subprocess.run(command+['-r',str(root/'runtime'/lock)],check=True)
        tag='xstream/check-dams-demo:1.0' if case=='check-dams' else 'xstream/observatory-demo:1.0'
        subprocess.run(['sudo','-n','docker','build','-t',tag,str(root)],check=True)
if __name__=='__main__':main()
