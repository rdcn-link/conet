"""Offline georeferenced display previews derived from the case's synthetic rasters."""
import io
import json
from pathlib import Path
import tarfile
import numpy as np
from PIL import Image
from rasterio.io import MemoryFile
from rasterio.transform import from_bounds
from rasterio.warp import reproject,Resampling,transform_bounds

def prepare(state):
    data=state/'data';output=state/'visuals';output.mkdir(exist_ok=True)
    sources=[data/'a/terrain.bundle',data/'b/imagery.bundle']
    signature=json.dumps([(str(p.resolve()),p.stat().st_size,p.stat().st_mtime_ns) for p in sources])
    stamp=output/'signature.json'
    if stamp.exists() and stamp.read_text()==signature and all((output/(n+'.png')).exists() for n in ['imagery','terrain','imagery-mercator','terrain-mercator']):return
    manifest=json.loads((data/'manifest.json').read_text());size=2048
    transform=from_bounds(*manifest['bbox_wgs84'],size,size)
    for kind,path in zip(['terrain','imagery'],sources):
        mosaic=np.zeros((3,size,size),dtype='uint8')
        with tarfile.open(path,'r|') as archive:
            for member in archive:
                if not member.name.endswith('/'+('dem.tif' if kind=='terrain' else 'imagery.tif')):continue
                with MemoryFile(archive.extractfile(member).read()) as mem,mem.open() as raster:
                    shape=(raster.count,600,600);values=raster.read(out_shape=shape,resampling=Resampling.bilinear)
                    src_transform=raster.transform @ raster.transform.scale(raster.width/600,raster.height/600)
                    if kind=='imagery':rgb=np.clip(values*255,0,255).astype('uint8')
                    else:
                        z=values[0];gy,gx=np.gradient(z);shade=np.clip(.8-(gx+gy)/80,.35,1)
                        t=np.clip((z-250)/1300,0,1)
                        low=np.array([59,121,118]);high=np.array([218,205,159]);colors=low[None,None,:]+t[:,:,None]*(high-low)[None,None,:]
                        rgb=np.moveaxis(np.clip(colors*shade[:,:,None],0,255).astype('uint8'),2,0)
                    for band in range(3):reproject(rgb[band],mosaic[band],src_transform=src_transform,src_crs=raster.crs,dst_transform=transform,dst_crs='EPSG:4326',resampling=Resampling.bilinear,init_dest_nodata=False)
        Image.fromarray(np.moveaxis(mosaic,0,2)).save(output/(kind+'.png'),optimize=True)
        projected=np.zeros_like(mosaic)
        projected_transform=from_bounds(*transform_bounds('EPSG:4326','EPSG:3857',*manifest['bbox_wgs84']),size,size)
        for band in range(3):reproject(mosaic[band],projected[band],src_transform=transform,src_crs='EPSG:4326',dst_transform=projected_transform,dst_crs='EPSG:3857',resampling=Resampling.bilinear)
        Image.fromarray(np.moveaxis(projected,0,2)).save(output/(kind+'-mercator.png'),optimize=True)
    stamp.write_text(signature)


if __name__ == '__main__':
    import argparse
    parser=argparse.ArgumentParser(description='Generate georeferenced case preview images')
    parser.add_argument('state',type=Path)
    prepare(parser.parse_args().state)
