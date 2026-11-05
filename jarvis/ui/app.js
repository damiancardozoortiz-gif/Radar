/* JARVIS · interfaz. JavaScript sin frameworks. */
"use strict";

// ---- Turnos de palabra: ajusta aquí ----
const SILENCE_MS = 900;        // silencio que cierra tu turno
const LEVEL_THRESHOLD = 0.03;  // volumen (RMS 0..1) a partir del cual cuenta como voz
const MIN_SPEECH_MS = 500;     // menos de medio segundo de voz = ruido, no se manda
const MAX_TURN_MS = 30000;     // un turno no dura más de esto
const VAD_INTERVAL_MS = 50;    // bucle del volumen: setInterval (rAF se para en segundo plano)

const $ = s => document.querySelector(s);
const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const EXAMPLES = [
  "¿Qué tengo hoy?", "¿Cómo va la semana?", "Planéame el día",
  "¿Qué pólizas de Logística Delta tenemos?", "¿Quién me ha escrito?",
  "¿Cuánto nos deja un seguro de vehículo?", "Recuerda que Marta vuelve el lunes",
  "Busca en internet cuánto sube el IPC y qué me supone",
];

const App = { state: "idle", status: null, conv: (crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)), busy: false };

// ---------------- avisos ----------------
let toastTimer = null;
function toast(msg, bad = false, ms = 6000) {
  const t = $("#toast");
  t.textContent = msg; t.classList.toggle("bad", bad); t.classList.remove("hidden");
  clearTimeout(toastTimer); if (ms) toastTimer = setTimeout(() => t.classList.add("hidden"), ms);
}
function chip(id, text, cls) { const el = $(id); el.textContent = text; el.className = "chip st " + (cls || ""); }

