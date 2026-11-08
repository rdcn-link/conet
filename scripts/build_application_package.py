#!/usr/bin/env python3
"""Package a declarative application directory; generated ZIPs contain no datasets or credentials."""
import argparse,json,zipfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
for name in ('application.yaml','job.yaml'):
 if not (args.directory/name).is_file():p.error('Missing '+name)
args.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.output,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('manifest.json',json.dumps({'format':'conet-application','version':1}))
 for name in ('application.yaml','job.yaml'):z.write(args.directory/name,name)
print(args.output)
