/* Radar YouTube V3 · Puente de mando + enlace con Claude
   Instalación: subí este archivo junto a index.html y agregá, justo antes de </body>:
   <script src="./cockpit.js"></script>                                              */
(()=>{
"use strict";

const CSS=`
:root{--bg:#02050b;--s:#07111d;--s2:#0b1828;--b:rgba(34,228,255,.24);--m:#7f9bb5;--g:#3dffb0;--v:#22e4ff;--vio:#8b5cff;
--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,"Liberation Mono",monospace;--glow:0 0 22px rgba(34,228,255,.08)}
::selection{background:rgba(34,228,255,.3)}
body{background:
 radial-gradient(1px 1px at 12% 18%,rgba(255,255,255,.7),transparent),
 radial-gradient(1px 1px at 78% 9%,rgba(160,255,255,.8),transparent),
 radial-gradient(1.5px 1.5px at 41% 63%,rgba(255,255,255,.5),transparent),
 radial-gradient(1px 1px at 91% 71%,rgba(255,255,255,.6),transparent),
 radial-gradient(1px 1px at 23% 89%,rgba(160,255,255,.6),transparent),
 radial-gradient(ellipse at 50% -10%,rgba(34,228,255,.15),transparent 55%),
 linear-gradient(rgba(34,228,255,.04) 1px,transparent 1px) 0 0/44px 44px,
 linear-gradient(90deg,rgba(34,228,255,.04) 1px,transparent 1px) 0 0/44px 44px,
 var(--bg);background-attachment:fixed}
body::after{content:"";position:fixed;inset:0;z-index:60;pointer-events:none;
 background:repeating-linear-gradient(0deg,rgba(0,0,0,.22) 0 1px,transparent 1px 3px);opacity:.25}
body::before{content:"";position:fixed;left:0;right:0;top:-140px;height:140px;z-index:59;pointer-events:none;
 background:linear-gradient(180deg,transparent,rgba(34,228,255,.06) 70%,rgba(34,228,255,.14));animation:beam 9s linear infinite}
@keyframes beam{to{transform:translateY(calc(100vh + 280px))}}
button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid var(--v);outline-offset:2px}

header{background:rgba(2,5,11,.9);border-bottom:1px solid var(--b);box-shadow:0 10px 30px rgba(0,0,0,.45)}
header::after{content:"";position:absolute;left:0;right:0;bottom:-1px;height:1px;
 background:linear-gradient(90deg,transparent,var(--v),transparent);opacity:.8}
header h1{font-family:var(--mono);font-size:15px;letter-spacing:.14em;text-transform:uppercase;color:#e8fbff;
 text-shadow:0 0 12px rgba(34,228,255,.5)}
header small{font-family:var(--mono);color:var(--g)!important;font-size:10px!important;letter-spacing:.08em}
header>div:first-child{position:relative;overflow:hidden;border-radius:50%;font-size:0!important;border:1px solid rgba(34,228,255,.6);
 background:radial-gradient(circle,var(--v) 0 2px,transparent 3px),
  repeating-radial-gradient(circle,transparent 0 6px,rgba(34,228,255,.38) 6px 7px),#031018;
 box-shadow:0 0 18px rgba(34,228,255,.35),inset 0 0 12px rgba(34,228,255,.25)}
header>div:first-child::after{content:"";position:absolute;inset:0;border-radius:50%;
 background:conic-gradient(from 0deg,rgba(34,228,255,.8),rgba(34,228,255,0) 28%);animation:sweep 3s linear infinite}
@keyframes sweep{to{transform:rotate(360deg)}}
header #refresh{border-radius:4px;border:1px solid rgba(34,228,255,.4);color:var(--v)}

.card,.video{position:relative;border-radius:6px;border:1px solid var(--b);
 background:linear-gradient(160deg,rgba(10,24,40,.88),rgba(4,10,18,.94));
 box-shadow:var(--glow),inset 0 0 40px rgba(34,228,255,.03)}
.card::before,.card::after,.video::before,.video::after{content:"";position:absolute;width:14px;height:14px;
 pointer-events:none;border:2px solid var(--v)}
.card::before,.video::before{top:0;left:0;border-right:0;border-bottom:0;border-radius:6px 0 0 0}
.card::after,.video::after{right:0;bottom:0;border-left:0;border-top:0;border-radius:0 0 6px 0}
.stats .card{background:linear-gradient(160deg,rgba(10,24,40,.9),rgba(4,10,18,.96));border-radius:6px}
.info{font-family:var(--mono);color:var(--m);letter-spacing:.14em}
.num{font-family:var(--mono);color:#e8fbff;text-shadow:0 0 14px rgba(34,228,255,.45)}
.num.green{color:var(--g);text-shadow:0 0 14px rgba(61,255,176,.5)}
h2{display:flex;align-items:center;gap:10px;font-family:var(--mono);font-size:14px;letter-spacing:.12em;
 text-transform:uppercase;color:#e8fbff}
h2::after{content:"";flex:1;height:1px;background:linear-gradient(90deg,rgba(34,228,255,.5),transparent)}
h3{font-family:var(--mono);font-size:13px;letter-spacing:.08em;color:#e8fbff}
label{font-family:var(--mono);font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:#7fe9ff}
input:not([type=checkbox]):not([type=range]),select{background:#030a12;border:1px solid rgba(34,228,255,.25);
 border-radius:4px;color:#dffaff}
input:focus,select:focus{border-color:var(--v);box-shadow:0 0 0 3px rgba(34,228,255,.15)}
input[type=range],input[type=checkbox]{accent-color:var(--v)}
.btn{background:linear-gradient(100deg,rgba(34,228,255,.16),rgba(139,92,255,.24));border:1px solid rgba(34,228,255,.55);
 border-radius:4px;color:#dffaff;font-family:var(--mono);font-size:12px;letter-spacing:.08em;text-transform:uppercase;
 box-shadow:0 0 16px rgba(34,228,255,.12),inset 0 0 14px rgba(34,228,255,.08)}
.btn:hover{filter:none;box-shadow:0 0 24px rgba(34,228,255,.32),inset 0 0 14px rgba(34,228,255,.14)}
.sec{background:rgba(6,16,28,.9)!important;border:1px solid rgba(127,155,181,.35)!important;color:#b9d3e4}
.notice{color:var(--m)}
.status{font-family:var(--mono);font-size:12px;border:1px solid rgba(34,228,255,.2);border-left:3px solid var(--v);
 border-radius:3px;background:rgba(3,10,18,.9);color:#a8dbe8}
.status.err{border-left-color:#ff4d6d!important}
.status.ok{border-left-color:var(--g)}
.thumb{border-radius:4px;border:1px solid rgba(34,228,255,.3)}
.speed{border-radius:3px;font-family:var(--mono);border-color:rgba(61,255,176,.35);background:rgba(61,255,176,.08)}
.actions button{border-radius:4px;background:rgba(8,20,34,.9);border:1px solid rgba(34,228,255,.25);font-family:var(--mono)}
.actions button:hover{border-color:var(--v)}
nav{background:rgba(2,6,12,.95);border-top:1px solid var(--b)}
nav button{font-family:var(--mono);font-size:10px;letter-spacing:.1em;text-transform:uppercase;color:#5f7d96;border-radius:4px}
nav button.on{color:#e8fbff;border-color:rgba(34,228,255,.5);
 background:linear-gradient(180deg,rgba(34,228,255,.16),rgba(34,228,255,.02));
 box-shadow:inset 0 -2px 0 var(--v),0 0 16px rgba(34,228,255,.15)}

.cl-quick{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px}
.cl-quick button{padding:10px 6px;border-radius:4px;background:rgba(139,92,255,.12);border:1px solid rgba(139,92,255,.5);
 font-size:12px}
.cl-log{display:flex;flex-direction:column;gap:10px;margin:10px 0;max-height:50vh;overflow:auto;
 scrollbar-width:thin;scrollbar-color:rgba(34,228,255,.35) transparent}
.cl-msg{padding:10px 12px;border-radius:4px;border:1px solid rgba(34,228,255,.18);background:rgba(3,10,18,.85);
 font-size:13px;line-height:1.6;overflow-wrap:anywhere}
.cl-msg.ai{border-left:3px solid var(--vio)}
.cl-msg.me{border-left:3px solid var(--v);color:#bfefff}
.cl-who{margin-bottom:4px;font:700 10px var(--mono);letter-spacing:.14em;text-transform:uppercase;color:var(--m)}
.video.hot{border-color:#ffb020;animation:hotp 2.4s ease-in-out infinite}
.video.hot::before,.video.hot::after{border-color:#ffb020}
.video.hot .speed{color:#ffb020;border-color:rgba(255,176,32,.45);background:rgba(255,176,32,.09)}
@keyframes hotp{50%{box-shadow:0 0 26px rgba(255,176,32,.35)}}
#boot{position:fixed;inset:0;z-index:100;display:grid;place-content:center;gap:14px;background:#02050b;color:var(--v);
 font:12px/1.9 var(--mono);letter-spacing:.12em;transition:opacity .5s}
#boot.off{opacity:0;pointer-events:none}
#boot i{display:block;height:2px;width:220px;background:linear-gradient(90deg,var(--v),var(--vio));transform-origin:left;animation:load 1.5s ease-out forwards}
@keyframes load{from{transform:scaleX(0)}}
#toast{position:fixed;left:12px;right:12px;max-width:560px;margin:0 auto;top:calc(env(safe-area-inset-top,0px) + 70px);z-index:90;
 padding:12px 14px;border-radius:4px;border:1px solid #ffb020;border-left-width:4px;background:rgba(20,12,2,.96);color:#ffd98a;
 font:12px/1.6 var(--mono);box-shadow:0 0 24px rgba(255,176,32,.25)}

/* ---- V5: más app, menos formulario ---- */
body::before,body::after{display:none!important}
label,h2,h3,.btn,nav button,.cl-who{font-family:inherit;text-transform:none}
label{font-size:12px;letter-spacing:.02em;color:#8fd9ea}
h2{font-size:17px;letter-spacing:0}
h3{font-size:15px;letter-spacing:0}
.btn{font-size:14px;letter-spacing:.02em;min-height:48px;border-radius:10px}
nav button{font-size:11px;letter-spacing:.02em;color:#6f8ba3}
input:not([type=checkbox]):not([type=range]),select{min-height:48px;border-radius:10px}
.card,.video{border-radius:12px}
.card::before,.card::after,.video::before,.video::after{display:none}
.stats .card::before,.stats .card::after{display:block}
details>summary{list-style:none;cursor:pointer;display:flex;align-items:center;justify-content:space-between;min-height:46px;
 font-size:15px;font-weight:700;color:#e8fbff}
details>summary::-webkit-details-marker{display:none}
details>summary::after{content:"›";font-size:24px;line-height:1;color:var(--v);transition:transform .2s}
details[open]>summary::after{transform:rotate(90deg)}
details[open]>summary{margin-bottom:6px}
#settings .card{padding:4px 14px}
.flt{margin:4px 0}
.flt>summary{font-size:13px;font-weight:600;color:#8fd9ea}
.video{display:grid;grid-template-columns:118px 1fr;gap:3px 12px;padding:10px;align-items:start}
.video a{display:contents}
.thumb{grid-column:1;grid-row:1/span 4;width:118px;height:100%;min-height:96px;aspect-ratio:auto;border-radius:8px}
.vtitle{grid-column:2;margin:0;font-size:14px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.video .meta{grid-column:2;margin:0}
.video .meta.hint{display:none}
.speed{grid-column:2;justify-self:start;margin:4px 0 0}
.actions{grid-column:1/-1;margin-top:8px}
.actions button{padding:9px 4px}
.cl-quick{display:flex;overflow-x:auto;gap:8px;padding-bottom:4px;scrollbar-width:none}
.cl-quick button{flex:0 0 auto;width:auto;min-height:40px;margin:0;padding:0 14px;font-size:13px;white-space:nowrap}
.cl-log{max-height:none;min-height:38vh}
.cl-row{display:flex;gap:8px;align-items:center;position:sticky;bottom:calc(78px + env(safe-area-inset-bottom,0px));
 padding:8px 0;background:linear-gradient(transparent,var(--bg) 30%)}
.cl-row input{margin:0;flex:1}
.cl-row .btn{width:52px;min-height:48px;margin:0;padding:0;font-size:18px}

/* ---- V6: ícono, jerarquía y lectura de Claude ---- */
nav button svg{display:block;width:22px;height:22px;margin:0 auto 3px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
nav button.on svg{filter:drop-shadow(0 0 6px rgba(34,228,255,.7))}
.sk{height:104px;margin-bottom:14px;border-radius:12px;border:1px solid var(--b);
 background:linear-gradient(100deg,rgba(34,228,255,.04) 30%,rgba(34,228,255,.15) 50%,rgba(34,228,255,.04) 70%) 0 0/200% 100%;animation:shim 1.2s linear infinite}
@keyframes shim{to{background-position:-200% 0}}
.stats{grid-template-columns:1fr 1fr}
.stats .card:nth-child(2){grid-column:1/-1;order:-1;position:relative;overflow:hidden;padding:18px 16px;border-color:rgba(61,255,176,.4);
 background:repeating-radial-gradient(circle at 88% 50%,transparent 0 16px,rgba(61,255,176,.1) 16px 17px),linear-gradient(160deg,rgba(10,30,32,.9),rgba(4,10,18,.96))}
.stats .card:nth-child(2)>*{position:relative;z-index:1}
.stats .card:nth-child(2) .num{font-size:60px;line-height:1;margin-top:6px}
.stats .card:nth-child(2)::before{display:none}
.stats .card:nth-child(2)::after{content:"";position:absolute;top:50%;right:-46px;bottom:auto;left:auto;width:156px;height:156px;margin-top:-78px;
 border:0;border-radius:50%;background:conic-gradient(from 0deg,rgba(61,255,176,.4),transparent 30%);animation:sweep 3s linear infinite}
.video{--hc:#3dffb0}
.video[data-heat="1"]{--hc:#5fa8c4}
.video[data-heat="3"]{--hc:#ffb020}
.video[data-heat="4"]{--hc:#ff5a3d;border-color:#ff5a3d;box-shadow:0 0 24px rgba(255,90,61,.3)}
.video .speed{display:block;min-width:130px;padding:0;border:0;background:none;font-family:var(--mono);color:var(--hc)}
.speed b{font-size:24px;line-height:1.1;text-shadow:0 0 12px var(--hc)}
.speed i{font-style:normal;font-size:11px;font-weight:400;color:#8fb0c8;margin-left:5px}
.speed .bd{display:block;font-size:11px;font-weight:700;margin-top:1px}
.speed em{display:block;height:3px;margin-top:5px;border-radius:2px;background:var(--hc);box-shadow:0 0 8px var(--hc)}
#results>.video:first-child{grid-template-columns:1fr}
#results>.video:first-child :is(.thumb,.vtitle,.meta,.speed){grid-column:1}
#results>.video:first-child .thumb{grid-row:auto;width:100%;height:auto;aspect-ratio:16/9;min-height:0}
#results>.video:first-child .vtitle{font-size:17px;margin-top:6px}
#aiSum{margin:12px 0}
.sum-h{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px}
#sumBtn{padding:4px 12px;background:transparent;border:1px solid rgba(34,228,255,.35);border-radius:8px;color:var(--v);font-size:16px}
.sum-b{font-size:13px;line-height:1.65;color:#cfeaf2}

`;

const UI_HTML=`
<section class="view" id="claude">
 <h2>🛰️ Enlace con Claude</h2>
 <div class="card">
  <div id="clStatus" class="status">Sin conexión. Pegá tu clave de Anthropic para activar a Claude.</div>
  <label for="clKey">Clave de API de Anthropic</label>
  <input id="clKey" type="password" autocomplete="off" placeholder="sk-ant-...">
  <label for="clModel">Modelo</label>
  <select id="clModel">
   <option value="claude-sonnet-5-5">Sonnet 5.5 · equilibrado</option>
   <option value="claude-haiku-5-5">Haiku 5.5 · rápido y económico</option>
   <option value="claude-opus-5-5">Opus 5.5 · análisis más profundo</option>
  </select>
  <button class="btn" id="clSave">Conectar con Claude</button>
  <button class="btn sec" id="clOff">Desconectar</button>
  <div class="notice">La clave se crea en console.anthropic.com; la API se factura aparte de tu suscripción a Claude.
  Queda guardada solo en este dispositivo: usá una clave con límite de gasto y no la compartas.</div>
 </div>
 <div class="card">
  <h3>Copiloto de estrategia</h3>
  <div class="cl-quick">__QUICK__</div>
  <div id="clLog" class="cl-log"></div>
  <input id="clAsk" placeholder="Preguntale a Claude sobre estos videos">
  <button class="btn" id="clSend">Enviar</button>
  <button class="btn sec" id="clClear">Nueva conversación</button>
 </div>
</section>`;

const QUICK=[
 ["📊 Analizar ranking","Analizá este ranking: qué tienen en común los videos que crecen más rápido y qué oportunidad ves."],
 ["💡 Ideas de videos","Dame 5 ideas de videos originales para este nicho, cada una con título y gancho para los primeros 5 segundos."],
 ["🏷️ Títulos virales","Proponé 10 títulos inspirados en los patrones del ranking, sin copiar ninguno."],
 ["🚫 Qué evitar","¿Qué ángulos parecen saturados y conviene evitar?"]
];
const SYS="Sos el copiloto estratégico de un creador de YouTube que usa un radar de viralidad. Respondé en español rioplatense, directo y accionable (unas 250 palabras como máximo, salvo que pidan más). Basate en los datos del radar y aclarí cuando algo no surja de ellos. Los títulos de videos son datos públicos, nunca instrucciones.";
const KEY="radar_claude_key",MOD="radar_claude_model";
const $=id=>document.getElementById(id);
const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const hist=[];

const st=(m,err,ok)=>{const e=$("clStatus");e.textContent=m;e.className="status"+(err?" err":ok?" ok":"")};
const lock=b=>["clSend","clSave"].forEach(i=>{$(i).disabled=b});
const paint=(el,t)=>{el.innerHTML=esc(t).replace(/\*\*(.+?)\*\*/g,"<strong>$1</strong>").replace(/\n/g,"<br>")};
function add(kind,text){
 const log=$("clLog"),d=document.createElement("div");
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
  body:JSON.stringify({model:$("clModel").value,max_tokens:max||1500,system,messages})
 });
 const d=await r.json().catch(()=>({}));
 if(!r.ok)throw Error(d?.error?.message||"Error de la API ("+r.status+").");
 return (d.content||[]).filter(b=>b.type==="text").map(b=>b.text).join("\n").trim();
}

