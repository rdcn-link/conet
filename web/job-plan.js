'use strict';
const byId=id=>document.getElementById(id);
const planId=new URLSearchParams(location.search).get('id');
let viewer,busy=false;
const labels={queued:'排队中',preparing:'准备中',running:'运行中',waiting_resources:'等待资源',succeeded:'已完成',failed:'失败',cancelled:'已取消',expired:'超时',interrupted:'已中断',unknown:'未知'};
const fmt={labels,terminal:new Set(['succeeded','failed','cancelled','expired','interrupted']),bytes:n=>n==null?'—':n>=1e9?(n/1e9).toFixed(2)+' GB':n>=1e6?(n/1e6).toFixed(1)+' MB':n>=1e3?(n/1e3).toFixed(1)+' kB':n+' B',time:n=>n?new Date(n).toLocaleString('zh-CN',{hour12:false}):'未观测',duration:n=>n==null?'—':(n/1000).toFixed(1)+' 秒'};
async function loadPlan(){
 if(busy)return;busy=true;byId('plan-refresh').disabled=true;byId('dag-error').hidden=true;
 try{
  if(!planId)throw Error('作业尚未提交到 Union，暂无物理计划。');
  const response=await fetch('/api/union-jobs/'+encodeURIComponent(planId),{cache:'no-store'});const job=await response.json();
  if(!response.ok)throw Error(job.error||'无法读取物理计划');
  if(!job.steps?.length)throw Error('作业尚未生成物理计划，请稍后刷新。');
  if(!window.DacpDag||!window.G6)throw Error('DAG 组件不可用，请确认 Union 服务正常。');
  if(!viewer){viewer=new DacpDag.Viewer(byId('dag'));viewer.setLayout('layout','swimlane');viewer.setLayout('direction','vertical');}
  await viewer.update(job,new Map(),fmt);
  byId('plan-summary').textContent=`${labels[job.state]||job.state} · ${new Set(job.steps.map(s=>s.node)).size} 个执行节点 · ${job.steps.length} 个步骤`;
 }catch(error){byId('dag-error').textContent=error.message;byId('dag-error').hidden=false;byId('plan-summary').textContent='物理计划暂不可用';}
 finally{busy=false;byId('plan-refresh').disabled=false;}
}
byId('plan-refresh').onclick=loadPlan;
document.addEventListener('keydown',event=>{if(event.key==='Escape')parent.postMessage('close-job-plan',location.origin);});
const corner=document.createElement('div');corner.className='plan-corner';for(const [id,label,symbol] of [['dag-fit','适应画布','⛶'],['plan-refresh','刷新计划','↻']]){const button=byId(id);button.textContent=symbol;button.title=label;button.setAttribute('aria-label',label);corner.append(button);}document.body.append(corner);
loadPlan();
