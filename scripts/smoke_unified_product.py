"""Verify both product entry points share dataset browsing and analysis details."""
import asyncio,json
from playwright.async_api import async_playwright
async def main():
 async with async_playwright() as p:
  b=await p.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox']);page=await b.new_page(viewport={'width':1440,'height':1000});errors=[];page.on('pageerror',lambda e:errors.append(str(e)));base='http://127.0.0.1:8870'
  catalog=await(await page.request.get(base+'/api/catalog')).json();d=next(d for d in catalog['datasets'] if d['product']);pid=d['metadata']['product_id'];target='/products/'+pid+'/'
  await page.goto(base+'/catalog/?dataset='+d['id']);await page.wait_for_url('**'+target);await page.wait_for_selector('#detail tbody tr');await page.wait_for_selector('#facts > div')
  assert await page.locator('#detail').evaluate('(e)=>e.getBoundingClientRect().top<document.querySelector("#result-section").getBoundingClientRect().top')
  assert await page.get_by_role('button',name='复制DACP URI',exact=True).count()>0
  await page.get_by_role('button',name='预览',exact=True).first.click();await page.wait_for_selector('#preview[open]');await page.wait_for_function("!document.querySelector('#preview-body').textContent.includes('正在读取')");await page.locator('#close-preview').click()
  selectors=['#detail','.detail-title','.description','.address','.breadcrumbs','#detail th','#detail td','#detail .actions button']
  async def styles():
   return await page.evaluate('''selectors=>Object.fromEntries(selectors.map(s=>{const c=getComputedStyle(document.querySelector(s));return [s,{font:c.fontSize,line:c.lineHeight,padding:c.padding,radius:c.borderRadius}]}))''',selectors)
  product_styles=await styles()
  await page.screenshot(path='/tmp/conet-unified-product.png',full_page=True)
  await page.set_viewport_size({'width':390,'height':900});assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
  await page.goto(base+'/workspace/');await page.get_by_role('button',name='我发起的数据分析',exact=True).click();await page.wait_for_selector('#my-results a');link=page.locator('#my-results a[href="'+target+'"]').first;assert await link.count()==1;await link.click();await page.wait_for_selector('#detail tbody tr');await page.wait_for_selector('#facts > div')
  original=next(d for d in catalog['datasets'] if not d['product']);await page.goto(base+'/catalog/?dataset='+original['id']);await page.wait_for_selector('#detail tbody tr');assert await page.locator('#facts').count()==0
  await page.set_viewport_size({'width':1440,'height':1000});assert await styles()==product_styles
  assert (await page.locator('h1').inner_text()).startswith('数据目录')
  await page.set_viewport_size({'width':390,'height':900});assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
  assert not errors,errors;print(json.dumps({'shared_product_page':True,'dataset_preview':True,'extra_details':True,'mobile':True,'original_dataset':True,'errors':errors}));await b.close()
asyncio.run(main())
