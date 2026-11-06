"""Bounded, sequential tile processing over Arrow byte streams."""
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import tarfile
import zipfile
import numpy as np
from rasterio.io import MemoryFile
from pyproj import Transformer,CRS
from shapely.geometry import Point,shape,mapping
from shapely.ops import transform as transform_geometry
from common import region,project_region,metric_crs,render

MAX_MEMBER=48*1024*1024
MAX_CELLS=3_000_000


class ChunkReader(io.RawIOBase):
    def __init__(self,chunks):self.chunks=iter(chunks);self.pending=memoryview(b'');self.closed_input=False
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
                if not member.isfile() or member.size>MAX_MEMBER or '..' in Path(member.name).parts:raise ValueError('invalid bundle member')
                yield member.name,archive.extractfile(member).read()
        # Consume the Arrow end frame even when tar reaches its end marker first.
        while stream.read(65536):pass


def read_json(ctx,name):
    data=bytearray()
    for block in ctx.input(name).bytes():
        data.extend(block)
        if len(data)>8*1024*1024:raise ValueError('intermediate JSON exceeds limit')
    return json.loads(data)


def gully_slop(dem,feature):
    line=shape(feature['geometry']);first,last=list(line.coords)[0],list(line.coords)[-1]
    values=[float(v[0]) for v in dem.sample([first,last],masked=True)]
    if not all(math.isfinite(v) and v!=dem.nodata for v in values) or line.length<=0:return None
    return (values[0]-values[1])/line.length


def geo_entropy(elevations,labels,basin_id,bins=16):
    values=elevations[(labels==basin_id)&np.isfinite(elevations)&(elevations!=-9999)]
    if not len(values):return None
    if np.ptp(values)==0:return 0.0
    counts=np.histogram(values,bins=bins)[0];p=counts[counts>0]/len(values)
    return float(-np.sum(p*np.log(p))/np.log(bins))


def terrain(chunks,params):
    region(params);metadata=None;dem_data=None;features=[];tiles=0
    for name,data in bundle(chunks):
        if name.endswith('/metadata.json'):metadata=json.loads(data);dem_data=None
        elif name.endswith('/dem.tif'):dem_data=data
        elif name.endswith('/catchments.tif'):
            if not metadata or dem_data is None:raise ValueError('invalid terrain member order')
            with MemoryFile(dem_data) as dm,MemoryFile(data) as cm,dm.open() as dem,cm.open() as catch:
                if dem.crs!=catch.crs or dem.transform!=catch.transform or dem.shape!=catch.shape or dem.width*dem.height>MAX_CELLS:raise ValueError('inconsistent terrain grids')
                metric_crs(dem.crs);z=dem.read(1);labels=catch.read(1);entropies={b:geo_entropy(z,labels,b,params.get('entropy_bins',16)) for b in [1,2]}
                for f in metadata['features']:
                    props=dict(f['properties']);slope=gully_slop(dem,f);entropy=entropies[props['basin_id']]
                    props.update(slope_ratio=slope,slope_percent=None if slope is None else slope*100,entropy=entropy)
                    features.append(dict(f,properties=props))
                tiles+=1
            dem_data=None
    return {'synthetic':True,'crs':'EPSG:32649','features':features,'tiles_processed':tiles,'region_bbox':params['region_bbox'],'region':params['region'],
            'entropy_definition':'H = -sum(p_i ln(p_i))/ln(16), equally spaced elevation bins over each complete synthetic tile basin'}


def susceptibility(metrics,params):
    by_id={f['properties']['HydroID']:f for f in metrics['features']};selected={};rejected=[]
    for identifier,f in by_id.items():
        p=f['properties'];s=p['slope_ratio'];h=p['entropy']
        if s is None or h is None or not params.get('min_slope_ratio',.08)<=s<=params.get('max_slope_ratio',.50) or h>=params.get('max_entropy',.2):
            rejected.append({'HydroID':identifier,'reason':'seed_threshold','slope_ratio':s,'entropy':h});continue
        current=identifier;seen=set()
        for hop in range(params.get('max_downstream_hops',32)+1):
            if current is None or current not in by_id:break
            if current in seen:raise ValueError('cycle in channel topology')
            seen.add(current);selected.setdefault(current,dict(seed_HydroID=identifier,downstream_hops=hop))
            current=by_id[current]['properties']['NextDownID']
    # Preserve complete topology for analysis; clip geometries only for rendering.
    selection=project_region(params,metrics['crs']);visible=[]
    for identifier,p in selected.items():
        f=by_id[identifier];geom=shape(f['geometry']).intersection(selection)
        if not geom.is_empty:visible.append(dict(f,geometry=mapping(geom),properties=dict(f['properties'],**p)))
    return {'synthetic':True,'crs':metrics['crs'],'region_bbox':params['region_bbox'],'region':params['region'],'HydroIDs':sorted(selected),
            'features':visible,'channel_rejections':rejected,'metrics':metrics['features'],'tiles_processed':metrics['tiles_processed']}


