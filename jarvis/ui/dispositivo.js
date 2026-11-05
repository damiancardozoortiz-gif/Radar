/* JARVIS en el teléfono: permisos, cámara, archivos, ubicación, apps y panel de control.
 *
 * Todo usa las API estándar del navegador, que en Android pasan por los diálogos de permiso del sistema.
 * Nada se simula: el estado de cada permiso es el que devuelve el navegador, y si una API no existe
 * se dice "no disponible". La cámara solo se enciende cuando lo pides, se ve un indicador rojo mientras
 * está activa, y se apaga al cerrarla, al pausar, con DETENER TODO o al salir de la pantalla.
 * Abrir apps, guardar o compartir siempre espera a que pulses un botón.
 */
"use strict";

const Dev = { catalog: null, cam: { stream: null, facing: "environment", count: 0 }, photo: null, statuses: {} };

function devLog(tipo, detalle) {
  fetch("/api/dispositivo/evento", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tipo, detalle }) }).catch(() => {});
}

// =====================================================================  DevicePermissionManager
const DevicePermissionManager = {
  // nombre del permiso en la Permissions API (si existe)
  apiName: { camera: "camera", microphone: "microphone", geolocation: "geolocation", notifications: "notifications" },

  supported(name) {
    switch (name) {
      case "camera": case "microphone": return !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
      case "geolocation": return "geolocation" in navigator;
      case "notifications": return "Notification" in window;
      case "files": return true;
      case "bluetooth": return !!navigator.bluetooth;
      case "network": return "onLine" in navigator;
      case "share": return !!navigator.share;
    }
    return false;
  },

  /** Estado REAL: "concedido" | "denegado" | "preguntará" | "no necesita" | "no disponible" | "desconocido" */
  async query(name) {
    if (!this.supported(name)) return "no disponible";
    if (["files", "share", "network"].includes(name)) return "no necesita";
    if (name === "bluetooth") return "se pide al usar";
    if (name === "notifications" && "Notification" in window) {
      return { granted: "concedido", denied: "denegado", default: "preguntará" }[Notification.permission] || "desconocido";
    }
    const api = this.apiName[name];
    if (!api || !navigator.permissions) return "desconocido";
    try {
      const st = await navigator.permissions.query({ name: api });
      if (!Dev.statuses[name]) {  // vigilar cambios: detecta cuando revocas un permiso
        Dev.statuses[name] = st;
        st.onchange = () => this.changed(name, st.state);
      }
      return { granted: "concedido", denied: "denegado", prompt: "preguntará" }[st.state] || "desconocido";
    } catch (_) { return "desconocido"; }
  },

  /** Empieza a vigilar los permisos al cargar: así se detecta una revocación aunque no abras el panel. */
  async watchAll() {
    for (const k of ["camera", "microphone", "geolocation", "notifications"]) await this.query(k);
  },

  changed(name, state) {
    const human = { granted: "concedido", denied: "denegado", prompt: "preguntará" }[state] || state;
    devLog("permiso", `${name}: ahora ${human}`);
    if (state !== "granted") {
      if (name === "camera" && Dev.cam.stream) { Camera.close("permiso retirado"); toast("Has retirado el permiso de cámara: la cierro.", true); }
      if (name === "microphone" && typeof stopConversation === "function" && Mic.conversation) { stopConversation(); toast("Has retirado el permiso de micrófono: dejo de escuchar.", true); }
    }
    Panel.refresh();
  },

  /** Pide el permiso SOLO cuando una función lo necesita. Devuelve true/false según lo que conceda Android. */
  async request(name) {
    if (App.paused) { toast("JARVIS está en pausa."); return false; }
    try {
      if (name === "camera") { const s = await navigator.mediaDevices.getUserMedia({ video: true }); s.getTracks().forEach(t => t.stop()); }
      else if (name === "microphone") { const s = await navigator.mediaDevices.getUserMedia({ audio: true }); s.getTracks().forEach(t => t.stop()); }
      else if (name === "geolocation") { await new Promise((ok, ko) => navigator.geolocation.getCurrentPosition(ok, ko, { timeout: 15000 })); }
      else if (name === "notifications") { const r = await Notification.requestPermission(); if (r !== "granted") throw new Error(r); }
      devLog("permiso", `${name}: concedido`);
      Panel.refresh();
      return true;
    } catch (e) {
      devLog("permiso", `${name}: no concedido (${e.name || e.message})`);
      Panel.refresh();
      this.explainDenied(name);
      return false;
    }
  },

  explainDenied(name) {
    const p = Dev.catalog && Dev.catalog.permisos[name];
    toast(`${p ? p.nombre : name}: Android no ha dado permiso. Para activarlo: ${p ? p.android : "ajustes del navegador"}`, true, 12000);
  },

  /** Una página web no puede abrir los ajustes de Android directamente: se explica el camino exacto. */
  openSettings(name) {
    const p = Dev.catalog && Dev.catalog.permisos[name];
    Panel.showHelp(p ? `${p.nombre}: ${p.android}` : "Ajustes del navegador → Configuración de sitios.");
  },
};

