#!/usr/bin/env python3
"""Check generated forms, editor preview, history rendering and initially blank results."""
import argparse
import asyncio
from playwright.async_api import async_playwright


async def run(args):
    async with async_playwright() as p:
        launch = {'headless': True, 'args': ['--no-sandbox']}
        if args.chromium:
            launch['executable_path'] = args.chromium
        browser = await p.chromium.launch(**launch)
        page = await browser.new_page(viewport={'width': 1440, 'height': 1000})
        pass
        await page.route('**/appbuilder/assets/map.html', lambda route: route.fulfill(
            body='<html lang="zh-CN"><body>地图测试占位；独立验证地图服务连接。</body></html>', content_type='text/html'))
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        for case in ['check-dams', 'observatory', 'emergency']:
            print('Checking', case, flush=True)
            await page.goto(args.url + '/apps/' + case + '/', wait_until='domcontentloaded')
            await page.wait_for_function("document.querySelector('#inputs').children.length > 0")
            assert await page.locator('#results').is_hidden()
            assert await page.get_by_text('应用工作台',exact=True).count()==0
            assert await page.locator('a[href="/"],a[href="/appbuilder/"]').count()==0
            assert await page.get_by_text('Job YAML',exact=True).count()==0
            # Empty histories are valid after an administrator clears usage records.
            jobs = await (await page.request.get(args.url + '/apps/' + case + '/api/jobs')).json()
            await page.locator('.history-panel>summary').click()
            assert await page.locator('.history-panel').get_attribute('open') is not None
            if any(j['state'] == 'succeeded' for j in jobs):
                complete = page.locator('#history button').filter(has_text='已完成').first
                await complete.click()
                await page.wait_for_function("!document.querySelector('#results').hidden")
                assert await page.locator('#downloads a').count() >= 2
                assert await page.locator('#views section').count() >= 2
            else:
                assert await page.locator('#history button').count() == len(jobs)
        await page.goto(args.url + '/appbuilder/', wait_until='domcontentloaded')
        await page.wait_for_function("document.querySelector('#overview-title').textContent !== '选择应用或新建应用'");await page.locator('#edit-app').click();await page.wait_for_function("document.querySelector('#application').value.length > 0")
        await page.locator('#validate').click()
        await page.wait_for_function("document.querySelector('#notice').textContent.includes('配置校验通过')")
        await page.locator('#open-preview').click()
        await page.locator('#auto-settings-generate').click()
        await page.wait_for_function("document.querySelector('#preview-panel').open")
        await page.frame_locator('#page-preview').locator('#inputs .field').first.wait_for()
        assert await page.frame_locator('#page-preview').locator('#submit').is_disabled()
        assert await page.locator('#statistics').text_content()
        if args.screenshot:
            await page.screenshot(path=args.screenshot, full_page=True)
        await browser.close()
        assert not errors, errors
        print('Application forms, initially blank results, history, result views and editor preview: passed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8870')
    parser.add_argument('--chromium')
    parser.add_argument('--screenshot')
    asyncio.run(run(parser.parse_args()))
