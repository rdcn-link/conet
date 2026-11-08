"""Verify the shared primary navigation on desktop and mobile pages."""
import argparse,asyncio,json
from playwright.async_api import async_playwright
async def main():
 p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8870');p.add_argument('--chromium',default='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome');args=p.parse_args();base=args.url.rstrip('/')
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path=args.chromium,args=['--no-sandbox']);page=await browser.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)));expected=['/','/services/','/nodes/','/catalog/','/appbuilder/','/workspace/','/screen']
  for path in expected:
   await page.goto(base+path,wait_until='domcontentloaded')
   if path=='/screen':
    assert await page.locator('.conet-navigation,nav').count()==0
    await page.wait_for_selector('#earth');await page.wait_for_selector('#reset-view')
    continue
   await page.wait_for_selector('.conet-navigation nav a')
   assert await page.locator('.conet-navigation nav a').evaluate_all('(els)=>els.map(e=>e.getAttribute("href"))')==expected[1:]
   assert await page.locator('.conet-brand').get_attribute('href')=='/'
   assert await page.get_by_role('link',name='应用首页',exact=True).count()==0
   if path=='/':assert await page.locator('.conet-brand').get_attribute('aria-current')=='page'
   else:assert await page.locator('.conet-navigation nav a[aria-current=page]').get_attribute('href')==path
   for width in [1440,390]:
    await page.set_viewport_size({'width':width,'height':1000})
    assert await page.locator('.conet-navigation').evaluate('(el)=>el.getBoundingClientRect().width<=innerWidth+1')
    assert await page.locator('.conet-navigation nav a').evaluate_all('(els)=>els.every(e=>e.getBoundingClientRect().right<=innerWidth+1)')
   await page.set_viewport_size({'width':1440,'height':1000});await page.screenshot(path='/tmp/conet-navigation-'+(path.strip('/') or 'home')+'.png')
  assert not errors,errors
  print(json.dumps({'primary_navigation':'passed','pages':expected,'browser_errors':errors}));await browser.close()
asyncio.run(main())
