#!/usr/bin/env python3
"""Exercise compact forms, nested groups, Job-derived bindings and result configuration without publishing."""
import argparse
import re
import asyncio
from playwright.async_api import async_playwright

async def run(args):
 async with async_playwright() as p:
  browser=await p.chromium.launch(headless=True,executable_path=args.chromium,args=['--no-sandbox'])
  page=await browser.new_page(viewport={'width':1440,'height':1050});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  pass
  await page.goto(args.url+'/appbuilder/')
  async def edit(app):
   await page.locator('#apps button').filter(has=page.locator('small',has_text=re.compile('^'+re.escape(app)+'$'))).click()
   await page.wait_for_function('(id)=>document.querySelector("#overview-id").textContent===id',arg=app)
   await page.locator('#edit-app').click()
   await page.wait_for_function('!document.querySelector("#app-editor").hidden')
  async def nav(title):
   await page.locator('.editor-tabs button').filter(has_text=title).click()
  async def parsed():
   await page.locator('[data-editor=source]').click()
   await page.wait_for_function('!document.querySelector("#source-editor").hidden')
   r=await page.request.post(args.url+'/api/appbuilder/convert',data={'application':await page.locator('#application').input_value(),'job':await page.locator('#job').input_value()})
   assert r.ok,await r.text()
   data=await r.json();await page.locator('[data-editor=inputs]').click();await page.wait_for_function('!document.querySelector("#application-visual").hidden');return data
  print('Checking observatory inputs',flush=True)
  await edit('observatory')
  assert await page.locator('.design-nav').count()==0
  await page.locator('[data-editor=basic]').click()
  assert await page.locator('.theme-card').count()==5
  await page.get_by_role('radio',name='珊瑚红',exact=True).check()
  data=await parsed();assert data['application']['presentation']['theme']=='rose'
  await page.locator('[data-editor=basic]').click()
  assert await page.get_by_role('radio',name='珊瑚红',exact=True).is_checked()
  await page.get_by_role('radio',name='星空靛',exact=True).check()
  await page.locator('[data-editor=inputs]').click()
  assert await page.get_by_role('radio',name='观测天区',exact=True).count()==1
  assert await page.locator('.value-type').count()==0
  original=(await (await page.request.get(args.url+'/api/appbuilder/apps/observatory')).json())['application']
  await nav('输入表单')
  assert await page.locator('.config-item-list>.config-item').count()==3
  assert await page.locator('.config-thumbnail svg').count()==1
  await page.locator('.config-item-list button').filter(has_text='最低信噪比').click()
  await page.get_by_label('默认值',exact=True).fill('8')
  await page.get_by_label('默认值',exact=True).press('Tab')
  data=await parsed();assert data['application']['inputs'][2]['default']==8
  print('Checking derived variable bindings',flush=True)
  await nav('变量绑定')
  assert await page.locator('.config-item-list>.config-item').count()==len(original['variables'])
  assert await page.locator('.binding-complete').count()==1
  assert await page.locator('input[aria-label="新增属性名"]').count()==0
  await page.locator('.config-item-list button').filter(has_text='G_CATALOG_URI').click()
  assert await page.get_by_label('数据集',exact=True).input_value()=='g-band'
  await page.locator('[data-editor=source]').click()
  await page.wait_for_function('!document.querySelector("#source-editor").hidden')
  job=await page.locator('#job').input_value()
  await page.locator('#job').fill(job.replace('$MIN_SNR','$min_snr')+'\n# $COMMENT_ONLY is not a variable\n')
  await page.locator('[data-editor=inputs]').click();await page.wait_for_function('!document.querySelector("#application-visual").hidden');await nav('变量绑定')
  await page.locator('.config-item-list button').filter(has_text='min_snr').click()
  assert await page.get_by_label('取值来源',exact=True).input_value()=='unbound'
  assert await page.get_by_text('COMMENT_ONLY',exact=True).count()==0
  await page.get_by_label('取值来源',exact=True).select_option('input')
  await page.get_by_label('表单字段',exact=True).select_option('min_snr')
  await page.locator('[data-editor=source]').click()
  await page.wait_for_function('!document.querySelector("#source-editor").hidden')
  assert 'min_snr:' in await page.locator('#application').input_value()
  await page.locator('[data-editor=inputs]').click();await page.wait_for_function('!document.querySelector("#application-visual").hidden');await nav('变量绑定')
  await page.locator('.config-advanced summary').click()
  await page.get_by_role('button',name='清理这些绑定').click()
  data=await parsed();assert 'MIN_SNR' not in data['application']['variables'];assert data['application']['variables']['min_snr']=={'input':'min_snr'}
  print('Checking result configuration',flush=True)
  await nav('结果与下载')
  assert await page.locator('.config-result-tabs button').count()==4
  await page.locator('.config-item-list button').filter(has_text='颜色－颜色图').click()
  assert await page.get_by_label('横轴字段',exact=True).input_value()=='g_r'
  await page.get_by_role('button',name='下载文件',exact=True).click()
  assert await page.get_by_label('文件格式',exact=True).input_value()=='csv'
  await page.get_by_label('导出列',exact=True).fill('source_id, ra, dec')
  await page.get_by_label('导出列',exact=True).press('Tab')
  data=await parsed();assert data['application']['result']['artifacts'][0]['columns']==['source_id','ra','dec']
  await page.locator('#validate').click();await page.wait_for_function('document.querySelector("#notice").textContent.includes("配置校验通过")')
  if args.screenshot:
   await nav('结果与下载');await page.get_by_role('button',name='展示组件',exact=True).click();await page.locator('.config-item-list button').filter(has_text='颜色－颜色图').click();await page.screenshot(path=args.screenshot,full_page=True)
  print('Checking nested groups',flush=True)
  await edit('emergency');await nav('输入表单');await page.locator('.config-item-list button').filter(has_text='事件参数').click()
  await page.get_by_role('button',name='编辑分组字段 →').click();assert await page.locator('.config-item-list>.config-item').count()==7
  await page.locator('.config-item-list button').filter(has_text='事件类型').click()
  assert await page.locator('.config-pair-row').count()==2
  await page.locator('.config-pair-row').first.get_by_label('显示文字').fill('地震应急')
  await page.locator('.config-pair-row').first.get_by_label('显示文字').press('Tab')
  data=await parsed();assert data['application']['inputs'][1]['fields'][0]['options'][0]['label']=='地震应急'
  assert data['application']['inputs'][1]['point_fields']==['longitude','latitude']
  await page.set_viewport_size({'width':390,'height':844});assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth')
  assert not errors,errors
  await browser.close();print('Compact typed forms, group navigation, Job-derived bindings, result/download controls and YAML roundtrip: passed')

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--url',default='http://10.0.82.103:8870');parser.add_argument('--chromium',default='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome');parser.add_argument('--screenshot');asyncio.run(run(parser.parse_args()))