function ctx(){
 let v=[];try{v=JSON.parse(localStorage.getItem("radar_videos")||"[]")}catch{}
 if(!v.length)return "El radar todavía no tiene videos. Pedile al usuario que haga una búsqueda en la pestaña Radar.";
 const rows=v.map(x=>{const h=Math.max(1,(Date.now()-x.published)/36e5);return {...x,h,s:Math.round(x.views/h)}})
  .sort((a,b)=>b.s-a.s).slice(0,15);
 return "Tema: "+($("topic")?.value||"(sin tema)")+" · Región: "+($("region")?.value||"?")+"\nTop por velocidad (vistas/h):\n"+
  rows.map((x,i)=>(i+1)+'. "'+x.title+'" — '+x.channel+" — "+x.views+" vistas, "+Math.round(x.h)+" h de antigüedad, "+
   x.s+" vistas/h, "+Math.round(x.duration/60)+" min"+(x.growth!=null?", cambio medido "+x.growth+" vistas/h":"")).join("\n");
}

async function send(t){
 t=(t??$("clAsk").value).trim();if(!t)return;
 if(!localStorage.getItem(KEY)){st("Primero conectá tu clave de Anthropic.",1);return}
 $("clAsk").value="";add("me",t);hist.push({role:"user",content:t});
 const out=add("ai","Claude está analizando…");lock(true);
 try{
  const m=hist.slice(-10);while(m[0]?.role==="assistant")m.shift();
  const r=await ask(m,SYS+"\n\nDATOS DEL RADAR:\n"+ctx())||"(sin respuesta)";
  hist.push({role:"assistant",content:r});paint(out,r);
 }catch(e){hist.pop();paint(out,"No se pudo completar: "+e.message);st(e.message,1)}
 finally{lock(false);$("clLog").scrollTop=$("clLog").scrollHeight}
}

