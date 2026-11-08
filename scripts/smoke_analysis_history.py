"""Verify complete analysis history, publication states and publishing without creating a dataset."""
import asyncio,json
from playwright.async_api import async_playwright
async def main():
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox']);p=await b.new_page();errors=[];p.on('pageerror',lambda e:errors.append(str(e)));published=False
  history=[{'id':'run-'+str(i),'app_id':'sample','app_title':'测试分析 '+str(i),'created_at':'2026-11-08T08:00:00Z','state':'failed' if i==1 else 'succeeded','url':'/apps/sample/?run='+str(i),'publications':[]} for i in range(23)]
  publication={'product_id':'x'*32,'published_at':'2026-11-08T08:02:00Z','dataset_uri':'dacp://example.test:19860/result-demo','url':'/products/'+'x'*32+'/'}
  history[2]['publications']=[publication]
  async def analyses(route):
   if published:history[0]['publications']=[publication]
   await route.fulfill(json=history)
  await p.route('**/api/analyses',analyses)
  async def publish(route):
   nonlocal published
   if route.request.method=='GET':
    await route.fulfill(json={'metadata':{'title':'测试分析','sources':[],'operators':[],'artifacts':[]},'rights':{'application':{'name':'作者','license':'CC0','view':'allowed','download':'allowed'},'sources':[]},'retention_days':30})
   else:
    body=route.request.post_data_json;assert body['confirmed'];published=True
    await route.fulfill(json={'dataset_uri':publication['dataset_uri'],'preview_url':publication['url']})
  await p.route('**/apps/sample/api/jobs/run-0/publication',publish)
  await p.goto('http://127.0.0.1:8870/workspace/');await p.get_by_role('button',name='我发起的数据分析',exact=True).click();await p.wait_for_selector('.analysis-card')
  assert await p.locator('.analysis-card').count()==20
  assert await p.locator('.analysis-card').nth(1).get_by_role('button',name='发布为 DACP 数据集',exact=True).count()==0
  assert '发布时间' in await p.locator('.analysis-card').nth(2).inner_text()
  await p.get_by_role('button',name='加载更多',exact=False).click();assert await p.locator('.analysis-card').count()==23
  await p.locator('.analysis-card').first.get_by_role('button',name='发布为 DACP 数据集',exact=True).click();await p.wait_for_selector('.publication-dialog[open]');await p.locator('.publication-dialog input[name=confirmed]').check();await p.locator('.publication-dialog').get_by_role('button',name='确认发布',exact=True).click();await p.wait_for_selector('.publication-created:not([hidden])')
  assert published
  assert '发布时间' in await p.locator('.analysis-card').first.inner_text()
  assert await p.locator('.analysis-card').first.get_by_role('button',name='发布为 DACP 数据集',exact=True).count()==0
  assert not errors,errors
  print(json.dumps({'history_pagination':True,'failed_run_not_publishable':True,'publication_details':True,'publish_and_refresh':True,'errors':errors}));await b.close()
asyncio.run(main())
