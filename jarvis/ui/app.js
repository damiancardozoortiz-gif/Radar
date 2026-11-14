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
const secs = ms => (ms / 1000).toFixed(1).replace(".", ",") + " s";

const EXAMPLES = [
  "Mira qué notificaciones tengo en el teléfono", "Escríbele a Ana por WhatsApp que llego a las nueve",
  "Busca en internet unos auriculares con cancelación de ruido por menos de 100 euros y compáralos",
  "¿Qué tengo hoy?", "Planéame el día", "¿Quién me ha escrito?", "Recuerda que Marta vuelve el lunes",
];
const HINTS = EXAMPLES.slice(0, 4);

const App = { state: "idle", status: null, conv: (crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)),
  busy: false, task: null, permits: [], seq: 0, canal: "telefono", screens: { telefono: 0, web: 0 }, level: 0 };

// ---------------- avisos ----------------
let toastTimer = null;
function toast(msg, bad = false, ms = 6000) {
  const t = $("#toast");
  t.textContent = msg; t.classList.toggle("bad", bad); t.classList.remove("hidden");
  clearTimeout(toastTimer); if (ms) toastTimer = setTimeout(() => t.classList.add("hidden"), ms);
}
function chip(id, text, cls) { const el = $(id); el.textContent = text; el.className = "chip st " + (cls || ""); }
async function post(path, body) {
  const r = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {}) });
  const o = await r.json().catch(() => ({}));
  return { ok: r.ok, status: r.status, o };
}

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

// ---------------- estado y orbe ----------------
const STATE_LABEL = { idle: "en espera", listening: "escuchando", thinking: "pensando", speaking: "hablando",
  acting: "actuando", waiting: "espera tu sí", paused: "en pausa" };
function orbState() {
  if (App.paused) return "paused";
  if (App.permits.length) return "waiting";
  if (App.state === "speaking" || App.state === "listening") return App.state;
  if (App.state === "thinking") return "thinking";
  if (App.task && App.task.estado === "en curso") return "acting";
  return "idle";
}
function paint() {
  const s = orbState();
  document.body.dataset.state = s;
  Orb.state = s;
  $("#state-label").textContent = STATE_LABEL[s] || s;
  $("#btn-stop").classList.toggle("hidden", !(App.task && App.task.estado === "en curso"));
}
function setState(s) { App.state = s; paint(); }
function said(text) { $("#said").textContent = text || ""; }
setInterval(() => {   // el orbe late con tu voz al escuchar y con la suya al hablar
  if (App.state === "listening") Orb.level = App.level || 0;
  else if (App.state === "speaking") Orb.level = Voice.level();
  else Orb.level = 0;
}, 40);

// ---------------- actividad: lo que hace, paso a paso ----------------
const ICONS = {
  telefono: '<svg viewBox="0 0 16 16"><rect x="4" y="1.5" width="8" height="13" rx="1.8"/><path d="M7 12.4h2"/></svg>',
  web: '<svg viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.2"/><path d="M1.8 8h12.4M8 1.8c2.2 2 2.2 10.400 0 12.4M8 1.8c-2.2 2-2.2 10.4 0 12.4"/></svg>',
  otro: '<svg viewBox="0 0 16 16"><circle cx="8" cy="8" r="2.2"/></svg>',
};
function taskStart(t) {
  App.task = { id: t.id, texto: t.texto, estado: "en curso" };
  $("#steps").innerHTML = "";
  $("#task-text").textContent = t.texto; $("#task-text").classList.remove("hidden");
  $("#activity-empty").classList.add("hidden");
  $("#task-meta").textContent = "en curso"; $("#task-meta").style.color = "";
  $("#answer").classList.add("hidden");
  paint();
}
function stepEvent(e) {
  let li = document.getElementById("step-" + e.n);
  if (!li) {
    li = document.createElement("li"); li.id = "step-" + e.n;
    li.innerHTML = `<span class="ic">${ICONS[e.canal] || ICONS.otro}</span><span class="tx"></span><span class="ms"></span>`;
    $("#steps").appendChild(li);
  }
  li.className = e.estado === "en curso" ? "run" : e.estado === "hecho" ? "done" : "fail";
  li.querySelector(".tx").innerHTML = esc(e.texto) + (e.detalle ? `<span class="sub">${esc(e.detalle)}</span>` : "");
  li.querySelector(".ms").textContent = e.ms != null ? secs(e.ms) : "";
  $("#steps").scrollTop = $("#steps").scrollHeight;
  if (e.estado === "en curso" && !App.permits.length && App.state !== "speaking") said(e.texto + "…");
}
function stepNote(text, cls) {
  const li = document.createElement("li"); li.className = cls || "note"; li.textContent = text;
  $("#steps").appendChild(li); $("#steps").scrollTop = $("#steps").scrollHeight;
}
function taskEnd(e, quiet) {
  if (App.task) App.task.estado = e.estado;
  const label = { terminada: "terminada", detenida: "detenida", fallo: "no se pudo" }[e.estado] || e.estado;
  $("#task-meta").textContent = `${label} · ${e.pasos} pasos · ${secs(e.ms)}`;
  $("#task-meta").style.color = e.estado === "terminada" ? "" : "var(--bad)";
  stepNote(e.decir, "end");
  showAnswer({ tool: "tarea", brain: "claude", ms: e.ms, say: e.decir, detail: e.detalle }, App.task && App.task.texto);
  paint();
  if (!quiet) sayAloud(e.decir);
}