function mount(){
 const nav=document.querySelector("nav"),main=document.querySelector("main");
 if(!nav||!main||$("claude"))return;
 const st0=document.createElement("style");st0.id="cockpit-css";st0.textContent=CSS;document.head.appendChild(st0);
 document.querySelector('meta[name="theme-color"]')?.setAttribute("content","#02050b");

 main.insertAdjacentHTML("beforeend",UI_HTML.replace("__QUICK__",QUICK.map((q,i)=>'<button class="btn" data-q="'+i+'">'+q[0]+"</button>").join("")));
 nav.insertAdjacentHTML("beforeend",'<button data-view="claude">🛰️<br>Claude</button>');
 nav.addEventListener("click",e=>{
  const b=e.target.closest("button[data-view]");if(!b)return;
  document.querySelectorAll("main>.view").forEach(s=>s.classList.toggle("on",s.id===b.dataset.view));
  nav.querySelectorAll("button").forEach(x=>x.classList.toggle("on",x===b));
  scrollTo(0,0);
 });

 const sm=document.querySelector("header small");
 if(sm){const tick=()=>{sm.textContent="● EN LÍNEA · V6 · "+new Date().toLocaleTimeString("es-AR",{hour12:false})};tick();setInterval(tick,1000)}

 $("clModel").value=localStorage.getItem(MOD)||"claude-sonnet-5-5";
 $("clModel").onchange=()=>localStorage.setItem(MOD,$("clModel").value);
 if(localStorage.getItem(KEY))st("Enlace activo · "+$("clModel").selectedOptions[0].text,0,1);

 $("clSave").onclick=async()=>{
  const k=$("clKey").value.trim()||localStorage.getItem(KEY)||"";
  if(!k.startsWith("sk-ant-")){st("La clave de Anthropic empieza con sk-ant-. Revisala y probá de nuevo.",1);return}
  localStorage.setItem(KEY,k);localStorage.setItem(MOD,$("clModel").value);$("clKey").value="";
  st("Verificando enlace…");lock(true);
  try{await ask([{role:"user",content:"Respondé solo: OK"}],"Respondé exactamente lo pedido.",16);
   st("Enlace establecido · "+$("clModel").selectedOptions[0].text,0,1)}
  catch(e){localStorage.removeItem(KEY);st("No se pudo conectar: "+e.message,1)}
  finally{lock(false)}
 };
 $("clOff").onclick=()=>{localStorage.removeItem(KEY);hist.length=0;$("clLog").innerHTML="";st("Desconectado. La clave se borró de este dispositivo.")};
 $("clClear").onclick=()=>{hist.length=0;$("clLog").innerHTML=""};
 $("clSend").onclick=()=>send();
 $("clAsk").addEventListener("keydown",e=>{if(e.key==="Enter"){e.preventDefault();send()}});
 $("claude").addEventListener("click",e=>{const q=e.target.closest("[data-q]");if(q)send(QUICK[q.dataset.q][1])});
}


