"""Verify anonymous workspace UI, file namespaces and removed account endpoints."""
import argparse, asyncio, json, urllib.request, urllib.error
from playwright.async_api import async_playwright
async def main():
 p=argparse.ArgumentParser();p.add_argument('--url',default='http://127.0.0.1:8870');p.add_argument('--chromium',default='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome');args=p.parse_args();base=args.url.rstrip('/')
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path=args.chromium,args=['--no-sandbox']);page=await browser.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto(base+'/workspace/');await page.wait_for_selector('#workspace-select option',state='attached')
  assert await page.locator('input[type=password]').count()==0
  await page.locator('#create-workspace input').fill('免登录回归空间');await page.locator('#create-workspace button').click();await page.wait_for_function("document.querySelector('#workspace-select').selectedOptions[0]?.textContent==='免登录回归空间'")
  result=await page.evaluate("""async()=>{const id=localStorage.getItem('conet.workspace');const upload=await fetch('/api/storage/upload',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:'sample.txt',content_base64:'aGVsbG8='})});const files=await(await fetch('/api/storage/list',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'})).json();return {id,status:upload.status,files}}""")
  assert result['status']==201 and any(f['name']=='sample.txt' for f in result['files'])
  await page.reload();await page.wait_for_selector('#workspace-select option',state='attached');await page.locator('[data-panel=space-panel]').click();await page.wait_for_selector('#files .file-row');assert await page.locator('#files').inner_text()
  for path in ['/api/account','/account/','/api/admin/users']:
   response=await page.request.get(base+path);assert response.status==404
  for width in [1440,390]:
   await page.set_viewport_size({'width':width,'height':900});assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth+1')
  await page.screenshot(path='/tmp/conet-workspaces.png',full_page=True)
  await page.evaluate("""async()=>await fetch('/api/storage/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:'sample.txt'})})""")
  assert not errors,errors
  print(json.dumps({'anonymous_workspace':'passed','browser_errors':errors,'screenshot':'/tmp/conet-workspaces.png'},ensure_ascii=False));await browser.close()
asyncio.run(main())
