import importlib.util
import io
import json
import math
from pathlib import Path
import subprocess
import sys
import unittest
import yaml
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
sys.path.insert(0,str(ROOT/'cases/check-dams/operator'))
dam=load('dam_analysis',ROOT/'cases/check-dams/operator/analysis.py')
astro=load('astro_analysis',ROOT/'cases/observatory/operator/analysis.py')

class StreamContracts(unittest.TestCase):
    def test_fragmented_chunks_and_empty_chunks(self):
        for cls in [dam.ChunkReader,astro.ChunkReader]:
            with io.BufferedReader(cls([b'',b'ab',b'',b'c',b'de'])) as stream:self.assertEqual(stream.read(),b'abcde')
    def test_repeated_variables_no_yaml_aliases(self):
        for case in ['check-dams','observatory']:
            command="import demo,yaml;v=demo.apply_variables('a: $CONE\\nb: $CONE',{'CONE':[150,2,.15]});assert '&id' not in v and '*id' not in v;assert yaml.safe_load(v)['a']==[150,2,.15]"
            subprocess.run([sys.executable,'-c',command],cwd=ROOT/'cases'/case,check=True)
    def test_job_ports_only_reference_existing_steps(self):
        for case in ['check-dams','observatory']:
            job=yaml.safe_load((ROOT/'cases'/case/'job.yaml').read_text());seen=set()
            for step in job['steps']:
                for source in step.get('inputs',{}).values():self.assertIn(source.split(':')[0],seen)
                seen.add(step['id'])
            for source in job['outputs'].values():self.assertIn(source.split(':')[0],seen)
    def test_dataset_case_routing(self):
        config=yaml.safe_load((ROOT/'conet.yaml').read_text());self.assertEqual(len(config['centers']),3)
        for center in config['centers']:
            self.assertEqual({d['case'] for d in center['datasets']},{c['id'] for c in config['cases']})
            for d in center['datasets']:self.assertTrue(d['title'] and d['description'])

class TerrainContracts(unittest.TestCase):
    def test_constant_entropy(self):self.assertEqual(dam.geo_entropy(np.ones((3,3)),np.ones((3,3)),1),0)
    def test_missing_basin(self):self.assertIsNone(dam.geo_entropy(np.ones((2,2)),np.zeros((2,2)),1))
    def test_slope_uses_distance(self):
        class DEM:
            nodata=-9999
            def sample(self,coords,masked):return [[120],[100]]
        feature={'geometry':{'type':'LineString','coordinates':[[0,0],[100,0]]}}
        self.assertAlmostEqual(dam.gully_slop(DEM(),feature),.2)
    def test_topology_cycle_rejected(self):
        features=[{'properties':{'HydroID':i,'NextDownID':3-i,'slope_ratio':.1,'entropy':.1}} for i in [1,2]]
        with self.assertRaisesRegex(ValueError,'cycle'):dam.susceptibility({'features':features},{'max_downstream_hops':32})

class AstronomyContracts(unittest.TestCase):
    def row(self,identifier,ra=150,dec=2,mag=18,error=.1):return dict(source_id=identifier,ra=ra,dec=dec,mag=mag,mag_error=error)
    def band(self,band,rows):return dict(band=band,cone=[150,2,.15],rows=rows,scanned_rows=len(rows),quality_rejections=0)
    def test_ids_are_not_matching_keys_and_errors_propagate(self):
        result=astro.crossmatch(self.band('g',[self.row(1,mag=20)]),self.band('r',[self.row(22,mag=18)]),self.band('i',[self.row(333,mag=17)]),{'cone':[150,2,.15]})
        self.assertEqual(len(result['rows']),1);row=result['rows'][0];self.assertEqual(row['g_r'],2);self.assertEqual(row['r_i'],1);self.assertAlmostEqual(row['g_r_error'],math.sqrt(.02));self.assertEqual(row['class'],'red_candidate')
    def test_ambiguous_match_rejected(self):
        matches,ambiguous=astro.pair([self.row(1)],[self.row(2),self.row(3)],.8);self.assertFalse(matches);self.assertEqual(ambiguous,{0})
    def test_ra_wrap(self):
        matches,_=astro.pair([self.row(1,359.99999,0)],[self.row(2,.00001,0)],.8);self.assertIn(0,matches)
    def test_missing_band(self):
        result=astro.crossmatch(self.band('g',[self.row(1)]),self.band('r',[]),self.band('i',[self.row(3)]),{'cone':[150,2,.15]})
        self.assertFalse(result['rows']);self.assertEqual(result['unmatched']['missing_r'],1)
    def test_pairwise_separation_guard(self):
        result=astro.crossmatch(self.band('g',[self.row(1,150)]),self.band('r',[self.row(2,150+.7/3600)]),self.band('i',[self.row(3,150-.7/3600)]),{'cone':[150,2,.15],'match_radius_arcsec':.8})
        self.assertFalse(result['rows'])

