#!/usr/bin/env python3
"""Publish a synthetic run through the UI, then verify an independent anonymous share page."""
import argparse,asyncio,json
from playwright.async_api import async_playwright
async def run(args):
 report=json.load(open(args.run));base=args.url.rstrip('/')
 async with async_playwright() as p:
  browser=await p.chromium.launch(headless=True,executable_path=args.chromium,args=['--no-sandbox'])
  page=await browser.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  pass
  await page.goto(base+'/apps/'+report['app']+'/')
  await page.locator('.history-panel summary').click()
  button=page.locator('#history button[data-id="'+report['run_id']+'"]');await button.click()
  await page.wait_for_selector('#publish-result');await page.locator('#publish-result').click()
  await page.wait_for_selector('.publication-party')
  dialog=page.locator('.publication-dialog')
  await dialog.get_by_label('应用作者',exact=True).fill('CONET 示例应用开发者')
  for party in await dialog.locator('.publication-party').all():
   owner=party.get_by_label('数据权利人',exact=True)
   if await owner.count():await owner.fill('CONET 合成演示数据生成方')
   await party.get_by_label('使用许可',exact=True).fill('仅供本机合成数据功能演示；不代表任何外部机构的真实数据授权，不用于实际科研或业务结论。')
   await party.get_by_label('署名及引用要求',exact=True).fill('注明 CONET 合成示例及对应分析编号。')
   await party.get_by_label('公开结果',exact=True).select_option('allowed')
   await party.get_by_label('成果下载',exact=True).select_option('allowed')
  await dialog.locator('.publication-downloads input[value=csv]').check()
  await dialog.locator('input[name=confirmed]').check()
  await dialog.get_by_role('button',name='确认发布',exact=True).click()
  await page.wait_for_selector('.publication-created:not([hidden])',timeout=60000)
  url=await dialog.locator('.publication-created>a').get_attribute('href');report['product_url']=url
  public=await browser.new_context(viewport={'width':1440,'height':1000});visitor=await public.new_page();visitor.on('pageerror',lambda e:errors.append(str(e)))
  await visitor.goto(base+url);await visitor.wait_for_function('document.querySelector("#product-title").textContent!=="正在加载分析结果…"')
  await visitor.wait_for_selector('#views section')
  assert (await visitor.locator('#dataset-uri').inner_text()).startswith('dacp://')
  assert await visitor.get_by_text('应用工作台',exact=True).count()==0
  assert await visitor.locator('#product-downloads a[href$="matches.csv"]').count()==1
  denied=await visitor.request.get(base+url+'files/analysis.json');assert denied.status==403
  private=await visitor.request.get(base+url+'files/publication.json');assert private.status==403
  meta=await (await visitor.request.get(base+url+'api')).json();assert 'manage_hash' not in json.dumps(meta);assert meta['rights']['verification']=='self_declared'
  assert 'must-not-appear' not in json.dumps(meta)
  if args.screenshot:await visitor.screenshot(path=args.screenshot,full_page=True)
  await visitor.set_viewport_size({'width':390,'height':844});assert await visitor.evaluate('document.documentElement.scrollWidth<=innerWidth')
  assert not errors,errors
  if args.output:
   with open(args.output,'w') as f:json.dump(report,f,ensure_ascii=False,indent=2)
  print(json.dumps(report,ensure_ascii=False,indent=2));await browser.close()
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--url',default='http://10.0.82.103:8870');parser.add_argument('--chromium',default='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome');parser.add_argument('--run',required=True);parser.add_argument('--output');parser.add_argument('--screenshot');asyncio.run(run(parser.parse_args()))
