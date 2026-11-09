(()=>{'use strict';
const $=id=>document.getElementById(id);
const equal=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,canonical(v[k])])):v;
function attach({payload,post,apply,publish,onStatus}){
 let draft=null,messages=[],busy=false,epoch=0,generation=0,revision=0,history=[],cursor=-1;
 const dialog=$('preview-panel'),autoDialog=$('auto-settings');let autoSource=null,autoApp=null;
 const previews=new Map();let renderedAppId=null;
 const controls=['open-preview','studio-auto','studio-smart','page-send','publish'];
 function status(text){$('studio-status').textContent=text;onStatus(text)}
 function open(){if(!dialog.open)dialog.showModal()}
 function close(){if(dialog.open)dialog.close()}
 function buttons(){
  $('studio-undo').disabled=busy||cursor<=0;$('studio-redo').disabled=busy||cursor>=history.length-1;
  document.querySelectorAll('.proposal-apply').forEach(b=>b.disabled=busy||Number(b.dataset.revision)!==revision);
 }
 function reset(){if(autoDialog.open)autoDialog.close();autoSource=null;autoApp=null;epoch++;revision++;draft=null;messages=[];generation=0;history=[];cursor=-1;$('page-messages').replaceChildren();$('open-preview').disabled=false;buttons();close()}
 function setBusy(value,refreshPreview=false){busy=value;for(const id of controls)$(id).disabled=value;$('page-prompt').disabled=value;$('page-generating').hidden=!(value&&refreshPreview);$('page-send').textContent=value&&!refreshPreview?'回复中…':'发送 ↗';$('page-chat').setAttribute('aria-busy',String(value&&!refreshPreview));dialog.setAttribute('aria-busy',String(value));buttons()}
 function say(role,text){const p=document.createElement('p');p.dataset.role=role;const name=document.createElement('strong'),body=document.createElement('span');name.textContent=role==='user'?'你':'界面助手';body.textContent=text;p.append(name,body);$('page-messages').append(p);$('page-messages').scrollTop=$('page-messages').scrollHeight;return p}
 async function render(next){
  const validated=await post('/api/appbuilder/validate',next),app=validated.application;
  const config={manifest:validated.manifest||{}};
  const response=await fetch('/appbuilder/assets/application.html');if(!response.ok)throw Error('预览模板加载失败');
  const html=await response.text();const data=JSON.stringify({application:app,manifest:config.manifest}).replace(/</g,'\\u003c');
  $('page-preview').srcdoc=html.replace('<script src=','<script>window.__conetPreview='+data+';<'+ '/script><script src=');
  renderedAppId=app.id;draft=structuredClone(next);$('open-preview').disabled=false;
 }
 async function commit(next,record=true){
  next=await apply(structuredClone(next));await render(next);revision++;
  if(record){history=history.slice(0,cursor+1);if(!equal(history.at(-1),next)){history.push(structuredClone(next));if(history.length>20)history.shift()}cursor=history.length-1}
  previews.set(renderedAppId,structuredClone(next));
  generation++;$('page-preview-title').textContent='应用预览 · '+generation;buttons();
 }
 async function currentMatches(base){return equal(await payload(),base)}
 async function restore(delta){
  if(busy||cursor+delta<0||cursor+delta>=history.length)return;
  setBusy(true,true);try{
   if(!await currentMatches(draft))throw Error('应用配置已改变，请重新生成界面后再撤销或重做');
   const target=cursor+delta;await commit(history[target],false);cursor=target;messages=[];
   status(delta<0?'已撤销 · 尚未发布':'已重做 · 尚未发布');
  }catch(e){status(e.message)}finally{setBusy(false)}
 }
 function proposal(node,next,base,requestEpoch){
  const expectedRevision=revision,button=document.createElement('button');button.type='button';button.className='proposal-apply';button.textContent='立即更新';button.dataset.revision=String(expectedRevision);node.append(button);
  button.onclick=async()=>{
   if(busy)return;setBusy(true,true);try{
    if(requestEpoch!==epoch||expectedRevision!==revision||!await currentMatches(base))throw Error('此建议基于旧版本，请重新描述调整要求');
    await commit(next);button.textContent='已更新';status('已应用建议 · 可撤销或重做 · 确认后保存并发布');
   }catch(e){button.dataset.revision='-1';status(e.message)}finally{setBusy(false)}
  };
 }
 async function generate(smart,adjust=false){
  if(busy)return;const requestEpoch=epoch;setBusy(true,!adjust);open();
  let userText='';
  try{
   const source=await payload();let next=structuredClone(source),conversation=[];
   if(adjust){
    if(!draft)throw Error('请先生成应用界面');
    if(!equal(source,draft))throw Error('应用配置已改变，请重新生成界面后再调整');
    userText=$('page-prompt').value.trim();if(!userText)return;
    conversation=[...messages,{role:'user',content:userText}];
   }
   status(adjust?'正在理解你的问题和调整要求…':smart?'正在理解任务和变量，设计应用界面…':'正在按表单与外观配置生成应用界面…');
   let reply='',changed=true;
   if(smart){
    const result=await post('/api/appbuilder/pages/generate',{...next,messages:conversation});next.application_yaml=result.application_yaml;reply=result.message;
    if(adjust){
     changed=result.changed===true;
     if(changed){const before=await post('/api/appbuilder/validate',source),after=await post('/api/appbuilder/validate',next);changed=!equal(canonical(before.application.presentation?.page),canonical(after.application.presentation?.page))}
    }
   }else{
    const validated=await post('/api/appbuilder/validate',next),app=validated.application;
    app.presentation??={};const preferences=app.presentation.design||{};
    const spatial=app.inputs.some(f=>['polygon','cone'].includes(f.widget));
    const palette=ConetPresentation.presets.find(p=>p.theme===(app.presentation.theme||'coast'));
    const accents={coast:'#147f91',forest:'#287653',indigo:'#6652ac',amber:'#a96421',rose:'#ab526d'};
    app.presentation.page={layout:spatial?'stacked':'split',hero:'banner',surface:'soft',density:'comfortable',...preferences,accent:accents[palette?.theme||'coast'],heading:app.title,intro:app.description};
    const converted=await post('/api/appbuilder/convert',{application:app,job:next.job_yaml});next.application_yaml=converted.application_yaml;
    reply='已按输入表单、结果展示和外观配置生成界面。可以继续对话调整，也可以返回修改配置后再次生成。';
   }
   if(requestEpoch!==epoch||!await currentMatches(source))throw Error('草稿已改变，本次生成未应用，请重新生成');
   if(adjust){
    say('user',userText);messages=conversation;$('page-prompt').value='';
    const node=say('assistant',reply);if(changed)proposal(node,next,source,requestEpoch);
    status(changed?'已给出修改建议 · 点击“立即更新”后才会更改预览':'已回复 · 当前预览保持不变');
   }else{
    await commit(next);messages=[];$('page-messages').replaceChildren();say('assistant',reply);
    status('界面已更新 · 预览不提交分析任务 · 确认后保存并发布');
   }
   messages.push({role:'assistant',content:reply});
  }catch(e){status(e.message)}finally{setBusy(false)}
 }
 async function configureAutomatic(){
  if(busy)return;setBusy(true);
  try{autoSource=await payload();const validated=await post('/api/appbuilder/validate',autoSource);autoApp=structuredClone(validated.application);$('auto-settings-fields').replaceChildren();ConetConfigurationForms.automatic($('auto-settings-fields'),autoApp);autoDialog.showModal()}
  catch(e){status(e.message)}finally{setBusy(false)}
 }
 $('auto-settings-close').onclick=$('auto-settings-cancel').onclick=()=>autoDialog.close();
 $('auto-settings-generate').onclick=async()=>{
  if(busy)return;setBusy(true);$('auto-settings-generate').disabled=true;
  try{
   if(!await currentMatches(autoSource))throw Error('应用配置已改变，请重新打开自动生成设置');
   const converted=await post('/api/appbuilder/convert',{application:autoApp,job:autoSource.job_yaml});
   await apply({...autoSource,application_yaml:converted.application_yaml});autoDialog.close();setBusy(false);await generate(false);
  }catch(e){status(e.message)}finally{setBusy(false);$('auto-settings-generate').disabled=false}
 };
 $('studio-auto').onclick=configureAutomatic;$('studio-smart').onclick=()=>generate(true);
 $('studio-undo').onclick=()=>restore(-1);$('studio-redo').onclick=()=>restore(1);
 $('close-preview').onclick=close;
 $('open-preview').onclick=async()=>{
  if(busy)return;
  try{
   if(!draft){
    setBusy(true);const source=await payload(),validated=await post('/api/appbuilder/validate',source);
    const previous=previews.get(validated.application.id)||(validated.application.presentation?.page?source:null);
    if(!previous){setBusy(false);await configureAutomatic();return}
    await render(previous);history=[structuredClone(previous)];cursor=0;generation=1;$('page-preview-title').textContent='应用预览 · 上次生成';buttons();
   }
   open();status(await currentMatches(draft)?'上次生成的界面 · 可重新生成、对话调整或保存并发布':'配置已改变，显示上次生成的界面，请重新生成后发布');
  }catch(e){status(e.message)}finally{setBusy(false)}
 };
 $('toggle-page-chat').onclick=()=>{
  const pane=$('page-conversation'),collapsed=!pane.hidden;pane.hidden=collapsed;
  pane.closest('.page-studio').classList.toggle('chat-collapsed',collapsed);
  $('toggle-page-chat').setAttribute('aria-expanded',String(!collapsed));$('toggle-page-chat').textContent=collapsed?'◧ 展开对话':'◧ 收起对话';
 };
 $('page-chat').onsubmit=e=>{e.preventDefault();generate(true,true)};
 $('page-prompt').addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='Enter'){e.preventDefault();generate(true,true)}});
 $('publish').onclick=async()=>{
  if(busy)return;setBusy(true);try{
   if(!draft||!await currentMatches(draft))throw Error('配置已改变，请先重新生成并预览页面');
   await publish(structuredClone(draft));close();
  }catch(e){status(e.message)}finally{setBusy(false)}
 };
 return{close,reset};
}
window.ConetPageStudio={attach};
})();
