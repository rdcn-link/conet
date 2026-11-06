import json, math, html
import numpy as np
from pyproj import CRS, Transformer
from shapely.geometry import box,Point,shape,mapping
from shapely.ops import transform as transform_geometry

def finite(value):
    value=float(value)
    if not math.isfinite(value):raise ValueError('finite coordinates required')
    return value

def region(params):
    from polygon_region import selection
    geometry=selection(params['region'])
    if list(geometry.bounds)!=params['region_bbox']:raise ValueError('region_bbox must match polygon bounds')
    return geometry

def project_region(params,crs):
    # Densify the actual rings, preserving concavities and holes under projection.
    from shapely.geometry import Polygon
    geometry=region(params)
    def densify(ring):
        points=[];coordinates=list(ring.coords)
        for a,b in zip(coordinates,coordinates[1:]):
            points.extend((a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t) for t in np.linspace(0,1,33))
        return points
    polygon=Polygon(densify(geometry.exterior),[densify(r) for r in geometry.interiors])
    return transform_geometry(Transformer.from_crs('EPSG:4326',crs,always_xy=True).transform,polygon)

def metric_crs(value):
    crs=CRS.from_user_input(value)
    if not crs.is_projected or any(a.unit_name.lower() not in ('metre','meter') for a in crs.axis_info[:2]):
        raise ValueError('projected metre CRS required')
    return crs

def render(result):
    crs=result['crs'];bounds=project_region(result,crs).bounds
    west,south,east,north=bounds;width=1000;height=760
    scale=min(820/max(east-west,1),490/max(north-south,1))
    def xy(x,y):return 90+(x-west)*scale,130+(north-y)*scale
    elements=[]
    selection=project_region(result,crs)
    def ring_path(ring):
        coords=[xy(*c) for c in ring.coords]
        return 'M'+'L'.join(f'{x:.2f},{y:.2f}' for x,y in coords)+'Z'
    path=ring_path(selection.exterior)+''.join(ring_path(r) for r in selection.interiors)
    elements.append(f'<path d="{path}" fill="#eef5ef" fill-rule="evenodd" stroke="#3b6580" stroke-width="2"/>')
    for f in result['susceptible_channels']:
        geometry=shape(f['geometry'])
        for line in (geometry.geoms if hasattr(geometry,'geoms') else [geometry]):
            points=' '.join(f'{x:.2f},{y:.2f}' for x,y in [xy(*c) for c in line.coords])
            elements.append(f'<polyline points="{points}" fill="none" stroke="#4b9bb5" stroke-width="9" opacity=".35"/>')
            elements.append(f'<polyline points="{points}" fill="none" stroke="#187e9b" stroke-width="2"/>')
    for f in result['features']:
        x,y=xy(*f['geometry']['coordinates']);label=html.escape(f['properties']['point_id'])
        elements.append(f'<circle cx="{x}" cy="{y}" r="7" fill="#e48128" stroke="#fff" stroke-width="2"/><text x="{x+12}" y="{y+4}">{label}</text>')
    if not result['features']:elements.append('<text x="120" y="180">没有候选点通过当前筛选条件。</text>')
    length=min(500,math.floor((east-west)/3/100)*100)
    if length>0:
        elements.append(f'<path d="M90 660h{length*scale}" stroke="#183b4a" stroke-width="4"/><text x="90" y="685">{length} m</text>')
    summary=f"{len(result['features'])} 个有效候选 · {len(result['rejections'])} 个剔除点 · {len(result['susceptible_channels'])} 条易发沟道"
    metadata=html.escape(json.dumps(result,allow_nan=False))
    bbox=', '.join(f'{v:.5f}' for v in result['region_bbox'])
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">'
            f'<metadata id="analysis-result">{metadata}</metadata><rect width="1000" height="760" fill="#fcfdfb"/>'
            '<style>text{font-family:system-ui,sans-serif;font-size:14px;fill:#183b4a}</style>'
            '<text x="40" y="45" style="font-size:27px;font-weight:650">淤地坝分布 · three-center Union analysis</text>'
            '<text x="40" y="77" fill="#a9561c">合成数据 · 筛选过程演示，不构成工程建议</text>'
            f'<text x="40" y="105">{summary}</text>'+''.join(elements)+
            '<path d="M940 165v-45m-7 12 7-12 7 12" fill="none" stroke="#183b4a" stroke-width="2"/><text x="934" y="110">N</text>'
            '<circle cx="450" cy="670" r="6" fill="#e48128"/><text x="465" y="675">有效候选点</text>'
            '<path d="M650 670h30" stroke="#187e9b" stroke-width="3"/><text x="690" y="675">易发沟道</text>'
            f'<text x="40" y="718">选择区域（WGS84）： {bbox}</text><text x="40" y="742">地图坐标系： {crs} · 已显示地理选择边界</text></svg>').encode()