// =====================================================================  Cámara
const Camera = {
  async open(facing) {
    if (App.paused) { toast("JARVIS está en pausa."); return false; }
    if (!DevicePermissionManager.supported("camera")) { toast("Este navegador no da acceso a la cámara.", true); return false; }
    if (!window.isSecureContext) { toast("La cámara solo funciona en https o en localhost.", true); return false; }
    this.close(null, true);
    Dev.cam.facing = facing || Dev.cam.facing || "environment";
    try {
      Dev.cam.stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: Dev.cam.facing }, width: { ideal: 1280 } }, audio: false });
    } catch (e) {
      devLog("camara", `no se pudo abrir (${e.name})`);
      if (e.name === "NotAllowedError") DevicePermissionManager.explainDenied("camera");
      else toast("No pude abrir la cámara: " + e.name, true);
      Panel.refresh();
      return false;
    }
    DevicePermissionManager.query("camera");  // asegura la vigilancia de revocación
    const devices = await navigator.mediaDevices.enumerateDevices().catch(() => []);
    Dev.cam.count = devices.filter(d => d.kind === "videoinput").length;
    const v = $("#cam-video"); v.srcObject = Dev.cam.stream; await v.play().catch(() => {});
    const s = Dev.cam.stream.getVideoTracks()[0].getSettings();
    $("#cam-info").textContent = `${Dev.cam.facing === "user" ? "frontal" : "trasera"} · ${s.width || "?"}×${s.height || "?"} · ${Dev.cam.count} cámara${Dev.cam.count === 1 ? "" : "s"}`;
    $("#camera").classList.remove("hidden"); $("#cam-photo-box").classList.add("hidden");
    document.body.classList.add("cam-on");
    devLog("camara", `abierta (${Dev.cam.facing === "user" ? "frontal" : "trasera"})`);
    Panel.refresh();
    return true;
  },

  close(why, silent) {
    if (Dev.cam.stream) {
      Dev.cam.stream.getTracks().forEach(t => t.stop());
      Dev.cam.stream = null;
      if (!silent) devLog("camara", "cerrada" + (why ? ` (${why})` : ""));
    }
    const v = $("#cam-video"); if (v) v.srcObject = null;
    if (!silent) { $("#camera").classList.add("hidden"); Dev.photo = null; }
    document.body.classList.remove("cam-on");
    Panel.refresh();
  },

  async switch() {
    if (Dev.cam.count < 2 && Dev.cam.stream) { toast(`Solo hay ${Dev.cam.count} cámara en este dispositivo.`); }
    return this.open(Dev.cam.facing === "user" ? "environment" : "user");
  },

  /** Saca una foto SOLO cuando lo ordenas. No se guarda ni se comparte sin tu confirmación. */
  capture() {
    if (!Dev.cam.stream) { toast("La cámara no está abierta."); return null; }
    const v = $("#cam-video"), c = document.createElement("canvas");
    const scale = Math.min(1, 1600 / Math.max(v.videoWidth || 1, v.videoHeight || 1));
    c.width = Math.round((v.videoWidth || 640) * scale); c.height = Math.round((v.videoHeight || 480) * scale);
    c.getContext("2d").drawImage(v, 0, 0, c.width, c.height);
    Dev.photo = c.toDataURL("image/jpeg", 0.85);
    $("#cam-photo").src = Dev.photo; $("#cam-photo-box").classList.remove("hidden");
    devLog("foto", `capturada ${c.width}×${c.height} (no guardada)`);
    return Dev.photo;
  },

  async describe(question) {
    let img = Dev.photo;
    if (!img && Dev.cam.stream) img = this.capture();
    if (!img) { toast("Abre la cámara o elige una foto primero."); return null; }
    return analyzeImage(img, question || "¿Qué ves? Si hay texto, léelo.");
  },

  save() {
    if (!Dev.photo) return;
    if (!confirm("¿Guardar esta foto en el teléfono?")) return;
    const a = document.createElement("a"); a.href = Dev.photo; a.download = `jarvis-${Date.now()}.jpg`; a.click();
    devLog("guardar", "foto guardada por el usuario");
  },

  async share() {
    if (!Dev.photo) return;
    if (!navigator.share) { toast("Este navegador no tiene el menú Compartir."); return; }
    if (!confirm("¿Compartir esta foto? Elegirás tú la app en el menú de Android.")) return;
    const blob = await (await fetch(Dev.photo)).blob();
    const file = new File([blob], `jarvis-${Date.now()}.jpg`, { type: "image/jpeg" });
    try {
      await navigator.share(navigator.canShare && navigator.canShare({ files: [file] }) ? { files: [file] } : { title: "Foto de JARVIS", url: location.href });
      devLog("compartir", "foto compartida por el usuario");
    } catch (e) { if (e.name !== "AbortError") toast("No se pudo compartir: " + e.name, true); }
  },
};

