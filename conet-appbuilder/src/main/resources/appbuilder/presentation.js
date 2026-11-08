(()=>{'use strict';
const presets=[
 {theme:'coast',name:'海湾青',label:'数据与流动',background:'/assets/background-coast.svg',cover:'/assets/app-cover.svg',terms:['数据','文件','csv','json','表格','data','file','table']},
 {theme:'forest',name:'山野绿',label:'地形与自然',background:'/assets/background-forest.svg',cover:'/assets/check-dams-cover.svg',terms:['淤地坝','沟道','地形','高程','生态','流域','terrain','forest','dam','ecology']},
 {theme:'indigo',name:'星空靛',label:'天文与观测',background:'/assets/background-indigo.svg',cover:'/assets/observatory-cover.svg',terms:['天文','星表','天区','波段','星系','astronomy','observatory','star','galaxy']},
 {theme:'amber',name:'暖阳金',label:'灾情与研判',background:'/assets/background-amber.svg',cover:'/assets/emergency-cover.svg',terms:['灾情','地震','泥石流','应急','风险','灾害','emergency','disaster','earthquake','risk']},
 {theme:'rose',name:'珊瑚红',label:'人群与生活',background:'/assets/background-rose.svg',cover:'/assets/app-cover.svg',terms:['健康','人群','人口','医疗','生活','health','population','medical','social']}
];
function recommend(app){const text=[app.title,app.description,...(app.presentation?.tags||[])].join(' ').toLowerCase();const ranked=presets.map((p,i)=>{const matched=p.terms.filter(k=>text.includes(k));let score=matched.length*3;if(app.inputs?.some(f=>f.widget==='cone')&&p.theme==='indigo')score+=4;return{...p,matched,score,index:i}}).sort((a,b)=>b.score-a.score||a.index-b.index);const chosen=ranked[0];return{...chosen,reason:chosen.matched.length?'根据“'+chosen.matched.slice(0,3).join('、')+'”推荐':chosen.score?'根据天区输入组件推荐':'通用数据分析配色与背景'};}
function applyBackground(body,presentation={}){const background=Object.hasOwn(presentation,'background')?presentation.background:presets.find(p=>p.theme===(presentation.theme||'coast'))?.background;if(background){body.style.setProperty('--app-background-image',`url(${JSON.stringify(background)})`);body.dataset.background='true'}else{body.style.removeProperty('--app-background-image');delete body.dataset.background}}
window.ConetPresentation={presets,recommend,applyBackground};
})();