def model_path():
    baked=Path('/opt/models/check-dam.pt')
    if baked.is_file():return baked
    source=Path(__file__)
    if source.exists():return source.with_name('model.pt')
    # Python entrypoint runs directly from a ZIP; the model is an immutable
    # package asset, extracted into tmpfs rather than an intermediate dataset.
    archive=next((p for p in source.parents if p.suffix=='.zip'),None)
    if archive is None:raise ValueError('model package not found')
    target=Path('/tmp/check-dam-model.pt')
    with zipfile.ZipFile(archive) as package:target.write_bytes(package.read('model.pt'))
    return target


def detect(chunks,params):
    import torch
    torch.set_num_threads(2)
    import contextlib,sys
    with contextlib.redirect_stdout(sys.stderr):
        from ultralytics import YOLO
    model=YOLO(str(model_path()));selection=region(params);metadata=None;image_data=None;output=[];windows=0;tiles=0
    for name,data in bundle(chunks):
        if name.endswith('/metadata.json'):metadata=json.loads(data);image_data=None
        elif name.endswith('/imagery.tif'):image_data=data
        elif name.endswith('/coordinates.csv'):
            if not metadata or image_data is None:raise ValueError('invalid imagery member order')
            with MemoryFile(image_data) as mem,mem.open() as image:
                if image.width*image.height>MAX_CELLS or image.count!=3:raise ValueError('invalid imagery')
                for row in csv.DictReader(io.StringIO(data.decode())):
                    x,y=image.xy(int(row['row']),int(row['col']))
                    if CRS.from_user_input(row['crs'])!=image.crs or math.hypot(x-float(row['x']),y-float(row['y']))>.01:raise ValueError('coordinate control mismatch')
                if not selection.intersects(__import__('shapely').geometry.box(*metadata['bbox_wgs84'])):continue
                tile_selection=project_region(params,image.crs);to_geo=Transformer.from_crs(image.crs,'EPSG:4326',always_xy=True)
                items=[];offsets=[]
                def run_batch():
                    nonlocal windows
                    if not items:return
                    predictions=model.predict(items,imgsz=128,conf=params.get('min_confidence',.35),iou=.5,device='cpu',verbose=False)
                    windows+=len(items)
                    for prediction,(r0,c0) in zip(predictions,offsets):
                        for coords,conf in zip(prediction.boxes.xyxy.tolist(),prediction.boxes.conf.tolist()):
                            x0,y0,x1,y1=coords;col=c0+(x0+x1)/2;row=r0+(y0+y1)/2
                            x,y=image.transform*(col+.5,row+.5);lon,lat=to_geo.transform(x,y)
                            if not selection.covers(Point(lon,lat)):continue
                            output.append({'point_id':f"{metadata['tile']}-M{len(output)+1:04}",'tile':metadata['tile'],'x':x,'y':y,
                                           'longitude':lon,'latitude':lat,'confidence':round(conf,5),'pixel_box':[c0+x0,r0+y0,c0+x1,r0+y1]})
                    items.clear();offsets.clear()
                from rasterio.windows import Window,bounds
                for row in range(0,image.height,96):
                    for col in range(0,image.width,96):
                        window=Window(col,row,min(128,image.width-col),min(128,image.height-row))
                        if not tile_selection.intersects(__import__('shapely').geometry.box(*bounds(window,image.transform))):continue
                        rgb=np.moveaxis(np.clip(image.read(window=window)*255,0,255).astype('uint8'),0,2)
                        padded=np.full((128,128,3),150,dtype='uint8');padded[:rgb.shape[0],:rgb.shape[1]]=rgb
                        # Ultralytics expects BGR arrays.
                        items.append(padded[:,:,::-1].copy());offsets.append((row,col))
                        if len(items)==16:run_batch()
                run_batch();tiles+=1
            image_data=None
    # Overlapping windows can detect the same object more than once.
    unique=[];duplicates=[]
    for p in sorted(output,key=lambda p:-p['confidence']):
        if any(math.hypot(p['x']-q['x'],p['y']-q['y'])<params.get('duplicate_distance_m',60) for q in unique):
            duplicates.append({'point_id':p['point_id'],'reason':'duplicate_detection'});continue
        unique.append(p)
    return {'synthetic':True,'crs':'EPSG:32649','region_bbox':params['region_bbox'],'region':params['region'],'points':unique,'rejections':duplicates,
            'windows_inferred':windows,'tiles_inferred':tiles,'model':'YOLO11n-seg trained only on synthetic motifs'}