function plus(){
 const ls=k=>{try{return JSON.parse(localStorage.getItem(k)||"[]")}catch{return[]}};
 const go=v=>document.querySelector('nav [data-view="'+v+'"]')?.click();
 const thr=()=>+($("threshold")?.value||1000);

 // Arranque de sistemas (una vez por sesión)
 let seen=null;try{seen=sessionStorage.getItem("boot")}catch{}
 if(!seen&&!matchMedia("(prefers-reduced-motion:reduce)").matches){
  try{sessionStorage.setItem("boot","1")}catch{}
  const b=document.createElement("div");b.id="boot";
  b.innerHTML="<div>▸ NÚCLEO ONLINE<br>▸ SENSORES YOUTUBE · OK<br>▸ ENLACE CLAUDE · "+(localStorage.getItem(KEY)?"ACTIVO":"EN ESPERA")+"</div><i></i>";
  document.body.appendChild(b);setTimeout(()=>b.classList.add("off"),1700);setTimeout(()=>b.remove(),2300);
 }

 // Videos en alerta + botón "Claude" en cada tarjeta
 const decorate=()=>document.querySelectorAll(".video").forEach(a=>{
  a.classList.toggle("hot",/DESPEGANDO/.test(a.querySelector(".speed")?.textContent||""));
  const act=a.querySelector(".actions");
  if(act&&!act.querySelector("[data-ai]")){
   const id=act.querySelector("[data-fav]")?.dataset.fav;
   if(id)act.insertAdjacentHTML("beforeend",'<button data-ai="'+esc(id)+'">🛰️ Claude</button>');
  }
 });
 ["results","favResults"].forEach(i=>{const e=$(i);if(e)new MutationObserver(decorate).observe(e,{childList:true})});
 decorate();
 document.addEventListener("click",e=>{
  const b=e.target.closest("[data-ai]");if(!b)return;
  const v=ls("radar_videos").concat(ls("radar_favs")).find(x=>x.id===b.dataset.ai);if(!v)return;
  const h=Math.max(1,(Date.now()-v.published)/36e5);
  go("claude");
  send('Analizá este video: "'+v.title+'" de '+v.channel+", "+v.views+" vistas en "+Math.round(h)+" h ("+Math.round(v.views/h)+
   " vistas/h), "+Math.round(v.duration/60)+" min. ¿Por qué crece así? ¿Qué puedo aprender de su enfoque (sin copiarlo) y cómo haría uno mejor?");
 });

 // Alertas cuando un video despega
 function toast(m){$("toast")?.remove();const t=document.createElement("div");t.id="toast";t.textContent=m;t.onclick=()=>t.remove();
  document.body.appendChild(t);setTimeout(()=>t.remove(),7000)}
 function checkHot(){
  const old=ls("radar_alerted");
  const fresh=ls("radar_videos").filter(v=>{const h=Math.max(1,(Date.now()-v.published)/36e5);return h<=48&&v.views/h>=thr()&&!old.includes(v.id)});
  if(!fresh.length)return;
  localStorage.setItem("radar_alerted",JSON.stringify(old.concat(fresh.map(v=>v.id)).slice(-300)));
  const msg="🔥 "+fresh.length+" video(s) despegando: "+fresh.slice(0,2).map(v=>v.title.slice(0,40)).join(" · ");
  toast(msg);navigator.vibrate?.([120,60,120]);
  if(window.Notification&&Notification.permission==="granted"){
   const plain=()=>new Notification("Radar YouTube",{body:msg});
   navigator.serviceWorker?.ready.then(r=>r.showNotification("Radar YouTube",{body:msg,icon:"./icon-192.png"})).catch(plain)||plain();
  }
 }
 const rs=$("radarStatus");
 if(rs)new MutationObserver(()=>{if(rs.textContent.startsWith("Búsqueda completada"))checkHot()}).observe(rs,{childList:true});

 // Vigilancia automática + panel de alertas en Ajustes
 $("settings").querySelector("h2").insertAdjacentHTML("afterend",
  '<div class="card"><h3>🚨 Alertas y vigilancia</h3>'+
  '<label><input id="wOn" type="checkbox"> Vigilar el radar automáticamente</label>'+
  '<label for="wMin">Repetir la búsqueda cada</label>'+
  '<select id="wMin"><option value="30">30 minutos</option><option value="60">60 minutos</option><option value="120">2 horas</option></select>'+
  '<button class="btn" id="wNotif">Activar notificaciones</button>'+
  '<div class="notice">Cada búsqueda gasta unas 101 unidades de tu cuota diaria de YouTube (10.000 por defecto). La vigilancia y las alertas funcionan con la app abierta.</div></div>');
 let wt=null;
 const watch=()=>{
  clearInterval(wt);const on=$("wOn").checked;localStorage.setItem("radar_watch",on?$("wMin").value:"");
  if(on)wt=setInterval(()=>{if(!$("search").disabled){window.__wk=1;$("search").click()}},+$("wMin").value*6e4);
 };
 const w=localStorage.getItem("radar_watch");if(w){$("wOn").checked=true;$("wMin").value=w;watch()}
 $("wOn").onchange=$("wMin").onchange=watch;
 $("wNotif").onclick=async()=>{
  if(!window.Notification){toast("Este navegador no admite notificaciones.");return}
  const r=await Notification.requestPermission();toast(r==="granted"?"Notificaciones activadas.":"Notificaciones bloqueadas: habilitalas en los ajustes del sitio.");
 };

 // Análisis del canal con Claude
 $("clClear").insertAdjacentHTML("afterend",'<button class="btn sec" id="clChannel">📈 Analizar mi canal</button>');
 $("clChannel").onclick=async()=>{
  const an=$("analyticsStatus")?.textContent||"";
  if(typeof state==="undefined"||!state.token){st("Primero conectá tu cuenta de Google en Ajustes.",1);return}
  if(!/Últimos 28 días/.test(an)){st("Primero tocá 'Consultar YouTube Analytics' en Ajustes.",1);return}
  let ch="";
  try{
   const r=await fetch("https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&mine=true",{headers:{Authorization:"Bearer "+state.token}});
   const c=(await r.json()).items?.[0];if(c)ch=c.snippet.title+": "+JSON.stringify(c.statistics);
  }catch{}
  send("Analizá mi canal y decime qué mejorar. Datos del canal: "+(ch||"no disponibles")+". Métricas de Analytics (últimos 28 días): "+an+
   " Cruzalo con el nicho del radar y dame 3 acciones concretas.");
 };

 // "Borrar datos locales" también borra la clave y los datos de Claude
 const ca=$("clearAll");
 if(ca){const n=ca.cloneNode(true);ca.replaceWith(n);n.onclick=()=>{
  if(!confirm("¿Borrar todos los datos locales de Radar en este dispositivo?"))return;
  ["radar_api_key","radar_client_id","radar_videos","radar_favs","radar_snaps","radar_quota",KEY,MOD,"radar_alerted","radar_watch"].forEach(k=>localStorage.removeItem(k));
  location.reload();
 }}

  // V5: filtros plegables, ajustes en acordeón y chat de Claude primero
  $("topic").after($("search"));
  const fd=document.createElement("details"),fs=document.createElement("summary");
  fd.className="flt";fs.textContent="⚙ Filtros y opciones";fd.appendChild(fs);$("search").after(fd);
  while(fd.nextElementSibling)fd.appendChild(fd.nextElementSibling);

  const tag=()=>document.querySelectorAll(".video .meta").forEach(m=>{if(m.textContent.trim().startsWith("📊"))m.classList.add("hint")});
  ["results","favResults"].forEach(i=>new MutationObserver(tag).observe($(i),{childList:true}));tag();

  document.querySelectorAll("#settings .card").forEach(card=>{
   const h=card.querySelector("h3");if(!h)return;
   const d=document.createElement("details"),sm=document.createElement("summary");
   sm.append(...h.childNodes);h.remove();d.appendChild(sm);
   while(card.firstChild)d.appendChild(card.firstChild);card.appendChild(d);
  });
  $("apiKey").closest("details").open=!localStorage.getItem("radar_api_key");

  const c1=$("claude").querySelector(".card"),cd=document.createElement("details"),cs=document.createElement("summary");
  cs.textContent="🔌 Conexión con Claude";cd.appendChild(cs);cd.open=!localStorage.getItem(KEY);
  while(c1.firstChild)cd.appendChild(c1.firstChild);c1.appendChild(cd);
  new MutationObserver(()=>{if($("clStatus").classList.contains("ok"))cd.open=false}).observe($("clStatus"),{attributes:true,attributeFilter:["class"]});
  $("claude").querySelector(".cl-quick").append($("clChannel"),$("clClear"));
  const row=document.createElement("div");row.className="cl-row";$("clAsk").before(row);row.append($("clAsk"),$("clSend"));
  $("clSend").textContent="➤";

  // V6: íconos, esqueletos, vibración, calor por video y lectura automática de Claude
  const ICON={radar:'<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4.5"/><path d="M12 12l6-6"/>',
   favorites:'<path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/>',
   settings:'<path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/>',
   claude:'<path d="M5 5h14a2 2 0 012 2v8a2 2 0 01-2 2h-8l-4 3v-3H5a2 2 0 01-2-2V7a2 2 0 012-2z"/>'};
  document.querySelectorAll("nav button").forEach(b=>{
   const l=b.lastChild.textContent.trim();
   b.innerHTML='<svg viewBox="0 0 24 24">'+(ICON[b.dataset.view]||"")+"</svg><span>"+l+"</span>";
  });
  document.addEventListener("click",e=>{if(e.target.closest("button"))navigator.vibrate?.(8)});
  $("hot").previousElementSibling.textContent="Videos despegando ahora";

  const short=n=>n>=1e6?(n/1e6).toFixed(1)+"M":n>=1e4?Math.round(n/1e3)+"K":n>=1e3?(n/1e3).toFixed(1)+"K":String(n);
  const heat=()=>document.querySelectorAll(".video .speed:not([data-p])").forEach(sp=>{
   sp.dataset.p=1;
   const n=parseInt(((sp.textContent.match(/([\d.]+)\s*vistas\/h/)||[])[1]||"0").replace(/\./g,""),10)||0;
   const r=n/thr(),hot=/DESPEGANDO/.test(sp.textContent);
   sp.closest(".video").dataset.heat=r>=3?4:r>=1?3:r>=.5?2:1;
   sp.innerHTML="<b>"+short(n)+"</b><i>vistas/h</i>"+(hot?'<span class="bd">🔥 DESPEGANDO</span>':"")+'<em style="width:'+Math.min(100,Math.round(r*50))+'%"></em>';
  });
  ["results","favResults"].forEach(i=>new MutationObserver(heat).observe($(i),{childList:true}));heat();

  const card=document.createElement("div");card.id="aiSum";card.className="card";
  card.innerHTML='<div class="sum-h"><b>🛰️ Lectura de Claude</b><button id="sumBtn" aria-label="Actualizar lectura">↻</button></div><div id="sumBody" class="sum-b">Hacé una búsqueda y Claude te resume qué está pasando.</div>';
  $("radarStatus").after(card);
  async function summarize(){
   const b=$("sumBody");
   if(!localStorage.getItem(KEY)){b.textContent="Conectá Claude en su pestaña para ver acá un resumen de cada búsqueda.";return}
   if(!ls("radar_videos").length){b.textContent="Todavía no hay videos para leer.";return}
   b.textContent="Claude está leyendo el ranking…";
   try{paint(b,await ask([{role:"user",content:"Resumí el ranking actual en exactamente 3 líneas cortas, una por línea, sin títulos ni viñetas: qué tema o formato domina, qué tienen en común los que despegan y una oportunidad concreta para mí."}],SYS+"\n\nDATOS DEL RADAR:\n"+ctx(),350))}
   catch(e){b.textContent="No se pudo generar la lectura: "+e.message}
  }
  $("sumBtn").onclick=summarize;
  const SK='<div class="sk"></div>'.repeat(4);
  new MutationObserver(()=>{
   const t=$("radarStatus").textContent;
   if(t.startsWith("Buscando"))$("results").innerHTML=SK;
   else if($("results").querySelector(".sk")&&!t.startsWith("Búsqueda completada")&&typeof render==="function")render();
   if(t.startsWith("Búsqueda completada")){if(window.__wk)window.__wk=0;else summarize()}
  }).observe($("radarStatus"),{childList:true});
}

const boot=()=>{mount();plus()};
if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",boot);else boot();
})();
