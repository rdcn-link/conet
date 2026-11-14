(()=>{'use strict';
const el=(tag,text)=>{const n=document.createElement(tag);if(text)n.textContent=text;return n};
window.ConetInputHistory={render(host,run,{base,onRerun,onChanged,onError}={}){
 const status=run.input_retention||{},box=el('div');box.className='input-retention';
 if(status.has_uploads){const label=el('strong',status.available?(status.retained?'输入已长期保留':'输入快照可用'):'输入不可用');box.append(label);
 const detail=el('small',status.available?(status.retained?'保持非公开，占用输入存储配额':status.expires_at?'自动清理时间：'+new Date(status.expires_at).toLocaleString('zh-CN'):'执行期间保留，结束后开始计算保留期'):(status.reason||'请重新上传文件'));box.append(detail);
 for(const file of status.files||[])box.append(el('small',(file.name||file.field)+(file.state==='external'?' · 远程 Item':' · '+Number(file.bytes||0).toLocaleString()+' 字节')));
 }else box.append(el('small','重新运行将复用历史 Job 和参数；远程数据以运行时内容为准。'));
 const actions=el('div');actions.className='input-history-actions';
 function action(text,callback){const b=el('button',text);b.type='button';b.onclick=async e=>{e.stopPropagation();b.disabled=true;try{await callback()}catch(error){if(onError)onError(error);else{let notice=box.querySelector('[role="alert"]');if(!notice){notice=el('small');notice.setAttribute('role','alert');box.append(notice)}notice.textContent=error.message}}finally{if(b.isConnected)b.disabled=false}};actions.append(b);return b}
 const active=['queued','running'].includes(run.state);
 const rerun=action('重新运行',async()=>{const result=await ConetAppBuilder.request(base+'/api/jobs/'+encodeURIComponent(run.id)+'/rerun',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});if(onRerun)await onRerun(result);else location.href='/apps/'+encodeURIComponent(run.app_id)+'/?run='+encodeURIComponent(result.id)});rerun.disabled=active||status.available!==true;
 if(!active&&status.available===false){const upload=el('a','打开应用重新提供输入');upload.href='/apps/'+encodeURIComponent(run.app_id)+'/';actions.append(upload)}
 if(status.has_uploads&&status.available)action(status.retained?'恢复自动清理':'长期保留输入',async()=>{await ConetAppBuilder.request(base+'/api/jobs/'+encodeURIComponent(run.id)+'/inputs/retention',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({keep:!status.retained})});if(onChanged)await onChanged()});
 box.append(actions);host.append(box);
}};
})();
