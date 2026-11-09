#!/usr/bin/env python3
"""Exercise rating widgets without writing visitor ratings to the running service."""
import asyncio,json
from playwright.async_api import async_playwright
async def main():
 async with async_playwright() as p:
  browser=await p.chromium.launch(headless=True,executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox'])
  page=await browser.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  votes={}
  async def ratings(route):
   request=route.request
   if request.method=='GET':
    kind=request.url.rsplit('/',1)[-1];await route.fulfill(json={'entries':[v for v in votes.values() if v['kind']==kind]})
   else:
    n=request.post_data_json;key=(n['kind'],n['id']);v={'kind':n['kind'],'id':n['id'],'count':1,'average':float(n['score']),'mine':n['score']};votes[key]=v;await route.fulfill(json=v)
  await page.route('**/api/ratings**',ratings)
  await page.goto('http://127.0.0.1:8870/services/');app=page.locator('[data-rating-kind=app]').first;await app.wait_for();url=page.url
  await app.get_by_role('button',name='评 5 分',exact=True).click();await app.get_by_text('5.0 分 · 1 次评分 · 你评 5 分',exact=True).wait_for();assert page.url==url
  await app.get_by_role('button',name='评 3 分',exact=True).click();await app.get_by_text('3.0 分 · 1 次评分 · 你评 3 分',exact=True).wait_for()
  await page.goto('http://127.0.0.1:8870/catalog/');dataset=page.locator('[data-rating-kind=dataset]').first;await dataset.wait_for(timeout=30000)
  await dataset.get_by_role('button',name='评 4 分',exact=True).click();await dataset.get_by_text('4.0 分 · 1 次评分 · 你评 4 分',exact=True).wait_for()
  await page.locator('.dataset-open').first.click();detail=page.locator('#detail [data-rating-kind=dataset]');await detail.wait_for();await detail.get_by_text('4.0 分 · 1 次评分 · 你评 4 分',exact=True).wait_for()
  await page.goto('http://127.0.0.1:8870/apps/observatory/');await page.locator('.app-hero-copy [data-rating-kind=app]').wait_for();assert not errors,errors
  await browser.close();print('PASS app/dataset ratings, replacement without increment, detail synchronization, card navigation and standalone app')
if __name__=='__main__':asyncio.run(main())
