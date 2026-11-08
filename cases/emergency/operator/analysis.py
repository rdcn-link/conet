"""Streaming synthetic emergency extraction and auditable spatial fusion."""
import io,json,math,tarfile
from datetime import datetime,timedelta
import numpy as np
from rasterio.io import MemoryFile
from pyproj import Transformer
from shapely.geometry import Point,box,shape,mapping
from shapely.ops import transform,unary_union
from polygon_region import selection

CRS='EPSG:32648';STRIDE=50

class ChunkReader(io.RawIOBase):
    def __init__(self,chunks):self.chunks=iter(chunks);self.pending=memoryview(b'')
    def readable(self):return True
    def readinto(self,target):
        while not self.pending:
            try:self.pending=memoryview(next(self.chunks))
            except StopIteration:return 0
        count=min(len(target),len(self.pending));target[:count]=self.pending[:count];self.pending=self.pending[count:];return count

def bundle(chunks):
    with io.BufferedReader(ChunkReader(chunks),buffer_size=65536) as stream:
        with tarfile.open(fileobj=stream,mode='r|') as archive:
            for member in archive:
                if not member.isfile() or member.size>48*1024*1024 or '..' in member.name.split('/'):raise ValueError('Invalid emergency bundle member')
                yield member.name,archive.extractfile(member).read()
        while stream.read(65536):pass

def read_json(chunks):
    data=bytearray()
    for part in chunks:
        data.extend(part)
        if len(data)>8*1024*1024:raise ValueError('Emergency JSON exceeds 8 MB limit')
    return json.loads(data)

def source_status(kind,params):
    observed={'terrain':'2026-08-01T00:00:00Z','imagery':'2026-08-14T12:00:00Z','hydrology':'2026-08-14T08:00:00Z','exposure':'2026-08-01T00:00:00Z'}[kind]
    maximum={'terrain':8760,'imagery':72,'hydrology':24,'exposure':8760}[kind]
    event=datetime.fromisoformat(params['event']['time'].replace('Z','+00:00'));assessment=event+timedelta(hours=12);date=datetime.fromisoformat(observed.replace('Z','+00:00'))
    simulation=params.get('simulation','none');status='available';reason='在演示时效范围内'
    if simulation=='missing_imagery' and kind=='imagery':status='missing';reason='用户选择的缺测演示'
    elif simulation=='stale_hydrology' and kind=='hydrology':status='stale';reason='用户选择的过期观测演示'
    elif date>assessment:status='unavailable';reason='观测晚于本次研判参考时刻'
    elif (assessment-date).total_seconds()/3600>maximum:status='stale';reason='超过该类数据的演示时效阈值'
    elif kind=='imagery' and date<event:status='unavailable';reason='未提供事件后的遥感观测'
    return {'source':kind,'status':status,'observed_at':observed,'assessment_at':assessment.isoformat(),'maximum_age_hours':maximum,'reason':reason,'synthetic':True}

