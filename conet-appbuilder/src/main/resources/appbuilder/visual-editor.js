(()=>{'use strict';
const $=id=>document.getElementById(id),A=ConetAppBuilder;
function el(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n}
function button(text,fn,cls=''){const n=el('button',cls,text);n.type='button';n.onclick=fn;return n}
function attach({onError}){
 let application={},job={},mode='job',group='basic',modelReady=false,designerUrl='',handoff=null,applicationGeneration=0;
 const host=$('application-visual'),jobHost=$('job-visual');
 async function convert(data){return A.request('/api/appbuilder/convert',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)})}
 function putSources(data){$('application').value=data.application_yaml;$('job').value=data.job_yaml}
 async function flush(){if(mode==='source'){const d=await convert({application:$('application').value,job:$('job').value});application=d.application;job=d.job;putSources(d)}else putSources(await convert({application,job}));}
 function renderApplication(){host.replaceChildren();const body=el('div','design-fields');host.append(body);
 if(group==='variables')ConetConfigurationForms.variables(body,application,job,onError);
 else ConetConfigurationForms[group](body,application,onError);
 }

 function renderJob(){
  jobHost.replaceChildren();const panel=el('div','designer-launch');panel.append(el('h2','','使用 XStream Job Designer 编辑流程'),el('p','','在完整的 Job Designer 中编辑算子、参数、连接和输出，完成后回传到当前应用。'));
  const summary=el('div','job-summary');summary.append(el('strong','',job.name||'未命名 Job'),el('span','',`${(job.steps||[]).length} 个步骤 · ${Object.keys(job.outputs||{}).length} 个输出`));panel.append(summary);
  const actions=el('div','designer-actions');actions.append(button('↗ 在 Job Designer 中编辑',()=>openDesigner(),'primary'),button('↓ 下载当前 Job YAML',async()=>{try{await flush();const link=document.createElement('a');link.href=URL.createObjectURL(new Blob([$('job').value],{type:'application/yaml'}));link.download=(application.id||'application')+'-job.yaml';link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000)}catch(e){onError(e.message)}}));panel.append(actions,el('small','','回传只更新分析流程。应用的输入表单、变量绑定和成果展示可在上方对应标签页中配置。'));jobHost.append(panel);
 }
 async function openDesigner(){
  const popup=window.open('about:blank','_blank');if(!popup){onError('请允许打开 Job Designer 窗口，或先下载 Job YAML 后手动加载。');return}
  try{
   await flush();const settings=await A.request('/api/appbuilder/settings');if(!settings.designer_url)throw Error('尚未配置 Job Designer 地址，请在 conet.yaml 设置 appbuilder.designer_url。');
   const url=new URL(settings.designer_url,location.href);designerUrl=url.href;
   const channel=Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');
   handoff={popup,origin:url.origin,channel,generation:applicationGeneration,yaml:$('job').value};url.searchParams.set('integration','window');url.searchParams.set('parent_origin',location.origin);url.searchParams.set('channel',channel);url.searchParams.set('return_label','应用此 Job 并返回');popup.location.replace(url.href);onError('已打开 Job Designer，完成设计后点击“应用此 Job 并返回”。');
  }catch(e){popup.close();onError(e.message)}
 }
 window.addEventListener('message',async e=>{
  const h=handoff;if(!h||e.source!==h.popup||e.origin!==h.origin||e.data?.channel!==h.channel)return;
  if(h.generation!==applicationGeneration){onError('当前应用已切换，请为当前应用重新打开 Job Designer。');return}
  try{
   if(e.data.type==='xstream-designer:ready')h.popup.postMessage({type:'xstream-designer:load',channel:h.channel,job_yaml:h.yaml,variables_yaml:'{}'},h.origin);
   else if(e.data.type==='xstream-designer:job'){
    if(typeof e.data.job_yaml!=='string'||e.data.job_yaml.length>262144)throw Error('Job 定义无效或过大');
    await flush();const d=await convert({application,job:e.data.job_yaml});if(h.generation!==applicationGeneration)return;job=d.job;putSources(d);renderJob();renderApplication();onError('已接收 Job Designer 的流程，请检查变量绑定并校验、发布。');window.focus();
   }else if(e.data.type==='xstream-designer:error')onError('Job Designer：'+String(e.data.error||'无法加载流程'));
  }catch(error){onError(error.message)}
 });
 async function switchMode(next){try{if(!modelReady)return;if(mode==='source'&&next!=='source'){const d=await convert({application:$('application').value,job:$('job').value});application=d.application;job=d.job;}else if(next==='source')await flush();mode=next;if(!['job','source'].includes(next))group=next;renderApplication();renderJob();host.hidden=['job','source'].includes(next);jobHost.hidden=next!=='job';$('source-editor').hidden=next!=='source';document.querySelectorAll('[data-editor]').forEach(b=>{const selected=b.dataset.editor===next;b.setAttribute('aria-selected',String(selected));b.tabIndex=selected?0:-1})}catch(e){onError(e.message)}}
 document.querySelectorAll('[data-editor]').forEach((b,i)=>{b.onclick=()=>switchMode(b.dataset.editor);b.onkeydown=e=>{const tabs=[...document.querySelectorAll('[data-editor]')];const next=e.key==='ArrowRight'?(i+1)%tabs.length:e.key==='ArrowLeft'?(i+tabs.length-1)%tabs.length:e.key==='Home'?0:e.key==='End'?tabs.length-1:null;if(next!==null){e.preventDefault();tabs[next].focus();switchMode(tabs[next].dataset.editor)}}});
 return{flush,async load(){modelReady=false;host.inert=true;jobHost.inert=true;const d=await convert({application:$('application').value,job:$('job').value});application=d.application;job=d.job;modelReady=true;host.inert=false;jobHost.inert=false;applicationGeneration++;renderApplication();renderJob();await switchMode(mode)}};
}
window.ConetVisualEditor={attach};})();