async function analyzeImage(dataUrl, question) {
  if (App.paused) { toast("JARVIS está en pausa."); return null; }
  setState("thinking");
  try {
    const r = await fetch("/api/vision", { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ image: dataUrl, question }) });
    const o = await r.json();
    if (!r.ok) { toast(r.status === 423 ? "JARVIS está en pausa." : (o.error || "No se pudo analizar."), true); setState("idle"); return null; }
    showAnswer({ ...o, tool: "visión", card: null }, question);
    if (window.onAnswer) await window.onAnswer(o); else setState("idle");
    return o;
  } catch (e) { toast("Error al analizar: " + e.message, true); setState("idle"); return null; }
}

// =====================================================================  Archivos, ubicación, red, Bluetooth, notificaciones
const Files = {
  pick(accept) {
    if (App.paused) { toast("JARVIS está en pausa."); return; }
    const inp = $("#file-input"); inp.value = ""; inp.accept = accept || ""; inp.click();
  },
  async handle(file) {
    if (!file) return;
    devLog("archivo", `elegido: ${file.type || "tipo desconocido"}, ${Math.round(file.size / 1024)} KB`);
    if (/^image\//.test(file.type)) {
      const dataUrl = await downscale(file);
      Dev.photo = dataUrl; $("#cam-photo").src = dataUrl;
      $("#camera").classList.remove("hidden"); $("#cam-photo-box").classList.remove("hidden");
      return analyzeImage(dataUrl, "Describe esta imagen. Si es un documento, léelo.");
    }
    if (/^text\/|json|markdown|csv/.test(file.type) || /\.(txt|md|csv|json)$/i.test(file.name)) {
      if (file.size > 2 * 1024 * 1024) { toast("Archivo de más de 2 MB: no lo leo."); return; }
      const text = await file.text();
      setState("thinking");
      const r = await fetch("/api/archivo", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: file.name, text, question: "Resume este documento y dime lo importante." }) });
      const o = await r.json();
      if (!r.ok) { toast(o.error || "No se pudo leer.", true); setState("idle"); return; }
      showAnswer({ ...o, tool: "documento", card: null }, file.name);
      if (window.onAnswer) await window.onAnswer(o); else setState("idle");
      return o;
    }
    toast(`«${file.name}»: de este tipo de archivo (${file.type || "desconocido"}) solo puedo leer imágenes y texto.`);
  },
};

