"""Create reproducible, explicitly synthetic terrain, image and HydroID bundles."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import numpy as np
import rasterio
from rasterio.io import MemoryFile
from rasterio.transform import from_origin, xy
from pyproj import Transformer

CRS='EPSG:32649'
BANDS=('red','green','blue')


def draw_dam(image,row,col,scale=1):
    """A fabricated wall/reservoir motif, shared with synthetic model training."""
    h,w=image.shape[:2];r,c=int(row),int(col);s=max(1,int(scale))
    def rect(r0,r1,c0,c1,color):
        image[max(0,r0):min(h,r1),max(0,c0):min(w,c1)]=color
    rect(r-13*s,r-3*s,c-10*s,c+10*s,[55,85,106])
    rect(r-3*s,r+3*s,c-14*s,c+14*s,[188,172,145])
    rect(r+3*s,r+5*s,c-14*s,c+14*s,[70,64,56])
    return [max(0,c-14*s),max(0,r-13*s),min(w-1,c+14*s),min(h-1,r+5*s)]


def rgb_background(size,seed):
    rng=np.random.default_rng(seed)
    noise=rng.normal(0,3,(size,size,1)).astype('float32')
    return np.clip(np.array([155,145,109],dtype='float32')+noise,0,255).astype('uint8')


def tif_bytes(array,transform,nodata):
    if array.ndim==2:array=array[None]
    with MemoryFile() as mem:
        with mem.open(driver='GTiff',width=array.shape[2],height=array.shape[1],count=array.shape[0],
                      dtype=array.dtype,crs=CRS,transform=transform,nodata=nodata) as dst:dst.write(array)
        return mem.read()


def add(archive,name,value):
    entry=tarfile.TarInfo(name);entry.size=len(value);entry.mtime=0;entry.mode=0o644
    archive.addfile(entry,io.BytesIO(value))


def generate(root,tile_size=1700,grid=4,resolution=5):
    if root.exists():raise FileExistsError('Data directory must be new')
    root.mkdir(parents=True)
    for center in 'abc':(root/center).mkdir()
    tiles=[];truth=[];all_lines=[]
    to_geo=Transformer.from_crs(CRS,'EPSG:4326',always_xy=True)
    archives=[tarfile.open(root/c/name,'w',format=tarfile.USTAR_FORMAT) for c,name in [('a','terrain.bundle'),('b','imagery.bundle'),('c','channels.bundle')]]
    try:
        for ty in range(grid):
            for tx in range(grid):
                index=ty*grid+tx;key=f't{index:02}';size=tile_size;step=resolution
                left=330000+tx*size*step;top=4000000-ty*size*step
                transform=from_origin(left,top,step,step)
                rr=np.arange(size)[:,None];cc=np.arange(size)[None,:]
                # Almost all basin pixels share a plateau bin, while narrow gullies
                # are incised: normalized elevation entropy is intentionally low.
                dem=np.full((size,size),1500,dtype='float32')
                c1,c2=int(size*.25),int(size*.75);width=max(2,int(size*.004))
                dem[:,c1-width:c1+width+1]=300+(size-1-rr)*step*.12
                dem[:,c2-width:c2+width+1]=350+(size-1-rr)*step*.02
                catchments=np.broadcast_to(np.where(cc<size//2,1,2),(size,size)).astype('int16').copy()
                hydro=np.zeros((size,size),dtype='int32');features=[]
                edges=[int(size*f) for f in [.03,.33,.65,.97]]
                for k in range(3):
                    hid=1000+index*10+k;start,end=edges[k],edges[k+1]
                    coords=[list(xy(transform,start,c1)),list(xy(transform,end,c1))]
                    features.append({'type':'Feature','geometry':{'type':'LineString','coordinates':coords},'properties':{
                        'HydroID':hid,'NextDownID':hid+1 if k<2 else None,'basin_id':1,'tile':key}})
                    hydro[start:end+1,c1-1:c1+2]=hid
                hid=1000+index*10+3
                features.append({'type':'Feature','geometry':{'type':'LineString','coordinates':[list(xy(transform,edges[0],c2)),list(xy(transform,edges[-1],c2))]},
                                 'properties':{'HydroID':hid,'NextDownID':None,'basin_id':2,'tile':key}})
                hydro[edges[0]:edges[-1]+1,c2-1:c2+2]=hid
                rgb=rgb_background(size,20261105+index)
                # Dam motifs on susceptible channels, off-channel and on a flat
                # non-susceptible channel. Roads are unlabelled confounders.
                locations=[(.16,c1,'eligible'),(.47,c1,'eligible'),(.81,c1,'eligible'),(.40,c2,'non_susceptible'),(.70,int(size*.47),'off_channel')]
                for number,(fraction,col,kind) in enumerate(locations):
                    row=int(size*fraction);bbox=draw_dam(rgb,row,col)
                    # Wall center (rather than reservoir centroid) is the point anchor.
                    x,y=xy(transform,row,col);lon,lat=to_geo.transform(x,y)
                    truth.append({'id':f'{key}-D{number+1}','tile':key,'kind':kind,'row':row,'col':col,
                                  'longitude':lon,'latitude':lat,'pixel_box':bbox})
                # A horizontal road and a dark vegetation patch are difficult negatives.
                road=int(size*.60);rgb[road:road+4,int(size*.5):int(size*.8)]=[185,176,160]
                rgb[int(size*.1):int(size*.14),int(size*.55):int(size*.62)]=[65,93,52]
                extent=[left,top-size*step,left+size*step,top]
                corners=[to_geo.transform(x,y) for x,y in [(extent[0],extent[1]),(extent[0],extent[3]),(extent[2],extent[1]),(extent[2],extent[3])]]
                wgs=[min(c[0] for c in corners),min(c[1] for c in corners),max(c[0] for c in corners),max(c[1] for c in corners)]
                metadata={'synthetic':True,'tile':key,'crs':CRS,'bounds':extent,'bbox_wgs84':wgs,
                          'size':size,'resolution_m':step,'transform':list(transform)[:6],'features':features,'bands':list(BANDS)}
                encoded=json.dumps(metadata).encode()
                for archive in archives:add(archive,key+'/metadata.json',encoded)
                add(archives[0],key+'/dem.tif',tif_bytes(dem,transform,-9999))
                add(archives[0],key+'/catchments.tif',tif_bytes(catchments,transform,0))
                add(archives[1],key+'/imagery.tif',tif_bytes(np.moveaxis(rgb.astype('float32')/255,2,0),transform,-9999))
                add(archives[2],key+'/hydroid.tif',tif_bytes(hydro,transform,0))
                # Independent coordinate control points, not target annotations.
                controls=['row,col,x,y,crs']
                for r,c in [(0,0),(0,size-1),(size-1,0),(size-1,size-1)]:
                    x,y=xy(transform,r,c);controls.append(f'{r},{c},{x},{y},{CRS}')
                add(archives[1],key+'/coordinates.csv',('\n'.join(controls)+'\n').encode())
                tiles.append({k:metadata[k] for k in ['tile','bounds','bbox_wgs84','size','resolution_m']})
                all_lines.extend(features)
                print('Generated',key,flush=True)
    finally:
        for archive in archives:archive.close()
    total_size=tile_size*grid*resolution
    corners=[to_geo.transform(x,y) for x,y in [(330000,4000000-total_size),(330000,4000000),(330000+total_size,4000000-total_size),(330000+total_size,4000000)]]
    bbox=[min(x for x,y in corners),min(y for x,y in corners),max(x for x,y in corners),max(y for x,y in corners)]
    (root/'truth.json').write_text(json.dumps({'synthetic':True,'targets':truth},indent=2)+'\n')
    geo_features=[]
    for f in all_lines:
        geo_features.append(dict(f,geometry={'type':'LineString','coordinates':[to_geo.transform(*p) for p in f['geometry']['coordinates']]}))
    (root/'overview.geojson').write_text(json.dumps({'type':'FeatureCollection','features':geo_features})+'\n')
    files={str(p.relative_to(root)):{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in root.glob('*/*.bundle')}
    info={'synthetic':True,'crs':CRS,'bbox_wgs84':bbox,'tiles':tiles,'tile_size':tile_size,'grid':grid,'resolution_m':resolution,
          'extent_km':total_size/1000,'total_pixels':(tile_size*grid)**2,'files':files,'total_data_bytes':sum(x['bytes'] for x in files.values()),
          'description':'Synthetic geography and dam motifs. Not actual data from the named data centers.'}
    (root/'manifest.json').write_text(json.dumps(info,indent=2)+'\n')
    return info

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);p.add_argument('--tile-size',type=int,default=1700);p.add_argument('--grid',type=int,default=4);p.add_argument('--resolution',type=float,default=5)
    a=p.parse_args();generate(a.output,a.tile_size,a.grid,a.resolution)
