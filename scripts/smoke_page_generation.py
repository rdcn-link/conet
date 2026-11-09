#!/usr/bin/env python3
"""Verify generation, conversational editing, exact preview publication and package persistence."""
import asyncio,io,json,zipfile
from playwright.async_api import async_playwright

async def main():
 async with async_playwright() as p:
  browser=await p.chromium.launch(headless=True,executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox'])
  page=await browser.new_page(viewport={'width':1560,'height':1000})
  errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto('http://127.0.0.1:8870/appbuilder/')
  await page.wait_for_function("document.querySelector('#overview-title').textContent !== '选择应用或新建应用'")
  await page.locator('#apps button').filter(has_text='observatory').first.click()
  await page.wait_for_function("document.querySelector('#overview-id').textContent === 'observatory'")
  await page.locator('#edit-app').click()
  await page.locator('#open-preview').wait_for(state='visible')
  assert await page.locator('#preview').count()==0
  assert await page.locator('#smart-generate').count()==0
  await page.locator('[data-editor=basic]').click()
  assert await page.get_by_label('内容布局',exact=True).count()==0
  assert await page.locator('link[rel=icon]').get_attribute('href')=='/assets/conet-mark.svg'
  await page.locator('#open-preview').click()
  await page.get_by_label('内容布局',exact=True).select_option('split')
  await page.locator('#auto-settings-cancel').click()
  assert not await page.locator('#preview-panel').evaluate('(d)=>d.open')
  await page.locator('#open-preview').click()
  await page.locator('#auto-settings').wait_for()
  await page.get_by_label('内容布局',exact=True).select_option('wide')
  await page.get_by_label('标题区域',exact=True).select_option('compact')
  await page.locator('#auto-settings-generate').click()
  frame=page.frame_locator('#page-preview')
  await frame.locator('#inputs .field').first.wait_for()
  assert await frame.locator('#submit').is_disabled()
  assert await frame.locator('body').get_attribute('data-page-layout')=='wide'
  assert await frame.locator('body').get_attribute('data-page-hero')=='compact'
  assert await page.locator('#assistant-open').count()==0
  assert await page.locator('#preview-panel').evaluate('(d)=>d.open')
  assert (await page.locator('#preview-panel').bounding_box())['width'] > 1400
  responses=[]
  chat_started=asyncio.Event();chat_release=asyncio.Event()
  async def generate(route):
   request=route.request.post_data_json
   if len(responses)==1:
    chat_started.set();await chat_release.wait()
   validated=await page.request.post('http://127.0.0.1:8870/api/appbuilder/validate',data={'application_yaml':request['application_yaml'],'job_yaml':request['job_yaml']})
   app=(await validated.json())['application'];idx=len(responses)
   app.setdefault('presentation',{})['page']={'layout':'stacked' if idx==0 else 'wide','hero':'centered','surface':'outlined','density':'compact','accent':'#126abc','heading':'智能页面'+str(idx),'intro':'对话调整后的介绍','theme':'forest','background':None,'cover':None,'input_labels':{'min_snr':'观测质量阈值'},'input_hints':{'min_snr':'提高阈值，仅保留可靠观测'},'input_order':[f['name'] for f in reversed(app['inputs'])]}
   converted=await page.request.post('http://127.0.0.1:8870/api/appbuilder/convert',data={'application':app,'job':request['job_yaml']})
   response=await converted.json();responses.append(request)
   await route.fulfill(json={'application_yaml':response['application_yaml'],'message':'已完成页面设计','changed':True})
  await page.route('**/api/appbuilder/pages/generate',generate)
  await page.locator('#studio-smart').click()
  await page.wait_for_function("!document.querySelector('#studio-smart').disabled")
  await page.wait_for_function("!document.querySelector('#studio-smart').disabled")
  await frame.locator('#hero-title').filter(has_text='智能页面0').wait_for()
  assert await frame.locator('body').get_attribute('data-page-layout')=='stacked'
  assert await frame.locator('body').get_attribute('data-theme')=='forest'
  assert await frame.locator('body').get_attribute('data-background') is None
  assert await frame.locator('#hero-cover').is_hidden()
  await frame.locator('body').evaluate("()=>window.previewState='keep-this-document'")
  await page.locator('#page-prompt').fill('结果区域加宽')
  await page.locator('#page-send').click()
  await asyncio.wait_for(chat_started.wait(),10)
  assert await page.locator('#page-generating').is_hidden()
  assert await page.locator('#page-send').inner_text()=='回复中…'
  assert await frame.locator('body').evaluate('()=>window.previewState')=='keep-this-document'
  assert await frame.locator('#hero-title').inner_text()=='智能页面0'
  chat_release.set()
  await page.locator('.proposal-apply').last.wait_for()
  assert await frame.locator('body').evaluate('()=>window.previewState')=='keep-this-document'
  assert await frame.locator('#hero-title').inner_text()=='智能页面0'
  await page.locator('.proposal-apply').last.click()
  await frame.locator('#hero-title').filter(has_text='智能页面1').wait_for()
  await page.locator('#studio-undo').click()
  await frame.locator('#hero-title').filter(has_text='智能页面0').wait_for()
  await page.locator('#studio-redo').click()
  await frame.locator('#hero-title').filter(has_text='智能页面1').wait_for()
  assert await page.locator('.proposal-apply').last.is_disabled()
  async def no_change(route):
   request=route.request.post_data_json
   await route.fulfill(json={'application_yaml':request['application_yaml'],'message':'当前页面采用宽幅布局。','changed':False})
  await page.route('**/api/appbuilder/pages/generate',no_change)
  await page.locator('#page-prompt').fill('说明一下当前布局')
  before=await page.locator('#page-preview').get_attribute('srcdoc')
  count=await page.locator('.proposal-apply').count()
  await page.locator('#page-send').click()
  await page.locator('#page-messages').get_by_text('当前页面采用宽幅布局。',exact=True).wait_for()
  assert await page.locator('#page-preview').get_attribute('srcdoc')==before
  assert await page.locator('.proposal-apply').count()==count
  await page.unroute('**/api/appbuilder/pages/generate',no_change)
  assert responses[1]['messages'][-1]['content']=='结果区域加宽'
  assert await frame.get_by_text('观测质量阈值',exact=True).count()==1
  assert await frame.get_by_text('提高阈值，仅保留可靠观测',exact=True).count()==1
  assert await page.locator('#preview-mobile').count()==0
  assert await page.locator('#preview-desktop').count()==0
  await page.locator('#toggle-page-chat').click()
  assert await page.locator('#page-conversation').is_hidden()
  await page.locator('#toggle-page-chat').click()
  assert await page.locator('#page-conversation').is_visible()
  assert await page.locator('#studio-auto').inner_text()=='▦ 重新自动生成'
  assert await page.locator('#studio-smart').inner_text()=='✧ 重新智能生成'
  await page.locator('#close-preview').click()
  assert await page.locator('#export-package').count()==0
  response=await page.request.post('http://127.0.0.1:8870/api/appbuilder/packages/export',data={'application_yaml':await page.locator('#application').input_value(),'job_yaml':await page.locator('#job').input_value()})
  assert response.ok
  with zipfile.ZipFile(io.BytesIO(await response.body())) as z:
   config=z.read('application.yaml').decode()
   assert 'page:' in config and '智能页面1' in config
  submitted=[]
  async def publish(route):
   data=route.request.post_data_json;submitted.append(data)
   await route.fulfill(status=400,json={'error':'测试不修改已发布应用'})
  await page.route('**/api/appbuilder/apps',publish)
  await page.locator('#open-preview').click()
  await page.locator('#publish').click()
  await page.wait_for_function("!document.querySelector('#publish').disabled")
  await page.wait_for_function("document.querySelector('#notice').textContent.includes('测试不修改')")
  assert '智能页面1' in submitted[0]['application_yaml']
  await page.locator('#close-preview').click()
  await page.locator('[data-editor=source]').click()
  await page.locator('#application').fill((await page.locator('#application').input_value()).replace('智能页面1','未预览的修改'))
  await page.locator('#open-preview').click()
  await page.locator('#publish').click()
  await page.wait_for_function("document.querySelector('#notice').textContent.includes('配置已改变')")
  assert len(submitted)==1
  await page.locator('#studio-auto').click()
  await page.locator('#auto-settings-generate').click()
  await page.wait_for_function("!document.querySelector('#studio-auto').disabled")
  await frame.locator('body[data-page-layout=wide]').wait_for()
  assert await frame.get_by_text('观测质量阈值',exact=True).count()==0
  await page.locator('#studio-smart').click()
  await frame.locator('#hero-title').filter(has_text='智能页面2').wait_for()
  assert responses[2]['messages']==[]
  await page.locator('#page-prompt').fill('再换一个布局')
  await page.locator('#page-send').click()
  await page.locator('.proposal-apply').last.wait_for()
  assert await frame.locator('#hero-title').inner_text()=='智能页面2'
  await page.locator('#studio-undo').click()
  await page.wait_for_function("!document.querySelector('#studio-undo').disabled")
  assert await page.locator('.proposal-apply').last.is_disabled()
  await page.locator('#studio-auto').click()
  await page.locator('#auto-settings-generate').click()
  await page.wait_for_function("!document.querySelector('#studio-auto').disabled")
  assert await page.locator('#studio-redo').is_disabled()
  await page.locator('#close-preview').click()
  order=await page.locator('.overview-actions').evaluate('(n)=>[...n.children].map(c=>c.id)')
  assert order[0]=='edit-app'
  actions=await page.locator('.editor-actionbar').evaluate('(n)=>[...n.children].map(c=>c.id)')
  assert actions[-1]=='finish-edit'
  await page.locator('#finish-edit').click()
  await page.locator('#edit-app').click()
  await page.locator('#open-preview').click()
  await page.wait_for_function("document.querySelector('#preview-panel').open")
  assert not await page.locator('#auto-settings').evaluate('(d)=>d.open')
  await frame.locator('#inputs .field').first.wait_for()
  await page.unroute('**/api/appbuilder/apps',publish)
  await page.reload()
  await page.locator('#apps button').filter(has_text='observatory').first.click()
  await page.wait_for_function("document.querySelector('#overview-id').textContent === 'observatory'")
  await page.locator('#edit-app').click()
  await page.locator('[data-editor=source]').click()
  source={'application_yaml':await page.locator('#application').input_value(),'job_yaml':await page.locator('#job').input_value()}
  config=(await (await page.request.post('http://127.0.0.1:8870/api/appbuilder/validate',data=source)).json())['application']
  config.setdefault('presentation',{})['page']={'layout':'stacked','hero':'compact','surface':'soft','density':'compact','accent':'#126abc','heading':'已保存的页面','intro':'恢复已有设计'}
  converted=await (await page.request.post('http://127.0.0.1:8870/api/appbuilder/convert',data={'application':config,'job':source['job_yaml']})).json()
  await page.locator('#application').fill(converted['application_yaml'])
  before_count=len(responses)
  await page.locator('#open-preview').click()
  await page.frame_locator('#page-preview').locator('#hero-title').filter(has_text='已保存的页面').wait_for()
  assert not await page.locator('#auto-settings').evaluate('(d)=>d.open')
  assert len(responses)==before_count
  assert not errors,errors
  await browser.close()
  print('Automatic/intelligent generation, conversation, disabled preview execution, package export and stale-preview protection passed')

if __name__=='__main__':asyncio.run(main())
