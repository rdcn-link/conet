"""Scientific-demo contracts: exact clipping, provenance and missing-data semantics."""
import importlib.util,io,json,sys,tarfile,unittest
from pathlib import Path
import numpy as np
from pyproj import Transformer
from rasterio.io import MemoryFile
from rasterio.transform import from_origin
from shapely.geometry import box,shape,mapping
from shapely.ops import transform

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'cases/emergency/operator'))
spec=importlib.util.spec_from_file_location('emergency_analysis',ROOT/'cases/emergency/operator/analysis.py');analysis=importlib.util.module_from_spec(spec);spec.loader.exec_module(analysis)

class EmergencyContracts(unittest.TestCase):
    def setUp(self):
        self.to_geo=Transformer.from_crs(32648,4326,always_xy=True)
        self.region=mapping(transform(self.to_geo.transform,box(339500,3449500,340500,3450500)))
        self.region=json.loads(json.dumps(self.region));lon,lat=self.to_geo.transform(340000,3450000)
        self.params={'region':self.region,'event':{'type':'earthquake','time':'2026-08-14T08:00:00Z','longitude':lon,'latitude':lat,'magnitude':6.5,'radius_km':20,'rainfall_mm':180},'simulation':'none'}
        self.cell={'id':'cell','bounds':[339750,3449750,340250,3450250]}
        self.exposure={'features':[{'type':'Feature','geometry':{'type':'LineString','coordinates':[self.to_geo.transform(339600,3450000),self.to_geo.transform(340400,3450000)]},'properties':{'id':'road','kind':'road','name':'test road'}},{'type':'Feature','geometry':{'type':'Point','coordinates':[lon,lat]},'properties':{'id':'village','kind':'settlement','name':'test village','population':120}}]}
    def run_fusion(self):
        values=[]
        for name,fields in [('terrain',{'slope_deg':22,'elevation_m':900}),('imagery',{'change_fraction':1}),('hydrology',{'rainfall_mm':200,'river_distance_m':100})]:
            values.append({'region':self.params['region'],'event':self.params['event'],'source':analysis.source_status(name,self.params),'cells':[{**self.cell,**fields}]})
        return analysis.fuse(*values,self.exposure,self.params)
    def test_earthquake_and_mudslide_produce_auditable_results(self):
        for kind in ['earthquake','mudslide']:
            self.params['event']['type']=kind;result=self.run_fusion();self.assertTrue(result['complete']);self.assertEqual(result['summary']['exposed_population'],120);self.assertAlmostEqual(result['summary']['suspected_area_km2'],.25,places=2);self.assertAlmostEqual(result['summary']['affected_road_km'],.5,places=2);self.assertTrue(result['evidence']);self.assertEqual(len(result['sources']),4)
    def test_missing_imagery_is_unknown_not_unaffected(self):
        self.params['simulation']='missing_imagery';result=self.run_fusion();self.assertFalse(result['complete']);self.assertIsNone(result['summary']['suspected_area_km2']);self.assertTrue(result['warnings']);self.assertTrue(all(f['properties']['assessment']=='unknown' for f in result['geojson']['features']))
    def test_stale_hydrology_blocks_mudslide_but_is_context_for_earthquake(self):
        self.params['simulation']='stale_hydrology';self.assertTrue(self.run_fusion()['complete']);self.params['event']['type']='mudslide';self.assertFalse(self.run_fusion()['complete'])
    def test_event_date_does_not_treat_historical_imagery_as_live(self):
        self.params['event']['time']='2026-11-01T08:00:00Z';self.assertEqual(analysis.source_status('imagery',self.params)['status'],'stale');self.assertFalse(self.run_fusion()['complete'])
    def test_nonrectangular_region_clips_final_polygons(self):
        a=self.to_geo.transform(339500,3449500);b=self.to_geo.transform(340500,3449500);c=self.to_geo.transform(339500,3450500)
        self.params['region']={'type':'Polygon','coordinates':[[list(a),list(b),list(c),list(a)]]};result=self.run_fusion();self.assertGreater(result['summary']['suspected_area_km2'],0);self.assertLess(result['summary']['suspected_area_km2'],.25)
        for f in result['geojson']['features']:
            if f['properties']['kind']=='suspected_area':self.assertTrue(shape(self.params['region']).buffer(1e-9).covers(shape(f['geometry'])))
    def test_outside_event_radius_is_not_reported_as_affected(self):
        self.params['event']['longitude']+=.2;self.params['event']['radius_km']=1;result=self.run_fusion();self.assertEqual(result['summary']['suspected_area_km2'],0);self.assertEqual(result['summary']['exposed_population'],0)
    def test_streamed_raster_uses_real_band_means_and_drains_chunks(self):
        with MemoryFile() as memory:
            with memory.open(driver='GTiff',count=2,width=100,height=100,dtype='float32',crs=analysis.CRS,transform=from_origin(339500,3450500,10,10)) as raster:raster.write(np.stack([np.full((100,100),1234,dtype='float32'),np.full((100,100),22,dtype='float32')]))
            data=memory.read()
        output=io.BytesIO()
        with tarfile.open(fileobj=output,mode='w') as archive:
            for name,content in [('tile/metadata.json',json.dumps({'tile':'tile','bbox_wgs84':list(shape(self.region).bounds)}).encode()),('tile/terrain.tif',data)]:
                member=tarfile.TarInfo(name);member.size=len(content);archive.addfile(member,io.BytesIO(content))
        payload=output.getvalue();consumed=[]
        def chunks():
            for i in range(0,len(payload),997):consumed.append(i);yield payload[i:i+997]
        result=analysis.extract(chunks(),self.params,'terrain');self.assertEqual(len(result['cells']),4);self.assertEqual(result['cells'][0]['elevation_m'],1234);self.assertEqual(result['cells'][0]['slope_deg'],22);self.assertEqual(len(consumed),(len(payload)+996)//997)

if __name__=='__main__':unittest.main()
