/* Radar YouTube V7 · Puente de mando supremo + Optimizaciones de Creador
   Instalación: reemplaza tu archivo cockpit.js anterior por este código. */
(()=>{
"use strict";

const CSS=`
:root{--bg:#02050b;--s:#07111d;--s2:#0b1828;--b:rgba(34,228,255,.24);--m:#7f9bb5;--g:#3dffb0;--v:#22e4ff;--vio:#8b5cff;
--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,"Liberation Mono",monospace;--glow:0 0 22px rgba(34,228,255,.08)}
::selection{background:rgba(34,228,255,.3)}
body{background:#02050b;color:#e8fbff;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid var(--v);outline-offset:2px}

header{background:rgba(2,5,11,.95);border-bottom:1px solid var(--b);box-shadow:0 10px 30px rgba(0,0,0,.45)}
header h1{font-family:var(--mono);font-size:15px;letter-spacing:.14em;text-transform:uppercase;color:#e8fbff;
 text-shadow:0 0 12px rgba(34,228,255,.5)}
header small{font-family:var(--mono);color:var(--g)!important;font-size:10px!important;letter-spacing:.08em}

.card,.video{position:relative;border-radius:12px;border:1px solid var(--b);
 background:linear-gradient(160deg,rgba(10,24,40,.88),rgba(4,10,18,.94));
 box-shadow:var(--glow);padding:14px;margin-bottom:12px}
.info{font-family:var(--mono);color:var(--m);letter-spacing:.14em}
.num{font-family:var(--mono);color:#e8fbff;text-shadow:0 0 14px rgba(34,228,255,.45)}
.num.green{color:var(--g);text-shadow:0 0 14px rgba(61,255,176,.5)}
h2{display:flex;align-items:center;gap:10px;font-size:17px;color:#e8fbff;margin-bottom:12px}
h3{font-size:15px;color:#e8fbff;margin-bottom:8px}
label{font-size:12px;color:#8fd9ea;display:block;margin:8px 0 4px}
input:not([type=checkbox]):not([type=range]),select{background:#030a12;border:1px solid rgba(34,228,255,.25);
 border-radius:10px;color:#dffaff;min-height:48px;padding:0 12px;width:100%;margin-bottom:8px}
input:focus,select:focus{border-color:var(--v);box-shadow:0 0 0 3px rgba(34,228,255,.15)}

.btn{background:linear-gradient(100deg,rgba(34,228,255,.16),rgba(139,92,255,.24));border:1px solid rgba(34,228,255,.55);
 border-radius:10px;color:#dffaff;font-size:14px;min-height:48px;padding:0 16px;cursor:pointer;
 box-shadow:0 0 16px rgba(34,228,255,.12);transition:all .2s;display:inline-flex;align-items:center;justify-content:center;gap:8px}
.btn:hover{box-shadow:0 0 24px rgba(34,228,255,.32);border-color:var(--v)}
.btn.sec{background:rgba(6,16,28,.9);border-color:rgba(127,155,181,.35);color:#b9d3e4}
.notice{font-size:11px;color:var(--m);line-height:1.5;margin-top:6px}
.status{font-family:var(--mono);font-size:12px;border:1px solid rgba(34,228,255,.2);border-left:3px solid var(--v);
 border-radius:3px;background:rgba(3,10,18,.9);color:#a8dbe8;padding:10px;margin-bottom:10px}
.status.err{border-left-color:#ff4d6d!important}
.status.ok{border-left-color:var(--g)}

/* Claude Chat & Copilot enhancements */
.cl-quick{display:flex;overflow-x:auto;gap:8px;padding-bottom:6px;scrollbar-width:none;margin-bottom:10px}
.cl-quick button{flex:0 0 auto;min-height:40px;padding:0 14px;font-size:13px;white-space:nowrap}
.cl-log{display:flex;flex-direction:column;gap:10px;margin:10px 0;min-height:38vh;max-height:55vh;overflow:auto;
 scrollbar-width:thin;scrollbar-color:rgba(34,228,255,.35) transparent}
.cl-msg{padding:12px;border-radius:8px;border:1px solid rgba(34,228,255,.18);background:rgba(3,10,18,.85);
 font-size:13px;line-height:1.6;overflow-wrap:anywhere}
.cl-msg.ai{border-left:3px solid var(--vio)}
.cl-msg.me{border-left:3px solid var(--v);color:#bfefff}
.cl-who{margin-bottom:4px;font:700 10px var(--mono);letter-spacing:.14em;text-transform:uppercase;color:var(--m)}
.cl-row{display:flex;gap:8px;align-items:center;position:sticky;bottom:calc(78px + env(safe-area-inset-bottom,0px));
 padding:8px 0;background:linear-gradient(transparent,var(--bg) 30%)}
.cl-row input{margin:0;flex:1}
.cl-row .btn{width:52px;min-height:48px;margin:0;padding:0;font-size:18px}

/* Video Cards & Heat */
.video{display:grid;grid-template-columns:118px 1fr;gap:3px 12px;padding:10px;align-items:start}
.video a{display:contents}
.thumb{grid-column:1;grid-row:1/span 4;width:118px;height:100%;min-height:96px;border-radius:8px;object-fit:cover}
.vtitle{grid-column:2;margin:0;font-size:14px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.video .meta{grid-column:2;margin:0;font-size:11px;color:var(--m)}
.video .meta.hint{display:none}
.speed{grid-column:2;justify-self:start;margin:4px 0 0;display:block;min-width:130px;color:var(--hc)}
.speed b{font-size:24px;line-height:1.1}
.speed i{font-style:normal;font-size:11px;color:#8fb0c8;margin-left:5px}
.speed .bd{display:block;font-size:11px;font-weight:700;margin-top:1px;color:#ffb020}
.speed em{display:block;height:3px;margin-top:5px;border-radius:2px;background:var(--hc)}
.video[data-heat="1"]{--hc:#5fa8c4}
.video[data-heat="3"]{--hc:#ffb020}
.video[data-heat="4"]{--hc:#ff5a3d;border-color:#ff5a3d;box-shadow:0 0 24px rgba(255,90,61,.3)}
.actions{grid-column:1/-1;margin-top:8px;display:flex;gap:6px;flex-wrap:wrap}
.actions button{padding:6px 10px;border-radius:6px;background:rgba(8,20,34,.9);border:1px solid rgba(34,228,255,.25);font-size:12px;color:#dffaff;cursor:pointer}
.actions button:hover{border-color:var(--v)}

/* Navigation */
nav{background:rgba(2,6,12,.95);border-top:1px solid var(--b)}
nav button{font-family:var(--mono);font-size:11px;color:#6f8ba3;border-radius:6px;background:transparent;border:0;cursor:pointer;padding:8px}
nav button svg{display:block;width:22px;height:22px;margin:0 auto 3px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
nav button.on{color:#e8fbff;background:linear-gradient(180deg,rgba(34,228,255,.16),rgba(34,228,255,.02));
 box-shadow:inset 0 -2px 0 var(--v),0 0 16px rgba(34,228,255,.15)}

#aiSum{margin:12px 0}
.sum-h{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;font-weight:bold}
#sumBtn{padding:4px 12px;background:transparent;border:1px solid rgba(34,228,255,.35);border-radius:8px;color:var(--v);font-size:16px;cursor:pointer}
.sum-b{font-size:13px;line-height:1.65;color:#cfeaf2}
details>summary{list-style:none;cursor:pointer;display:flex;align-items:center;justify-content:space-between;min-height:46px;font-weight:700}
details>summary::-webkit-details-marker{display:none}
details>summary::after{content:"›";font-size:24px;color:var(--v);transition:transform .2s}
details[open]>summary::after{transform:rotate(90deg)}
`;

const UI_HTML=`
<section class="view" id="claude">
 <h2>🛰️ Enlace con Claude & Copiloto</h2>
 <div class="card" id="clConnCard">
  <div id="clStatus" class="status">Sin conexión. Pegá tu clave de Anthropic para activar el cerebro estratégico.</div>
  <label for="clKey">Clave de API de Anthropic</label>
  <input id="clKey" type="password" autocomplete="off" placeholder="sk-ant-...">
  <label for="clModel">Modelo</label>
  <select id="clModel">
   <option value="claude-sonnet-5-5">Sonnet 5.5 · equilibrado</option>
   <option value="claude-haiku-5-5">Haiku 5.5 · rápido y económico</option>
   <option value="claude-opus-5-5">Opus 5.5 · análisis profundo</option>
  </select>
  <div style="display:flex; gap:8px; margin-top:8px;">
   <button class="btn" id="clSave" style="flex:1;">Conectar</button>
   <button class="btn sec" id="clOff" style="flex:1;">Desconectar</button>
  </div>
  <div class="notice">Clave local exclusiva para este dispositivo. Se recomienda usar límites de gasto en tu consola de Anthropic.</div>
 </div>
 <div class="card">
  <h3>Copiloto Estratégico</h3>
  <div class="cl-quick">__QUICK__</div>
  <div id="clLog" class="cl-log"></div>
  <div class="cl-row">
   <input id="clAsk" placeholder="Preguntale a Claude sobre estrategia, títulos o guiones...">
   <button class="btn" id="clSend">➤</button>
  </div>
  <div style="display:flex; gap:8px; margin-top:8px;">
   <button class="btn sec" id="clClear" style="flex:1;">Limpiar chat</button>
   <button class="btn sec" id="exportSwipe" style="flex:1;">📥 Exportar Ideas (Markdown)</button>
  </div>
 </div>
</section>`;

const QUICK=[
 ["📊 Analizar ranking","Analizá este ranking: qué patrones de retención y títulos dominan los videos que crecen más rápido."],
 ["💡 Ideas de impacto","Dame 5 ideas de videos orientadas a desarrollo personal y estoicismo, con título potente y gancho de 5s."],
 ["🏷️ Títulos virales","Proponé 10 estructuras de títulos basadas en las anomalías de este ranking."],
 ["🎬 Estructura de Guion","Armá una estructura de guion de 3 minutos para un video dinámico y sin mostrar la cara sobre disciplina mental."]
];

const SYS="Sos el copiloto estratégico experto en YouTube de un creador de contenido enfocado en desarrollo personal, motivación y estoicismo. Respondé en español rioplatense, directo, analítico y ultra accionable. Basate estrictamente en los datos del radar provistos.";
const KEY="radar_claude_key",MOD="radar_claude_model";
const $=id=>document.getElementById(id);
const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const hist=[];

const st=(m,err,ok)=>{const e=$("clStatus");if(!e)return;e.textContent=m;e.className="status"+(err?" err":ok?" ok":"")};
const lock=b=>["clSend","clSave"].forEach(i=>{if($(i))$(i).disabled=b});
const paint=(el,t)=>{el.innerHTML=esc(t).replace(/\*\*(.+?)\*\*/g,"<strong>$1</strong>").replace(/\n/g,"<br>")};

function add(kind,text){
 const log=$("clLog");if(!log)return;
 const d=document.createElement("div");
 d.className="cl-msg "+kind;
 d.innerHTML='<div class="cl-who">'+(kind==="me"?"Vos":"Claude")+'</div><div class="cl-b"></div>';
 const body=d.lastChild;paint(body,text);log.appendChild(d);log.scrollTop=log.scrollHeight;
 return body;
}

async function ask(messages,system,max){
 const r=await fetch("https://api.anthropic.com/v1/messages",{
  method:"POST",
  headers:{
   "content-type":"application/json",
   "x-api-key":localStorage.getItem(KEY)||"",
   "anthropic-version":"2023-06-01",
   "anthropic-dangerous-direct-browser-access":"true"
  },
  body:JSON.stringify({model:$("clModel")?.value||"claude-sonnet-5-5",max_tokens:max||1500,system,messages})
 });
 const d=await r.json().catch(()=>({}));
 if(!r.ok)throw Error(d?.error?.message||"Error de la API ("+r.status+").");
 return (d.content||[]).filter(b=>b.type==="text").map(b=>b.text).join("\n").trim();
}

function ctx(){
 let v=[];try{v=JSON.parse(localStorage.getItem("radar_videos")||"[]")}catch{}
 if(!v.length)return "El radar no tiene videos cargados actualmente.";
 const rows=v.map(x=>{const h=Math.max(1,(Date.now()-x.published)/36e5);return {...x,h,s:Math.round(x.views/h)}})
  .sort((a,b)=>b.s-a.s).slice(0,15);
 return "Tema: "+($("topic")?.value||"General")+"\nTop por velocidad:\n"+
  rows.map((x,i)=>(i+1)+'. "'+x.title+'" — '+x.channel+" — "+x.views+" vistas, "+Math.round(x.h)+"h, "+x.s+" vistas/h").join("\n");
}

async function send(t){
 t=(t??$("clAsk")?.value)??"";t=t.trim();if(!t)return;
 if(!localStorage.getItem(KEY)){st("Primero conectá tu clave de Anthropic.",1);return}
 if($("clAsk"))$("clAsk").value="";
 add("me",t);hist.push({role:"user",content:t});
 const out=add("ai","Claude está procesando la estrategia…");lock(true);
 try{
  const m=hist.slice(-10);while(m[0]?.role==="assistant")m.shift();
  const r=await ask(m,SYS+"\n\nDATOS DEL RADAR:\n"+ctx())||"(sin respuesta)";
  hist.push({role:"assistant",content:r});paint(out,r);
}catch(e){hist.pop();paint(out,"No se pudo completar: "+e.message);st(e.message,1)}
 finally{lock(false);if($("clLog"))$("clLog").scrollTop=$("clLog").scrollHeight}
}

function mount(){
 const nav=document.querySelector("nav"),main=document.querySelector("main");
 if(!nav||!main||$("claude"))return;
 const st0=document.createElement("style");st0.id="cockpit-css";st0.textContent=CSS;document.head.appendChild(st0);
 document.querySelector('meta[name="theme-color"]')?.setAttribute("content","#02050b");

 main.insertAdjacentHTML("beforeend",UI_HTML.replace("__QUICK__",QUICK.map((q,i)=>'<button class="btn sec" data-q="'+i+'">'+q[0]+"</button>").join("")));
 nav.insertAdjacentHTML("beforeend",'<button data-view="claude"><svg viewBox="0 0 24 24"><path d="M5 5h14a2 2 0 012 2v8a2 2 0 01-2 2h-8l-4 3v-3H5a2 2 0 01-2-2V7a2 2 0 012-2z"/></svg><span>Copiloto</span></button>');
 
 nav.addEventListener("click",e=>{
  const b=e.target.closest("button[data-view]");if(!b)return;
  document.querySelectorAll("main>.view").forEach(s=>s.classList.toggle("on",s.id===b.dataset.view));
  nav.querySelectorAll("button").forEach(x=>x.classList.toggle("on",x===b));
  scrollTo(0,0);
 });

 const sm=document.querySelector("header small");
 if(sm){const tick=()=>{sm.textContent="● EN LÍNEA · V7 · "+new Date().toLocaleTimeString("es-AR",{hour12:false})};tick();setInterval(tick,1000)}

 const mSel=$("clModel");if(mSel)mSel.value=localStorage.getItem(MOD)||"claude-sonnet-5-5";
 if(mSel)mSel.onchange=()=>localStorage.setItem(MOD,mSel.value);
 if(localStorage.getItem(KEY))st("Enlace activo con Claude",0,1);

 const sBtn=$("clSave");
 if(sBtn)sBtn.onclick=async()=>{
  const k=$("clKey")?.value.trim()||localStorage.getItem(KEY)||"";
  if(!k.startsWith("sk-ant-")){st("La clave de Anthropic debe comenzar con sk-ant-.",1);return}
  localStorage.setItem(KEY,k);if(mSel)localStorage.setItem(MOD,mSel.value);if($("clKey"))$("clKey").value="";
  st("Verificando credenciales…");lock(true);
  try{await ask([{role:"user",content:"Respondé solo: OK"}],"Respondé exactamente lo pedido.",16);
   st("Enlace establecido correctamente",0,1)}
  catch(e){localStorage.removeItem(KEY);st("Error al conectar: "+e.message,1)}
  finally{lock(false)}
 };

 const offBtn=$("clOff");
 if(offBtn)offBtn.onclick=()=>{localStorage.removeItem(KEY);hist.length=0;if($("clLog"))$("clLog").innerHTML="";st("Desconectado de este dispositivo.")};
 
 const clrBtn=$("clClear");
 if(clrBtn)clrBtn.onclick=()=>{hist.length=0;if($("clLog"))$("clLog").innerHTML=""};
 
 const sendBtn=$("clSend");
 if(sendBtn)sendBtn.onclick=()=>send();
 
 const askInput=$("clAsk");
 if(askInput)askInput.addEventListener("keydown",e=>{if(e.key==="Enter"){e.preventDefault();send()}});
 
 const clView=$("claude");
 if(clView)clView.addEventListener("click",e=>{const q=e.target.closest("[data-q]");if(q)send(QUICK[q.dataset.q][1])});

 // Exportar Swipe File / Ideas en Markdown
 const expBtn=$("exportSwipe");
 if(expBtn)expBtn.onclick=()=>{
  let favs=[];try{favs=JSON.parse(localStorage.getItem("radar_favs")||"[]")}catch{}
  if(!favs.length){alert("No hay videos guardados en tus favoritos/swipe file para exportar.");return}
  const md="# Swipe File - Radar YouTube\n\n"+favs.map(v=>`### ${v.title}\n- **Canal:** ${v.channel}\n- **Vistas:** ${v.views}\n- **Enlace:** https://youtu.be/${v.id}\n`).join("\n");
  const blob=new Blob([md],{type:"text/markdown;charset=utf-8;"});
  const url=URL.createObjectURL(blob);
  const a=document.createElement("a");a.href=url;a.download="swipe-file-radar.md";a.click();
  URL.revokeObjectURL(url);
 };
}

function plus(){
 const ls=k=>{try{return JSON.parse(localStorage.getItem(k)||"[]")}catch{return[]}};
 const thr=()=>+($("threshold")?.value||1000);

 // Botón Claude en cada tarjeta de video
 const decorate=()=>document.querySelectorAll(".video").forEach(a=>{
  a.classList.toggle("hot",/DESPEGANDO/.test(a.querySelector(".speed")?.textContent||""));
  const act=a.querySelector(".actions");
  if(act&&!act.querySelector("[data-ai]")){
   const id=act.querySelector("[data-fav]")?.dataset.fav;
   if(id)act.insertAdjacentHTML("beforeend",'<button data-ai="'+esc(id)+'">🛰️ Consultar a Claude</button>');
  }
 });
 ["results","favResults"].forEach(i=>{const e=$(i);if(e)new MutationObserver(decorate).observe(e,{childList:true})});
 decorate();

 document.addEventListener("click",e=>{
  const b=e.target.closest("[data-ai]");if(!b)return;
  const v=ls("radar_videos").concat(ls("radar_favs")).find(x=>x.id===b.dataset.ai);if(!v)return;
  const h=Math.max(1,(Date.now()-v.published)/36e5);
  document.querySelector('nav [data-view="claude"]')?.click();
  send('Analizá este video en profundidad: "'+v.title+'" de '+v.channel+", con "+v.views+" vistas en "+Math.round(h)+"h. ¿Qué patrón de gancho o título generó este rendimiento y cómo puedo adaptarlo a mi contenido de motivación sin plagiarlo?");
 });

 // Resumen automático con Claude en la pantalla de radar
 const resDiv=$("radarStatus");
 if(resDiv && !$("aiSum")){
  const card=document.createElement("div");card.id="aiSum";card.className="card";
  card.innerHTML='<div class="sum-h"><span>🛰️ Lectura Exprés de Claude</span><button id="sumBtn" aria-label="Actualizar lectura">↻</button></div><div id="sumBody" class="sum-b">Hacé una búsqueda en el radar para generar un resumen táctico automático.</div>';
  resDiv.after(card);
  
  async function summarize(){
   const b=$("sumBody");if(!b)return;
   if(!localStorage.getItem(KEY)){b.textContent="Conectá tu clave de Claude en su pestaña para activar los resúmenes automáticos.";return}
   if(!ls("radar_videos").length){b.textContent="Todavía no hay datos de video para procesar.";return}
   b.textContent="Analizando tendencias…";
   try{
    const ans=await ask([{role:"user",content:"Resumí el ranking actual en exactamente 3 líneas cortas y directas: tendencia principal, punto en común de los ganadores, y oportunidad clave para el creador."}],SYS+"\n\nDATOS:\n"+ctx(),300);
    paint(b,ans);
   }catch(e){b.textContent="Error al generar resumen: "+e.message}
  }
  const sBtn=$("sumBtn");if(sBtn)sBtn.onclick=summarize;
  
  new MutationObserver(()=>{
   const t=resDiv.textContent;
   if(t.startsWith("Búsqueda completada"))summarize();
  }).observe(resDiv,{childList:true});
 }
}

const boot=()=>{mount();plus()};
if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",boot);else boot();
})();
