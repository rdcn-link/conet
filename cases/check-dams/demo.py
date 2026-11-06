#!/usr/bin/env python3
"""Case operators and fixed-job submission to the shared CONET Union."""
import argparse
import json
import os
from pathlib import Path
import time
import zipfile
from xml.etree import ElementTree
import yaml

HERE=Path(__file__).resolve().parent
ROOT=HERE
from dacp import Source, Union
STATE=Path(os.environ.get('CONET_CASE_STATE', HERE.parent.parent/'.state/cases'/HERE.name)).resolve()
IMAGE='xstream/check-dams-demo:1.0'


def write_yaml(path,data):path.write_text(yaml.safe_dump(data,sort_keys=False))


def build_operators():
    library=STATE/'operators'
    schemas={'region':{'type':'object','properties':{'type':{'type':'string','enum':['Polygon']},'coordinates':{'type':'array','minItems':1,'maxItems':16,'items':{'type':'array','minItems':4,'maxItems':256,'items':{'type':'array','minItems':2,'maxItems':2,'items':{'type':'number'}}}}},'required':['type','coordinates'],'additionalProperties':False},'region_bbox':{'type':'array','items':{'type':'number'},'minItems':4,'maxItems':4},
      'operation':{'type':'string'},'entropy_bins':{'type':'integer','minimum':2,'maximum':32},
      'min_slope_ratio':{'type':'number'},'max_slope_ratio':{'type':'number'},'max_entropy':{'type':'number'},
      'max_downstream_hops':{'type':'integer'},'min_confidence':{'type':'number'},
      'duplicate_distance_m':{'type':'number'},'bias':{'type':'integer'}}
    for name,inputs in [('terrain',['source']),('susceptibility',['source']),('detect',['source']),('overlap',['a_hazards','b_candidates','c_grids']),('map',['source'])]:
        full='check_dam_'+name;folder=library/full/'1.1';folder.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(folder/'analysis.zip','w') as archive:
            for source in (HERE/'operator').glob('*.py'):archive.write(source,source.name)
            if name=='detect':archive.write(STATE/'model/model.pt','model.pt')
        write_yaml(folder/'operator.yaml',dict(name=full,version='1.1',description='Synthetic three-center check-dam '+name,
            image=IMAGE,executor='python',entrypoint='analysis',artifact='analysis.zip',
            inputs={n:{'representations':['bytes'],'formats':[]} for n in inputs},
            parameters={'type':'object','properties':schemas,'required':['operation','region','region_bbox'],'additionalProperties':False},
            icon={'path':'M3 19L9 5L14 13L18 7L22 19ZM5 17H20','color':'#14866b'}))
    return library


def catalog(connection):
    return Source.datasets(connection['union_url'],tls_root_certs=Path(connection['ca']).read_bytes(),token=Path(connection['token_file']).read_text().strip())


def apply_variables(template,variables):
    import re
    def expand(value):
        if isinstance(value,str) and re.fullmatch(r'\$[A-Za-z_][A-Za-z0-9_]*',value):
            key=value[1:]
            if key not in variables:raise ValueError('Missing variable: '+key)
            # Distinct copies prevent YAML aliases, disallowed by server parsers.
            return json.loads(json.dumps(variables[key],allow_nan=False))
        if isinstance(value,list):return [expand(v) for v in value]
        if isinstance(value,dict):return {k:expand(v) for k,v in value.items()}
        return value
    return yaml.safe_dump(expand(yaml.safe_load(template)),sort_keys=False)


def execute(region,output,on_submitted=None,on_status=None,min_confidence=.35):
    import sys
    sys.path.insert(0,str(HERE/"operator"))
    from polygon_region import selection
    bbox=list(selection(region).bounds)
    # Catalog entries come from Union and keep its public names; no member URI
    # or explicit node placement is included in the submitted logical Job.
    connection=json.loads((STATE/'connection.json').read_text());items={}
    for dataset in catalog(connection):
        metadata=json.loads(dataset.description)
        if metadata.get('case')=='check-dams':items[metadata['dataset']]=dataset.uri
    variables={'REGION':region,'REGION_BBOX':bbox,'MIN_CONFIDENCE':min_confidence,'TERRAIN_URI':items['terrain']+'/terrain.bundle',
               'IMAGERY_URI':items['imagery']+'/imagery.bundle','CHANNEL_GRIDS_URI':items['channels']+'/channels.bundle'}
    token=Path(connection['token_file']).read_text().strip();out=bytearray()
    with Union(connection['union_url'],token=token,ca=Path(connection['ca']),timeout=300) as union:
        with union.submit(apply_variables((HERE/'job.yaml').read_text(),variables)) as execution:
            job_id=execution.job_id
            if on_submitted:on_submitted(job_id,variables)
            import threading
            finished=threading.Event()
            def monitor():
                while not finished.is_set():
                    try:
                        if on_status:on_status(execution.status())
                    except Exception:pass
                    finished.wait(1)
            thread=threading.Thread(target=monitor,daemon=True);thread.start()
            try:
                for event in execution.results():
                    if event['type']=='data':
                        out.extend(event['data'])
                        if len(out)>8*1024*1024:raise ValueError('map exceeds demo output limit')
                    elif event['type'] in ('error','failed'):raise RuntimeError(str(event))
            finally:
                finished.set();thread.join(timeout=3)
            if on_status:
                try:
                    for _ in range(10):
                        final=execution.status();on_status(final)
                        if final.get('state') not in ('queued','running','waiting_resources'):break
                        time.sleep(.1)
                except Exception:pass
    tree=ElementTree.fromstring(out);metadata=tree.find('{http://www.w3.org/2000/svg}metadata')
    result=json.loads(metadata.text);output.mkdir(parents=True,exist_ok=False)
    (output/'distribution.svg').write_bytes(out)
    (output/'sites.geojson').write_text(json.dumps(result['geojson'],indent=2)+'\n')
    (output/'analysis.json').write_text(json.dumps(result,indent=2)+'\n')
    write_yaml(output/'variables.yaml',variables)
    (output/'submission.json').write_text(json.dumps({'job_id':job_id,'union_url':connection['union_url'],'synthetic':True},indent=2)+'\n')
    return {'job_id':job_id,'valid_sites':len(result['features']),'rejected':len(result['rejections']),'output':str(output)}


def main():
    p=argparse.ArgumentParser(description='Run this case against the shared CONET Union')
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--bbox',type=float,nargs=4,required=True)
    args=p.parse_args()
    import sys
    sys.path.insert(0,str(HERE/'operator'))
    from polygon_region import rectangle
    print(json.dumps(execute(rectangle(args.bbox),args.output),indent=2))

if __name__=='__main__':main()