def overlap(hazards,detected,chunks,params):
    if hazards['region']!=detected['region'] or hazards['region']!=params['region']:raise ValueError('selection mismatch')
    selection=region(params);selected=set(hazards['HydroIDs']);accepted=[];rejected=list(detected['rejections']);processed=set();metadata=None
    by_tile={}
    for p in detected['points']:
        if not selection.covers(Point(p['longitude'],p['latitude'])):
            rejected.append({'point_id':p['point_id'],'reason':'outside_region'});processed.add(p['point_id']);continue
        by_tile.setdefault(p['tile'],[]).append(p)
    bias=params.get('bias',3)
    if not isinstance(bias,int) or not 0<=bias<=16:raise ValueError('invalid pixel window bias')
    for name,data in bundle(chunks):
        if name.endswith('/metadata.json'):metadata=json.loads(data)
        elif name.endswith('/hydroid.tif'):
            for p in by_tile.get(metadata['tile'],[]):
                with MemoryFile(data) as mem,mem.open() as grid:
                    row,col=grid.index(p['x'],p['y']);choices=[]
                    from rasterio.windows import Window
                    r0,c0=max(0,row-bias),max(0,col-bias);r1,c1=min(grid.height,row+bias+1),min(grid.width,col+bias+1)
                    if r1>r0 and c1>c0:
                        values=grid.read(1,window=Window(c0,r0,c1-c0,r1-r0))
                        for rr,cc in zip(*np.nonzero(values)):
                            x,y=grid.xy(r0+rr,c0+cc);choices.append((math.hypot(p['x']-x,p['y']-y),int(values[rr,cc])))
                processed.add(p['point_id']);choices.sort(key=lambda v:(v[0],v[1]));ids=sorted(set(v[1] for v in choices))
                if not choices or choices[0][1] not in selected:
                    rejected.append(dict(point_id=p['point_id'],reason='no_channel_in_window' if not choices else 'non_susceptible_channel',matched_HydroIDs=ids));continue
                props=dict(p,HydroID=choices[0][1],distance_to_channel_m=round(choices[0][0],2),ambiguous=len(ids)>1,matched_HydroIDs=ids)
                accepted.append({'type':'Feature','geometry':{'type':'Point','coordinates':[p['x'],p['y']]},'properties':props})
    for p in detected['points']:
        if p['point_id'] not in processed:rejected.append({'point_id':p['point_id'],'reason':'missing_channel_tile'})
    geojson={'type':'FeatureCollection','features':[dict(f,geometry={'type':'Point','coordinates':[f['properties']['longitude'],f['properties']['latitude']]}) for f in accepted]}
    return {'synthetic':True,'crs':hazards['crs'],'region_bbox':params['region_bbox'],'region':params['region'],'features':accepted,'geojson':geojson,
       'susceptible_channels':hazards['features'],'candidate_count':len(detected['points']),'rejections':rejected,
       'channel_rejections':hazards['channel_rejections'],'parameters':params,'model':detected['model'],
       'HydroIDs':hazards['HydroIDs'],'windows_inferred':detected['windows_inferred'],
       'method':'Synthetic YOLO detection, normalized elevation entropy and topology, nearest HydroID in 7x7 window.'}


def process(ctx):
    params=dict(ctx.params);region(params);operation=params['operation']
    if operation=='terrain':value=terrain(ctx.input('source').bytes(),params)
    elif operation=='susceptibility':value=susceptibility(read_json(ctx,'source'),params)
    elif operation=='detect':value=detect(ctx.input('source').bytes(),params)
    elif operation=='overlap':value=overlap(read_json(ctx,'a_hazards'),read_json(ctx,'b_candidates'),ctx.input('c_grids').bytes(),params)
    elif operation=='map':
        out=ctx.bytes_output('result','svg');out.write(render(read_json(ctx,'source')));return
    else:raise ValueError('unknown operation')
    out=ctx.bytes_output('result','json');out.write(json.dumps(value,allow_nan=False).encode())


def infer_outputs(inputs,params):
    from xstream_operator import descriptor,result
    return result({'result':descriptor(['bytes'],['svg' if params['operation']=='map' else 'json'],None,certainty='known')})


def preview_outputs(inputs,params,limits):
    from xstream_operator import preview_process
    return preview_process(process,inputs,params,limits)

if __name__=='__main__':
    from xstream_operator import run_operator
    run_operator(process,infer_outputs,preview_outputs=preview_outputs)
