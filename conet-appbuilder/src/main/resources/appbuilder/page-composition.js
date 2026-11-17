(()=>{'use strict';
function mount(body,page){
 if(!page.composition)return;
 const main=body.querySelector('.app-main');if(!main)return;
 const slots={intro:main.querySelector('.app-hero'),inputs:main.querySelector('.input-panel'),progress:main.querySelector('.progress-panel'),results:main.querySelector('.result-panel')};
 if(Object.values(slots).some(n=>!n))throw Error('页面缺少分析功能区域');
 const used=new Set();let count=0;
 function build(n,depth=0){
  if(!n||depth>6||++count>80)throw Error('页面结构无效');
  const node=document.createElement(n.type==='text'?'p':n.type==='image'?'figure':'div');node.className='composition-node composition-'+n.type;
  for(const key of ['gap','padding','radius'])if(Number.isInteger(n[key]))node.style.setProperty(key==='radius'?'border-radius':key,n[key]+'px');
  for(const key of ['background','color'])if(/^#[0-9a-f]{6}$/i.test(n[key]||''))node.style[key]=n[key];
  if(n.align)node.style.textAlign=n.align;
  if(n.span)node.style.gridColumn='span '+n.span;
  if(n.type==='grid')node.style.gridTemplateColumns=`repeat(${n.columns||12},minmax(0,1fr))`;
  if(['grid','stack','panel'].includes(n.type)){
   if(n.title){const heading=document.createElement('h2');heading.className='composition-heading';heading.textContent=n.title;node.append(heading)}
   if(n.description){const p=document.createElement('p');p.className='composition-description';p.textContent=n.description;node.append(p)}
   for(const child of n.children||[])node.append(build(child,depth+1));
  }else if(n.type==='slot'){
   const section=slots[n.name];if(!section||used.has(n.name))throw Error('页面功能区域无效');used.add(n.name);
   node.dataset.slot=n.name;node.append(section);
   if(n.title&&n.name!=='progress'){const h=section.querySelector(n.name==='intro'?'h2':'.section-heading h2');if(h)h.textContent=n.title}
   if(n.description&&n.name!=='progress'){const p=section.querySelector(n.name==='intro'?'#description':'.section-heading p');if(p)p.textContent=n.description}
   if(n.columns)node.style.setProperty('--composition-columns',n.columns);
   if(n.map_height)node.style.setProperty('--composition-map-height',n.map_height+'px');
  }else if(n.type==='text'){
   node.textContent=n.text;if(n.size)node.style.fontSize=n.size+'px';if(n.weight)node.style.fontWeight=n.weight;
  }else if(n.type==='image'){
   const image=document.createElement('img');image.src=n.src;image.alt=n.alt||'';image.loading='lazy';node.append(image);if(n.ratio)image.style.aspectRatio=n.ratio;
  }else throw Error('未知页面区域');
  return node;
 }
 const composition=build(page.composition);if(used.size!==4)throw Error('页面缺少分析功能区域');
 const root=document.createElement('div');root.className='generated-page';root.append(composition);
 const notice=main.querySelector('#notice');main.replaceChildren(...(notice?[notice]:[]),root);
 body.dataset.pageLayout='free';main.style.maxWidth=(page.max_width||1368)+'px';
 const properties={background:'--app-bg',surface:'--page-surface',text:'--page-text',muted:'--page-muted',border:'--app-border',accent:'--app-accent'};
 for(const [key,value]of Object.entries(page.palette||{}))if(properties[key])body.style.setProperty(properties[key],value);
 if(page.palette?.text)body.style.setProperty('--app-deep',page.palette.text);
 body.style.setProperty('--app-soft','color-mix(in srgb,var(--app-accent) 10%,var(--app-bg))');
}
window.ConetPageComposition={mount};
})();
