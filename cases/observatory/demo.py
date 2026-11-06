#!/usr/bin/env python3
"""Case operators and fixed-job submission to the shared CONET Union."""
import argparse
import json
import os
from pathlib import Path
import time
import zipfile
import yaml

HERE=Path(__file__).resolve().parent
ROOT=HERE
from dacp import Source, Union
STATE=Path(os.environ.get('CONET_CASE_STATE', HERE.parent.parent/'.state/cases'/HERE.name)).resolve()
IMAGE='xstream/observatory-demo:1.0'


def write_yaml(path,data):path.write_text(yaml.safe_dump(data,sort_keys=False))


def build_operators():
    library=STATE/'operators'
    schemas={'cone':{'type':'array','items':{'type':'number'},'minItems':3,'maxItems':3},
             'operation':{'type':'string'},'min_snr':{'type':'number'},'match_radius_arcsec':{'type':'number'}}
    for name,inputs in [('select',['source']),('crossmatch',['g','r','i'])]:
        full='observatory_'+name;folder=library/full/'1.0';folder.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(folder/'analysis.zip','w') as archive:
            for source in (HERE/'operator').glob('*.py'):archive.write(source,source.name)
        write_yaml(folder/'operator.yaml',dict(name=full,version='1.0',description='Synthetic three-observatory '+name,
            image=IMAGE,executor='python',entrypoint='analysis',artifact='analysis.zip',
            inputs={n:{'representations':['bytes'],'formats':[]} for n in inputs},
            parameters={'type':'object','properties':schemas,'required':['operation','cone'],'additionalProperties':False},
            icon={'path':'M12 2L14 9L22 12L14 15L12 22L10 15L2 12L10 9Z','color':'#6653b1'}))
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


def execute(cone,output,on_submitted=None,on_status=None,match_radius=.8,min_snr=5):
    # Catalog entries come from Union and keep its public names; no member URI
    # or explicit node placement is included in the submitted logical Job.
    connection=json.loads((STATE/'connection.json').read_text());items={}
    for dataset in catalog(connection):
        metadata=json.loads(dataset.description)
        if metadata.get('case')=='observatory':items[metadata['dataset']]=dataset.uri
    variables={'CONE':cone,'MATCH_RADIUS_ARCSEC':match_radius,'MIN_SNR':min_snr,
               'G_CATALOG_URI':items['g-band']+'/catalog.bundle','R_CATALOG_URI':items['r-band']+'/catalog.bundle','I_CATALOG_URI':items['i-band']+'/catalog.bundle'}
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
    result=json.loads(out);output.mkdir(parents=True,exist_ok=False)
    (output/'analysis.json').write_text(json.dumps(result,indent=2)+'\n')
    import csv
    with (output/'matches.csv').open('w') as handle:
        columns=['source_id','ra','dec','g','r','i','g_r','r_i','g_r_error','r_i_error','gr_separation_arcsec','gi_separation_arcsec','class']
        writer=csv.DictWriter(handle,fieldnames=columns,extrasaction='ignore');writer.writeheader();writer.writerows(result['rows'])
    write_yaml(output/'variables.yaml',variables)
    (output/'submission.json').write_text(json.dumps({'job_id':job_id,'union_url':connection['union_url'],'synthetic':True},indent=2)+'\n')
    return {'job_id':job_id,'matched_sources':len(result['rows']),'output':str(output)}


def main():
    p=argparse.ArgumentParser(description='Run this case against the shared CONET Union')
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--cone',type=float,nargs=3,default=[150,2,.15])
    args=p.parse_args();print(json.dumps(execute(args.cone,args.output),indent=2))

if __name__=='__main__':main()