// ---------------- markdown mínimo con [[enlaces]] pulsables ----------------
function renderMarkdown(md, links) {
  const linkIds = new Map((links || []).map(l => [l.name.toLowerCase(), l.id]));
  const inline = s => esc(s)
    .replace(/\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]+))?\]\]/g, (_, name, alias) => {
      const id = linkIds.get(name.trim().toLowerCase());
      const label = esc(alias || name);
      return id ? `<a class="wl" data-id="${esc(id)}">${label}</a>` : `<a class="wl missing" title="Sin nota">${label}</a>`;
    })
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>");
  const out = []; let list = null;
  for (const raw of md.replace(/\r/g, "").split("\n")) {
    const line = raw.trimEnd();
    let m;
    if ((m = line.match(/^\s*[-*]\s+(.*)/))) { if (list !== "ul") { closeList(); out.push("<ul>"); list = "ul"; } out.push(`<li>${inline(m[1])}</li>`); continue; }
    if ((m = line.match(/^\s*\d+\.\s+(.*)/))) { if (list !== "ol") { closeList(); out.push("<ol>"); list = "ol"; } out.push(`<li>${inline(m[1])}</li>`); continue; }
    closeList();
    if ((m = line.match(/^(#{1,6})\s+(.*)/))) { if (m[1].length === 1) continue; out.push(`<h3>${inline(m[2])}</h3>`); continue; }
    if (line.trim()) out.push(`<p>${inline(line)}</p>`);
  }
  closeList();
  return out.join("");
  function closeList() { if (list) { out.push(`</${list}>`); list = null; } }
}

// ---------------- notas ----------------
async function openNote(id, center = true) {
  try {
    const r = await fetch("/api/note?id=" + encodeURIComponent(id));
    if (!r.ok) throw new Error("no encontrada");
    const n = await r.json();
    $("#note-type").textContent = n.type + " · " + n.degree + " enlaces";
    $("#note-type").style.color = Graph.colorFor(n.type);
    $("#note-title").textContent = n.title;
    $("#note-body").innerHTML = renderMarkdown(n.body, n.links);
    $("#note-backlinks").innerHTML = n.backlinks.length
      ? "Enlazan aquí: " + n.backlinks.slice(0, 30).map(b => `<a class="wl" data-id="${esc(b.id)}">${esc(b.title)}</a>`).join("")
        + (n.backlinks.length > 30 ? ` y ${n.backlinks.length - 30} más` : "")
      : "";
    $("#note").classList.remove("hidden");
    $("#note-body").scrollTop = 0;
    Graph.select(id, center);
  } catch (e) { toast("No pude abrir la nota: " + e.message, true); }
}
document.addEventListener("click", e => {
  const a = e.target.closest("a.wl[data-id]");
  if (a) { e.preventDefault(); openNote(a.dataset.id); }
});
$("#note-close").onclick = () => { $("#note").classList.add("hidden"); Graph.select(null); };

// ---------------- filtros y más conectadas ----------------
function buildFilters(byType) {
  const box = $("#filters"); box.innerHTML = "";
  const hidden = new Set();
  Object.entries(byType).sort((a, b) => b[1] - a[1]).forEach(([t, n]) => {
    const b = document.createElement("button");
    b.innerHTML = `<span class="dot" style="background:${Graph.colorFor(t)}"></span>${esc(t)} <span class="n">${n}</span>`;
    b.onclick = () => { hidden.has(t) ? hidden.delete(t) : hidden.add(t); b.classList.toggle("off", hidden.has(t)); Graph.setHidden(new Set(hidden)); };
    box.appendChild(b);
  });
}
async function buildTop() {
  const top = await (await fetch("/api/top")).json();
  $("#top-list").innerHTML = top.map(n =>
    `<li data-id="${esc(n.id)}"><span class="dot" style="background:${Graph.colorFor(n.type)}"></span>${esc(n.title)}<span class="n">${n.degree}</span></li>`).join("");
  $("#top-list").onclick = e => { const li = e.target.closest("li"); if (li) openNote(li.dataset.id); };
}

// ---------------- reactor ----------------
const REACTOR_LABEL = { idle: "en espera", listening: "escuchando", thinking: "pensando", speaking: "hablando" };
function setState(s) {
  App.state = s;
  $("#reactor-state").textContent = REACTOR_LABEL[s] || s;
}
function drawReactor() {
  const c = $("#reactor"), ctx = c.getContext("2d"), dpr = window.devicePixelRatio || 1;
  if (c.width !== 200 * dpr) { c.width = 200 * dpr; c.height = 200 * dpr; }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, 200, 200);
  const t = performance.now() / 1000, s = App.state;
  const speed = { idle: 0.15, listening: 0.6, thinking: 1.6, speaking: 0.9 }[s] || 0.15;
  const active = s !== "idle";
  const lvl = App.level || 0;
  const rings = [[88, 72, 4, 1], [74, 48, 7, -1.4], [60, 36, 3, 0.8], [47, 24, 5, -2]];
  ctx.translate(100, 100);
  rings.forEach(([r, n, len, dir], i) => {
    ctx.save(); ctx.rotate(t * speed * dir * 0.6 + i);
    ctx.strokeStyle = active ? `rgba(92,200,214,${0.35 + 0.12 * i})` : `rgba(140,152,165,${0.18 + 0.06 * i})`;
    ctx.lineWidth = i % 2 ? 1.5 : 1;
    for (let k = 0; k < n; k++) {
      if (i === 1 && k % 6 === 0) continue;
      const a = (k / n) * Math.PI * 2;
      const extra = s === "listening" && i === 0 ? lvl * 14 * (0.5 + 0.5 * Math.sin(k * 1.7 + t * 8)) : 0;
      ctx.beginPath();
      ctx.moveTo(Math.cos(a) * r, Math.sin(a) * r);
      ctx.lineTo(Math.cos(a) * (r - len - extra), Math.sin(a) * (r - len - extra));
      ctx.stroke();
    }
    ctx.restore();
  });
  const pulse = s === "speaking" ? 0.5 + 0.5 * Math.sin(t * 9) : s === "thinking" ? 0.5 + 0.5 * Math.sin(t * 4) : 0.3;
  ctx.strokeStyle = active ? `rgba(92,200,214,${0.25 + 0.35 * pulse})` : "rgba(140,152,165,0.25)";
  ctx.beginPath(); ctx.arc(0, 0, 32, 0, Math.PI * 2); ctx.stroke();
  ctx.fillStyle = active ? "#5cc8d6" : "#9aa4ae";
  ctx.font = "600 10px ui-monospace, Menlo, Consolas, monospace";
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillText("J.A.R.V.I.S.", 0, 0);
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  requestAnimationFrame(drawReactor);
}

// ---------------- conversación ----------------
const TOOL_LABEL = { conversacion: "conversación", buscar_en_mi_cerebro: "mis archivos", investigar_en_la_web: "internet",
  leer_correo: "correo", parte_del_dia: "parte del día", recordar: "memoria", planear_el_dia: "plan" };

function renderCard(card) {
  if (!card) return "";
  let h = `<h3>${esc(card.title || "")}</h3>`;
  for (const n of card.notes || []) h += `<div class="warnline">${esc(n)}</div>`;
  if (card.rows && card.rows.length) {
    h += "<table>" + card.rows.map(r => {
      const main = r.id ? `<a class="wl" data-id="${esc(r.id)}">${esc(r.main)}</a>` : esc(r.main);
      return `<tr><td>${main}${r.sub ? `<div class="dim">${esc(r.sub)}</div>` : ""}${r.src ? `<div class="src">${esc(r.src)}</div>` : ""}</td></tr>`;
    }).join("") + "</table>";
  }
  return h;
}

function showAnswer(o, question) {
  const secs = (o.ms / 1000).toFixed(1).replace(".", ",");
  const who = o.brain === "claude" ? "claude" : "RESPALDO · sin cerebro";
  $("#answer-meta").textContent = `${TOOL_LABEL[o.tool] || o.tool} · ${who} · ${secs} s`;
  $("#answer-meta").style.color = o.brain === "claude" ? (o.ms > 6000 && o.tool !== "investigar_en_la_web" ? "var(--warn)" : "") : "var(--bad)";
  $("#answer-say").textContent = o.say || "";
  let html = "";
  if (question) html += `<div class="src">Tú: ${esc(question)}</div>`;
  if (o.saved) html += `<div class="warnline">Guardado en ${esc(o.saved.file)}</div>`;
  if (o.detail) html += `<div class="note-body">${renderMarkdown(o.detail, [])}</div>`;
  html += renderCard(o.card);
  $("#answer-card").innerHTML = html;
  $("#answer").classList.remove("hidden");
  if (o.warning) toast(o.warning, o.brain !== "claude", 9000);
}
$("#answer-close").onclick = () => $("#answer").classList.add("hidden");

async function ask(text, tool) {
  if (App.busy) return;
  App.busy = true; setState("thinking");
  try {
    if (text && !tool) {
      const r = await (await fetch("/api/route?q=" + encodeURIComponent(text))).json();
      if (r.tool === "investigar_en_la_web") toast("Buscando en internet: tarda entre 15 y 40 segundos.", false, 40000);
    }
    const res = await fetch("/api/ask", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text: text || "", tool: tool || null, conv: App.conv }) });
    const o = await res.json();
    if (!res.ok) throw new Error(o.error || res.status);
    if (o.tool === "investigar_en_la_web" && !o.warning) $("#toast").classList.add("hidden");
    showAnswer(o, text);
    if (o.saved) refreshStatus();
    App.last = o;
    App.busy = false;
    if (window.onAnswer) await window.onAnswer(o); // voz
  } catch (e) {
    toast("Error del servidor: " + e.message, true);
  } finally {
    App.busy = false;
    if (App.state === "thinking") setState("idle");
  }
}