function downscale(file) {
  return new Promise((ok, ko) => {
    const img = new Image(); img.onerror = ko;
    img.onload = () => {
      const s = Math.min(1, 1600 / Math.max(img.width, img.height)), c = document.createElement("canvas");
      c.width = Math.round(img.width * s); c.height = Math.round(img.height * s);
      c.getContext("2d").drawImage(img, 0, 0, c.width, c.height); URL.revokeObjectURL(img.src);
      ok(c.toDataURL("image/jpeg", 0.85));
    };
    img.src = URL.createObjectURL(file);
  });
}

const Place = {
  async where() {
    if (App.paused) { toast("JARVIS está en pausa."); return null; }
    if (!("geolocation" in navigator)) { toast("Este navegador no da la ubicación.", true); return null; }
    try {
      const pos = await new Promise((ok, ko) => navigator.geolocation.getCurrentPosition(ok, ko, { enableHighAccuracy: true, timeout: 15000 }));
      const { latitude: la, longitude: lo, accuracy: acc } = pos.coords;
      devLog("ubicacion", `leída una vez (±${Math.round(acc)} m), no enviada a Claude`);
      const url = `https://www.google.com/maps/search/?api=1&query=${la.toFixed(6)},${lo.toFixed(6)}`;
      showAnswer({ say: `Estás en ${la.toFixed(4)}, ${lo.toFixed(4)}, con unos ${Math.round(acc)} metros de margen.`, tool: "ubicación",
        brain: "dispositivo", ms: 0, detail: "" }, "¿Dónde estoy?");
      showOpenApp({ action: "open_app", app: "maps", url, label: "Ver tu ubicación en Google Maps" });
      return pos;
    } catch (e) {
      devLog("ubicacion", `no disponible (${e.code || e.name})`);
      if (e.code === 1) DevicePermissionManager.explainDenied("geolocation"); else toast("No pude saber dónde estás.", true);
      return null;
    } finally { Panel.refresh(); }
  },
};

const Net = {
  info() {
    const c = navigator.connection || {};
    return { online: navigator.onLine, type: c.effectiveType || c.type || "desconocido", saveData: !!c.saveData };
  },
};

const Bt = {
  async scan() {
    if (!navigator.bluetooth) { toast("Este navegador no tiene Bluetooth web (en Android: Chrome con Bluetooth y Ubicación activados)."); return; }
    try {
      const d = await navigator.bluetooth.requestDevice({ acceptAllDevices: true });
      devLog("bluetooth", "dispositivo elegido por el usuario");
      toast(`Dispositivo elegido: ${d.name || "(sin nombre)"}. JARVIS solo lo identifica: no lo empareja ni lo controla.`);
    } catch (e) { if (e.name !== "NotFoundError") toast("Bluetooth: " + e.message, true); }
  },
};

async function notifyTest() {
  if (!("Notification" in window)) { toast("Este navegador no tiene notificaciones.", true); return; }
  const ok = Notification.permission === "granted" || await DevicePermissionManager.request("notifications");
  if (!ok) return;
  try {
    const reg = navigator.serviceWorker && await navigator.serviceWorker.getRegistration().catch(() => null);
    if (reg) await reg.showNotification("JARVIS", { body: "Notificaciones activas.", icon: "/favicon.svg" });
    else new Notification("JARVIS", { body: "Notificaciones activas." });
    devLog("notificacion", "notificación de prueba");
    return true;
  } catch (e) {
    devLog("notificacion", `rechazada por el sistema (${e.name})`);
    DevicePermissionManager.explainDenied("notifications");
    return false;
  }
}