def extract(chunks,params,kind):
    region=selection(params['region']);cells=[];metadata=None;before=None;scanned=0;sampled=0;status=source_status(kind,params);to_geo=Transformer.from_crs(CRS,4326,always_xy=True)
    for name,data in bundle(chunks):
        if name.endswith('/metadata.json'):metadata=json.loads(data);before=None;continue
        if not metadata:raise ValueError('Missing raster tile metadata')
        if not name.endswith('.tif'):continue
        scanned+=1
        if status['status']!='available' or not region.intersects(box(*metadata['bbox_wgs84'])):continue
        with MemoryFile(data) as memory,memory.open() as raster:
            if str(raster.crs)!=CRS or raster.count!=2 or raster.width*raster.height>3_000_000:raise ValueError('Unexpected emergency raster contract')
            values=raster.read();affine=raster.transform
            if not np.isfinite(values).all():raise ValueError('Nonfinite emergency raster values')
            if kind=='imagery' and name.endswith('/before.tif'):before=values;continue
            if kind=='imagery':
                if before is None or before.shape!=values.shape:raise ValueError('Missing or inconsistent before/after pair')
                difference=np.mean(np.abs(values-before),axis=0);before=None
            for row in range(0,raster.height,STRIDE):
                for col in range(0,raster.width,STRIDE):
                    rr=min(row+STRIDE,raster.height);cc=min(col+STRIDE,raster.width)
                    west,north=affine*(col,row);east,south=affine*(cc,rr)
                    projected=box(west,south,east,north);geographic=transform(to_geo.transform,projected)
                    if not region.intersects(geographic):continue
                    item={'id':f"{metadata['tile']}/{row}/{col}",'bounds':[west,south,east,north]}
                    view=values[:,row:rr,col:cc]
                    if kind=='terrain':item.update(elevation_m=round(float(view[0].mean()),2),slope_deg=round(float(view[1].mean()),2))
                    elif kind=='imagery':item.update(change_fraction=round(float((difference[row:rr,col:cc]>.08).mean()),4),mean_spectral_change=round(float(difference[row:rr,col:cc].mean()),4))
                    else:item.update(rainfall_mm=round(float(view[0].mean())*params['event']['rainfall_mm']/180,2),river_distance_m=round(float(view[1].mean()),2))
                    cells.append(item);sampled+=1
    return {'region':params['region'],'event':params['event'],'source':status,'cells':cells,'raster_members_scanned':scanned,'sample_cells':sampled,'analysis_cell_m':500,'synthetic':True}

