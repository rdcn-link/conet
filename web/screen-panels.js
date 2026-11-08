/* Panels dock in ordered side rails; only a temporary title follows the pointer. */
(()=>{
const rails=[document.querySelector('.left-rail'),document.querySelector('.right-rail')],panels=rails.flatMap(r=>[...r.querySelectorAll(':scope>.wall-panel')]),key='conet.screen.panel-order';
panels.forEach((p,i)=>p.dataset.panelKey='panel-'+i);
const defaults=rails.map(r=>[...r.children].map(p=>p.dataset.panelKey)),byId=new Map(panels.map(p=>[p.dataset.panelKey,p]));let active=null;
const marker=document.createElement('div');marker.className='panel-dock-marker';marker.setAttribute('aria-hidden','true');
function persist(){try{localStorage.setItem(key,JSON.stringify(rails.map(r=>[...r.querySelectorAll(':scope>.wall-panel')].map(p=>p.dataset.panelKey))))}catch{}}
function restore(order){order.forEach((ids,i)=>ids.forEach(id=>rails[i].append(byId.get(id))))}
try{localStorage.removeItem('conet.screen.panels');const order=JSON.parse(localStorage.getItem(key)||'null');if(Array.isArray(order)&&order.length===2&&order.every(Array.isArray)){const ids=order.flat();if(ids.length===panels.length&&new Set(ids).size===panels.length&&ids.every(id=>byId.has(id)))restore(order)}}catch{}
function target(x,y){return rails.find(r=>{const b=r.getBoundingClientRect();return x>=b.left&&x<=b.right&&y>=b.top&&y<=b.bottom})}
function preview(x,y){
 rails.forEach(r=>r.classList.remove('panel-drop-target'));marker.remove();const rail=target(x,y);active.target=rail||null;if(!rail)return;rail.classList.add('panel-drop-target');
 const next=[...rail.querySelectorAll(':scope>.wall-panel')].filter(p=>p!==active.panel).find(p=>{const b=p.getBoundingClientRect();return y<b.top+b.height/2});rail.insertBefore(marker,next||null);
}
function finish(cancel=false){
 if(!active)return;const a=active;active=null;if(!cancel&&a.moved&&a.target&&marker.parentElement===a.target){a.target.insertBefore(a.panel,marker);persist()}
 marker.remove();a.ghost?.remove();a.panel.classList.remove('panel-dragging');a.handle.setAttribute('aria-pressed','false');rails.forEach(r=>r.classList.remove('panel-drop-target'));if(a.handle.hasPointerCapture(a.pointer))a.handle.releasePointerCapture(a.pointer);a.handle.focus({preventScroll:true});
}
panels.forEach(panel=>{
 const handle=panel.querySelector('h2');handle.classList.add('panel-drag-handle');handle.tabIndex=0;handle.setAttribute('role','button');handle.setAttribute('aria-pressed','false');handle.title='拖动到左右侧栏调整排列；方向键调整顺序或侧栏，Esc 取消';
 handle.addEventListener('pointerdown',e=>{if(e.button!==0||active)return;e.preventDefault();active={panel,handle,pointer:e.pointerId,x:e.clientX,y:e.clientY,moved:false,target:null};handle.setPointerCapture(e.pointerId)});
 handle.addEventListener('pointermove',e=>{
  if(active?.handle!==handle||active.pointer!==e.pointerId)return;
  if(!active.moved&&Math.hypot(e.clientX-active.x,e.clientY-active.y)<5)return;
  if(!active.moved){active.moved=true;panel.classList.add('panel-dragging');handle.setAttribute('aria-pressed','true');active.ghost=document.createElement('div');active.ghost.className='panel-drag-preview';active.ghost.textContent=handle.textContent;document.body.append(active.ghost)}
  active.ghost.style.left=Math.max(0,Math.min(innerWidth-220,e.clientX+12))+'px';active.ghost.style.top=Math.max(0,Math.min(innerHeight-45,e.clientY+12))+'px';preview(e.clientX,e.clientY);
  const rail=active.target;if(rail){const b=rail.getBoundingClientRect();if(e.clientY<b.top+40)rail.scrollTop-=12;else if(e.clientY>b.bottom-40)rail.scrollTop+=12}
 });
 handle.addEventListener('pointerup',e=>{if(active?.handle===handle&&active.pointer===e.pointerId){if(active.moved)preview(e.clientX,e.clientY);finish()}});
 handle.addEventListener('pointercancel',()=>{if(active?.handle===handle)finish(true)});
 handle.addEventListener('lostpointercapture',()=>{if(active?.handle===handle)finish(true)});
 handle.addEventListener('keydown',e=>{
  if(e.key==='Escape'){finish(true);return}if(!['ArrowUp','ArrowDown','ArrowLeft','ArrowRight'].includes(e.key))return;e.preventDefault();if(active)return;
  const rail=panel.parentElement,items=[...rail.querySelectorAll(':scope>.wall-panel')],i=items.indexOf(panel);
  if(e.key==='ArrowUp'&&i>0)rail.insertBefore(panel,items[i-1]);if(e.key==='ArrowDown'&&i<items.length-1)rail.insertBefore(items[i+1],panel);
  if(e.key==='ArrowLeft'&&rail!==rails[0])rails[0].append(panel);if(e.key==='ArrowRight'&&rail!==rails[1])rails[1].append(panel);persist();handle.focus({preventScroll:true});
 });
});
window.addEventListener('keydown',e=>{if(e.key==='Escape')finish(true)});window.addEventListener('resize',()=>finish(true));
document.getElementById('reset-panels').onclick=()=>{finish(true);restore(defaults);try{localStorage.removeItem(key)}catch{}};
})();