$("#ask").addEventListener("submit", e => {
  e.preventDefault();
  const t = $("#q").value.trim(); if (!t) return;
  $("#q").value = ""; ask(t);
});
$("#btn-brief").onclick = () => ask(null, "parte_del_dia");
$("#btn-plan").onclick = () => ask(null, "planear_el_dia");
$("#btn-memory").onclick = async () => {
  const o = await (await fetch("/api/memory")).json();
  $("#answer-meta").textContent = "memoria · memory/"; $("#answer-meta").style.color = "";
  $("#answer-say").textContent = ""; $("#answer-card").innerHTML = renderCard(o.card);
  $("#answer").classList.remove("hidden");
};

async function refreshStatus() {
  try {
    const st = await (await fetch("/api/status")).json();
    const b = st.brain || {};
    const map = { listo: ["cerebro: listo", "ok"], calentando: ["cerebro: calentando…", "warn"], arrancando: ["cerebro: arrancando…", "warn"],
                  respaldo: ["cerebro: RESPALDO", "bad"], "no disponible": ["cerebro: NO DISPONIBLE", "bad"] };
    const [txt, cls] = map[b.brain] || ["cerebro: " + b.brain, ""];
    chip("#st-brain", txt + (b.last_ms ? ` · ${(b.last_ms / 1000).toFixed(1).replace(".", ",")} s` : ""), cls);
    $("#st-brain").title = b.detail || "";
    if (b.brain === "no disponible" && !App.warnedBrain) { App.warnedBrain = true; toast("El cerebro no está disponible: " + (b.detail || "") + ". Funciono en modo respaldo.", true, 0); }
    if (b.brain === "listo" && App.warnedBrain) { App.warnedBrain = false; $("#toast").classList.add("hidden"); }
  } catch (e) { chip("#st-brain", "servidor: sin conexión", "bad"); }
}
setInterval(refreshStatus, 5000);

