(()=>{'use strict';
const widgets={file:['文件上传','⇧'],text:['文本','T'],number:['数值','#'],select:['选项','☷'],datetime:['日期时间','◷'],polygon:['地图选区','⬡'],cone:['观测天区','◎'],object:['字段分组','▦']};
const views={metrics:['指标卡片','▥'],table:['数据表格','▤'],scatter:['散点图','⁙'],geojson:['空间地图','⌖']};
const states=new WeakMap();
const node=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n};
const action=(label,fn,cls='')=>{const b=node('button',cls,label);b.type='button';b.onclick=fn;return b};
function field(parent,obj,key,label,{type='text',options,optional=false,placeholder='',change}={}){
 const wrap=node('label','config-control'),title=node('span','',label);let input;
 if(options){input=node('select');for(const[value,text]of Object.entries(options)){const o=node('option','',text);o.value=value;input.append(o)}input.value=obj[key]??Object.keys(options)[0]}
 else if(type==='checkbox'){input=node('input');input.type='checkbox';input.checked=obj[key]!==false;wrap.classList.add('config-check')}
 else{input=node(type==='textarea'?'textarea':'input');if(type==='textarea')input.rows=3;else input.type=type;input.value=obj[key]??'';input.placeholder=placeholder;if(type==='number')input.step='any'}
 input.setAttribute('aria-label',label);input.onchange=()=>{if(optional&&input.value==='')delete obj[key];else obj[key]=type==='checkbox'?input.checked:type==='number'?Number(input.value):input.value;change?.()};wrap.append(title,input);parent.append(wrap);return input;
}
const palettes={coast:['海湾青','清新 · 通用','#147f91','#e2f3f5','#f2f8fa'],forest:['山野绿','自然 · 地理','#287653','#e4f1e5','#f4f7f0'],indigo:['星空靛','科学 · 观测','#6652ac','#eeebfb','#f5f4fb'],amber:['暖阳金','温暖 · 大地','#a96421','#f8eddb','#faf7f0'],rose:['珊瑚红','明快 · 活力','#ab526d','#f9e9ef','#fcf5f7']};
function themePicker(parent,app){
 const box=node('fieldset','config-themes config-wide');box.append(node('legend','','界面主题'));const cards=node('div','theme-cards');box.append(cards);parent.append(box);
 for(const[key,[title,hint,accent,soft,bg]]of Object.entries(palettes)){
  const card=node('label','theme-card'),input=node('input');input.type='radio';input.name='application-theme';input.value=key;input.checked=(app.presentation.theme||'coast')===key;input.setAttribute('aria-label',title);card.style.setProperty('--theme-accent',accent);card.style.setProperty('--theme-soft',soft);card.style.setProperty('--theme-bg',bg);
  const sample=node('span','theme-sample');sample.setAttribute('aria-hidden','true');const heading=node('span','theme-sample-heading');heading.append(node('i'),node('b','',app.title||'我的应用'));const body=node('span','theme-sample-body');body.append(node('i'),node('i'),node('em'));sample.append(heading,body);
  const caption=node('span','theme-caption');caption.append(node('strong','',title),node('small','',hint));const check=node('span','theme-check','✓');check.setAttribute('aria-hidden','true');card.append(input,sample,caption,check);cards.append(card);input.onchange=()=>{if(input.checked)app.presentation.theme=key};
 }
}
function componentPicker(parent,obj,key,label,choices,change){
 const box=node('fieldset','component-picker config-wide');box.append(node('legend','',label));const grid=node('div','component-choices');box.append(grid);parent.append(box);
 for(const[value,[title,icon]]of Object.entries(choices)){
  const card=node('label','component-choice'),input=node('input');input.type='radio';input.name='component-'+key;input.value=value;input.checked=obj[key]===value;input.setAttribute('aria-label',title);card.append(input,node('span','component-choice-icon',icon),node('span','',title));grid.append(card);input.onchange=()=>{obj[key]=value;change()};
 }
}
function columns(parent,obj,key,label){const wrap=node('label','config-control config-wide'),input=node('textarea');input.rows=2;input.value=(obj[key]||[]).join(', ');input.placeholder='例如：name, longitude, latitude';input.setAttribute('aria-label',label);input.onchange=()=>{const a=input.value.split(/[,，\n]/).map(s=>s.trim()).filter(Boolean);if(a.length)obj[key]=a;else delete obj[key]};wrap.append(node('span','',label),input);parent.append(wrap)}
function advanced(parent,fn){const details=node('details','config-advanced');details.append(node('summary','','更多设置'));const grid=node('div','config-grid');fn(grid);details.append(grid);parent.append(details)}
function pairs(parent,obj,key,{title,left,right,seed}){
 const box=node('div','config-inline-table');box.append(node('h4','',title));const values=obj[key]??=[];
 values.forEach((value,i)=>{if(typeof value==='string'){value={value,label:value};values[i]=value}const row=node('div','config-pair-row');
  field(row,value,left.key,left.label);field(row,value,right.key,right.label);row.append(action('×',()=>{values.splice(i,1);box.replaceWith(pairs(document.createElement('div'),obj,key,{title,left,right,seed}))},'config-remove'));box.append(row)});
 box.append(action('＋ 添加'+title,()=>{values.push({...seed});const temp=node('div');box.replaceWith(pairs(temp,obj,key,{title,left,right,seed}))},'config-add'));parent.append(box);return box;
}
function collection(parent,items,{title,description,selection,setSelection,caption,icon,create,edit,changed}){
 const header=node('div','config-section-head');const text=node('div');text.append(node('h3','',title),node('p','',description));header.append(text,action('＋ 添加',()=>{items.push(create());setSelection(items.length-1);changed()},'primary'));parent.append(header);
 if(!items.length){parent.append(node('div','config-empty','还没有配置项，点击“添加”开始设计。'));return}
 const index=Math.max(0,Math.min(selection,items.length-1));setSelection(index);
 const layout=node('div','config-master-detail'),list=node('div','config-item-list'),detail=node('div','config-item-detail');layout.append(list,detail);parent.append(layout);
 function refreshList(){list.replaceChildren();items.forEach((item,i)=>{const b=action('',()=>{setSelection(i);changed()},'config-item'+(i===index?' selected':''));b.setAttribute('aria-pressed',String(i===index));const names=node('span','config-item-names');names.append(node('strong','',caption(item)),node('small','',item.name||item.id||item.type||item.format||''));b.append(node('span','config-item-icon',icon(item)),names);list.append(b)})}
 refreshList();const toolbar=node('div','config-detail-actions');toolbar.append(node('small','',`第 ${index+1} 项 / 共 ${items.length} 项`));
 for(const[delta,label]of [[-1,'↑ 上移'],[1,'↓ 下移']]){const b=action(label,()=>{const target=index+delta;if(target<0||target>=items.length)return;[items[index],items[target]]=[items[target],items[index]];setSelection(target);changed()});b.disabled=index+delta<0||index+delta>=items.length;toolbar.append(b)}
 toolbar.append(action('删除',()=>{items.splice(index,1);setSelection(Math.max(0,index-1));changed()},'danger'));detail.append(toolbar);edit(detail,items[index],refreshList);
}
function thumbnail(parent,type,title){
 const preview=node('div','config-thumbnail');preview.setAttribute('aria-label','样式示意');preview.append(node('small','','样式示意'));
 const canvas=node('div','config-thumbnail-body');const label=node('span','',title||'显示名称');canvas.append(label);
 if(type==='table'){const table=node('div','thumbnail-table');for(let i=0;i<12;i++)table.append(node('i'));canvas.append(table)}
 else if(type==='metrics'){const row=node('div','thumbnail-metrics');for(const value of ['128','36','92%'])row.append(node('b','',value));canvas.append(row)}
 else if(type==='scatter'||type==='geojson'||type==='polygon'||type==='cone'){
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 200 65');
  if(type==='scatter'){svg.innerHTML='<path d="M12 5v50h175" fill="none" stroke="#c9dce3"/>';for(const[x,y]of [[29,45],[47,42],[61,32],[78,36],[93,24],[115,29],[131,20],[147,16],[172,10]]){const c=document.createElementNS(svg.namespaceURI,'circle');c.setAttribute('cx',x);c.setAttribute('cy',y);c.setAttribute('r','3');c.setAttribute('fill','#64aab5');svg.append(c)}}
  else if(type==='cone')svg.innerHTML='<ellipse cx="100" cy="32" rx="83" ry="26" fill="#e7f2f6" stroke="#c1dce5"/><path d="M17 32h166M100 6v52" stroke="#d2e4eb"/><circle cx="103" cy="30" r="15" fill="#70b9c328" stroke="#4398a8"/><g fill="#82b4c4"><circle cx="53" cy="22" r="1.5"/><circle cx="129" cy="42" r="2"/><circle cx="113" cy="18" r="2"/></g>';
  else svg.innerHTML='<path d="m0 12 48 17 55-22 55 13 42-13M0 49l48-20 55 23 55-32 42 20M48 0v65m55-65v65m55-65v65" stroke="#d6e4e6" fill="none"/><path d="m56 15 62-6 25 35-56 12-38-19Z" fill="#68b8be28" stroke="#59a2ac" stroke-dasharray="4 3"/><circle cx="97" cy="30" r="4" fill="#3e919e"/>';
  canvas.append(svg);
 }else if(type==='file'){const file=node('div','thumbnail-upload');file.append(node('b','','⇧'),node('span','','上传文件 / 远程 Item URI'));canvas.append(file)}else if(type==='object'){const group=node('div','thumbnail-group');for(let i=0;i<3;i++)group.append(node('i'));canvas.append(group)}
 else{const input=node('div','thumbnail-input',type==='select'?'请选择选项                     ▾':type==='datetime'?'2026-11-07 12:00          ◷':type==='number'?'0                         −  +':'请输入…');canvas.append(input)}preview.append(canvas);parent.append(preview);
}
function inputForms(parent,app,onError){
 let state=states.get(app);if(!state){state={input:0,path:[],view:0,download:0,summary:0,resultTab:'views'};states.set(app,state)}
 app.inputs??=[];
 function render(){parent.replaceChildren();let fields=app.inputs;const crumbs=node('div','config-breadcrumb');crumbs.append(action('全部输入字段',()=>{state.path=[];state.input=0;render()}));
  state.path=state.path.filter((item,i)=>{if(i===0)return app.inputs.includes(item);return state.path[i-1]?.fields?.includes(item)});
  state.path.forEach((item,i)=>{crumbs.append(node('span','','/'),action(item.label||item.name,()=>{state.path=state.path.slice(0,i+1);state.input=0;render()}));fields=item.fields??=[]});if(state.path.length)parent.append(crumbs);
  collection(parent,fields,{title:state.path.length?'分组内的字段':'输入表单',description:'左侧选择字段，右侧设置它在应用页面上的名称、控件与取值范围。',selection:state.input,setSelection:i=>state.input=i,caption:f=>f.label||f.name||'未命名字段',icon:f=>widgets[f.widget]?.[1]||'T',create:()=>{let i=1;while(fields.some(f=>f.name==='field_'+i))i++;return{name:'field_'+i,label:'新输入字段',widget:'text',required:true}},changed:render,
   edit(detail,f,refresh){thumbnail(detail,f.widget,f.label);const grid=node('div','config-grid');detail.append(grid);field(grid,f,'label','显示名称',{change:refresh});field(grid,f,'name','字段标识',{change:refresh});
    componentPicker(grid,f,'widget','控件类型',widgets,()=>{if(f.widget==='object')f.fields??=[];if(f.widget==='file'){f.extensions??=['.csv'];f.max_bytes??=16777216;f.view??='records';delete f.default;delete f.default_manifest}if(f.widget==='select')f.options??=[{value:'option',label:'新选项'}];render()});field(grid,f,'required','必填',{type:'checkbox'});
    if(!['polygon','object','cone','file'].includes(f.widget))field(grid,f,'default','默认值',{type:f.widget==='number'?'number':'text',optional:true,placeholder:f.widget==='datetime'?'例如：2026-11-07T12:00:00Z':'未设置',change:()=>{if(Object.hasOwn(f,'default'))delete f.default_manifest}});
    if(f.widget==='file'){const extensions=field(grid,{extensions:(f.extensions||[]).join(', ')},'extensions','允许的扩展名',{placeholder:'.csv, .jsonl, .zip',change:()=>{f.extensions=extensions.value.split(/[,，\s]+/).filter(Boolean)}});field(grid,f,'max_bytes','单文件上限（字节）',{type:'number'});field(grid,f,'view','读取视图',{placeholder:'例如 records 或 bytes'});detail.append(node('p','config-hint','文件上传后由服务端登记为临时 Item。将此字段绑定到 Job 的 URI 变量；Job 中 read_item 的 view 须与此处一致。'))}
    if(f.widget==='number'){field(grid,f,'min','最小值',{type:'number',optional:true});field(grid,f,'max','最大值',{type:'number',optional:true})}
    if(f.widget==='text')field(grid,f,'max_length','最大长度',{type:'number',optional:true});
    if(f.widget==='cone'){field(grid,f,'min_radius','最小半径',{type:'number',optional:true});field(grid,f,'max_radius','最大半径',{type:'number',optional:true})}
    if(f.widget==='select')pairs(detail,f,'options',{title:'选项',left:{key:'value',label:'选项值'},right:{key:'label',label:'显示文字'},seed:{value:'option',label:'新选项'}});
    if(f.widget==='object'){const hint=node('div','config-group-link');hint.append(node('span','',`此分组包含 ${(f.fields||[]).length} 个字段`),action('编辑分组字段 →',()=>{state.path.push(f);state.input=0;render()}));detail.append(hint)}
    if(['polygon','cone'].includes(f.widget))detail.append(node('p','config-help',f.widget==='polygon'?'用户在地图上选择多边形范围；初始区域可从数据上下文读取。':'用户设置赤经、赤纬和天区半径。'));
    advanced(detail,g=>{for(const[k,label]of [['initial_manifest','初始值来源'],['default_manifest','默认值来源'],['coverage_manifest','数据覆盖范围来源']])field(g,f,k,label,{optional:true,placeholder:'Manifest 路径，例如 /default_cone',change:()=>{if(k==='default_manifest'&&f.default_manifest)delete f.default}});
     field(g,f,'unit','单位',{optional:true,placeholder:'按数据的实际单位填写'});if(f.widget==='object')columns(g,f,'point_fields','经纬度字段');
     if(['cone','polygon','object'].includes(f.widget)){const input=field(g,{value:f.default===undefined?'':JSON.stringify(f.default)},'value','固定默认值（JSON）',{type:'textarea',optional:true});input.onchange=()=>{try{if(!input.value.trim())delete f.default;else{f.default=JSON.parse(input.value);delete f.default_manifest}input.setCustomValidity('')}catch{input.setCustomValidity('请输入有效的 JSON');input.reportValidity();onError('固定默认值不是有效的 JSON')}}}
    });
   }});
 }render();
}
function resultForms(parent,app,onError){
 let state=states.get(app);if(!state){state={input:0,path:[],view:0,download:0,summary:0,resultTab:'views'};states.set(app,state)}
 const result=app.result??={decoder:'json',views:[],artifacts:[]};
 function render(){parent.replaceChildren();const nav=node('div','config-result-tabs');for(const[key,title]of [['views','展示组件'],['artifacts','下载文件'],['summary','汇总指标'],['publication','署名与许可']]){const b=action(title,()=>{state.resultTab=key;render()},state.resultTab===key?'selected':'');b.setAttribute('aria-pressed',String(state.resultTab===key));nav.append(b)}parent.append(nav);
  const decoder=node('div','config-decoder');field(decoder,result,'decoder','成果解析方式',{options:{json:'JSON 数据', 'svg-metadata':'含元数据的 SVG'}});parent.append(decoder);
  if(state.resultTab==='publication'){publicationEditor(parent,app);return}
  const key=state.resultTab,selection=key==='views'?'view':key==='artifacts'?'download':'summary';
  if(key==='summary'){summaryEditor(parent,result,state,render,onError);return}
  const items=result[key]??=[];
  collection(parent,items,{title:key==='views'?'结果展示':'成果下载',description:key==='views'?'按展示顺序配置指标、表格、图表和地图。':'设置用户可以下载的文件及其来源。',selection:state[selection],setSelection:i=>state[selection]=i,caption:v=>v.title||v.label||v.file||'未命名组件',icon:v=>key==='views'?views[v.type]?.[1]||'▤':'↓',create:()=>{if(key==='views')return{type:'table',title:'新结果表格',path:'/rows',columns:[]};let i=1;while(items.some(a=>a.id==='download_'+i))i++;return{id:'download_'+i,label:'新下载文件',file:'result-'+i+'.json',format:'json',path:''}},changed:render,
   edit(detail,v,refresh){const grid=node('div','config-grid');detail.append(grid);
    if(key==='views'){
     thumbnail(detail,v.type,v.title);detail.insertBefore(detail.lastChild,grid);
     field(grid,v,'title','组件标题',{change:refresh});componentPicker(grid,v,'type','展示形式',views,()=>{if(v.type==='metrics')v.items??=[];else v.path??='/rows';if(v.type==='table')v.columns??=[];if(v.type==='scatter'){v.x??='x';v.y??='y'}render()});
     if(v.type==='metrics')pairs(detail,v,'items',{title:'指标',left:{key:'label',label:'指标名称'},right:{key:'path',label:'数据路径'},seed:{label:'新指标',path:'/value'}});
     else field(grid,v,'path','数据路径',{placeholder:'例如 /rows 或 /geojson'});
     if(v.type==='table')columns(grid,v,'columns','展示列');
     if(v.type==='scatter'){field(grid,v,'x','横轴字段');field(grid,v,'y','纵轴字段');field(grid,v,'reverse_x','横轴反向',{type:'checkbox'});grid.querySelector('input[type=checkbox]').checked=!!v.reverse_x}
    }else{
     field(grid,v,'label','下载名称',{change:refresh});field(grid,v,'id','下载标识',{change:refresh});field(grid,v,'file','文件名',{change:refresh});field(grid,v,'format','文件格式',{options:{json:'JSON',csv:'CSV 表格',raw:'原始文件'},change:render});
     if(v.format!=='raw')field(grid,v,'path','数据路径',{placeholder:'留空表示整个结果'});if(v.format==='csv')columns(grid,v,'columns','导出列');
    }
    if(v.type==='table'||key==='artifacts'&&v.format!=='raw')advanced(detail,g=>{field(g,v,'project','行属性路径',{optional:true,placeholder:'例如 /properties'});const filters=v.exclude??{};if(Object.keys(filters).length){const label=node('div','config-wide');label.append(node('h4','','排除条件'));for(const[k,value]of Object.entries(filters))field(label,filters,k,k,{type:typeof value==='number'?'number':'text'});g.append(label)}});
   }});
 }render();
}
function publicationEditor(parent,app){
 const publication=app.publication??={};publication.application??={name:'',license:'',view:'unconfirmed',download:'unconfirmed',commercial:'unconfirmed'};publication.sources??=[];
 const datasets=[...new Set(Object.values(app.variables||{}).filter(b=>b.dataset).map(b=>b.dataset))];for(const dataset of datasets)if(!publication.sources.some(s=>s.dataset===dataset))publication.sources.push({dataset,rights_holder:'',provider:'',license:'',view:'unconfirmed',download:'unconfirmed',commercial:'unconfirmed'});
 let selected=0;function render(){const old=parent.querySelector('.config-publication');if(old)old.remove();const root=node('div','config-publication');parent.append(root);const head=node('div','config-section-head'),text=node('div');text.append(node('h3','','署名与分享许可'),node('p','','为应用和数据来源设置发布默认值。运行完成后仍须确认；此处不代表权利或身份已经核验。'));head.append(text);root.append(head);
 const parties=[{policy:publication.application,label:'应用与分析方法',type:'app'},...publication.sources.map(p=>({policy:p,label:p.dataset||p.uri||'数据来源',type:'source'}))];const layout=node('div','config-master-detail'),list=node('div','config-item-list'),detail=node('div','config-item-detail');layout.append(list,detail);root.append(layout);
 parties.forEach((p,i)=>{const b=action('',()=>{selected=i;render()},'config-item'+(selected===i?' selected':''));const names=node('span','config-item-names');names.append(node('strong','',p.label),node('small','',p.policy.view==='allowed'?'✓ 允许公开':p.policy.view==='denied'?'禁止公开':'许可待确认'));b.append(node('span','config-item-icon',p.type==='app'?'◈':'▥'),names);list.append(b)});
 const chosen=parties[selected],policy=chosen.policy,grid=node('div','config-grid');detail.append(grid);field(grid,policy,chosen.type==='app'?'name':'rights_holder',chosen.type==='app'?'应用作者':'数据权利人');if(chosen.type==='source')field(grid,policy,'provider','数据托管方');field(grid,policy,'license','使用许可',{type:'textarea'}).closest('label').classList.add('config-wide');field(grid,policy,'attribution','署名与引用要求',{type:'textarea',optional:true}).closest('label').classList.add('config-wide');const permissions={unconfirmed:'未确认',allowed:'允许',denied:'禁止'};field(grid,policy,'view','公开分析结果',{options:permissions,change:render});field(grid,policy,'download','下载成果文件',{options:permissions});field(grid,policy,'commercial','商业使用',{options:permissions});detail.append(node('p','config-help','具体按已有授权填写。未确认或禁止公开时，系统会阻止结果发布。许可冲突需要人工解决。'));
 }render();
}
function summaryEditor(parent,result,state,render,onError){
 const values=result.summary??={};const keys=Object.keys(values);const header=node('div','config-section-head'),text=node('div');text.append(node('h3','','汇总指标'),node('p','','为执行记录提取摘要数值或统计集合数量。'));header.append(text,action('＋ 添加指标',()=>{let i=1;while(values['metric_'+i])i++;values['metric_'+i]={path:'/value'};render()},'primary'));parent.append(header);
 if(!keys.length){parent.append(node('div','config-empty','尚未配置汇总指标。普通结果展示不要求设置此项。'));return}
 const table=node('div','config-summary-table');keys.forEach(key=>{const row=node('div','config-summary-row'),name={key};field(row,name,'key','指标标识',{change:()=>{const next=name.key.trim();if(!next||next!==key&&Object.hasOwn(values,next)){onError('汇总指标标识不能为空或重复');name.key=key;render();return}const entries=Object.entries(values).map(([k,v])=>[k===key?next:k,v]);result.summary=Object.fromEntries(entries);render()}});field(row,values[key],'label','显示名称',{optional:true});field(row,values[key],'path','数据路径');const operation={operation:values[key].operation||'value'};field(row,operation,'operation','取值方式',{options:{value:'直接取值',count:'集合数量'},change:()=>{if(operation.operation==='count')values[key].operation='count';else delete values[key].operation}});row.append(action('删除',()=>{delete values[key];render()},'danger'));table.append(row)});parent.append(table);
}
function variables(parent,app,job,onError){
 const names=[];function scan(value){if(typeof value==='string'&&/^\$[A-Za-z_][A-Za-z0-9_]*$/.test(value)){const name=value.slice(1);if(!names.includes(name))names.push(name)}else if(Array.isArray(value))value.forEach(scan);else if(value&&typeof value==='object')Object.values(value).forEach(scan)}scan(job);
 app.variables??={};let selected=0,datasets=[];
 function inputs(fields,prefix=''){return fields.flatMap(f=>{const path=prefix+f.name;return[{path,label:(f.label||f.name)+' · '+path,widget:f.widget},...(f.widget==='object'?inputs(f.fields||[],path+'.'):[])]})}
 const available=inputs(app.inputs||[]);function complete(b={}){if(Object.hasOwn(b,'value'))return true;if(b.input)return available.some(f=>f.path===b.input);if(b.bbox)return available.some(f=>f.path===b.bbox&&f.widget==='polygon');if(b.dataset)return !!b.item;if(b.manifest)return b.manifest.startsWith('/');return false}const sources={unbound:'选择取值来源',input:'表单字段',value:'固定值',dataset:'数据集 Item',manifest:'上下文元数据',bbox:'选区边界框'};
 function render(){parent.replaceChildren();const head=node('div','config-section-head'),text=node('div');text.append(node('h3','','变量绑定'),node('p','','变量由 Job 自动提取；为每个变量选择取值来源，无需重复填写变量名。'));head.append(text,node('span','config-count',names.length+' 个变量'));parent.append(head);
  if(!names.length){parent.append(node('div','config-empty','当前 Job 未引用变量。请先在 Job Designer 中设计流程，使用 $变量名 表示可配置的参数。'));return}
  const bound=names.filter(n=>complete(app.variables[n])).length;
  const status=node('div','config-binding-status');status.append(node('span',bound===names.length?'binding-complete':'binding-pending',bound===names.length?'✓ 全部已绑定':'◌ '+(names.length-bound)+' 个变量待绑定'),node('small','',`${bound} / ${names.length}`));parent.append(status);
  const layout=node('div','config-master-detail'),list=node('div','config-item-list'),detail=node('div','config-item-detail');layout.append(list,detail);parent.append(layout);
  names.forEach((name,i)=>{const binding=app.variables[name]||{},source=Object.keys(sources).find(k=>Object.hasOwn(binding,k));const b=action('',()=>{selected=i;render()},'config-item'+(selected===i?' selected':''));const title=node('span','config-item-names');title.append(node('code','',name),node('small','',(sources[source]||'尚未绑定')+(source&&!complete(binding)?' · 待完善':'')));b.append(node('span','config-item-icon',complete(binding)?'↗':'!'),title);list.append(b)});
  const name=names[selected];let binding=app.variables[name]||{};const source=Object.keys(sources).find(k=>Object.hasOwn(binding,k))||'unbound';
  const title=node('div','config-variable-title');title.append(node('span','config-item-icon','$'),node('code','',name));detail.append(title);const grid=node('div','config-grid');detail.append(grid);
  field(grid,{source},'source','取值来源',{options:sources,change:undefined}).onchange=e=>{const k=e.target.value;if(k==='unbound')delete app.variables[name];else app.variables[name]=k==='value'?{value:''}:k==='dataset'?{dataset:datasets[0]?.name||'',item:''}:k==='input'?{input:available[0]?.path||''}:k==='bbox'?{bbox:available.find(f=>f.widget==='polygon')?.path||''}:{manifest:''};render()};
  if(source==='input'||source==='bbox'){const choices=available.filter(f=>source!=='bbox'||f.widget==='polygon');const options=Object.fromEntries([['','请选择字段'],...choices.map(f=>[f.path,f.label])]);if(binding[source]&&!options[binding[source]])options[binding[source]]=binding[source]+'（字段不存在）';field(grid,binding,source,'表单字段',{options,change:render});if(!choices.length)detail.append(node('p','config-help','请先在“输入表单”中添加相应字段。'))}
  else if(source==='dataset'){const options=Object.fromEntries([['','请选择数据集'],...datasets.map(d=>[d.name,d.title||d.name])]);if(binding.dataset&&!options[binding.dataset])options[binding.dataset]=binding.dataset;field(grid,binding,'dataset','数据集',{options,change:render});field(grid,binding,'item','Item 相对路径',{placeholder:'例如 catalog.bundle',change:render});detail.append(node('p','config-help','提交时自动解析该数据集在 Union 上的实际访问地址。'))}
  else if(source==='manifest')field(grid,binding,'manifest','元数据路径',{placeholder:'例如 /default_cone',change:render});
  else if(source==='value'){const value=binding.value;const type=Array.isArray(value)?'json':value===null?'json':typeof value==='object'?'json':typeof value;const types={string:'文本',number:'数值',boolean:'开关',json:'结构化数据'};field(grid,{type},'type','数据类型',{options:types}).onchange=e=>{binding.value=({string:'',number:0,boolean:false,json:{}})[e.target.value];render()};
   if(type==='boolean')field(grid,binding,'value','固定值',{type:'checkbox'});
   else if(type==='json'){const f=field(grid,{text:JSON.stringify(value,null,2)},'text','固定值（JSON）',{type:'textarea'});f.onchange=()=>{try{binding.value=JSON.parse(f.value);f.setCustomValidity('')}catch{f.setCustomValidity('请输入有效的 JSON');f.reportValidity();onError('固定值不是有效的 JSON')}}}
   else field(grid,binding,'value','固定值',{type:type==='number'?'number':'text'});
  }
  const unused=Object.keys(app.variables).filter(k=>!names.includes(k));if(unused.length){const note=node('details','config-advanced');note.append(node('summary','','未被当前 Job 使用的绑定（'+unused.length+'）'),node('p','config-help',unused.join('、')),action('清理这些绑定',()=>{unused.forEach(k=>delete app.variables[k]);render()}));parent.append(note)}
 }render();ConetAppBuilder.request('/api/config').then(config=>{datasets=config.centers.flatMap(c=>c.datasets||[]).filter(d=>d.case===app.data?.scope);if(!parent.isConnected)return;const select=parent.querySelector('select[aria-label="数据集"]');if(select){const selected=select.value;select.replaceChildren();const options=Object.fromEntries([['','请选择数据集'],...datasets.map(d=>[d.name,d.title||d.name])]);if(selected&&!options[selected])options[selected]=selected;for(const[value,label]of Object.entries(options)){const option=node('option','',label);option.value=value;select.append(option)}select.value=selected}}).catch(()=>{});
}
function basic(parent,app){
 app.presentation??={};const head=node('div','config-section-head');head.append(node('h3','','应用信息'),node('p','','介绍应用，设置数据上下文与标签。'));parent.append(head);
 const grid=node('div','config-grid');parent.append(grid);
 const title=field(grid,app,'title','应用名称');field(grid,app,'id','应用标识');const description=field(grid,app,'description','应用介绍',{type:'textarea'});description.closest('label').classList.add('config-wide');

 columns(grid,app.presentation,'tags','应用标签');
}
function automatic(parent,app){
 app.presentation??={};const grid=node('div','config-grid');parent.append(grid);
 const suggestion=node('div','appearance-recommendation config-wide');grid.append(suggestion);
 function recommend(){const r=ConetPresentation.recommend(app);suggestion.replaceChildren();suggestion.style.setProperty('--recommend-accent',palettes[r.theme][2]);const images=node('div','recommend-images'),background=node('img'),cover=node('img');background.src=r.background;background.alt='推荐背景图';cover.src=r.cover;cover.alt='推荐缩略图';images.append(background,cover);const text=node('div','recommend-text');text.append(node('small','','为这个应用推荐'),node('strong','',r.name+' · '+r.label),node('p','',r.reason));suggestion.append(images,text,action('应用整套推荐',()=>{Object.assign(app.presentation,{theme:r.theme,background:r.background,cover:r.cover});parent.replaceChildren();automatic(parent,app)},'primary'));}
 recommend();themePicker(grid,app);
 const defaults={layout:app.inputs?.some(f=>['polygon','cone'].includes(f.widget))?'stacked':'split',hero:'banner',surface:'soft',density:'comfortable'};const design=app.presentation.design??={};for(const [key,value]of Object.entries(defaults))design[key]??=value;const layout=node('fieldset','config-wide component-picker');layout.append(node('legend','','页面布局 · 自动生成使用此配置'));const layoutChoices=node('div','config-grid');layout.append(layoutChoices);grid.append(layout);
 field(layoutChoices,design,'layout','内容布局',{options:{stacked:'上下布局 · 适合地图与天区',split:'均衡两栏',wide:'结果区域加宽'}});
 field(layoutChoices,design,'hero','标题区域',{options:{banner:'场景横幅',compact:'紧凑标题',centered:'居中介绍'}});
 field(layoutChoices,design,'surface','面板样式',{options:{soft:'柔和卡片',outlined:'细线边框',elevated:'悬浮阴影'}});
 field(layoutChoices,design,'density','内容间距',{options:{comfortable:'舒适',compact:'紧凑'}});

 const gallery=node('fieldset','background-gallery config-wide');gallery.append(node('legend','','应用背景图'));const choices=node('div','background-choices');gallery.append(choices);grid.append(gallery);
 for(const p of [{id:'auto',name:'跟随主题'},{id:'none',name:'无背景'},...ConetPresentation.presets.map(p=>({id:p.background,name:p.label,image:p.background}))]){
  const label=node('label','background-choice'),radio=node('input');radio.type='radio';radio.name='application-background';radio.value=p.id;radio.setAttribute('aria-label',p.name+(['auto','none'].includes(p.id)?'':'背景'));radio.checked=p.id==='auto'?!Object.hasOwn(app.presentation,'background'):p.id==='none'?app.presentation.background===null:app.presentation.background===p.id;
  const sample=node('span','background-choice-sample');if(p.image){const image=node('img');image.src=p.image;image.alt='';sample.append(image)}else sample.textContent=p.id==='auto'?'◈':'∅';label.append(radio,sample,node('span','',p.name));choices.append(label);radio.onchange=()=>{if(p.id==='auto')delete app.presentation.background;else app.presentation.background=p.id==='none'?null:p.id;updatePreview()};
 }
 const media=node('div','appearance-media config-wide');grid.append(media);
 function imageEditor(key,label){const box=node('div','appearance-image-editor');box.append(node('h4','',label));const preview=node('img');preview.alt=label+'预览';box.append(preview);const address=field(box,app.presentation,key,label+'地址',{optional:true,placeholder:'https://… 或 /assets/…',change:updatePreview});const upload=node('input');upload.type='file';upload.accept='image/png,image/jpeg';upload.setAttribute('aria-label','上传'+label);box.append(upload,node('small','','PNG / JPEG，最大 2 MB'));media.append(box);
  upload.onchange=async()=>{const file=upload.files[0];if(!file)return;if(file.size>2097152){upload.setCustomValidity('图片最大 2 MB');upload.reportValidity();return}upload.disabled=true;upload.setCustomValidity('');try{const content=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=reject;reader.readAsDataURL(file)});const result=await ConetAppBuilder.request('/api/appbuilder/media',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({content_base64:content})});app.presentation[key]=result.url;address.value=result.url;updatePreview()}catch(e){upload.setCustomValidity(e.message);upload.reportValidity()}finally{upload.disabled=false}};
  return{preview,address};
 }
 const bg=imageEditor('background','背景图'),cover=imageEditor('cover','缩略图');
 function updatePreview(){const background=Object.hasOwn(app.presentation,'background')?app.presentation.background:ConetPresentation.presets.find(p=>p.theme===(app.presentation.theme||'coast')).background;for(const [editor,value]of [[bg,background],[cover,app.presentation.cover]]){editor.preview.hidden=!value;if(value)editor.preview.src=value;editor.address.value=app.presentation[editor===bg?'background':'cover']||''}for(const radio of choices.querySelectorAll('input'))radio.checked=radio.value==='auto'?!Object.hasOwn(app.presentation,'background'):radio.value==='none'?app.presentation.background===null:radio.value===app.presentation.background;}
 grid.querySelectorAll('.theme-card input').forEach(input=>input.addEventListener('change',updatePreview));updatePreview();
}

function settings(parent,app){app.limits??={max_queued:4,max_result_bytes:8388608};app.presentation??={};const head=node('div','config-section-head');head.append(node('h3','','运行设置'));parent.append(head);const grid=node('div','config-grid');parent.append(grid);field(grid,app.limits,'max_queued','等待及运行任务上限',{type:'number'});const max={size:(app.limits.max_result_bytes||8388608)/1048576};field(grid,max,'size','结果大小上限（MiB）',{type:'number',change:()=>app.limits.max_result_bytes=Math.round(max.size*1048576)});field(grid,app.presentation,'notice','页面提示',{type:'textarea',optional:true}).closest('label').classList.add('config-wide');field(grid,app,'synthetic','标注为合成数据演示',{type:'checkbox'})}
window.ConetConfigurationForms={inputs:inputForms,result:resultForms,variables,basic,automatic,options:settings};})();
