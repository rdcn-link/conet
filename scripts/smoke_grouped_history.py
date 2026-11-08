"""Verify node filters, App groups and variable-resolved historical DAG snapshots."""
import asyncio,json
from playwright.async_api import async_playwright
async def main():
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox']);p=await b.new_page(viewport={'width':1440,'height':1000});errors=[];p.on('pageerror',lambda e:errors.append(str(e)));base='http://127.0.0.1:8870'
  response=await p.request.post(base+'/api/catalog/refresh',data={});catalog=await response.json();nodes={d['node_id'] for d in catalog['datasets']};assert len(nodes)==8 and 'unknown' not in nodes,nodes
  await p.goto(base+'/catalog/');await p.wait_for_selector('.dataset')
  for nid in nodes:
   await p.locator('#node-filter').select_option(nid);assert await p.locator('.dataset').count()==sum(d['node_id']==nid for d in catalog['datasets'])
  await p.locator('#node-filter').select_option('shared');await p.locator('[data-kind=product]').click();assert await p.locator('.dataset').count()==sum(d['node_id']=='shared' and d['product'] for d in catalog['datasets'])
  history=await(await p.request.get(base+'/api/analyses')).json();run=next(r for r in history if r['state']=='succeeded')
  assert run['app_description'] and run['app_url']
  snapshot=await(await p.request.get(base+'/apps/'+run['app_id']+'/api/jobs/'+run['id']+'/plan')).json();assert snapshot['job']['steps'];assert '$' not in json.dumps([s.get('params',{}) for s in snapshot['job']['steps']])
  original=await(await p.request.get(base+'/apps/'+run['app_id']+'/api/jobs/'+run['id'])).json()
  for step in snapshot['job']['steps']:
   if step['operator'].startswith('read_item@'):assert step['params']['uri'].startswith(('dacp:','context:'))
  await p.goto(base+'/workspace/');await p.get_by_role('button',name='我发起的数据分析',exact=True).click();await p.wait_for_selector('.analysis-card');assert await p.locator('.analysis-app-group').count()==len({r['app_id'] for r in history[:20]})
  await p.get_by_label('发布状态',exact=True).select_option('published');assert await p.locator('.analysis-card').count()==sum(bool(r['publications']) for r in history)
  await p.get_by_label('发布状态',exact=True).select_option('all');await p.get_by_label('执行状态',exact=True).select_option('succeeded');assert await p.locator('.analysis-card').count()==min(20,sum(r['state']=='succeeded' for r in history))
  await p.get_by_role('button',name='查看 Job DAG',exact=True).first.click();await p.wait_for_selector('.analysis-plan-dialog[open]')
  frame=p.frame_locator('.analysis-plan-dialog iframe');await frame.locator('#plan-summary').filter(has_text='已填充变量').wait_for(state='attached',timeout=30000);assert not await frame.locator('#dag-error').is_visible();assert await frame.get_by_role('button',name='YAML',exact=True).count()==0;await p.locator('.analysis-plan-heading button').click()
  await p.screenshot(path='/tmp/conet-grouped-history.png',full_page=True)
  await p.set_viewport_size({'width':390,'height':900});assert await p.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
  assert not errors,errors;print(json.dumps({'node_filters':len(nodes),'history_groups':True,'state_filters':True,'app_details':True,'resolved_dag':True,'mobile':True,'errors':errors}));await b.close()
asyncio.run(main())