// ---------------- voz: escuchar ----------------
const Mic = { stream: null, ctx: null, analyser: null, buf: null, rec: null, chunks: [], timer: null,
  conversation: false, deaf: true, t0: 0, voiceMs: 0, lastVoice: 0, heard: false, mime: "" };

function buildBars() { $("#bars").innerHTML = "<i></i>".repeat(14); }
buildBars();

async function micInit() {
  if (Mic.stream) return true;
  if (!navigator.mediaDevices || !window.MediaRecorder) {
    chip("#st-mic", "micrófono: navegador sin soporte", "bad");
    toast("Este navegador no puede grabar audio (MediaRecorder). Usa Chrome, Edge, Firefox o Safari recientes.", true, 0);
    return false;
  }
  try {
    Mic.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
  } catch (e) {
    const blocked = e.name === "NotAllowedError" || e.name === "SecurityError";
    chip("#st-mic", blocked ? "micrófono: BLOQUEADO" : "micrófono: no encontrado", "bad");
    $("#btn-mic").classList.add("blocked");
    toast(blocked ? "El micrófono está bloqueado. Permítelo en el candado de la barra de direcciones y vuelve a pulsar el micrófono."
                  : "No encuentro ningún micrófono (" + e.name + ").", true, 0);
    return false;
  }
  Mic.ctx = new (window.AudioContext || window.webkitAudioContext)();
  const src = Mic.ctx.createMediaStreamSource(Mic.stream);
  Mic.analyser = Mic.ctx.createAnalyser(); Mic.analyser.fftSize = 1024;
  Mic.buf = new Float32Array(Mic.analyser.fftSize);
  src.connect(Mic.analyser);
  Mic.mime = ["audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus", "audio/mp4"].find(m => MediaRecorder.isTypeSupported(m)) || "";
  $("#btn-mic").classList.remove("blocked");
  Mic.stream.getAudioTracks()[0].addEventListener("ended", () => {
    chip("#st-mic", "micrófono: desconectado", "bad"); toast("El micrófono se ha desconectado.", true, 0);
    Mic.stream = null; stopConversation();
  });
  return true;
}

function level() {
  Mic.analyser.getFloatTimeDomainData(Mic.buf);
  let sum = 0; for (let i = 0; i < Mic.buf.length; i++) sum += Mic.buf[i] * Mic.buf[i];
  return Math.sqrt(sum / Mic.buf.length);
}

function startListening() {
  if (!Mic.stream || App.state === "speaking" || App.busy) return;
  if (Mic.ctx.state === "suspended") Mic.ctx.resume();
  Mic.chunks = []; Mic.heard = false; Mic.voiceMs = 0; Mic.t0 = performance.now(); Mic.lastVoice = 0;
  Mic.rec = new MediaRecorder(Mic.stream, Mic.mime ? { mimeType: Mic.mime } : undefined);
  Mic.rec.ondataavailable = e => { if (e.data.size) Mic.chunks.push(e.data); };
  Mic.rec.onstop = onRecorded;
  Mic.rec.start(250);
  Mic.deaf = false;
  setState("listening");
  chip("#st-mic", "micrófono: escuchando", "ok");
  $("#btn-mic").classList.add("on");
  $("#listen").classList.remove("hidden");
  clearInterval(Mic.timer);
  Mic.timer = setInterval(vadTick, VAD_INTERVAL_MS);
}

