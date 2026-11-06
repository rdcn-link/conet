"""Train a real YOLO segmentation model on fabricated dam motifs, CPU only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import numpy as np
from PIL import Image
import yaml
from generate_data import draw_dam,rgb_background


def train(root,epochs=100):
    os.environ.setdefault('YOLO_CONFIG_DIR',str(root/'yolo-settings'))
    import torch
    from ultralytics import YOLO
    torch.set_num_threads(4)
    data=root/'training';data.mkdir(parents=True,exist_ok=True)
    rng=np.random.default_rng(42)
    for split,count in [('train',64),('val',16)]:
        for kind in ['images','labels']:(data/kind/split).mkdir(parents=True,exist_ok=True)
        for i in range(count):
            image=rgb_background(128,400+i+(1000 if split=='val' else 0));labels=[]
            if i%8!=0:
                r,c=int(rng.integers(25,100)),int(rng.integers(25,100));bounds=draw_dam(image,r,c)
                x0,y0,x1,y1=bounds
                labels.append('0 '+' '.join(str(v/128) for v in [x0,y0,x1,y0,x1,y1,x0,y1]))
            if i%3==0:image[100:104,10:110]=[185,176,160]
            Image.fromarray(image).save(data/'images'/split/f'{i:03}.png')
            (data/'labels'/split/f'{i:03}.txt').write_text('\n'.join(labels)+'\n')
    configuration={'path':str(data.resolve()),'train':'images/train','val':'images/val','names':{0:'synthetic_check_dam'}}
    (data/'data.yaml').write_text(yaml.safe_dump(configuration))
    model=YOLO('yolo11n-seg.yaml')
    model.train(data=str(data/'data.yaml'),epochs=epochs,imgsz=128,batch=8,device='cpu',workers=0,
       project=str(root/'training-runs'),name='synthetic-dams',exist_ok=True,seed=42,deterministic=True,
       pretrained=False,amp=False,plots=False,save=True,verbose=False,patience=0,
       mosaic=0,flipud=0,fliplr=0,degrees=0,translate=0.05,scale=0.1,hsv_h=0,hsv_s=0.05,hsv_v=0.1)
    source=root/'training-runs/synthetic-dams/weights/best.pt';target=root/'model.pt';target.write_bytes(source.read_bytes())
    metrics={k:float(v) for k,v in model.metrics.results_dict.items()}
    (root/'model-card.json').write_text(json.dumps({'synthetic':True,'architecture':'YOLO11n-seg','epochs':epochs,
       'training_images':64,'validation_images':16,'training_seed':42,'weights_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
       'metrics':metrics,'scope':'Trained and evaluated only on synthetic motifs; not validated for real dams.'},indent=2)+'\n')
    return target

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,default=Path('.state/model'));p.add_argument('--epochs',type=int,default=100)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True);print(train(a.output,a.epochs))
