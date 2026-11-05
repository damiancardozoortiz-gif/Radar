// Pruebas de navegador de las funciones del teléfono: permisos, cámara, apps, archivos, ubicación, notificaciones,
// DETENER TODO y panel. Usa la cámara FALSA de Chromium (una imagen de prueba en movimiento), un `claude` simulado
// y estado/memoria temporales. No usa internet ni tu suscripción.
// Lo que solo se puede probar en un teléfono Android real lo marca como PENDIENTE (no como superado).
// Uso: node tests/navegador_dispositivo.js   (requiere Playwright; ver tests/package.json)
"use strict";
const { chromium } = require("playwright");
const { spawn } = require("child_process");
const fs = require("fs"), os = require("os"), path = require("path"), net = require("net");

const ROOT = path.resolve(__dirname, "..");
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), "jarvis-disp-"));
const R = [];
const ok = (c, m) => { R.push([c ? "PASA" : "FALLA", m]); console.log((c ? "PASA " : "FALLA") + "  " + m); };
const pend = m => { R.push(["PENDIENTE", m]); console.log("PENDIENTE  " + m); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const freePort = () => new Promise(res => { const s = net.createServer().listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); });

async function main() {
  const port = await freePort(), url = `http://localhost:${port}`, claudeLog = path.join(TMP, "claude.log");
  const env = Object.assign({}, process.env, { JARVIS_PORT: String(port), JARVIS_STATE_DIR: path.join(TMP, "estado"),
    JARVIS_MEMORY_DIR: path.join(TMP, "memory"), JARVIS_CLAUDE_BIN: path.join(ROOT, "tests", "fake_claude.py"),
    FAKE_CLAUDE_LOG: claudeLog, FAKE_CLAUDE_SLEEP: "0.2", ELEVENLABS_API_KEY: "" });
  let srv;
  const start = async () => { srv = spawn("python3", [path.join(ROOT, "agent", "main.py")], { cwd: ROOT, env, stdio: "ignore" });
    for (let i = 0; i < 100; i++) { try { if ((await fetch(url + "/api/status")).ok) return; } catch (_) {} await sleep(100); } throw new Error("no arrancó"); };
  const stop = async () => { if (srv.exitCode === null) { const d = new Promise(r => srv.once("exit", r)); srv.kill("SIGKILL"); await d; } };
  const status = () => fetch(url + "/api/status").then(r => r.json());
  const actions = async () => (await (await fetch(url + "/api/dispositivo")).json()).acciones;
  await start();

  const browser = await chromium.launch({ args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream", "--autoplay-policy=no-user-gesture-required"] });
  const errs = [];
  try {
    // ---------------------------------------------------------------- permisos reales y panel
    console.log("\n== permisos y panel");
    let ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });  // SIN permisos concedidos
    let p = await ctx.newPage(); p.on("pageerror", e => errs.push(String(e)));
    await p.goto(url); await sleep(800);
    await p.click("#btn-device"); await sleep(800);
    const panelTxt = await p.textContent("#panel-body");
    ok(/Cámara/.test(panelTxt) && /Micrófono/.test(panelTxt) && /Ubicación/.test(panelTxt) && /Notificaciones/.test(panelTxt) && /Archivos/.test(panelTxt),
      "el panel lista cámara, micrófono, ubicación, notificaciones y archivos");
    const real = await p.evaluate(async () => (await navigator.permissions.query({ name: "camera" })).state);
    const shown = await p.evaluate(async () => DevicePermissionManager.query("camera"));
    ok(({ granted: "concedido", denied: "denegado", prompt: "preguntará" })[real] === shown, `el estado mostrado es el real del navegador (cámara: navegador «${real}» → panel «${shown}»)`);
    ok(/Leer tus mensajes/.test(panelTxt) && /no conectada/.test(panelTxt), "el panel dice lo que las apps NO permiten (sin prometer de más)");
    ok(/DETENER TODO/.test(panelTxt), "el panel tiene el botón DETENER TODO");
    await ctx.close();

    // denegación real: contexto que deniega la cámara
    ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
    await ctx.grantPermissions([], { origin: url });
    p = await ctx.newPage(); p.on("pageerror", e => errs.push(String(e)));
    await p.goto(url); await sleep(500);
    await p.evaluate(() => { navigator.mediaDevices.getUserMedia = () => Promise.reject(new DOMException("denegado", "NotAllowedError")); });
    const opened = await p.evaluate(() => Camera.open());
    const toastTxt = await p.textContent("#toast");
    ok(!opened && /Android no ha dado permiso/.test(toastTxt) && /Configuración de sitios/.test(toastTxt),
      "si Android deniega la cámara, no se abre y explica cómo activarla");
    ok(!(await p.isVisible("#camera")), "con permiso denegado no aparece ninguna vista de cámara");
    await ctx.close();

    // ---------------------------------------------------------------- cámara
    console.log("\n== cámara (cámara falsa de Chromium)");
    ctx = await browser.newContext({ viewport: { width: 1280, height: 860 }, acceptDownloads: true });
    await ctx.grantPermissions(["camera", "microphone", "geolocation", "notifications", "clipboard-read", "clipboard-write"], { origin: url });
    p = await ctx.newPage(); p.on("pageerror", e => errs.push(String(e))); p.on("console", m => { if (m.type() === "error") errs.push(m.text()); });
    await p.goto(url); await sleep(800);
    await p.click("#btn-mute");
    await p.fill("#q", "abre la cámara"); await p.press("#q", "Enter");
    await p.waitForFunction(() => Dev.cam.stream && $("#cam-video").videoWidth > 0, null, { timeout: 10000 });
    const cam = await p.evaluate(() => ({ w: $("#cam-video").videoWidth, h: $("#cam-video").videoHeight, live: Dev.cam.stream.getVideoTracks()[0].readyState, rec: $(".rec").textContent }));
    ok(cam.live === "live" && cam.w > 0, `la orden «abre la cámara» enciende la cámara con vista previa (${cam.w}×${cam.h})`);
    ok(/CÁMARA ACTIVA/.test(cam.rec) && await p.isVisible(".rec"), "indicador rojo visible mientras está activa");
    await p.fill("#q", "hazme una foto"); await p.press("#q", "Enter"); await sleep(800);
    const ph = await p.evaluate(() => ({ has: !!Dev.photo && Dev.photo.startsWith("data:image/jpeg"), vis: !$("#cam-photo-box").classList.contains("hidden") }));
    ok(ph.has && ph.vis, "la orden «hazme una foto» captura una foto y la muestra");
    let downloads = 0; p.on("download", () => downloads++);
    p.once("dialog", d => d.dismiss()); await p.click("#cam-save"); await sleep(500);
    ok(downloads === 0, "Guardar: si no confirmas, no se guarda nada");
    p.once("dialog", d => d.accept()); await p.click("#cam-save"); await sleep(1000);
    ok(downloads === 1, "Guardar: solo tras confirmar, se guarda");
    await p.fill("#q", "cambia de cámara"); await p.press("#q", "Enter"); await sleep(1500);
    const sw = await p.evaluate(() => ({ facing: Dev.cam.facing, count: Dev.cam.count, live: !!Dev.cam.stream }));
    ok(sw.live && sw.facing === "user", `cambiar de cámara pide la frontal y sigue funcionando (${sw.count} cámara en este equipo)`);
    pend("frontal y trasera reales: este equipo solo tiene 1 cámara falsa; hay que comprobarlo en el teléfono");
    const n0 = (fs.existsSync(claudeLog) ? fs.readFileSync(claudeLog, "utf8") : "").split("\n").filter(l => l.startsWith("start")).length;
    await p.fill("#q", "¿qué ves?"); await p.press("#q", "Enter");
    await p.waitForFunction(() => /visión/.test($("#answer-meta").textContent), null, { timeout: 15000 });
    const n1 = fs.readFileSync(claudeLog, "utf8").split("\n").filter(l => l.startsWith("start")).length;
    ok(n1 === n0 + 1 && /claude/.test(await p.textContent("#answer-meta")), "«¿qué ves?» manda la foto al cerebro (visión) y muestra la respuesta");
    // segundo plano
    await p.evaluate(() => { Object.defineProperty(document, "hidden", { value: true, configurable: true }); document.dispatchEvent(new Event("visibilitychange")); });
    await sleep(300);
    ok(await p.evaluate(() => !Dev.cam.stream), "al pasar a segundo plano, la cámara se apaga sola");
    await p.evaluate(() => { Object.defineProperty(document, "hidden", { value: false, configurable: true }); });
    // revocación del permiso con la cámara abierta
    await p.evaluate(() => Camera.open()); await sleep(800);
    await ctx.clearPermissions(); await sleep(1500);
    const rev = await p.evaluate(async () => ({ state: (await navigator.permissions.query({ name: "camera" })).state, open: !!Dev.cam.stream }));
    if (rev.state !== "granted") ok(!rev.open, `al retirar el permiso (ahora «${rev.state}») JARVIS cierra la cámara`);
    else pend("revocación: este navegador de pruebas no notifica la retirada del permiso; hay que probarlo en Android");
    await ctx.grantPermissions(["camera", "microphone", "geolocation", "notifications", "clipboard-read", "clipboard-write"], { origin: url });
    // pausa y detener todo
    await p.evaluate(() => Camera.open()); await sleep(800);
    await p.evaluate(() => document.activeElement && document.activeElement.blur());  // la P no cuenta mientras escribes
    await p.keyboard.press("p"); await sleep(600);
    ok(await p.evaluate(() => !Dev.cam.stream && App.paused), "la PAUSA (tecla P) apaga la cámara");
    await p.click("#btn-unpause"); await sleep(500);
    await p.evaluate(() => Camera.open()); await sleep(800);
    await p.click("#btn-mic"); await sleep(1200);
    await p.fill("#q", "JARVIS, detener todo"); await p.press("#q", "Enter"); await sleep(1200);
    const st = await status();
    const after = await p.evaluate(() => ({ cam: !!Dev.cam.stream, mic: Mic.conversation, rec: !!Mic.rec, paused: App.paused }));
    ok(!after.cam && !after.mic && !after.rec && after.paused && st.pause.on, "«JARVIS, detener todo»: cámara, micrófono y cerebro parados, y el servidor en pausa");
    await p.click("#btn-unpause"); await sleep(500);

    // ---------------------------------------------------------------- abrir apps
    console.log("\n== abrir apps (siempre con tu clic)");
    let popups = [], navs = []; ctx.on("page", pg => popups.push(pg)); ctx.on("request", r => navs.push(r.url()));
    await p.fill("#q", "busca recetas de paella en youtube"); await p.press("#q", "Enter"); await sleep(800);
    const href = await p.getAttribute("#app-go", "href");
    ok(popups.length === 0 && /^https:\/\/www\.youtube\.com\/results\?search_query=recetas/.test(href), "YouTube: muestra el botón con el enlace oficial y NO abre nada solo");
    await p.click("#app-cancel"); await sleep(300);
    ok(popups.length === 0 && !(await p.isVisible("#app-card")), "Cancelar no abre nada");
    await p.fill("#q", "escribe por whatsapp diciendo Llego en diez minutos, ¿vale?"); await p.press("#q", "Enter"); await sleep(800);
    const wa = decodeURIComponent(await p.getAttribute("#app-go", "href"));
    ok(wa === "https://wa.me/?text=Llego en diez minutos, ¿vale?", "WhatsApp: enlace oficial wa.me con tu texto exacto; lo envías tú");
    const before = navs.filter(u => u.startsWith("https://wa.me/")).length;
    await p.click("#app-go"); await sleep(1500);
    const tried = navs.filter(u => u.startsWith("https://wa.me/")).length - before;
    ok(popups.length === 1 && tried >= 1, `solo al pulsar «Abrir» se abre el enlace oficial (${tried} navegación a wa.me; aquí no hay red hacia wa.me, en el teléfono abre WhatsApp)`);
    for (const pg of popups) await pg.close();
    await p.fill("#q", "¿cómo llego a la Puerta del Sol?"); await p.press("#q", "Enter"); await sleep(600);
    ok(/google\.com\/maps\/dir\/\?api=1&destination=la%20Puerta%20del%20Sol/.test(await p.getAttribute("#app-go", "href")), "Google Maps: ruta con Maps URLs oficiales");
    await p.click("#app-cancel");
    pend("que el enlace abra la APP (no la web) depende de Android y de que la app esté instalada: comprobar en el teléfono");

    // ---------------------------------------------------------------- archivos
    console.log("\n== archivos y fotos (selector)");
    const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");
    await p.fill("#q", "abre una foto de la galería"); await p.press("#q", "Enter"); await sleep(300);
    await p.setInputFiles("#file-input", { name: "foto.png", mimeType: "image/png", buffer: png });
    await p.waitForFunction(() => /visión/.test($("#answer-meta").textContent) && !/q/.test(""), null, { timeout: 15000 }).catch(() => {});
    ok(/visión/.test(await p.textContent("#answer-meta")), "el selector de fotos manda la foto elegida a la visión");
    await p.setInputFiles("#file-input", { name: "nota.txt", mimeType: "text/plain", buffer: Buffer.from("Reunión el jueves a las 10 con Logística Delta.") });
    await p.waitForFunction(() => /documento/.test($("#answer-meta").textContent), null, { timeout: 15000 }).catch(() => {});
    ok(/documento/.test(await p.textContent("#answer-meta")), "el selector de documentos lee un texto y lo resume");
    await p.setInputFiles("#file-input", { name: "x.zip", mimeType: "application/zip", buffer: Buffer.from("PK") });
    await sleep(400);
    ok(/solo puedo leer imágenes y texto/.test(await p.textContent("#toast")), "con un tipo que no sabe leer, lo dice (no finge)");

    // ---------------------------------------------------------------- ubicación, notificaciones, red
    console.log("\n== ubicación, notificaciones, red");
    await ctx.setGeolocation({ latitude: 40.4169, longitude: -3.7035, accuracy: 25 });
    await p.fill("#q", "¿dónde estoy?"); await p.press("#q", "Enter"); await sleep(1500);
    ok(/40\.41/.test(await p.textContent("#answer-say")) && /google\.com\/maps\/search/.test(await p.getAttribute("#app-go", "href")),
      "«¿dónde estoy?» lee la ubicación una vez y ofrece verla en Maps");
    await p.click("#app-cancel");
    const acts = await actions();
    ok(acts.some(a => a.tipo === "ubicacion" && /no enviada a Claude/.test(a.detalle)), "la ubicación no se envía a Claude y queda registrada");
    const pn = await ctx.newPage(); await pn.goto(url); await sleep(800);
    const sent = await pn.evaluate(() => notifyTest());
    const perm = await pn.evaluate(() => Notification.permission);
    if (perm === "granted") ok(sent === true, "notificaciones: permiso concedido y notificación de prueba enviada");
    else {
      // Chromium sin pantalla dice «denied» aunque se conceda: JARVIS no debe fingir que se envió
      ok(sent === false && /Notificaciones/.test(await pn.textContent("#toast")), `notificaciones: el sistema la rechaza («${perm}») y JARVIS lo dice en vez de fingir`);
      pend("que la notificación aparezca de verdad: Chromium sin pantalla no muestra notificaciones; probar en Android");
    }
    await pn.close();
    ok(await p.evaluate(() => typeof Net.info().online === "boolean"), "estado de la red disponible");
    pend("Bluetooth: Chromium de pruebas no tiene adaptador; probar «Buscar» en Android con Bluetooth activado");
    pend("Compartir (menú de Android): no existe en Chromium de escritorio; probar en el teléfono");

    // ---------------------------------------------------------------- registro y reinicio
    console.log("\n== registro y reinicio");
    const log = await actions();
    const tipos = new Set(log.map(a => a.tipo));
    ok(["camara", "foto", "vision", "app", "archivo", "ubicacion", "detener_todo", "guardar"].every(t => tipos.has(t)), `registro de acciones con ${tipos.size} tipos: ${[...tipos].join(", ")}`);
    const raw = fs.readFileSync(path.join(TMP, "estado", "acciones.jsonl"), "utf8");
    ok(!/base64|iVBOR|Reunión el jueves|diez minutos/.test(raw), "el registro no guarda fotos, textos de documentos ni mensajes");
    await p.click("#btn-pause"); await sleep(400);
    await stop(); await start(); await p.reload(); await sleep(1200);
    ok(await p.evaluate(() => App.paused), "tras reiniciar el servidor, sigue en pausa");
    await p.click("#btn-unpause"); await sleep(300);
    ok(errs.length === 0, "sin errores de JavaScript " + (errs.length ? JSON.stringify(errs.slice(0, 3)) : ""));
  } catch (e) {
    ok(false, "error inesperado: " + e.message);
  } finally {
    await browser.close(); await stop(); fs.rmSync(TMP, { recursive: true, force: true });
  }
  const f = R.filter(r => r[0] === "FALLA").length, pn = R.filter(r => r[0] === "PENDIENTE").length;
  console.log(`\n${R.filter(r => r[0] === "PASA").length} PASA · ${f} FALLA · ${pn} PENDIENTE (en el teléfono)`);
  process.exit(f ? 1 : 0);
}
main();