// =====================================================================  Abrir apps (siempre con tu clic)
function showOpenApp(dev) {
  const box = $("#app-card");
  box.innerHTML = `<div class="tag">${esc(dev.app)}</div><p>${esc(dev.label)}</p>
    <div class="row"><a class="pill on" id="app-go" href="${esc(dev.url)}" target="_blank" rel="noopener noreferrer">Abrir</a>
    <button class="pill" id="app-cancel">Cancelar</button></div>
    <div class="src">${esc(dev.url)}</div>`;
  box.classList.remove("hidden");
  $("#app-go").onclick = () => { devLog("app", `abierta por el usuario: ${dev.app}`); box.classList.add("hidden"); };
  $("#app-cancel").onclick = () => { devLog("app", `cancelada: ${dev.app}`); box.classList.add("hidden"); };
}

// =====================================================================  Órdenes que llegan del servidor
async function runDevice(dev) {
  if (!dev) return;
  switch (dev.action) {
    case "stop_all": stopAll("voz"); break;
    case "camera_open": await Camera.open(dev.facing); break;
    case "camera_close": Camera.close("por voz"); break;
    case "camera_switch": await Camera.switch(); break;
    case "camera_capture": if (!Dev.cam.stream) await Camera.open(); Camera.capture(); break;
    case "camera_describe": await Camera.describe(); break;
    case "location": await Place.where(); break;
    case "pick_file": Files.pick(dev.accept); break;
    case "open_app": showOpenApp(dev); break;
  }
}

/** JARVIS DETENER TODO: pausa el servidor y apaga cámara, micrófono, voz y lecturas en curso. */
function stopAll(origin) {
  Camera.close("detener todo");
  if (typeof stopSpeaking === "function") stopSpeaking();
  if (typeof stopConversation === "function") stopConversation();
  $("#app-card").classList.add("hidden");
  devLog("detener_todo", `desde ${origin}`);
  if (typeof setPause === "function") setPause(true);
}