class ObservabilityContracts(unittest.TestCase):
    def test_failed_case_keeps_other_observations_and_member_mapping(self):
        import tempfile
        from unittest.mock import patch
        server=load('conet_server',ROOT/'server.py')
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp);(path/'.state/network').mkdir(parents=True)
            connection={'web_urls':{'union':'http://127.0.0.1:1234'},'node_urls':{'a':'dacp://127.0.0.1:1','b':'dacp://127.0.0.1:2','c':'dacp://127.0.0.1:3'}}
            (path/'.state/network/connection.json').write_text(json.dumps(connection))
            def fake(url):
                if ':8871/' in url:raise TimeoutError()
                if url.endswith('/api/jobs'):return [{'id':'astro-test','state':'succeeded'}]
                return {'members':[{'url':'dacp://127.0.0.1:3','identity':'C'},{'url':'dacp://127.0.0.1:1','identity':'A'}]}
            with patch.object(server,'ROOT',path),patch.object(server,'get_json',side_effect=fake):value=server.snapshot()
            self.assertTrue(value['available'])
            self.assertEqual([m['center_id'] for m in value['overview']['members']],['c','a'])
            cases={c['id']:c for c in value['cases']};self.assertFalse(cases['check-dams']['available']);self.assertTrue(cases['observatory']['available']);self.assertEqual(len(cases['observatory']['jobs']),1)

class MapPreviewContracts(unittest.TestCase):
    def test_georeferenced_preview_and_reuse(self):
        import tempfile,tarfile
        from rasterio.io import MemoryFile
        from rasterio.transform import from_bounds
        from PIL import Image
        visuals=load('dam_visuals',ROOT/'cases/check-dams/visuals.py')
        with tempfile.TemporaryDirectory() as tmp:
            state=Path(tmp);data=state/'data';(data/'a').mkdir(parents=True);(data/'b').mkdir()
            (data/'manifest.json').write_text(json.dumps({'bbox_wgs84':[10,20,11,21]}))
            for center,name,member,values in [('a','terrain.bundle','dem.tif',np.full((1,8,8),1000,dtype='float32')),('b','imagery.bundle','imagery.tif',np.broadcast_to(np.array([.25,.5,.75],dtype='float32')[:,None,None],(3,8,8)).copy())]:
                with MemoryFile() as mem:
                    with mem.open(driver='GTiff',height=8,width=8,count=len(values),dtype='float32',crs='EPSG:4326',transform=from_bounds(10,20,11,21,8,8)) as raster:raster.write(values)
                    content=mem.read()
                with tarfile.open(data/center/name,'w') as archive:
                    info=tarfile.TarInfo('t00/'+member);info.size=len(content);archive.addfile(info,io.BytesIO(content))
            visuals.prepare(state)
            preview=state/'visuals/imagery.png'
            with Image.open(preview) as image:self.assertEqual(image.getpixel((1024,1024)),(63,127,191))
            before=preview.stat().st_mtime_ns;visuals.prepare(state);self.assertEqual(preview.stat().st_mtime_ns,before)

if __name__=='__main__':unittest.main()