function vadTick() {
  if (Mic.deaf || !Mic.rec || Mic.rec.state !== "recording") return;
  const now = performance.now(), lv = level();
  App.level = Math.min(1, lv * 8);
  const bars = $("#bars").children;
  for (let i = 0; i < bars.length; i++) bars[i].style.height = Math.max(2, Math.min(24, lv * 260 * (0.5 + Math.random()))) + "px";
  $("#listen-time").textContent = ((now - Mic.t0) / 1000).toFixed(1).replace(".", ",") + " s";
  if (lv > LEVEL_THRESHOLD) {
    if (Mic.lastVoice) Mic.voiceMs += now - Mic.lastVoice > VAD_INTERVAL_MS * 3 ? VAD_INTERVAL_MS : now - Mic.lastVoice;
    Mic.heard = true; Mic.lastVoice = now;
  }
  const silentFor = Mic.lastVoice ? now - Mic.lastVoice : 0;
  if ((Mic.heard && silentFor > SILENCE_MS) || now - Mic.t0 > MAX_TURN_MS) stopRecording();
  // En modo conversación, si no has dicho nada en 30 s, se reinicia el turno sin mandar nada.
}

function stopRecording() {
  clearInterval(Mic.timer); Mic.deaf = true; App.level = 0;
  if (Mic.rec && Mic.rec.state === "recording") Mic.rec.stop();
}

async function onRecorded() {
  const blob = new Blob(Mic.chunks, { type: (Mic.rec && Mic.rec.mimeType) || "audio/webm" });
  const speech = Mic.voiceMs, heard = Mic.heard;
  Mic.rec = null;
  $("#listen").classList.add("hidden");
  if (!Mic.conversation) { finishTurn(); return; }
  if (!heard || speech < MIN_SPEECH_MS) { startListening(); return; } // ruido: no se manda
  setState("thinking");
  chip("#st-mic", "micrófono: transcribiendo…", "");
  let text = "";
  try {
    const r = await fetch("/api/listen", { method: "POST", headers: { "Content-Type": blob.type.split(";")[0] }, body: blob });
    const o = await r.json();
    if (r.ok) text = (o.text || "").trim();
    else markVoice(o.error);
  } catch (e) { /* sin error en pantalla: seguimos escuchando */ }
  if (!text || !Mic.conversation) { setState("idle"); if (Mic.conversation) startListening(); return; }
  $("#q").value = ""; $("#q").placeholder = "«" + text + "»";
  setState("idle");
  await ask(text);
}

function finishTurn() {
  $("#listen").classList.add("hidden");
  if (App.state === "listening") setState("idle");
}

async function startConversation() {
  if (!(await micInit())) return;
  Mic.conversation = true;
  startListening();
}
function stopConversation() {
  Mic.conversation = false;
  stopRecording();
  $("#btn-mic").classList.remove("on");
  if (Mic.stream) chip("#st-mic", "micrófono: listo", "");
  finishTurn();
}

// ---------------- voz: hablar ----------------
const Voice = { audio: null, muted: false, ok: true };
function markVoice(err) {
  Voice.ok = false;
  chip("#st-voice", "voz: NO DISPONIBLE", "bad"); $("#st-voice").title = err || "";
  toast("La voz no está disponible: " + (err || "error") + ". Sigo por escrito.", true, 9000);
}

function stopSpeaking() {
  if (Voice.audio) { Voice.audio.onended = null; Voice.audio.pause(); Voice.audio = null; }
  if (App.state === "speaking") setState("idle");
}

