"""Generate approximately 1 GB of deterministic g/r/i FITS catalogs locally."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import numpy as np
from astropy.io import fits


def generate(root,rows=9_000_000,chunk_rows=500_000):
    if root.exists():raise FileExistsError('Data directory must be new')
    root.mkdir(parents=True);files={};bands=['g','r','i'];corrections=[.03,.07,-.04]
    for band_index,(center,band,correction) in enumerate(zip('abc',bands,corrections)):
        folder=root/center;folder.mkdir();path=folder/'catalog.bundle'
        with tarfile.open(path,'w',format=tarfile.USTAR_FORMAT) as archive:
            for part,start in enumerate(range(0,rows,chunk_rows)):
                count=min(chunk_rows,rows-start);rng=np.random.default_rng(20261105+part)
                # Shared latent positions, independently perturbed at each telescope.
                ra=140+20*rng.random(count);dec=-3+10*rng.random(count)
                rmag=15+7*rng.random(count);population=rng.random(count)
                gr=np.where(population<.04,1.9,np.where(population<.08,-.25,.6))+rng.normal(0,.12,count)
                ri=np.where(population<.04,1.1,np.where(population<.08,-.15,.25))+rng.normal(0,.08,count)
                mag=[rmag+gr,rmag,rmag-ri][band_index]
                observed=np.random.default_rng(99000+part+band_index*1000)
                ra+=observed.normal(0,.08/3600,count)/np.cos(np.deg2rad(dec));dec+=observed.normal(0,.08/3600,count)
                ids=np.arange(start,start+count,dtype='int64')
                # Missing counterpart cases, without using IDs for matching.
                ra=np.where((ids%97==band_index)&(band_index!=1),ra+5/3600,ra)
                snr=10+90*observed.random(count);mag+=observed.normal(0,.01,count)
                flux=10**((25-mag+correction)/2.5);quality=(ids%211==band_index).astype('int32')
                columns=[fits.Column(name='SOURCE_ID',format='K',array=ids+band_index*100_000_000),
                  fits.Column(name='RA',format='D',unit='deg',array=ra),fits.Column(name='DEC',format='D',unit='deg',array=dec),
                  fits.Column(name='FLUX',format='E',unit='adu',array=flux.astype('float32')),
                  fits.Column(name='FLUX_ERR',format='E',unit='adu',array=(flux/snr).astype('float32')),
                  fits.Column(name='QUALITY',format='J',array=quality)]
                table=fits.BinTableHDU.from_columns(columns,name='CATALOG');table.header['BAND']=band;table.header['MAGZP']=25.;table.header['ZPCORR']=correction;table.header['RADESYS']='ICRS';table.header['SYNTHET']=True
                stream=io.BytesIO();fits.HDUList([fits.PrimaryHDU(),table]).writeto(stream);data=stream.getvalue()
                member=tarfile.TarInfo(f'{band}/part-{part:03}.fits');member.size=len(data);member.mtime=0;archive.addfile(member,io.BytesIO(data))
                print(center,band,part,flush=True)
        digest=hashlib.sha256()
        with path.open('rb') as source:
            for block in iter(lambda:source.read(1048576),b''):digest.update(block)
        files[str(path.relative_to(root))]={'bytes':path.stat().st_size,'sha256':digest.hexdigest(),'band':band,'rows':rows}
    info={'synthetic':True,'description':'Three fictional observatories; coordinates and photometry are fabricated.',
       'rows_per_observatory':rows,'total_rows':rows*3,'chunk_rows':chunk_rows,'bands':bands,'files':files,
       'total_data_bytes':sum(f['bytes'] for f in files.values()),'default_cone':[150.,2.,.15],'footprint':[140,-3,160,7],
       'columns':['SOURCE_ID','RA','DEC','FLUX','FLUX_ERR','QUALITY'],'seed':20261105,'coordinate_frame':'ICRS','photometric_system':'synthetic AB-like calibration'}
    (root/'manifest.json').write_text(json.dumps(info,indent=2)+'\n');return info

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);p.add_argument('--rows',type=int,default=9_000_000);p.add_argument('--chunk-rows',type=int,default=500_000)
    a=p.parse_args();generate(a.output,a.rows,a.chunk_rows)