// ---------------- vista: lo que ve en el teléfono y en el navegador ----------------
function setCanal(c) {
  App.canal = c;
  for (const k of ["telefono", "web"]) { $("#tab-" + k).classList.toggle("on", k === c); if (k === c) $("#tab-" + k).classList.remove("fresh"); }
  $("#screen").className = "screen " + c;
  renderScreen();
}
function renderScreen(toque) {
  const c = App.canal, v = App.screens[c], st = App.status || {}, img = $("#screen-img");
  if (v) { img.src = `/api/screen?canal=${c}&v=${v}`; img.classList.remove("hidden"); $("#screen-empty").classList.add("hidden"); }
  else {
    img.classList.add("hidden"); $("#screen-empty").classList.remove("hidden");
    const ph = st.phone || {}, br = st.browser || {};
    $("#screen-empty").textContent = c === "telefono"
      ? (ph.conectado ? "Teléfono conectado. Pulsa ⟳ para ver su pantalla." : "Sin teléfono: " + (ph.detalle || "conéctalo por USB con la depuración activada") + ".")
      : (br.disponible ? "El navegador de JARVIS se abre cuando lo necesita." : "No encuentro Chrome, Chromium ni Edge en esta computadora.");
  }
  const tap = $("#tap");
  if (toque && v) {
    tap.style.left = (toque.fx * 100) + "%"; tap.style.top = (toque.fy * 100) + "%";
    tap.classList.remove("hidden"); tap.style.animation = "none"; void tap.offsetWidth; tap.style.animation = "";
  } else if (!toque) tap.classList.add("hidden");
  viewMeta();
}
function viewMeta() {
  const st = App.status || {}, ph = st.phone || {};
  $("#view-meta").textContent = App.canal === "telefono"
    ? (ph.conectado ? `${ph.modelo} · ${ph.bateria ?? "?"} %${ph.cargando ? " ⚡" : ""}${ph.bloqueado ? " · BLOQUEADO" : ""} · ${App.titles.telefono || ph.app || ""}` : "sin conexión")
    : (App.titles.web || "sin página abierta");
}
App.titles = { telefono: "", web: "" };
function screenEvent(e, quiet) {
  App.screens[e.canal] = e.v; App.titles[e.canal] = e.titulo || App.titles[e.canal];
  if (quiet) return;
  if (e.canal !== App.canal) { if (App.task && App.task.estado === "en curso") setCanal(e.canal); else $("#tab-" + e.canal).classList.add("fresh"); }
  if (e.canal === App.canal) renderScreen(e.toque);
}
$("#tab-telefono").onclick = () => setCanal("telefono");
$("#tab-web").onclick = () => setCanal("web");
$("#view-refresh").onclick = async () => {
  const r = await post("/api/look", { canal: App.canal });
  if (!r.ok) toast(r.o.error || "No pude mirar.", true);
};