async function speak(text) {
  if (!text || Voice.muted) return false;
  if (App.status && App.status.voice && !App.status.voice.configured) return false;
  let r;
  try { r = await fetch("/api/speak", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text }) }); }
  catch (e) { markVoice("sin conexión con el servidor"); return false; }
  if (!r.ok) { const o = await r.json().catch(() => ({})); markVoice(o.error); return false; }
  if (!Voice.ok) { Voice.ok = true; chip("#st-voice", "voz: lista", "ok"); }
  const url = URL.createObjectURL(await r.blob());
  stopRecording(); Mic.deaf = true;                // sordo mientras habla
  if (Mic.stream) chip("#st-mic", "micrófono: sordo mientras hablo", "warn");
  return new Promise(resolve => {
    const a = Voice.audio = new Audio(url);
    a.onended = a.onerror = () => { URL.revokeObjectURL(url); Voice.audio = null; resolve(true); };
    setState("speaking");
    a.play().catch(() => { Voice.audio = null; toast("El navegador no dejó reproducir el audio. Pulsa en la página y repite.", true); resolve(false); });
  });
}

window.onAnswer = async o => {
  await speak(o.say);
  setState("idle");                                // siempre vuelve a "en espera"
  if (Mic.conversation) startListening();           // modo conversación: vuelve a escuchar solo
};

// ---------------- botones y teclas ----------------
$("#btn-mic").onclick = () => {
  if (App.state === "speaking") { stopSpeaking(); if (!Mic.conversation) startConversation(); else startListening(); return; }
  if (Mic.conversation) stopConversation(); else startConversation();
};
$("#btn-mute").onclick = () => {
  Voice.muted = !Voice.muted;
  $("#btn-mute").classList.toggle("on", Voice.muted);
  $("#btn-mute").textContent = Voice.muted ? "sin voz" : "silencio";
  if (Voice.muted) stopSpeaking();
};
document.addEventListener("keydown", e => {
  if (e.target.tagName === "INPUT" && e.key !== "Escape") return;
  if (e.key === "Escape") { stopSpeaking(); stopConversation(); setState("idle"); }
  else if (e.code === "Space") {
    e.preventDefault();
    if (App.state === "speaking") { stopSpeaking(); if (Mic.conversation) startListening(); else startConversation(); }
    else if (App.state === "idle" && !App.busy) { if (Mic.conversation) startListening(); else startConversation(); }
  }
});

// ---------------- ejemplos que rotan ----------------
let exI = 0;
setInterval(() => { if (document.activeElement !== $("#q")) { exI = (exI + 1) % EXAMPLES.length; $("#q").placeholder = EXAMPLES[exI]; } }, 4000);
$("#q").placeholder = EXAMPLES[0];

// ---------------- arranque ----------------
async function boot() {
  try {
    const st = await (await fetch("/api/status")).json();
    App.status = st;
    $("#mode").textContent = st.mode === "demo" ? "DEMO · hoy " + st.today : "DATOS REALES";
    $("#mode").className = "chip " + (st.mode === "demo" ? "demo" : "");
    $("#counts").textContent = `${st.notes} notas · ${st.edges} enlaces`;
    if (st.skipped.length) toast((st.skipped.length === 1 ? "Se saltó 1 archivo: " : `Se saltaron ${st.skipped.length} archivos: `) + st.skipped.map(s => s[0].split("/").pop() + " (" + s[1] + ")").join(", "), false, 9000);
    const graph = await (await fetch("/api/graph")).json();
    Graph.init($("#graph"), graph);
    Graph.onClick = n => openNote(n.id, false);
    buildFilters(st.by_type);
    buildTop();
  } catch (e) {
    toast("No hay conexión con el servidor de JARVIS. ¿Está arrancado ./jarvis.sh?", true, 0);
  }
  fetch("/api/reset", { method: "POST", body: JSON.stringify({ conv: App.conv }) }).catch(() => {});
  refreshStatus();
  chip("#st-mic", window.MediaRecorder ? "micrófono: pulsa para hablar" : "micrófono: sin soporte", window.MediaRecorder ? "" : "bad");
  const v = (App.status && App.status.voice) || {};
  if (!v.configured) { Voice.ok = false; chip("#st-voice", "voz: sin clave", "bad"); toast("La voz no está configurada: falta ELEVENLABS_API_KEY en .env. Funciono por escrito.", true, 9000); }
  else chip("#st-voice", "voz: lista", "");
  requestAnimationFrame(drawReactor);
}
boot();
