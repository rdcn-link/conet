"""Verify SDK storage in opaque map frames without weakening iframe isolation."""
import argparse,asyncio
from playwright.async_api import async_playwright

async def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',default='http://127.0.0.1:8870')
    parser.add_argument('--chromium',help='Optional Chromium executable path')
    args=parser.parse_args()
    async with async_playwright() as pw:
        browser=await pw.chromium.launch(executable_path=args.chromium,args=['--no-sandbox','--no-proxy-server'])
        page=await browser.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
        await page.route('https://api.tianditu.gov.cn/api**',lambda route:route.fulfill(content_type='text/javascript',body="""
            for (const name of ['localStorage','sessionStorage']) {
                const s=window[name];s.setItem('conet-test','map');
                if(s.getItem('conet-test')!=='map')throw Error('SDK preference unavailable');
                for(let i=0;i<100;i++)s.setItem('key'+i,'value');
                if(s.length>64)throw Error('Unbounded frame storage');
                s.clear();s.setItem('conet-test','map');
            }
            let isolated=false;try{void parent.document.body}catch{isolated=true}
            window.T={Map:function(){}};
            parent.postMessage({type:'storage-probe',isolated,value:localStorage.getItem('conet-test')},'*');
        """))
        await page.goto(args.url,wait_until='domcontentloaded')
        await page.evaluate("""()=>{
            localStorage.setItem('conet-test','parent');
            window.addEventListener('message',e=>{if(e.data?.type==='storage-probe')window.storageProbe=e.data});
            const frame=document.createElement('iframe');frame.sandbox='allow-scripts';
            frame.srcdoc='<script src="/assets/tianditu.js"><'+'/script>';document.body.append(frame);
        }""")
        await page.wait_for_function('window.storageProbe?.value==="map"')
        assert await page.evaluate('storageProbe.isolated')
        assert await page.evaluate('localStorage.getItem("conet-test")')=='parent'
        assert not errors,errors
        await page.evaluate('localStorage.removeItem("conet-test")')
        await browser.close();print('PASS: bounded map SDK preferences and parent isolation')
asyncio.run(main())
