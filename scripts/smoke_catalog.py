"""Verify lazy dataset browsing, bounded preview and trusted-CA presigned S3 Range reads."""
import argparse,asyncio,json,ssl,urllib.request
from pathlib import Path
from playwright.async_api import async_playwright
async def main():
 p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8870');a=p.parse_args();base=a.url.rstrip('/')
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox']);page=await browser.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto(base+'/catalog/');await page.wait_for_selector('.dataset',timeout=30000)
  catalog=await(await page.request.get(base+'/api/catalog')).json();assert catalog['complete'] and len(catalog['datasets'])>=14
  sample=None;previewed=False
  for d in catalog['datasets']:
   r=await page.request.post(base+'/api/catalog/items',data={'dataset':d['id'],'path':'/'});assert r.status==200,await r.text()
   items=(await r.json())['items']
   for i in items:
    assert i['dacp_uri'].startswith('dacp://') and '?' not in i['dacp_uri']
    if 'bytes' in i['views'] and i.get('s3_uri') and i['kind']=='data':
     sample=(d,i)
     r=await page.request.post(base+'/api/catalog/preview',data={'dataset':d['id'],'path':i['path']});assert r.status==200,await r.text();n=await r.json()
     if n['preview_kind']=='records':assert len(n['rows'])<=20 and len(n['columns'])<=64
     else:assert n.get('sample_bytes',0)<=4096
     previewed=True;break
   if sample:break
  assert sample and previewed
  d,i=sample
  signed=await(await page.request.post(base+'/api/catalog/presign',data={'dataset':d['id'],'path':i['path']})).json();assert 30<=signed['expires_in']<=900
  connection=json.loads(Path('.state/network/connection.json').read_text());context=ssl.create_default_context(cafile=connection['ca'])
  with urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context)).open(urllib.request.Request(signed['url'],headers={'Range':'bytes=0-15'}),timeout=30) as r:assert r.status==206 and len(r.read())<=16
  invalid=await page.request.post(base+'/api/catalog/items',data={'dataset':'../invalid','path':'/'});assert invalid.status==400
  for case in ['check-dams','observatory','emergency']:
   assert (await page.request.get(base+'/cases/'+case+'/')).status==404
   assert (await page.request.get(base+'/apps/'+case+'/')).status==200
  await page.locator('.dataset').filter(has_text=d['id']).click();await page.wait_for_selector('tbody tr')
  assert await page.get_by_role('button',name='临时链接',exact=True).count()==0
  assert await page.get_by_role('button',name='复制S3地址',exact=True).count()>0
  await page.get_by_role('button',name='复制DACP URI',exact=True).first.click();await page.wait_for_function("document.querySelector('#notice').textContent.includes('复制')")
  await page.get_by_role('button',name='预览',exact=True).first.click();await page.wait_for_selector('#preview[open]');await page.wait_for_function("!document.querySelector('#preview-body').textContent.includes('正在读取')");await page.locator('#close-preview').click()
  await page.screenshot(path='/tmp/conet-catalog.png',full_page=True)
  await page.set_viewport_size({'width':390,'height':900});assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
  assert not errors,errors
  print(json.dumps({'datasets':len(catalog['datasets']),'bounded_preview':True,'s3_presign_range':True,'old_case_routes':404,'browser_errors':errors},ensure_ascii=False));await browser.close()
asyncio.run(main())
