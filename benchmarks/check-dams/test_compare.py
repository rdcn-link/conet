import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import tempfile
import xml.sax.saxutils
sys.path.insert(0,str(Path(__file__).parent))
import compare

class ComparisonContracts(unittest.TestCase):
    def test_region_matches_default_first_tile(self):
        p=compare.Benchmark.inputs_for({'tiles':[{'bbox_wgs84':[109,35,110,36]}]})
        self.assertEqual(p['region']['coordinates'],[[[109,35],[110,35],[110,36],[109,36],[109,35]]])
        self.assertEqual(p['min_confidence'],.35)
    def test_result_digest_uses_every_analysis_field_but_not_json_key_order(self):
        value=dict(features=[{'id':'a'}],rejections=[],candidate_count=2,windows_inferred=16,diagnostic={'x':1})
        def svg(v,sort=False):return ('<svg><metadata id="analysis-result">'+xml.sax.saxutils.escape(json.dumps(v,sort_keys=sort))+'</metadata></svg>').encode()
        a,_=compare.parse_result(svg(value));b,_=compare.parse_result(svg(value,True))
        self.assertEqual(a['result_sha256'],b['result_sha256'])
        self.assertNotEqual(a['svg_sha256'],b['svg_sha256'])
        value['diagnostic']['x']=2;c,_=compare.parse_result(svg(value))
        self.assertNotEqual(a['result_sha256'],c['result_sha256'])
        self.assertEqual(a['valid_sites'],1)
    def test_download_uses_binary_ftp_and_verifies_the_original_bytes(self):
        payload=b'original source bundle';calls=[]
        class FTP:
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def connect(self,*args,**kwargs):calls.append(('connect',args))
            def login(self,*args):calls.append(('login',args))
            def voidcmd(self,command):calls.append(('command',command))
            def retrbinary(self,command,consumer,blocksize):
                calls.append(('retrieve',command));consumer(payload[:5]);consumer(payload[5:])
        with tempfile.TemporaryDirectory() as directory:
            b=compare.Benchmark.__new__(compare.Benchmark);b.out=Path(directory)
            b.args=SimpleNamespace(timeout=600);b.ftp=SimpleNamespace(ports={'a':12345})
            source=dict(dataset='terrain',item='terrain.bundle',center='a',bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest())
            with patch.object(compare.ftplib,'FTP',FTP):result=b.download('TERRAIN_URI',source)
            self.assertEqual(result['protocol'],'ftp')
            self.assertEqual((b.out/'downloads/terrain/terrain.bundle').read_bytes(),payload)
            self.assertIn(('command','TYPE I'),calls)
            self.assertIn(('retrieve','RETR terrain.bundle'),calls)
            source['sha256']='bad'
            with patch.object(compare.ftplib,'FTP',FTP),self.assertRaises(ValueError):b.download('TERRAIN_URI',source)
    def test_observed_remote_payload_counts_output_ports_once(self):
        plan=dict(sub_jobs=[dict(node=k,steps=[k],state='succeeded') for k in ['a','b','c']],elapsed_ms=1000,
                  nodes={'one':{'ports':{'step/inputs/source':{'remote_payload_bytes':99},'step/outputs/result':{'remote_payload_bytes':99}}}})
        result=compare.plan_observations(plan,dict(a='a',b='b',c='c'))
        self.assertEqual(result['observed_remote_output_payload_bytes'],99)
        plan['sub_jobs'].pop()
        with self.assertRaises(ValueError):compare.plan_observations(plan,dict(a='a',b='b',c='c'))
    def test_cross_cpu_comparison_checks_full_structure_and_narrow_tolerances(self):
        a=dict(features=[dict(properties=dict(x=332127.0,y=3996022.0,latitude=36.09,longitude=109.13,confidence=.89,pixel_box=[785.644268],HydroID=1001))],candidate_count=5)
        b=json.loads(json.dumps(a));b['features'][0]['properties']['y']+=.00001;b['features'][0]['properties']['pixel_box'][0]+=.000004
        result=compare.compare_analysis(a,b);self.assertEqual(result['different_floats'],2)
        for key,value in [('HydroID',1002),('latitude',36.090001),('confidence',.89001),('x',332127.01)]:
            c=json.loads(json.dumps(b));c['features'][0]['properties'][key]=value
            with self.assertRaises(ValueError):compare.compare_analysis(a,c)
        b['extra']='wrong'
        with self.assertRaises(ValueError):compare.compare_analysis(a,b)
    def test_missing_result_metadata_is_rejected(self):
        with self.assertRaises(ValueError):compare.parse_result(b'<svg/>')

if __name__=='__main__':unittest.main()
