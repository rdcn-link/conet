"""Verify the single-user resource page and removal of workspace management."""
import argparse,asyncio,json
from playwright.async_api import async_playwright
async def main():
 p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8870');a=p.parse_args();base=a.url.rstrip('/')
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox']);page=await browser.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto(base+'/workspace/');await page.wait_for_function("document.querySelector('#my-results').textContent.trim().length>0")
  assert await page.locator('#workspace-select,#create-workspace,#space-panel').count()==0
  apps=await(await page.request.get(base+'/api/appbuilder/apps')).json();products=await(await page.request.get(base+'/api/products')).json()
  assert await page.locator('#resources .card').count()==len(apps)
  await page.get_by_role('button',name='我发布的分析产品',exact=True).click();assert await page.locator('#my-results .card').count()==len(products)
  for path in ['/api/workspaces','/api/workspaces/resources','/api/account','/appbuilder/assets/workspace-context.js']:
   assert (await page.request.get(base+path)).status==404
  assert (await page.request.post(base+'/api/workspaces',data={'name':'removed'})).status==404
  first=await(await page.request.get(base+'/api/storage/summary')).json();second=await(await page.request.get(base+'/api/storage/summary',headers={'X-Conet-Workspace':'no-such-space'})).json();assert first==second
  for width in [1440,390]:
   await page.set_viewport_size({'width':width,'height':900});assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
  assert not errors,errors
  print(json.dumps({'single_user':'passed','apps':len(apps),'products':len(products),'workspace_management_removed':True,'errors':errors}));await browser.close()
asyncio.run(main())
