"""Exercise the published app's remote DACP Item input through a real Union Job."""
import asyncio,json,urllib.request
from playwright.async_api import async_playwright
async def main():
 base='http://127.0.0.1:8870'
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox']);page=await browser.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  data=await(await page.request.get(base+'/api/catalog')).json();uri=None
  for d in data['datasets']:
   items=await(await page.request.post(base+'/api/catalog/items',data={'dataset':d['id'],'path':'/'})).json()
   for i in items.get('items',[]):
    if i['kind']=='data' and i['path'].lower().endswith('.csv') and 0<i.get('bytes',0)<100000:uri=i['dacp_uri'];break
   if uri:break
  assert uri,'No small CSV Item is available for this integration test'
  await page.goto(base+'/apps/uploaded-csv/');await page.wait_for_selector('.file-upload [role=radiogroup]');await page.get_by_role('radio',name='Item URI 输入',exact=True).check();await page.get_by_role('textbox',name='远程 DACP Item URI').fill(uri)
  assert await page.locator('input[type=file]').is_hidden()
  await page.locator('#submit').click();await page.wait_for_function("['已完成','执行失败'].includes(document.querySelector('#job-state').textContent)",timeout=180000)
  jobs=await(await page.request.get(base+'/apps/uploaded-csv/api/jobs')).json();run=await(await page.request.get(base+'/apps/uploaded-csv/api/jobs/'+jobs[0]['id'])).json();assert run['state']=='succeeded',run.get('error');assert run['variables']['INPUT_URI']==uri;assert run['result']['row_count']>0
  assert not errors,errors
  print(json.dumps({'remote_item_job':run['job_id'],'run':run['id'],'rows':run['result']['row_count'],'browser_errors':errors},ensure_ascii=False));await browser.close()
asyncio.run(main())
