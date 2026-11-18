// Sandboxed map frames have an opaque origin. Keep SDK preferences in bounded,
// frame-local memory when browser storage is unavailable; retain the sandbox.
for(const name of ['localStorage','sessionStorage']){
 try{void window[name]}catch{
  const values=new Map();let size=0;
  const storage={get length(){return values.size},key(index){return [...values.keys()][index]??null},getItem(key){return values.get(String(key))??null},removeItem(key){key=String(key);if(values.has(key)){size-=key.length+values.get(key).length;values.delete(key)}},clear(){values.clear();size=0},setItem(key,value){key=String(key);value=String(value);if(key.length+value.length>65536)return;this.removeItem(key);while(values.size>=64||size+key.length+value.length>65536)this.removeItem(values.keys().next().value);values.set(key,value);size+=key.length+value.length}};
  Object.defineProperty(window,name,{configurable:true,value:storage});
 }
}
window.conetTiandituReady=(function loadTianditu(){return new Promise((resolve,reject)=>{if(window.T?.Map){resolve();return}const script=document.createElement('script');script.src='https://api.tianditu.gov.cn/api?v=4.0&tk=de3b89892ae2abd084521e77b924f5c3';const timer=setTimeout(()=>reject(Error('天地图加载超时，请检查网络或浏览器端 Key 的域名设置')),20000);script.onload=()=>{clearTimeout(timer);window.T?.Map?resolve():reject(Error('天地图 API 不可用，请检查浏览器端 Key'))};script.onerror=()=>{clearTimeout(timer);reject(Error('天地图加载失败，请检查网络和 Key 设置'))};document.head.appendChild(script)})}
)();
window.conetTiandituReady.catch(()=>{});
