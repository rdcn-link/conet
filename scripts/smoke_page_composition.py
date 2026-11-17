#!/usr/bin/env python3
"""Exercise composed pages with a mocked backend; never submit a real analysis."""
import asyncio,json,mimetypes
from pathlib import Path
from urllib.parse import urlsplit
from playwright.async_api import async_playwright

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'conet-appbuilder/src/main/resources/appbuilder'

async def main():
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox'])
  page=await browser.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  app=dict(id='composition-test',title='内容自由组合测试',description='验证自由布局中的分析交互',inputs=[dict(name='threshold',label='分析阈值',widget='number',default=5,required=True)],result=dict(views=[dict(type='table',title='结果记录',path='/rows',columns=['id','value'])]),presentation=dict(page=dict(accent='#147f91',heading='自由组合分析台',intro='结果区域可以独立放置，输入和进度在侧栏组合。',max_width=1480,palette=dict(background='#edf3f6',surface='#ffffff',text='#213f54',muted='#718694',border='#d7e3eb',accent='#147f91'),composition=dict(type='grid',columns=12,gap=24,children=[dict(type='panel',span=8,children=[dict(type='slot',name='intro'),dict(type='slot',name='results')]),dict(type='stack',span=4,children=[dict(type='panel',children=[dict(type='text',text='配置分析条件',size=22,weight=700),dict(type='slot',name='inputs')]),dict(type='panel',children=[dict(type='slot',name='progress')])])]))))
  submissions=[];polls=0;cancelled=False
  async def route(request):
   nonlocal polls,cancelled
   path=urlsplit(request.request.url).path
   if path=='/apps/composition-test/':return await request.fulfill(content_type='text/html',body=(ASSETS/'application.html').read_text())
   if path.startswith('/appbuilder/assets/'):
    file=ASSETS/path.rsplit('/',1)[-1]
    if file.is_file():return await request.fulfill(content_type=mimetypes.guess_type(file.name)[0] or 'application/octet-stream',body=file.read_bytes())
   if path in ['/ratings.js','/ratings.css'] or path.startswith('/assets/'):
    file=ROOT/'web'/path.lstrip('/')
    if file.is_file():return await request.fulfill(content_type=mimetypes.guess_type(file.name)[0] or 'application/octet-stream',body=file.read_bytes())
   if path=='/apps/composition-test/api/config':return await request.fulfill(json=dict(application=app,manifest={}))
   if path=='/api/ratings/app':return await request.fulfill(json={})
   if path=='/apps/composition-test/api/jobs' and request.request.method=='POST':
    submissions.append(request.request.post_data_json);polls=0;cancelled=False
    return await request.fulfill(status=202,json=dict(id='run-test',state='queued'))
   if path=='/apps/composition-test/api/jobs/run-test/cancel':
    cancelled=True;return await request.fulfill(json=dict(id='run-test',state='cancelled'))
   if path=='/apps/composition-test/api/jobs/run-test':
    polls+=1
    if len(submissions)==2:return await request.fulfill(json=dict(id='run-test',state='failed',error='步骤 parse 读取输入失败：示例错误'))
    if polls<2 or len(submissions)==3:return await request.fulfill(json=dict(id='run-test',state='running'))
    return await request.fulfill(json=dict(id='run-test',state='succeeded',result=dict(rows=[dict(id=i,value=i*2) for i in range(25)]),artifacts=dict(table='/example.csv')))
   return await request.fulfill(status=404,json={'error':'Mock route unavailable'})
  await page.route('**/*',route)
  await page.goto('http://composition.test/apps/composition-test/')
  await page.locator('.generated-page [data-slot=inputs] input').wait_for()
  assert await page.locator('body').get_attribute('data-page-layout')=='free'
  assert await page.locator('[data-slot]').count()==4
  results=await page.locator('[data-slot=results]').bounding_box();inputs=await page.locator('[data-slot=inputs]').bounding_box()
  assert results['x']<inputs['x'] and results['width']>inputs['width']
  assert await page.locator('#results').is_hidden()
  await page.locator('#inputs input').fill('12')
  await page.locator('#submit').click();await page.locator('#cancel-job').wait_for(state='visible')
  assert await page.locator('#submit').is_disabled()
  await page.locator('#results').wait_for(state='visible')
  assert submissions==[{'threshold':12}]
  assert await page.locator('#views tbody tr').count()==10
  assert await page.locator('#downloads a').count()==1
  await page.locator('#submit').click();await page.locator('#job-error').wait_for(state='visible')
  assert '示例错误' in await page.locator('#job-error').inner_text()
  assert await page.locator('#submit').is_enabled()
  await page.locator('#submit').click();await page.locator('#cancel-job').wait_for(state='visible')
  await page.locator('#cancel-job').click();await page.wait_for_function("document.body.dataset.execution==='cancelled'")
  assert cancelled and await page.locator('#submit').is_enabled()
  await page.set_viewport_size({'width':390,'height':860})
  assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth')
  results=await page.locator('[data-slot=results]').bounding_box();inputs=await page.locator('[data-slot=inputs]').bounding_box()
  assert inputs['y']>results['y']
  assert not errors,errors
  await browser.close();print('PASS: free composition, form binding, disabled submit, progress, errors, cancellation, result/download rendering and narrow screens')

if __name__=='__main__':asyncio.run(main())
