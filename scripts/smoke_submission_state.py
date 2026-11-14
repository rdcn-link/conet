"""Control delayed browser API replies to verify submission exclusion and terminal recovery."""
import asyncio,json
from playwright.async_api import async_playwright
async def main():
 async with async_playwright() as p:
  b=await p.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox']);page=await b.new_page();posts=0;state='queued';reject=False;transient=False
  async def jobs(route):
   nonlocal posts
   if route.request.method=='POST':
    posts+=1;await asyncio.sleep(.4)
    await route.fulfill(status=400 if reject else 200,json={'error':'参数校验失败'} if reject else {'id':'test-run'})
   else:await route.fulfill(json=[])
  async def status(route):
   nonlocal transient,state
   if route.request.url.endswith('/cancel'):state='cancelled'
   if transient:transient=False;await route.fulfill(status=503,json={'error':'暂时不可用'});return
   await route.fulfill(json={'id':'test-run','state':state,'created_at':'2026-01-01T00:00:00Z','execution':{'steps':[]}})
  await page.route('**/apps/uploaded-csv/api/jobs',jobs);await page.route('**/apps/uploaded-csv/api/jobs/test-run**',status)
  await page.goto('http://127.0.0.1:8870/apps/uploaded-csv/');await page.wait_for_selector('.file-upload [role=radiogroup]');await page.get_by_role('radio',name='Item URI 输入',exact=True).check();await page.get_by_label('远程 DACP Item URI').fill('dacp://example.invalid:8815/ds/input.csv')
  button=page.locator('#submit');await button.click();assert await button.is_disabled()
  await page.locator('#form').dispatch_event('submit');await asyncio.sleep(.7);assert posts==1
  await page.wait_for_function("document.querySelector('#job-state').textContent==='排队中'");assert await button.is_disabled()
  state='running';await page.wait_for_function("document.querySelector('#job-state').textContent==='执行中'");assert await button.is_disabled()
  transient=True;await asyncio.sleep(1.5);assert await button.is_disabled()
  state='failed';await page.wait_for_function('!document.querySelector("#submit").disabled',timeout=10000)
  reject=True;await button.click();assert await button.is_disabled();await page.wait_for_function('!document.querySelector("#submit").disabled');assert posts==2
  reject=False;state='running';await button.click();await page.wait_for_function("document.querySelector('#job-state').textContent==='执行中'");await page.locator('#cancel-job').click();await page.wait_for_function('!document.querySelector("#submit").disabled');assert await page.locator('#job-state').text_content()=='已取消'
  await b.close();print(json.dumps({'duplicate_submission_blocked':True,'queued_and_running_disabled':True,'transient_poll_failure_stays_disabled':True,'terminal_and_rejection_recovery':True,'cancel_recovery':True}))
asyncio.run(main())