// =====================================================================  Panel de control
const Panel = {
  async load() {
    try { Dev.catalog = await (await fetch("/api/dispositivo")).json(); } catch (_) { Dev.catalog = null; }
  },
  async refresh() {
    if ($("#panel").classList.contains("hidden") || !Dev.catalog) return;
    const rows = [];
    for (const [k, p] of Object.entries(Dev.catalog.permisos)) {
      const st = await DevicePermissionManager.query(k);
      const cls = { concedido: "ok", denegado: "bad", "no disponible": "bad" }[st] || "";
      const act = { camera: "Probar cámara", microphone: "Pedir micrófono", geolocation: "Pedir ubicación", notifications: "Activar",
        files: "Elegir archivo", bluetooth: "Buscar", share: "", network: "" }[k];
      rows.push(`<div class="perm"><div class="perm-head"><strong>${esc(p.nombre)}</strong><span class="chip ${cls}">${esc(st)}</span></div>
        <div class="dim">${esc(p.para)}</div><div class="dim small">${esc(p.cuando)}</div>
        <div class="row">${act ? `<button class="pill" data-perm-act="${k}">${act}</button>` : ""}<button class="pill" data-perm-help="${k}">Cómo activarlo</button></div></div>`);
    }
    const n = Net.info();
    const ints = Dev.catalog.integraciones.map(i => `<div class="perm"><div class="perm-head"><strong>${esc(i.app)}</strong>
      <span class="chip ${i.estado === "disponible" ? "ok" : "warn"}">${esc(i.estado)}</span></div>
      <div class="dim small">${esc(i.mecanismo)}</div>
      <div class="small">✔ ${i.puede.map(esc).join("<br>✔ ")}</div><div class="small dim">✘ ${i.no_puede.map(esc).join("<br>✘ ")}</div></div>`).join("");
    const acts = (Dev.catalog.acciones || []).slice(0, 15).map(a => `<div class="small"><span class="src">${esc(a.ts)}</span> ${esc(a.tipo)} · ${esc(a.detalle)}</div>`).join("") || "<div class='dim'>Sin acciones registradas.</div>";
    const st = App.status || {};
    $("#panel-body").innerHTML = `
      <div class="perm"><div class="perm-head"><strong>Servicios</strong></div>
        <div class="small">Cerebro: ${esc((st.brain || {}).brain || "?")} · Voz: ${st.voice && st.voice.configured ? "configurada" : "sin clave"} ·
        Red: ${n.online ? "conectado" : "SIN CONEXIÓN"} (${esc(n.type)}) · Cámara: ${Dev.cam.stream ? "<b class='bad-text'>ACTIVA</b>" : "apagada"} ·
        Micrófono: ${Mic.conversation ? "<b class='bad-text'>ESCUCHANDO</b>" : "apagado"} · PAUSA: ${App.paused ? "SÍ" : "no"} ·
        Acceso desde el teléfono: ${Dev.catalog.remoto.activo ? "activo (" + esc(Dev.catalog.remoto.host) + ")" : "desactivado (ver README)"}</div>
        <div class="row"><button class="pill pause" id="panel-stop">DETENER TODO</button></div></div>
      <h3>Permisos</h3>${rows.join("")}
      <h3>Aplicaciones</h3>${ints}
      <h3>Últimas acciones</h3>${acts}`;
    $("#panel-stop").onclick = () => stopAll("panel");
    document.querySelectorAll("[data-perm-help]").forEach(b => b.onclick = () => DevicePermissionManager.openSettings(b.dataset.permHelp));
    document.querySelectorAll("[data-perm-act]").forEach(b => b.onclick = () => {
      const k = b.dataset.permAct;
      if (k === "camera") Camera.open(); else if (k === "files") Files.pick(""); else if (k === "bluetooth") Bt.scan();
      else if (k === "notifications") notifyTest(); else if (k === "geolocation") Place.where(); else DevicePermissionManager.request(k);
    });
  },
  async toggle() {
    const p = $("#panel"); p.classList.toggle("hidden");
    if (!p.classList.contains("hidden")) { await this.load(); await this.refresh(); }
  },
  showHelp(text) { toast(text, false, 15000); },
};

// =====================================================================  enganches
$("#btn-device").onclick = () => Panel.toggle();
$("#panel-close").onclick = () => $("#panel").classList.add("hidden");
$("#cam-close").onclick = () => Camera.close("botón");
$("#cam-switch").onclick = () => Camera.switch();
$("#cam-shot").onclick = () => Camera.capture();
$("#cam-describe").onclick = () => Camera.describe();
$("#cam-save").onclick = () => Camera.save();
$("#cam-share").onclick = () => Camera.share();
$("#cam-discard").onclick = () => { Dev.photo = null; $("#cam-photo-box").classList.add("hidden"); devLog("foto", "descartada"); };
$("#file-input").onchange = e => Files.handle(e.target.files[0]);
// No grabar en segundo plano: al salir de la pantalla, la cámara se apaga.
document.addEventListener("visibilitychange", () => { if (document.hidden && Dev.cam.stream) Camera.close("pantalla en segundo plano"); });
window.addEventListener("online", () => Panel.refresh());
window.addEventListener("offline", () => { toast("Sin conexión: el cerebro y la voz no funcionarán hasta que vuelva.", true); Panel.refresh(); });
// Aplicación instalable (PWA) y notificaciones en Android: requiere https o localhost.
if ("serviceWorker" in navigator && window.isSecureContext) navigator.serviceWorker.register("/sw.js").catch(() => {});
Panel.load();
DevicePermissionManager.watchAll();
