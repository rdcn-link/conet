"""Verify description-driven visual suggestions, draft application and YAML persistence."""
import asyncio,json,re
from playwright.async_api import async_playwright
async def main():
 async with async_playwright() as p:
  b=await p.chromium.launch(executable_path='/home/cnic/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome',args=['--no-sandbox']);page=await b.new_page(viewport={'width':1440,'height':1100});errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto('http://127.0.0.1:8870/appbuilder/');await page.locator('#apps button').filter(has=page.locator('small',has_text=re.compile('^uploaded-csv$'))).click();await page.locator('#edit-app').click();await page.locator('[data-editor=basic]').click()
  await page.get_by_label('应用名称',exact=True).fill('多波段天文星表分析');await page.get_by_label('应用介绍',exact=True).fill('联合天文台星表，分析天区与颜色。')
  await page.wait_for_function("document.querySelector('.recommend-text strong').textContent.includes('星空靛')")
  await page.get_by_role('button',name='应用整套推荐',exact=True).click();assert await page.get_by_role('radio',name='星空靛',exact=True).is_checked()
  assert await page.get_by_label('背景图地址',exact=True).input_value()=='/assets/background-indigo.svg';assert await page.get_by_label('缩略图地址',exact=True).input_value()=='/assets/observatory-cover.svg'
  await page.locator('[data-editor=source]').click();assert '/assets/background-indigo.svg' in await page.locator('#application').input_value()
  await page.locator('[data-editor=basic]').click();await page.get_by_role('radio',name='无背景',exact=True).check();await page.locator('[data-editor=source]').click();assert 'background: null' in await page.locator('#application').input_value()
  await page.locator('[data-editor=basic]').click();await page.set_viewport_size({'width':390,'height':900});assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1')
  await page.set_viewport_size({'width':1440,'height':1100});await page.screenshot(path='/tmp/conet-appearance-recommendations.png',full_page=True)
  assert not errors,errors;await b.close();print(json.dumps({'recommendation_from_description':True,'apply_to_draft':True,'background_yaml_roundtrip':True,'explicit_plain_background':True,'browser_errors':errors}))
asyncio.run(main())
