(()=>{'use strict';
const cache=new Map(),widgets=new Set();
async function request(url,options){const response=await fetch(url,options),body=await response.json();if(!response.ok)throw Error(body.error||'评分暂不可用');return body}
async function entries(kind){if(!cache.has(kind))cache.set(kind,request('/api/ratings/'+kind).then(n=>new Map(n.entries.map(e=>[e.id,e]))).catch(e=>{cache.delete(kind);throw e}));return cache.get(kind)}
function mount(host,kind,id){
 const box=document.createElement('fieldset');box.className='conet-rating';box.dataset.ratingKind=kind;box.dataset.ratingId=id;
 const legend=document.createElement('legend');legend.textContent='评分';const stars=document.createElement('div');stars.className='rating-stars';const summary=document.createElement('span');summary.className='rating-summary';summary.setAttribute('aria-live','polite');summary.textContent='读取评分…';box.append(legend,stars,summary);host.append(box);
 let current={count:0,average:0},saving=false;
 function draw(){for(const button of stars.children){const value=Number(button.dataset.score);button.classList.toggle('selected',value<=(current.mine||0));button.setAttribute('aria-pressed',String(value===current.mine));button.disabled=saving}summary.textContent=current.count?current.average.toFixed(1)+' 分 · '+current.count+' 次评分'+(current.mine?' · 你评 '+current.mine+' 分':''):'暂无评分';box.title='选择 1–5 分；同一浏览器再次评分会替换原评分'}
 const widget={kind,id,box,update:n=>{current=n;draw()}};widgets.add(widget);
 for(let score=1;score<=5;score++){
  const button=document.createElement('button');button.type='button';button.dataset.score=String(score);button.textContent='★';button.setAttribute('aria-label','评 '+score+' 分');button.onclick=async event=>{
   event.preventDefault();event.stopPropagation();if(saving)return;saving=true;draw();summary.textContent='保存评分…';
   try{const result=await request('/api/ratings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({kind,id,score})});const all=await entries(kind);all.set(id,result);
    for(const w of widgets){if(!w.box.isConnected){widgets.delete(w);continue}if(w.kind===kind&&w.id===id)w.update(result)}
   }catch(e){summary.textContent=e.message}finally{saving=false;for(const b of stars.children)b.disabled=false}
  };stars.append(button);
 }
 entries(kind).then(all=>widget.update(all.get(id)||current)).catch(e=>{summary.textContent=e.message;for(const button of stars.children)button.disabled=true});
 return box;
}
window.ConetRatings={mount};
})();
