#!/usr/bin/env python3
"""Verify lifecycle controls using mocked mutations; keep published applications unchanged."""
import asyncio
from playwright.async_api import async_playwright

async def main():
 async with async_playwright() as p:
  b=await p.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox'])
  page=await b.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  base='http://127.0.0.1:8870'
  doc=await (await page.request.get(base+'/api/appbuilder/apps/observatory')).json()
  all_apps=await (await page.request.get(base+'/api/appbuilder/apps')).json()
  app=next(a for a in all_apps if a['id']=='observatory');deleted=False;calls=[]
  async def listing(route):await route.fulfill(json=[] if deleted else [{**app,'status':doc['status']}])
  async def detail(route):await route.fulfill(json=doc)
  async def change(route):
   nonlocal deleted
   calls.append(route.request.url);assert route.request.post_data_json['revision']==doc['revision']
   if route.request.url.endswith('/delete'):deleted=True;await route.fulfill(json={'deleted':True})
   else:doc['status']=route.request.post_data_json['status'];await route.fulfill(json=doc)
  await page.route('**/api/appbuilder/apps',listing)
  await page.route('**/api/appbuilder/apps/observatory',detail)
  await page.route('**/api/appbuilder/apps/observatory/status',change)
  await page.route('**/api/appbuilder/apps/observatory/delete',change)
  await page.goto(base+'/appbuilder/')
  await page.locator('#toggle-publication').filter(has_text='下线').wait_for()
  await page.locator('#edit-app').click();await page.locator('[data-editor=basic]').click()
  assert await page.get_by_label('数据上下文',exact=True).count()==0
  await page.locator('#finish-edit').click()
  await page.locator('#toggle-publication').click();await page.get_by_role('button',name='确认下线',exact=True).click()
  await page.locator('#toggle-publication').filter(has_text='上线发布').wait_for()
  assert await page.locator('#open-app').is_hidden()
  await page.goto(base+'/services/');await page.locator('#apps-status').filter(has_text='暂无已发布').wait_for()
  assert await page.locator('.case-card').count()==0
  await page.goto(base+'/appbuilder/');await page.locator('#toggle-publication').filter(has_text='上线发布').wait_for()
  await page.locator('#toggle-publication').click();await page.locator('.app-lifecycle-dialog').get_by_role('button',name='上线发布',exact=True).click()
  await page.locator('#toggle-publication').filter(has_text='下线').wait_for()
  assert await page.locator('#open-app').is_visible()
  await page.locator('#delete-app').click();await page.locator('.app-lifecycle-dialog').get_by_role('button',name='取消',exact=True).click()
  assert len(calls)==2
  await page.locator('#delete-app').click();await page.get_by_role('button',name='确认删除',exact=True).click()
  await page.wait_for_function("document.querySelector('#notice').textContent.includes('应用已删除')")
  assert await page.locator('#apps button').count()==0
  assert await page.locator('.overview-actions').is_hidden()
  assert len(calls)==3 and not errors,errors
  await b.close();print('PASS offline/online/delete confirmations, public list visibility and removed data-context control')

if __name__=='__main__':asyncio.run(main())