// ---------------- permisos: lo que espera tu sí ----------------
function renderPermits(list, quiet) {
  const before = new Set(App.permits.map(a => a.id));
  App.permits = list || [];
  const a = App.permits[0];
  $("#permit").classList.toggle("hidden", !a);
  if (a) {
    $("#permit").dataset.aid = a.id;
    $("#permit-action").textContent = a.accion;
    $("#permit-why").textContent = `En ${a.donde || (a.canal === "web" ? "el navegador" : "el teléfono")} · ${a.motivo}.`;
    $("#permit-extra").textContent = a.text || "";
    $("#permit-more").textContent = App.permits.length > 1 ? `y ${App.permits.length - 1} más en espera` : "Solo vale tu clic: decir «sí» no aprueba nada.";
    if (a.canal && a.canal !== App.canal) setCanal(a.canal);
    if (!before.has(a.id) && !quiet) { said("Necesito tu sí: " + a.accion); sayAloud("Necesito tu sí en pantalla."); }
  }
  paint();
}
async function decidePermit(decision) {
  const id = $("#permit").dataset.aid; if (!id) return;
  const r = await post("/api/approve", { id, decision });
  if (!r.ok) toast("No se pudo registrar: " + (r.o.error || r.status), true);
  else renderPermits(App.permits.filter(a => a.id !== id), true);
}
$("#permit-yes").onclick = () => decidePermit("aprobar");
$("#permit-no").onclick = () => decidePermit("descartar");

// ---------------- eventos del servidor (espera larga) ----------------
function handle(e, quiet) {
  if (e.tipo === "tarea") taskStart(e);
  else if (e.tipo === "paso") stepEvent(e);
  else if (e.tipo === "nota") stepNote(e.texto);
  else if (e.tipo === "guardado") stepNote("Guardado en la memoria: " + e.texto);
  else if (e.tipo === "pantalla") screenEvent(e, quiet);
  else if (e.tipo === "permiso") renderPermits(e.pendientes, quiet);
  else if (e.tipo === "fin") taskEnd(e, quiet);
}
async function eventLoop() {
  for (;;) {
    try {
      const r = await fetch("/api/events?desde=" + App.seq);
      if (!r.ok) throw new Error(r.status);
      const d = await r.json();
      for (const e of d.eventos) { App.seq = e.seq; handle(e, false); }
      if (d.ultimo < App.seq) App.seq = 0;                      // el servidor se reinició
    } catch (_) { await new Promise(r => setTimeout(r, 2000)); }
  }
}

// ---------------- conversación ----------------
const TOOL_LABEL = { conversacion: "conversación", buscar_en_mi_cerebro: "mis archivos", investigar_en_la_web: "internet",
  leer_correo: "correo", parte_del_dia: "parte del día", recordar: "memoria", planear_el_dia: "plan", tarea: "tarea autónoma",
  autorizacion: "permiso", pausa: "pausa" };

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
  const who = o.brain === "claude" ? "claude" : o.brain === "servidor" || o.brain === "pausa" ? "servidor" : "RESPALDO · sin cerebro";
  $("#answer-meta").textContent = `${TOOL_LABEL[o.tool] || o.tool} · ${who} · ${secs(o.ms || 0)}`;
  $("#answer-meta").style.color = who === "RESPALDO · sin cerebro" ? "var(--bad)" : (o.ms > 6000 && !["investigar_en_la_web", "tarea"].includes(o.tool) ? "var(--warn)" : "");
  $("#answer-say").textContent = o.say || "";
  let html = "";
  if (question) html += `<div class="src">Tú: ${esc(question)}</div>`;
  if (o.saved) html += `<div class="warnline">Guardado en ${esc(o.saved.file)}</div>`;
  if (o.detail) html += `<div class="note-body">${renderMarkdown(o.detail, [])}</div>`;
  if (o.approval) html += approvalHtml(o.approval);
  html += renderCard(o.card);
  $("#answer-card").innerHTML = html;
  $("#answer").classList.remove("hidden");
  said(o.say);
  if (o.warning) toast(o.warning, o.brain !== "claude", 9000);
}
// ---------------- borradores: esperan tu sí y los envías tú ----------------
function approvalHtml(a) {
  const done = a.state !== "pendiente";
  return `<div class="approval ${done ? "done" : ""}" data-aid="${esc(a.id)}">
    <div class="state">${done ? esc(a.state.toUpperCase() + " · " + (a.result || "")) : "BORRADOR · espera tu sí. Aprobar lo copia; lo envías tú."}</div>
    ${done ? "" : `<div class="row"><button class="pill" data-decision="aprobar">Aprobar</button><button class="pill" data-decision="descartar">Descartar</button></div>`}
  </div>`;
}
document.addEventListener("click", async e => {
  const b = e.target.closest(".approval button[data-decision]"); if (!b) return;
  const box = b.closest(".approval");
  const r = await post("/api/approve", { id: box.dataset.aid, decision: b.dataset.decision });
  const a = r.o;
  if (!r.ok) { toast("No se pudo registrar: " + (a.error || r.status), true); return; }
  box.outerHTML = approvalHtml(a);
  if (a.state === "aprobado") {
    try { await navigator.clipboard.writeText(a.text); toast("Aprobado y copiado. No se ha enviado nada: envíalo tú."); }
    catch (_) { toast("Aprobado. No se ha enviado nada: copia el borrador y envíalo tú."); }
  }
});

