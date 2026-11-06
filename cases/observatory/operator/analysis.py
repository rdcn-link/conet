"""Cone selection, calibrated photometry and spherical three-band matching."""
import io
import json
import math
import tarfile
import numpy as np
from astropy.io import fits
from astropy.coordinates import SkyCoord,search_around_sky
import astropy.units as u


class ChunkReader(io.RawIOBase):
    def __init__(self,chunks):self.chunks=iter(chunks);self.pending=memoryview(b'')
    def readable(self):return True
    def readinto(self,target):
        while not self.pending:
            try:self.pending=memoryview(next(self.chunks))
            except StopIteration:return 0
        n=min(len(target),len(self.pending));target[:n]=self.pending[:n];self.pending=self.pending[n:];return n


def validate_cone(params):
    ra,dec,radius=params['cone']
    if not all(isinstance(x,(int,float)) and math.isfinite(x) for x in [ra,dec,radius]) or not (0<=ra<360 and -90<=dec<=90 and .001<=radius<=1):raise ValueError('Expected ICRS RA [0,360), Dec [-90,90], radius [0.001,1] degrees')
    return SkyCoord(ra*u.deg,dec*u.deg,frame='icrs'),radius


def select(chunks,params):
    center,radius=validate_cone(params);rows=[];scanned=0;bad=0;band=None
    with io.BufferedReader(ChunkReader(chunks),buffer_size=65536) as stream:
        with tarfile.open(fileobj=stream,mode='r|') as archive:
            for member in archive:
                if not member.isfile() or member.size>24*1024*1024 or not member.name.endswith('.fits'):raise ValueError('Invalid catalog bundle member')
                data=archive.extractfile(member).read()
                with fits.open(io.BytesIO(data),memmap=False) as hdus:
                    table=hdus['CATALOG'];values=table.data;scanned+=len(values);current=table.header['BAND']
                    if band is not None and band!=current:raise ValueError('Mixed bands in catalog')
                    band=current
                    if table.header.get('RADESYS')!='ICRS':raise ValueError('ICRS required')
                    coordinates=SkyCoord(np.asarray(values['RA'])*u.deg,np.asarray(values['DEC'])*u.deg,frame='icrs')
                    inside=coordinates.separation(center).deg<=radius
                    valid=inside&(values['QUALITY']==0)&np.isfinite(values['FLUX'])&(values['FLUX']>0)&np.isfinite(values['FLUX_ERR'])&(values['FLUX_ERR']>0)
                    snr=values['FLUX']/np.maximum(values['FLUX_ERR'],1e-20);valid&=snr>=params.get('min_snr',5)
                    bad+=int(np.count_nonzero(inside&~valid))
                    for i in np.flatnonzero(valid):
                        flux=float(values['FLUX'][i]);error=float(values['FLUX_ERR'][i]);mag=table.header['MAGZP']-2.5*math.log10(flux)+table.header['ZPCORR']
                        rows.append({'source_id':int(values['SOURCE_ID'][i]),'ra':float(values['RA'][i]),'dec':float(values['DEC'][i]),
                                     'mag':mag,'mag_error':2.5/math.log(10)*error/flux,'snr':float(snr[i])})
                    if len(rows)>50000:raise ValueError('More than 50,000 selected sources; choose a smaller sky region')
        while stream.read(65536):pass
    return {'synthetic':True,'band':band,'cone':params['cone'],'rows':rows,'scanned_rows':scanned,'quality_rejections':bad,
            'photometry':'synthetic AB-like magnitude = MAGZP - 2.5 log10(FLUX) + ZPCORR'}


def sky(rows):return SkyCoord([r['ra'] for r in rows]*u.deg,[r['dec'] for r in rows]*u.deg,frame='icrs')


def pair(reference,other,radius):
    if not reference or not other:return {},set()
    a,b,sep,_=search_around_sky(sky(reference),sky(other),radius*u.arcsec)
    by_a={};by_b={}
    for ia,ib,d in zip(a.tolist(),b.tolist(),sep.arcsec.tolist()):
        by_a.setdefault(ia,[]).append((d,ib));by_b.setdefault(ib,[]).append((d,ia))
    accepted={};ambiguous=set()
    for ia,choices in by_a.items():
        choices.sort();distance,ib=choices[0];reverse=sorted(by_b[ib])
        if len(choices)>1 or len(reverse)>1:ambiguous.add(ia);continue
        if reverse[0][1]==ia:accepted[ia]=(ib,distance)
    return accepted,ambiguous


