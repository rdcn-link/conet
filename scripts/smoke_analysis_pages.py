#!/usr/bin/env python3
"""Check independent history pages with isolated browser fixtures; creates no real runs."""
import asyncio,copy,json
from playwright.async_api import async_playwright
BASE='http://127.0.0.1:8870'
CHROME='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome'
async def main():
 async with async_playwright() as pw:
  b=await pw.chromium.launch(executable_path=CHROME,args=['--no-sandbox'])
  page=await b.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  records=[{'id':'run-1','app_id':'amber','app_title':'金色应用','state':'failed','created_at':'2026-11-15T08:00:00Z','error':'环境准备失败；缺少算子 test@1.0','publications':[]}, {'id':'run-2','app_id':'forest','app_title':'森林应用','state':'succeeded','created_at':'2026-11-15T08:01:00Z','publications':[]}]
  definitions={a:{'id':a,'title':title,'presentation':{'theme':a,'page':{'layout':'editorial','accent':'#ff0000'},'background':{'image':'/should-not-load.svg'}},'inputs':[{'id':'threshold','label':'筛选阈值','widget':'number'}],'result':{'views':[{'type':'table','path':'/rows','title':'数据预览','columns':['value']}],'artifacts':[]}} for a,title in [('amber','金色应用'),('forest','森林应用')]}
  async def api(route):
   path=route.request.url.split('?')[0].replace(BASE,'')
   if path=='/apps/observatory/api/config':data={'application':{**definitions['forest'],'id':'observatory'},'manifest':{},'web_urls':{}}
   elif path=='/apps/observatory/api/jobs':
    assert route.request.method=='POST';data={'id':'current-run','app_id':'observatory','state':'running'}
   elif path=='/apps/observatory/api/jobs/current-run':data={'id':'current-run','app_id':'observatory','state':'succeeded','result':{'rows':[{'value':1}]},'artifacts':{}}
   elif path=='/api/analyses':data=records
   elif path=='/api/products':data=[]
   elif path=='/api/snapshot':data={'overview':{'members':[]}}
   elif path=='/api/config':data={'centers':[]}
   elif path.endswith('/plan'):data={'job':{'steps':[]}}
   elif path.endswith('/rerun'):
    assert route.request.method=='POST';data={'id':'run-3'}
   elif '/api/jobs/' in path:
    a=path.split('/')[2];r=copy.deepcopy(records[0 if a=='amber' else 1]);r.update(app_id=a,definition=definitions[a],threshold=0.75,result={'rows':[{'value':i} for i in range(30)]},input_retention={'available':True},artifacts={'report':'/fixture-report.md'});data=r
   else:await route.continue_();return
   await route.fulfill(json=data)
  await page.route('**/api/**',api)
  await page.goto(BASE+'/analyses/',wait_until='domcontentloaded');await page.locator('.record-card').first.wait_for();assert await page.locator('.record-card').count()==2
  await page.locator('#filter-app').select_option('amber');assert await page.locator('.record-card').count()==1
  await page.locator('.record-card a').first.click();await page.locator('#run-error').wait_for();assert 'test@1.0' in await page.locator('#run-error').inner_text()
  assert await page.locator('#run-inputs').inner_text()=='筛选阈值\n0.75'
  assert await page.locator('#run-results').is_hidden();assert await page.locator('#publish-run').is_hidden()
  style1=await page.locator('body').evaluate('(e)=>[getComputedStyle(e).backgroundColor,getComputedStyle(e).fontFamily,e.dataset.theme||null]')
  await page.goto(BASE+'/analyses/forest/run-2',wait_until='domcontentloaded');await page.locator('#run-results').wait_for();assert await page.locator('#run-views tbody tr').count()==10
  assert await page.locator('#run-downloads a').get_attribute('href')=='/fixture-report.md'
  assert await page.locator('#publish-run').is_visible()
  style2=await page.locator('body').evaluate('(e)=>[getComputedStyle(e).backgroundColor,getComputedStyle(e).fontFamily,e.dataset.theme||null]');assert style1==style2,(style1,style2)
  assert not await page.locator('script[src$="presentation.js"],link[href$="application.css"]').count()
  # Rerun returns to the independent record, never to a styled application page.
  await page.get_by_role('button',name='重新运行',exact=True).click();await page.wait_for_url('**/analyses/forest/run-3')
  await page.set_viewport_size({'width':390,'height':844});await page.locator('#record-detail').wait_for();assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth')
  await page.goto(BASE+'/apps/observatory/',wait_until='domcontentloaded');await page.locator('#inputs input').first.wait_for()
  assert not await page.locator('.history-panel').count()
  assert await page.locator('#analysis-history-link').get_attribute('href')=='/analyses/?app=observatory'
  await page.locator('#inputs input').first.fill('0.75');await page.locator('#submit').click()
  await page.locator('#analysis-record-link').wait_for();assert await page.locator('#analysis-record-link').get_attribute('href')=='/analyses/observatory/current-run'
  await page.locator('#results').wait_for();assert await page.locator('#submit').is_enabled()
  assert not errors,errors
  print('PASS: history filters, failure details, saved inputs, theme independence, 10-row preview, download/publication actions, rerun route and mobile layout')
  await b.close()
asyncio.run(main())
