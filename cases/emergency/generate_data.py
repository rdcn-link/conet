#!/usr/bin/env python3
"""Generate deterministic, georeferenced synthetic emergency inputs (~1 GB)."""
import argparse,io,json,tarfile
from pathlib import Path
import numpy as np
from rasterio.io import MemoryFile
from rasterio.transform import from_origin,from_bounds
from rasterio.warp import reproject,calculate_default_transform,Resampling
from pyproj import Transformer
from PIL import Image

CRS='EPSG:32648';CX=340000;CY=3450000;SEED=20261107

def write_member(archive,name,data):
    item=tarfile.TarInfo(name);item.size=len(data);item.mtime=0;archive.addfile(item,io.BytesIO(data))

def raster(values,transform):
    with MemoryFile() as mem:
        with mem.open(driver='GTiff',height=values.shape[1],width=values.shape[2],count=len(values),dtype='float32',crs=CRS,transform=transform) as out:out.write(values.astype('float32'))
        return mem.read()

def generate(target,size=1400,grid=4,resolution=10):
    if target.exists():raise ValueError('Output already exists; choose a new directory')
    target.mkdir(parents=True);rng=np.random.default_rng(SEED);to_geo=Transformer.from_crs(CRS,4326,always_xy=True)
    half=size*grid*resolution/2;west=CX-half;north=CY+half;files={};tiles=[];archives={}
    for center,name in [('a','terrain.bundle'),('b','imagery.bundle'),('c','hydrology.bundle')]:
        folder=target/center;folder.mkdir();archives[center]=tarfile.open(folder/name,'w')
    preview_size=min(size,140);before_preview=np.zeros((grid*preview_size,grid*preview_size,3),dtype='uint8');after_preview=before_preview.copy()
    try:
        for row in range(grid):
            for col in range(grid):
                name=f't{row*grid+col:02}';x0=west+col*size*resolution;y0=north-row*size*resolution
                xx,yy=np.meshgrid(x0+(np.arange(size)+.5)*resolution,y0-(np.arange(size)+.5)*resolution);dx=xx-CX;dy=yy-CY
                river=np.abs(dx-4000*np.sin(dy/10000))
                elevation=800+230*np.sin(dx/6000)+180*np.cos(dy/9000)+.006*dx+.004*dy+40*np.sin((dx+dy)/1200)
                slope=8+18*np.abs(np.sin(dx/6000)*np.cos(dy/9000))+8*np.exp(-(river/1800)**2)
                green=.18+.025*rng.random((size,size));nir=.30+.03*np.sin(dx/3000);water=river<160
                green=np.where(water,.28,green);nir=np.where(water,.06,nir)
                change=((dx+1500)/13000)**2+((dy-1200)/10500)**2<1
                change&=(river<1600)|(np.sin(dx/1400)*np.cos(dy/1800)>.2)
                post_green=green+change*.20;post_nir=nir-change*.17
                rainfall=140+85*np.exp(-((dx/19000)**2+(dy/23000)**2))+15*np.cos(dy/5000)
                bounds=[x0,y0-size*resolution,x0+size*resolution,y0];w,s=to_geo.transform(bounds[0],bounds[1]);e,n=to_geo.transform(bounds[2],bounds[3])
                corners=[to_geo.transform(x,y) for x,y in [(bounds[0],bounds[1]),(bounds[0],bounds[3]),(bounds[2],bounds[1]),(bounds[2],bounds[3])]]
                bbox=[min(p[0] for p in corners),min(p[1] for p in corners),max(p[0] for p in corners),max(p[1] for p in corners)]
                meta={'tile':name,'bbox_wgs84':bbox,'bounds':bounds,'resolution_m':resolution,'synthetic':True};tiles.append(meta);encoded=json.dumps(meta).encode();transform=from_origin(x0,y0,resolution,resolution)
                for center in archives:write_member(archives[center],name+'/metadata.json',encoded)
                write_member(archives['a'],name+'/terrain.tif',raster(np.stack([elevation,slope]),transform))
                for stage,values,preview in [('before',[green,nir],before_preview),('after',[post_green,post_nir],after_preview)]:
                    write_member(archives['b'],name+'/'+stage+'.tif',raster(np.stack(values),transform))
                    idx=(np.arange(preview_size)*size/preview_size).astype(int);g=values[0][np.ix_(idx,idx)];v=values[1][np.ix_(idx,idx)]
                    rgb=np.stack([np.clip(v*480,0,255),np.clip(g*650,0,255),np.clip((g-v+.3)*470,0,255)],axis=-1).astype('uint8')
                    preview[row*preview_size:(row+1)*preview_size,col*preview_size:(col+1)*preview_size]=rgb
                write_member(archives['c'],name+'/hydrology.tif',raster(np.stack([rainfall,river]),transform))
                print(name,flush=True)
    finally:
        for archive in archives.values():archive.close()
    features=[]
    for axis in ['horizontal','vertical']:
        for j,offset in enumerate(np.linspace(-half+1000,half-1000,29)):
            coords=([(CX-half+500,CY+offset),(CX+half-500,CY+offset)] if axis=='horizontal' else [(CX+offset,CY-half+500),(CX+offset,CY+half-500)])
            features.append({'type':'Feature','geometry':{'type':'LineString','coordinates':[to_geo.transform(*p) for p in coords]},'properties':{'id':f'road-{axis}-{j:02}','kind':'road','name':f'演示道路 {axis[0].upper()}{j+1:02}','observed_at':'2026-08-01T00:00:00Z'}})
    for k in range(180):
        x=CX+rng.uniform(-half*.9,half*.9);y=CY+rng.uniform(-half*.9,half*.9);kind='hospital' if k<10 else 'shelter' if k<25 else 'settlement'
        features.append({'type':'Feature','geometry':{'type':'Point','coordinates':to_geo.transform(x,y)},'properties':{'id':f'{kind}-{k:03}','kind':kind,'name':{'hospital':'演示医院','shelter':'演示避难场所','settlement':'合成聚落'}[kind]+str(k+1),'population':int(rng.integers(100,3000)) if kind=='settlement' else 0,'observed_at':'2026-08-01T00:00:00Z'}})
    folder=target/'d';folder.mkdir();(folder/'exposure.geojson').write_text(json.dumps({'type':'FeatureCollection','synthetic':True,'features':features},ensure_ascii=False))
    for file in sorted(target.glob('*/*')):
        if file.is_file():files[str(file.relative_to(target))]={'bytes':file.stat().st_size}
    corners=[to_geo.transform(x,y) for x,y in [(west,north),(west+2*half,north),(west,north-2*half),(west+2*half,north-2*half)]]
    bbox=[min(p[0] for p in corners),min(p[1] for p in corners),max(p[0] for p in corners),max(p[1] for p in corners)]
    for stage,preview in [('before',before_preview),('after',after_preview)]:
        src=from_bounds(west,north-2*half,west+2*half,north,preview.shape[1],preview.shape[0]);dst,width,height=calculate_default_transform(CRS,'EPSG:3857',preview.shape[1],preview.shape[0],west,north-2*half,west+2*half,north)
        result=np.zeros((height,width,3),dtype='uint8')
        for band in range(3):reproject(preview[:,:,band],result[:,:,band],src_transform=src,src_crs=CRS,dst_transform=dst,dst_crs='EPSG:3857',resampling=Resampling.bilinear)
        Image.fromarray(result).save(target/(stage+'.png'))
    lon,lat=to_geo.transform(CX,CY);radius=.14
    manifest={'synthetic':True,'seed':SEED,'crs':CRS,'bbox_wgs84':bbox,'tiles':tiles,'resolution_m':resolution,'files':files,'total_data_bytes':sum(v['bytes'] for v in files.values()),'default_event':[lon,lat], 'default_region':{'type':'Polygon','coordinates':[[[lon-radius,lat-radius],[lon+radius,lat-radius],[lon+radius,lat+radius],[lon-radius,lat+radius],[lon-radius,lat-radius]]]},'event_time':'2026-08-14T08:00:00Z','description':'Fabricated emergency scenario in a Sichuan regional frame; no real damage observations.'}
    (target/'manifest.json').write_text(json.dumps(manifest,indent=2))
    print('bytes',manifest['total_data_bytes'],flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path);parser.add_argument('--tile-size',type=int,default=1400);parser.add_argument('--grid',type=int,default=4);parser.add_argument('--resolution',type=float,default=10);args=parser.parse_args()
    if not 100<=args.tile_size<=1600 or not 1<=args.grid<=8 or args.resolution<=0:parser.error('invalid grid settings')
    generate(args.output,args.tile_size,args.grid,args.resolution)