def crossmatch(g,r,i,params):
    validate_cone(params)
    if [v['band'] for v in [g,r,i]]!=['g','r','i'] or any(v['cone']!=params['cone'] for v in [g,r,i]):raise ValueError('Band/region mismatch')
    radius=params.get('match_radius_arcsec',.8)
    if not isinstance(radius,(int,float)) or not math.isfinite(radius) or not .05<=radius<=3:raise ValueError('Match radius must be 0.05–3 arcsec')
    gr,agr=pair(g['rows'],r['rows'],radius);gi,agi=pair(g['rows'],i['rows'],radius);rows=[];counts={'missing_r':0,'missing_i':0,'ambiguous':0}
    for index,gv in enumerate(g['rows']):
        if index in agr or index in agi:counts['ambiguous']+=1;continue
        if index not in gr:counts['missing_r']+=1;continue
        if index not in gi:counts['missing_i']+=1;continue
        ri,rd=gr[index];ii,idistance=gi[index];rv=r['rows'][ri];iv=i['rows'][ii]
        # All three pairwise separations must be consistent, not only g–r/g–i.
        distance=float(sky([rv]).separation(sky([iv])).arcsec[0])
        if distance>radius:counts['ambiguous']+=1;continue
        color_gr=gv['mag']-rv['mag'];color_ri=rv['mag']-iv['mag']
        classification='red_candidate' if color_gr>=1.5 and color_ri>=.8 else 'blue_candidate' if color_gr<=0 and color_ri<=0 else 'stellar_locus'
        rows.append({'source_id':gv['source_id'],'ra':gv['ra'],'dec':gv['dec'],'g':gv['mag'],'r':rv['mag'],'i':iv['mag'],
             'g_r':color_gr,'r_i':color_ri,'g_r_error':math.hypot(gv['mag_error'],rv['mag_error']),
             'r_i_error':math.hypot(rv['mag_error'],iv['mag_error']),'gr_separation_arcsec':rd,'gi_separation_arcsec':idistance,
             'ri_separation_arcsec':distance,'class':classification,
             'matched_source_ids':{'g':gv['source_id'],'r':rv['source_id'],'i':iv['source_id']}})
    return {'synthetic':True,'cone':params['cone'],'rows':rows,'unmatched':counts,'match_radius_arcsec':radius,
       'band_counts':{v['band']:len(v['rows']) for v in [g,r,i]},'scanned_rows':sum(v['scanned_rows'] for v in [g,r,i]),
       'quality_rejections':sum(v['quality_rejections'] for v in [g,r,i]),
       'classes':{name:sum(r['class']==name for r in rows) for name in ['red_candidate','blue_candidate','stellar_locus']},
       'method':'ICRS spherical, unique mutual matches; all pairwise separations within radius; calibrated synthetic g/r/i colors. Classes are illustrative color selections, not physical identifications.'}


def read_json(ctx,name):
    data=bytearray()
    for block in ctx.input(name).bytes():
        data.extend(block)
        if len(data)>32*1024*1024:raise ValueError('Selected catalog exceeds transfer limit')
    return json.loads(data)


def process(ctx):
    params=dict(ctx.params)
    if params['operation']=='select':value=select(ctx.input('source').bytes(),params)
    elif params['operation']=='crossmatch':
        g=read_json(ctx,'g');i=read_json(ctx,'i');r=read_json(ctx,'r')
        value=crossmatch(g,r,i,params)
    else:raise ValueError('Unknown operation')
    output=ctx.bytes_output('result','json');output.write(json.dumps(value,allow_nan=False).encode())


def infer_outputs(inputs,params):
    from xstream_operator import descriptor,result
    return result({'result':descriptor(['bytes'],['json'],None,certainty='known')})


def preview_outputs(inputs,params,limits):
    from xstream_operator import preview_process
    return preview_process(process,inputs,params,limits)

if __name__=='__main__':
    from xstream_operator import run_operator
    run_operator(process,infer_outputs,preview_outputs=preview_outputs)
