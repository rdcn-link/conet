"""Check independent app pages, theme palettes, initial empty results and narrow screens."""
import asyncio,json
from playwright.async_api import async_playwright
async def main():
 async with async_playwright() as p:
  b=await p.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox'])
  page=await b.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  checked=[]
  for app,theme in [('check-dams','forest'),('observatory','indigo'),('emergency','amber'),('uploaded-csv','coast')]:
   await page.set_viewport_size({'width':1440,'height':1000})
   await page.goto('http://127.0.0.1:8870/apps/'+app+'/');await page.wait_for_function('!!document.body.dataset.theme')
   assert await page.locator('body').get_attribute('data-theme')==theme
   assert await page.locator('.app-hero').is_visible()
   assert await page.locator('#results').is_hidden() and await page.locator('#empty').is_visible()
   assert await page.locator('.conet-navigation').count()==0
   for width in [1440,390]:
    await page.set_viewport_size({'width':width,'height':1000})
    assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'),app
   checked.append(app)
  await b.close();assert not errors,errors;print(json.dumps({'apps':checked,'empty_before_submission':True,'mobile_overflow':False,'browser_errors':errors}))
asyncio.run(main())