def fuse(terrain,imagery,hydrology,exposure,params):
    region=selection(params['region']);event=params['event'];kind=event['type']
    for value in [terrain,imagery,hydrology]:
        if value['region']!=params['region'] or value['event']!=event:raise ValueError('Emergency selection/event mismatch')
    sources=[v['source'] for v in [terrain,imagery,hydrology]]+[source_status('exposure',params)]
    required={'terrain','imagery','exposure'}|({'hydrology'} if kind=='mudslide' else set())
    complete=all(s['status']=='available' for s in sources if s['source'] in required)
    warnings=[f"{s['source']}: {s['reason']}" for s in sources if s['status']!='available']
    to_metric=Transformer.from_crs(4326,CRS,always_xy=True);to_geo=Transformer.from_crs(CRS,4326,always_xy=True);project=lambda geometry:transform(to_metric.transform,geometry);unproject=lambda geometry:transform(to_geo.transform,geometry)
    region_metric=project(region);event_x,event_y=to_metric.transform(event['longitude'],event['latitude']);changes={c['id']:c for c in imagery['cells']};rain={c['id']:c for c in hydrology['cells']};polygons=[];evidence=[]
    if complete:
        for cell in terrain['cells']:
            image=changes.get(cell['id']);water=rain.get(cell['id'])
            if image is None:continue
            w,s,e,n=cell['bounds'];distance=math.hypot((w+e)/2-event_x,(s+n)/2-event_y)/1000
            if distance>event['radius_km']:continue
            change=image['change_fraction'];slope=cell['slope_deg']
            if kind=='earthquake':
                score=.55*change+.35*max(0,1-distance/event['radius_km'])*min(1,event['magnitude']/7)+.1*min(1,slope/40)
                candidate=change>=params.get('change_threshold',.15) and score>=.35
            else:
                if water is None:continue
                score=.4*change+.35*min(1,water['rainfall_mm']/200)+.25*min(1,slope/35)
                candidate=water['rainfall_mm']>=120 and 12<=slope<=45 and water['river_distance_m']<=2000 and change>=params.get('change_threshold',.15) and score>=.45
            if candidate:
                clipped=box(w,s,e,n).intersection(region_metric)
                if clipped.area>0:polygons.append(clipped);evidence.append({'cell':cell['id'],'score':round(score,3),'distance_km':round(distance,2),'slope_deg':slope,'change_fraction':change,'rainfall_mm':water['rainfall_mm'] if water else None})
    affected=unary_union(polygons) if polygons else __import__('shapely').geometry.GeometryCollection();features=[];road_km=0;settlements=0;people=0;facilities=0
    if not affected.is_empty:
        parts=list(affected.geoms) if hasattr(affected,'geoms') else [affected]
        for i,part in enumerate(parts):features.append({'type':'Feature','geometry':mapping(unproject(part).intersection(region)),'properties':{'id':f'impact-{i+1:03}','kind':'suspected_area','assessment':'suspected','name':f'疑似影响区 {i+1}','area_km2':round(part.area/1e6,3),'basis':'合成影像变化与事件、地形约束；泥石流另使用降雨及沟道距离'}})
    for feature in exposure['features']:
        geometry=shape(feature['geometry']);clipped=geometry.intersection(region)
        if clipped.is_empty:continue
        properties=dict(feature['properties']);metric=project(clipped);hit=complete and metric.intersects(affected);properties['assessment']='unknown' if not complete else 'suspected' if hit else 'not_detected'
        properties['assessment_label']={'unknown':'数据不足，无法评估','suspected':'疑似受影响','not_detected':'当前数据未检出影响（非安全判定）'}[properties['assessment']]
        if properties['kind']=='road':
            length=metric.intersection(affected).length if complete else None;properties['affected_length_m']=None if length is None else round(length,1)
            if length is not None:road_km+=length/1000
        elif hit and properties['kind']=='settlement':settlements+=1;people+=properties.get('population',0)
        elif hit:facilities+=1
        features.append({'type':'Feature','geometry':mapping(clipped),'properties':properties})
    return {'synthetic':True,'event':event,'region':params['region'],'region_bbox':list(region.bounds),'complete':complete,'warnings':warnings,'sources':sources,'geojson':{'type':'FeatureCollection','features':features},'summary':{'suspected_area_km2':round(affected.area/1e6,3) if complete else None,'affected_road_km':round(road_km,2) if complete else None,'affected_settlements':settlements if complete else None,'exposed_population':people if complete else None,'affected_facilities':facilities if complete else None},'evidence':evidence[:2000],'analysis_cell_m':500,'method':{'name':'Synthetic spectral-change and terrain/hydrology screening','imagery_change':'fraction of pixels with mean absolute green/NIR reflectance change > 0.08','earthquake':'change >= threshold; event radius; heuristic score >= 0.35 (not a shaking or damage model)','mudslide':'rain >= 120 mm; slope 12–45 deg; channel distance <= 2000 m; change >= threshold; heuristic score >= 0.45','context':'Whole source tiles are streamed sequentially; analysis uses 500 m cells and exact Polygon clipping. Outputs are suspected areas, not verified disaster observations.','reference_time':'event time + 12 hours','simulation':params.get('simulation','none')},'data_status':'complete' if complete else 'insufficient'}

def process(ctx):
    params=dict(ctx.params);selection(params['region']);operation=params['operation']
    if operation in ('terrain','imagery','hydrology'):result=extract(ctx.input('source').bytes(),params,operation)
    elif operation=='fusion':
        # The streaming SDK consumes named inputs once in lexical order.
        inputs={name:read_json(ctx.input(name).bytes()) for name in ('exposure','hydrology','imagery','terrain')}
        result=fuse(inputs['terrain'],inputs['imagery'],inputs['hydrology'],inputs['exposure'],params)
    else:raise ValueError('Unknown emergency operation')
    out=ctx.bytes_output('result','json');out.write(json.dumps(result,allow_nan=False).encode())

def infer_outputs(inputs,params):
    from xstream_operator import descriptor,result
    return result({'result':descriptor(['bytes'],['json'],None,certainty='known')})

def preview_outputs(inputs,params,limits):
    from xstream_operator import preview_process
    return preview_process(process,inputs,params,limits)

if __name__=='__main__':
    from xstream_operator import run_operator
    run_operator(process,infer_outputs,preview_outputs=preview_outputs)