// ---------------- PAUSA ----------------
function showPause(on) {
  App.paused = !!on;
  document.body.classList.toggle("paused", App.paused);
  $("#pause-banner").classList.toggle("hidden", !App.paused);
  $("#btn-pause").classList.toggle("on", App.paused);
  $("#btn-pause").textContent = App.paused ? "en pausa" : "pausa";
  if (App.paused) { stopSpeaking(); stopConversation(); }
  paint();
}
async function setPause(on) {
  if (on) showPause(true); // corta en el navegador ya, sin esperar al servidor
  try { const r = await post("/api/pause", { on }); showPause(r.o.on); }
  catch (e) { toast("No pude hablar con el servidor para cambiar la pausa.", true); }
}
$("#btn-pause").onclick = () => setPause(!App.paused);
$("#btn-unpause").onclick = () => setPause(false);
$("#btn-stop").onclick = async () => { await post("/api/task/stop"); };
$("#answer-close").onclick = () => $("#answer").classList.add("hidden");

async function ask(text, tool) {
  if (App.busy) return;
  if (App.paused && !(text && /^\s*pausa\s*$/i.test(text))) { toast("JARVIS está en pausa. Quítala para seguir."); return; }
  App.busy = true; setState("thinking"); said(text ? "«" + text + "»" : "");
  try {
    if (text && !tool) {
      const r = await (await fetch("/api/route?q=" + encodeURIComponent(text))).json();
      if (r.tool === "investigar_en_la_web") toast("Buscando en internet: tarda entre 15 y 40 segundos.", false, 40000);
    }
    const res = await post("/api/ask", { text: text || "", tool: tool || null, conv: App.conv });
    const o = res.o;
    if (!res.ok) throw new Error(o.error || res.status);
    if (o.tool === "investigar_en_la_web" && !o.warning) $("#toast").classList.add("hidden");
    if (o.task) { said(o.say); if (o.warning) toast(o.warning, true, 9000); } else showAnswer(o, text);
    if (o.saved) refreshStatus();
    if (o.paused) showPause(true);
    App.busy = false;
    if (!o.paused) await sayAloud(o.say);
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
$("#hints").innerHTML = HINTS.map(h => `<li>${esc(h)}</li>`).join("");
$("#hints").onclick = e => { const li = e.target.closest("li"); if (li) ask(li.textContent); };

// ---------------- mapa de notas (se carga la primera vez que lo abres) ----------------
$("#btn-map").onclick = async () => {
  const on = !document.body.classList.contains("mapa");
  document.body.classList.toggle("mapa", on); $("#btn-map").classList.toggle("on", on);
  if (on && !App.graphReady) {
    App.graphReady = true;
    try {
      const graph = await (await fetch("/api/graph")).json();
      Graph.init($("#graph"), graph);
      Graph.onClick = n => openNote(n.id, false);
      buildFilters(App.status.by_type); buildTop();
    } catch (e) { toast("No pude cargar el mapa.", true); }
  }
};

async function refreshStatus() {
  try {
    const st = await (await fetch("/api/status")).json();
    App.status = st;
    if (st.pause && !!st.pause.on !== !!App.paused) showPause(st.pause.on);
    const b = st.brain || {};
    const map = { listo: ["cerebro: listo", "ok"], calentando: ["cerebro: calentando…", "warn"], arrancando: ["cerebro: arrancando…", "warn"],
                  respaldo: ["cerebro: RESPALDO", "bad"], "no disponible": ["cerebro: NO DISPONIBLE", "bad"] };
    const [txt, cls] = map[b.brain] || ["cerebro: " + b.brain, ""];
    chip("#st-brain", txt + (b.last_ms ? ` · ${secs(b.last_ms)}` : ""), cls);
    $("#st-brain").title = b.detail || "";
    const ph = st.phone || {}, br = st.browser || {};
    chip("#st-phone", ph.conectado ? `teléfono: ${ph.bateria ?? "?"} %${ph.bloqueado ? " · bloqueado" : ""}` : (ph.disponible ? "teléfono: sin conectar" : "teléfono: falta adb"), ph.conectado ? "ok" : "warn");
    $("#st-phone").title = ph.conectado ? `${ph.modelo} · Android ${ph.android}` : (ph.detalle || "");
    chip("#st-web", br.disponible ? (br.abierto ? "navegador: abierto" : "navegador: listo") : "navegador: no hay", br.disponible ? (br.abierto ? "ok" : "") : "bad");
    for (const c of ["telefono", "web"]) if (st.screens && st.screens[c].v > App.screens[c]) { App.screens[c] = st.screens[c].v; if (c === App.canal) renderScreen(); }
    if (!App.screens[App.canal]) renderScreen(); else viewMeta();
    if (b.brain === "no disponible" && !App.warnedBrain) { App.warnedBrain = true; toast("El cerebro no está disponible: " + (b.detail || "") + ". Funciono en modo respaldo y no puedo actuar.", true, 0); }
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
  if (!Mic.stream || App.state === "speaking" || App.busy || App.paused) return;
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
  if (!Mic.conversation || App.paused) { finishTurn(); return; }
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
  if (Mic.conversation && !App.busy && App.state === "idle") startListening();
}

function finishTurn() {
  $("#listen").classList.add("hidden");
  if (App.state === "listening") setState("idle");
}

async function startConversation() {
  if (App.paused) { toast("JARVIS está en pausa. Quítala para hablar."); return; }
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
const Voice = { audio: null, muted: false, ok: true, analyser: null, buf: null, queue: Promise.resolve(),
  level() {
    if (!this.audio) return 0;
    if (this.analyser) {
      this.analyser.getFloatTimeDomainData(this.buf);
      let s = 0; for (let i = 0; i < this.buf.length; i++) s += this.buf[i] * this.buf[i];
      return Math.min(1, Math.sqrt(s / this.buf.length) * 5);
    }
    return 0.35 + 0.3 * Math.sin(performance.now() / 90);   // sin analizador: un latido
  } };
function markVoice(err) {
  Voice.ok = false;
  chip("#st-voice", "voz: NO DISPONIBLE", "bad"); $("#st-voice").title = err || "";
  toast("La voz no está disponible: " + (err || "error") + ". Sigo por escrito.", true, 9000);
}

function stopSpeaking() {
  if (Voice.audio) { const a = Voice.audio; Voice.audio = null; const done = a.onended; a.onended = a.onerror = null; a.pause(); if (done) done(); }
  if (App.state === "speaking") setState("idle");
}

async function speak(text) {
  if (!text || Voice.muted || App.paused) return false;
  if (App.status && App.status.voice && !App.status.voice.configured) return false;
  let r;
  try { r = await fetch("/api/speak", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text }) }); }
  catch (e) { markVoice("sin conexión con el servidor"); return false; }
  if (!r.ok) { const o = await r.json().catch(() => ({})); markVoice(o.error); return false; }
  if (!Voice.ok) { Voice.ok = true; chip("#st-voice", "voz: lista", "ok"); }
  const url = URL.createObjectURL(await r.blob());
  if (App.paused) { URL.revokeObjectURL(url); return false; }
  stopRecording(); Mic.deaf = true;                // sordo mientras habla
  if (Mic.stream) chip("#st-mic", "micrófono: sordo mientras hablo", "warn");
  return new Promise(resolve => {
    const a = Voice.audio = new Audio(url);
    Voice.analyser = null;
    if (Mic.ctx && Mic.ctx.state === "running") {   // el orbe late con su voz
      try {
        const src = Mic.ctx.createMediaElementSource(a);
        Voice.analyser = Mic.ctx.createAnalyser(); Voice.analyser.fftSize = 512; Voice.buf = new Float32Array(512);
        src.connect(Voice.analyser); Voice.analyser.connect(Mic.ctx.destination);
      } catch (_) { Voice.analyser = null; }
    }
    a.onended = a.onerror = () => { URL.revokeObjectURL(url); Voice.audio = null; resolve(true); };
    setState("speaking");
    a.play().catch(err => {
      const interrupted = err && err.name === "AbortError" || Voice.audio !== a; // lo cortaste tú (Esc, Espacio, pausa)
      if (Voice.audio === a) Voice.audio = null;
      if (!interrupted) toast("El navegador no dejó reproducir el audio. Pulsa en la página y repite.", true);
      resolve(false);
    });
  });
}

// Todo lo que JARVIS dice en voz alta pasa por aquí, de uno en uno; al acabar vuelve a escuchar si estabais hablando.
function sayAloud(text) {
  Voice.queue = Voice.queue.then(async () => {
    await speak(text);
    if (App.paused) return;
    if (App.state === "speaking") setState("idle");
    if (Mic.conversation && !App.busy) startListening();
  }).catch(() => {});
  return Voice.queue;
}

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
  if (e.target.tagName === "BUTTON" && (e.code === "Space" || e.key === "Enter")) return;   // un botón enfocado se pulsa él
  if (e.key === "p" || e.key === "P") { setPause(!App.paused); return; }
  if (e.key === "Escape") { stopSpeaking(); stopConversation(); if (!App.paused) setState("idle"); }
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
  Orb.init($("#orb"));
  try {
    const st = await (await fetch("/api/status")).json();
    App.status = st;
    if (st.pause && st.pause.on) showPause(true);
    $("#mode").textContent = st.mode === "demo" ? "DEMO · hoy " + st.today : "DATOS REALES";
    $("#mode").className = "chip " + (st.mode === "demo" ? "demo" : "");
    $("#counts").textContent = `${st.notes} notas · ${st.edges} enlaces`;
    // Lo que pasó antes de abrir esta página: se repinta la última tarea, sin volver a decirla en voz alta.
    const d = await (await fetch("/api/events?desde=-1")).json();
    let from = 0; d.eventos.forEach((e, i) => { if (e.tipo === "tarea") from = i; });
    for (const e of d.eventos.slice(from)) handle(e, true);
    App.seq = d.ultimo;
    if (st.task && st.task.estado !== "en curso" && App.task) App.task.estado = st.task.estado;
    renderPermits((st.approvals || []).filter(a => a.kind === "accion"), true);
  } catch (e) {
    toast("No hay conexión con el servidor de JARVIS. ¿Está arrancado ./jarvis.sh?", true, 0);
  }
  post("/api/reset", { conv: App.conv }).catch(() => {});
  await refreshStatus();
  setCanal(App.screens.telefono || !App.screens.web ? "telefono" : "web");
  chip("#st-mic", window.MediaRecorder ? "micrófono: pulsa para hablar" : "micrófono: sin soporte", window.MediaRecorder ? "" : "bad");
  const v = (App.status && App.status.voice) || {};
  if (!v.configured) { Voice.ok = false; chip("#st-voice", "voz: sin clave", "bad"); }
  else chip("#st-voice", "voz: lista", "");
  paint();
  eventLoop();
}
boot();
