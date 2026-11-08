"""Bounded WGS84 Polygon selection shared by HTTP validation and operators."""
import math
from shapely.geometry import Polygon,box

MAX_VERTICES=256


def selection(value):
    if not isinstance(value,dict) or set(value)!={'type','coordinates'} or value.get('type')!='Polygon':
        raise ValueError('region must be a GeoJSON Polygon geometry')
    rings=value['coordinates']
    if not isinstance(rings,list) or not rings or len(rings)>16:
        raise ValueError('region requires an exterior ring and at most 15 holes')
    if sum(len(r) for r in rings if isinstance(r,list))>MAX_VERTICES:
        raise ValueError('region exceeds 256 vertices')
    for ring in rings:
        if not isinstance(ring,list) or len(ring)<4 or ring[0]!=ring[-1]:raise ValueError('polygon rings must be closed and have at least three vertices')
        for coordinate in ring:
            if not isinstance(coordinate,list) or len(coordinate)!=2 or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in coordinate):raise ValueError('polygon coordinates must be finite longitude/latitude pairs')
            lon,lat=coordinate
            if not -180<=lon<=180 or not -85<lat<85:raise ValueError('polygon coordinates outside supported WGS84 range')
    polygon=Polygon(rings[0],rings[1:])
    if not polygon.is_valid or polygon.is_empty or polygon.area<=0:raise ValueError('polygon must be valid, nonempty and without self-intersections')
    if polygon.bounds[2]-polygon.bounds[0]>180:raise ValueError('antimeridian-crossing regions are not supported')
    return polygon


def rectangle(bounds):
    w,s,e,n=bounds
    return {'type':'Polygon','coordinates':[[[w,s],[e,s],[e,n],[w,n],[w,s]]]}
