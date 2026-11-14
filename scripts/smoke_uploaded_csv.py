"""Load the CSV application package and verify real browser upload, Union execution and export."""
import argparse,asyncio,json,io,zipfile,time
from pathlib import Path
from playwright.async_api import async_playwright
async def main():
 p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8870');p.add_argument('--chromium',default='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome');args=p.parse_args();base=args.url.rstrip('/');root=Path(__file__).resolve().parents[1]
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path=args.chromium,args=['--no-sandbox']);page=await browser.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  manifest={'format':'conet-application','version':1};out=io.BytesIO()
  with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
   z.writestr('manifest.json',json.dumps(manifest))
   for name in ['application.yaml','job.yaml']:z.writestr(name,(root/'examples/uploaded-csv'/name).read_bytes())
  await page.goto(base+'/appbuilder/');await page.wait_for_selector('#apps .app-card')
  await page.locator('#package-file').set_input_files({'name':'uploaded-csv.conetapp','mimeType':'application/zip','buffer':out.getvalue()})
  await page.wait_for_function("document.querySelector('#notice').textContent.includes('程序包已导入草稿')")
  assert await page.locator('[data-editor]').first.get_attribute('data-editor')=='job'
  assert await page.locator('#job-visual').is_visible()
  assert await page.locator('.configuration-guide').count()==0
  await page.locator('#publish').click();await page.wait_for_function("document.querySelector('#notice').textContent.includes('发布成功')")
  response=await page.request.get(base+'/api/appbuilder/packages/uploaded-csv');assert response.status==200
  with zipfile.ZipFile(io.BytesIO(await response.body())) as package:assert set(package.namelist())=={'manifest.json','application.yaml','job.yaml'}
  await page.goto(base+'/apps/uploaded-csv/');await page.wait_for_selector('input[type=file]')
  await page.locator('input[type=file]').set_input_files(str(root/'examples/uploaded-csv/sample.csv'))
  await page.wait_for_function("document.querySelector('.upload-status').textContent.includes('已就绪')")
  await page.locator('#submit').click();await page.wait_for_function("document.querySelector('#job-state').textContent==='已完成'",timeout=180000)
  rows=await page.request.get(base+'/apps/uploaded-csv/api/jobs');latest=(await rows.json())[0];run=await(await page.request.get(base+'/apps/uploaded-csv/api/jobs/'+latest['id'])).json()
  assert run['state']=='succeeded',run.get('error');assert run['result']['row_count']==3;assert run['result']['column_count']==5
  assert run['source']['uri'].startswith('dacp://');assert run['source']['sha256'];assert run['variables']['INPUT_URI']==run['source']['uri']
  assert run['result']['column_statistics'][3]['empty_count']==1
  assert await page.locator('#views table').count()==2
  for url in run['artifacts'].values():
   response=await page.request.get(base+url);assert response.status==200 and await response.body()
  for width in [1440,390]:
   await page.set_viewport_size({'width':width,'height':950});assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
  await page.set_viewport_size({'width':1440,'height':1000});await page.screenshot(path='/tmp/conet-uploaded-csv.png',full_page=True)
  assert not errors,errors
  print(json.dumps({'url':base+'/apps/uploaded-csv/','run':run['id'],'job_id':run['job_id'],'rows':run['result']['row_count'],'columns':run['result']['column_count'],'browser_errors':errors},ensure_ascii=False));await browser.close()
asyncio.run(main())
