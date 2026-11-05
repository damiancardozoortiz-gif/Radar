/* JARVIS · interfaz. JavaScript sin frameworks. */
"use strict";

const $ = s => document.querySelector(s);
const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const EXAMPLES = [
  "¿Qué tengo hoy?", "¿Cómo va la semana?", "Planéame el día",
  "¿Qué pólizas de Logística Delta tenemos?", "¿Quién me ha escrito?",
  "¿Cuánto nos deja un seguro de vehículo?", "Recuerda que Marta vuelve el lunes",
  "Busca en internet cuánto sube el IPC y qué me supone",
];

const App = { state: "idle", status: null };

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
  chip("#st-brain", "cerebro: paso 3", "");
  chip("#st-mic", "micrófono: paso 4", "");
  chip("#st-voice", "voz: paso 4", "");
  requestAnimationFrame(drawReactor);
}
boot();
