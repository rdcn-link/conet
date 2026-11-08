#!/usr/bin/env python3
"""Verify explicit authoring, XStream Designer return, YAML synchronization and standalone apps."""
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
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        await page.goto(args.url + '/appbuilder/', wait_until='domcontentloaded')
        await page.wait_for_function("document.querySelector('#overview-title').textContent !== '选择应用或新建应用'")
        assert await page.locator('#app-editor').is_hidden()
        await page.locator('#apps button').filter(has_text='observatory-builder-demo').click()
        await page.wait_for_function("document.querySelector('#overview-id').textContent === 'observatory-builder-demo'")
        original = await (await page.request.get(args.url + '/api/appbuilder/apps/observatory-builder-demo')).json()
        await page.locator('#duplicate-app').click()
        await page.wait_for_function("!document.querySelector('#app-editor').hidden")
        await page.locator('[data-editor=basic]').click()
        assert '-copy' in await page.locator('#application-visual input[aria-label="应用标识"]').input_value()
        await page.locator('#finish-edit').click()
        await page.locator('#new-app').click()
        await page.wait_for_function("!document.querySelector('#app-editor').hidden")
        await page.locator('[data-editor=basic]').click()
        assert (await page.locator('#application-visual input[aria-label="应用标识"]').input_value()).startswith('app-')
        await page.locator('#finish-edit').click()
        await page.locator('#edit-app').click()
        await page.locator('[data-editor=basic]').click()
        await page.wait_for_function("document.querySelector('#application-visual input[aria-label=\"应用标识\"]')?.value === 'observatory-builder-demo'")
        await page.locator('#application-visual').get_by_role('textbox', name='应用介绍', exact=True).fill('联合三个天文台观测，探索天体颜色。')
        await page.locator('#application-visual').get_by_role('textbox', name='应用介绍', exact=True).press('Tab')
        await page.locator('[data-editor="job"]').click()
        async with page.expect_popup() as event:
            await page.get_by_role('button', name='↗ 在 Job Designer 中编辑').click()
        designer = await event.value
        designer.on('pageerror', lambda e: errors.append(str(e)))
        await designer.wait_for_selector('#integration-return:not([disabled])', timeout=90000)
        stored = await designer.evaluate("localStorage.getItem('xstream-designer')")
        before = await page.locator('#job').input_value()
        await designer.locator('#integration-return').click()
        await page.wait_for_function("document.querySelector('#notice').textContent.includes('已接收 Job Designer')", timeout=30000)
        assert 'observatory_select@1.0' in await page.locator('#job').input_value()
        assert await designer.evaluate("localStorage.getItem('xstream-designer')") == stored
        await designer.close()
        await page.locator('[data-editor="source"]').click()
        assert '探索天体颜色' in await page.locator('#application').input_value()
        value = await page.locator('#application').input_value()
        await page.locator('#application').fill(value.replace('探索天体颜色', '分析天体颜色'))
        await page.locator('[data-editor="basic"]').click()
        assert '分析天体颜色' in await page.locator('#application-visual').get_by_role('textbox', name='应用介绍', exact=True).input_value()
        await page.locator('#validate').click()
        await page.wait_for_function("document.querySelector('#notice').textContent.includes('配置校验通过')")
        await page.locator('#preview').click()
        await page.wait_for_function("!document.querySelector('#preview-panel').hidden")
        assert await page.locator('#preview-inputs .field').count() > 0
        if args.chat:
            await page.locator('#assistant-open').click()
            await page.wait_for_function("document.querySelectorAll('.interview-bubble.assistant').length === 1", timeout=140000)
            assert await page.locator('.interview-proposal').is_hidden()
            await page.get_by_role('textbox', name='回答助手的问题').fill('面向天文科普学生，应用名称改为天文颜色课堂，其他数据和计算流程保持原样。')
            await page.locator('.application-assistant button[type=submit]').click()
            await page.wait_for_function("document.querySelectorAll('.interview-bubble.assistant').length === 2", timeout=140000)
            await page.get_by_role('textbox', name='回答助手的问题').fill('输入参数和输出图表、表格与下载全部保留当前设计，描述改为学生探索三波段天体颜色。请生成完整应用配置。')
            await page.locator('.application-assistant button[type=submit]').click()
            await page.wait_for_function("!document.querySelector('.interview-proposal').hidden", timeout=140000)
            await page.get_by_role('button', name='应用到草稿', exact=True).click()
            await page.wait_for_function("!document.querySelector('.application-assistant').open")
            assert '天文颜色课堂' in await page.locator('#application-visual input[aria-label="应用名称"]').input_value()
        after = await (await page.request.get(args.url + '/api/appbuilder/apps/observatory-builder-demo')).json()
        assert after['revision'] == original['revision'], 'Draft edits must not publish'
        await page.goto(args.url + '/', wait_until='domcontentloaded')
        await page.wait_for_function("document.querySelectorAll('#case-cards .case-card').length > 0")
        applications = await (await page.request.get(args.url + '/api/appbuilder/apps')).json()
        assert await page.locator('#case-cards .case-card').count() == len(applications)
        assert await page.locator('#clock').count() == 0
        assert await page.locator('header nav a').last.get_attribute('href') == '/screen'
        for case in ['check-dams', 'observatory', 'emergency']:
            await page.goto(args.url + '/apps/' + case + '/', wait_until='domcontentloaded')
            await page.wait_for_function("document.querySelector('#inputs').children.length > 0")
            assert await page.get_by_text('应用工作台', exact=True).count() == 0
            assert await page.locator('#job-yaml').count() == 0
            assert await page.locator('#results').is_hidden()
            assert await page.locator('.app-mark').get_attribute('src') == '/assets/conet-mark.svg'
            assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        if args.screenshot:
            await page.screenshot(path=args.screenshot, full_page=True)
        await page.set_viewport_size({'width': 390, 'height': 844})
        await page.wait_for_timeout(200)
        assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        assert not errors, errors
        await browser.close()
        print('Explicit editing, new/copy drafts, Designer return, YAML roundtrip, dynamic cards, standalone/mobile layouts: passed')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://10.0.82.103:8870')
    parser.add_argument('--chromium')
    parser.add_argument('--screenshot')
    parser.add_argument('--chat', action='store_true', help='Run an actual three-request model interview')
    asyncio.run(run(parser.parse_args()))
