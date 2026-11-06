#!/usr/bin/env python3
"""Generate small, factual reference datasets for the supplementary demo centers."""
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def prepare(root=ROOT):
    data=root/'.state/cases'
    dam=json.loads((data/'check-dams/data/manifest.json').read_text())
    astronomy=json.loads((data/'observatory/data/manifest.json').read_text())
    records={
        ('check-dams','d','tile-coverage.jsonl'):[{'tile':t['tile'],'bbox_wgs84':t['bbox_wgs84'],'resolution_m':t['resolution_m'],'synthetic':True} for t in dam['tiles']],
        ('observatory','e','band-catalog.jsonl'):[{'band':v['band'],'rows':v['rows'],'bytes':v['bytes'],'source_file':k,'synthetic':True} for k,v in astronomy['files'].items()],
        ('check-dams','f','analysis-methods.jsonl'):[{'method':'channel_susceptibility','min_slope_ratio':.08,'max_slope_ratio':.5,'max_entropy':.2,'synthetic':True},{'method':'spatial_overlap','bias_pixels':3,'region_encoding':'GeoJSON Polygon','synthetic':True}],
        ('observatory','g','survey-coverage.jsonl'):[{'footprint':astronomy['footprint'],'coordinate_frame':astronomy['coordinate_frame'],'photometric_system':astronomy['photometric_system'],'total_rows':astronomy['total_rows'],'synthetic':True}]
    }
    for (case,center,name),rows in records.items():
        folder=data/case/'data'/'references'/center;folder.mkdir(parents=True,exist_ok=True)
        content=''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in rows)
        file=folder/name
        if not file.exists() or file.read_text()!=content:file.write_text(content)

if __name__=='__main__':prepare()
