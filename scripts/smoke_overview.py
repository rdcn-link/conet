"""Verify overview counts, bounded recommendations, catalog filters and independent lists."""
import asyncio,json
from playwright.async_api import async_playwright
async def main():
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox'])
  page=await browser.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)));base='http://127.0.0.1:8870'
  apps=await(await page.request.get(base+'/api/appbuilder/apps')).json()
  catalog=await(await page.request.get(base+'/api/catalog')).json()
  stats=await(await page.request.get(base+'/api/appbuilder/statistics')).json()
  await page.goto(base+'/');await page.wait_for_function("document.querySelector('#stat-apps').textContent!=='—'")
  assert await page.locator('#stat-apps').inner_text()==str(len(apps))
  assert await page.locator('#stat-runs').inner_text()==str(stats['submissions'])
  assert await page.locator('.case-card').count()==min(3,len(apps))
  assert await page.locator('#centers').count()==0
  await page.screenshot(path='/tmp/conet-overview.png',full_page=True)
  for width in [390,800,1440]:
   await page.set_viewport_size({'width':width,'height':1000})
   assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'),width
  await page.goto(base+'/services/');await page.wait_for_function("!document.querySelector('#apps-status').textContent.includes('正在')")
  assert await page.locator('.case-card').count()==len(apps)
  await page.goto(base+'/nodes/');await page.wait_for_selector('.center-card');assert await page.locator('.center-card').count()==8
  await page.goto(base+'/catalog/');await page.wait_for_selector('.dataset')
  assert await page.locator('.dataset').count()==len(catalog['datasets'])
  for kind in ['source','product','all']:
   await page.locator('[data-kind='+kind+']').click()
   expected=sum(1 for d in catalog['datasets'] if kind=='all' or bool(d['product'])==(kind=='product'))
   assert await page.locator('.dataset').count()==expected
  await page.route('**/api/snapshot',lambda route:route.fulfill(status=503,content_type='application/json',body='{}'))
  await page.goto(base+'/');await page.wait_for_function("!document.querySelector('#overview-status').textContent.includes('正在')")
  assert await page.locator('#stat-nodes').inner_text()=='—'
  assert await page.locator('#stat-apps').inner_text()==str(len(apps))
  assert not errors,errors
  print(json.dumps({'overview':'passed','catalog_filters':'passed','nodes':8,'service_failure':'passed','browser_errors':errors}));await browser.close()
asyncio.run(main())
