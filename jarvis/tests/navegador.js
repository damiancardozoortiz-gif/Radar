// Pruebas de navegador de JARVIS: mapa, voz, PAUSA, Esc/Espacio y borradores (también tras reiniciar).
//
// Reproducible desde un entorno limpio: arranca su propio JARVIS en un puerto libre con estado/ y memory/
// temporales, un `claude` simulado (tests/fake_claude.py) y un ElevenLabs simulado (tests/mock_elevenlabs.py),
// y un micrófono falso de Chromium. No usa internet, ni tu suscripción, ni ElevenLabs reales.
//
// Requisito: Node 18+ y Playwright con Chromium (es una herramienta de pruebas, JARVIS no la necesita):
//   npm install --no-save playwright && npx playwright install chromium
// Uso:  node tests/navegador.js
"use strict";
const { chromium } = require("playwright");
const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const net = require("net");

const ROOT = path.resolve(__dirname, "..");
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), "jarvis-nav-"));
const results = [];
const ok = (cond, msg) => { results.push([!!cond, msg]); console.log((cond ? "PASA " : "FALLA") + "  " + msg); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const freePort = () => new Promise(res => { const s = net.createServer().listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => res(p)); }); });

// Micrófono falso: 1,5 s de "voz" y 2,5 s de silencio (WAV 16 kHz).
function micWav(file) {
  const rate = 16000, n = rate * 4, buf = Buffer.alloc(44 + n * 2);
  buf.write("RIFF", 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write("WAVEfmt ", 8); buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(rate, 24); buf.writeUInt32LE(rate * 2, 28);
  buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write("data", 36); buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) { const t = i / rate; const v = t < 1.5 ? Math.round(9000 * Math.sin(2 * Math.PI * 220 * t) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 4 * t))) : 0; buf.writeInt16LE(v, 44 + i * 2); }
  fs.writeFileSync(file, buf);
}

class Jarvis {
  constructor(port, mockPort) {
    this.port = port; this.url = `http://localhost:${port}`;
    this.claudeLog = path.join(TMP, "claude.log");
    this.env = Object.assign({}, process.env, {
      JARVIS_PORT: String(port), JARVIS_DEMO: "1",
      JARVIS_STATE_DIR: path.join(TMP, "estado"), JARVIS_MEMORY_DIR: path.join(TMP, "memory"),
      JARVIS_CLAUDE_BIN: path.join(ROOT, "tests", "fake_claude.py"), FAKE_CLAUDE_LOG: this.claudeLog, FAKE_CLAUDE_SLEEP: "0.3",
      ELEVENLABS_API_KEY: "clave-de-prueba-no-real", ELEVENLABS_BASE_URL: `http://127.0.0.1:${mockPort}`,
    });
  }
  async start() {
    this.p = spawn("python3", [path.join(ROOT, "agent", "main.py")], { cwd: ROOT, env: this.env, stdio: "ignore" });
    for (let i = 0; i < 100; i++) { try { if ((await fetch(this.url + "/api/status")).ok) return; } catch (_) {} await sleep(100); }
    throw new Error("JARVIS no arrancó");
  }
  async stop() { if (this.p && this.p.exitCode === null) { const done = new Promise(r => this.p.once("exit", r)); this.p.kill("SIGKILL"); await done; } }
  status() { return fetch(this.url + "/api/status").then(r => r.json()); }
  claudeStarts() { return fs.existsSync(this.claudeLog) ? fs.readFileSync(this.claudeLog, "utf8").split("\n").filter(l => l.startsWith("start")).length : 0; }
}

async function main() {
  if (!fs.existsSync(path.join(ROOT, "data", "demo", "meta.json"))) {
    await new Promise((res, rej) => spawn("python3", [path.join(ROOT, "data", "generate_demo.py")], { stdio: "ignore" }).on("exit", c => c ? rej(c) : res()));
  }
  const wav = path.join(TMP, "mic.wav"); micWav(wav);
  const mockPort = await freePort(), mockLog = path.join(TMP, "mock.log");
  const mock = spawn("python3", [path.join(ROOT, "tests", "mock_elevenlabs.py"), String(mockPort), mockLog], { env: Object.assign({}, process.env, { MOCK_TTS_SECONDS: "4" }), stdio: "ignore" });
  const J = new Jarvis(await freePort(), mockPort);
  await J.start();
  const browser = await chromium.launch({ args: ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream", `--use-file-for-fake-audio-capture=${wav}`, "--autoplay-policy=no-user-gesture-required"] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, permissions: ["microphone", "clipboard-read", "clipboard-write"] });
  const errs = [];
  const newPage = async () => { const p = await ctx.newPage(); p.on("pageerror", e => errs.push(String(e))); p.on("console", m => { if (m.type() === "error") errs.push(m.text()); }); await p.goto(J.url); await p.waitForFunction(() => window.Graph && Graph.nodes.length > 0); await sleep(500); return p; };
  try {
    // ------------------------------------------------------------ 1. mapa
    console.log("\n== mapa");
    let p = await newPage();
    const g = await p.evaluate(() => ({ n: Graph.nodes.length, e: Graph.edges.length, top: document.querySelectorAll("#top-list li").length, f: document.querySelectorAll("#filters button").length }));
    ok(g.n === 420 && g.e === 1056, `el mapa carga las notas de demo (${g.n} notas, ${g.e} enlaces)`);
    ok(g.top === 15 && g.f > 0, "lista de las más conectadas y filtros por tipo");
    const pos = await p.evaluate(() => { const n = Graph.order[0], k = Graph.cam.k; return [(n.x - Graph.cam.x) * k + Graph.w / 2, (n.y - Graph.cam.y) * k + Graph.h / 2]; });
    await p.mouse.move(pos[0], pos[1]); await sleep(200);
    ok(await p.evaluate(() => Graph.hover && Graph.hover.title === Graph.order[0].title), "pasar el ratón ilumina el punto");
    await p.mouse.click(pos[0], pos[1]); await sleep(600);
    const t1 = await p.textContent("#note-title");
    ok(t1 === (await p.evaluate(() => Graph.order[0].title)), `clic abre la nota («${t1}»)`);
    await p.click("#note-body a.wl[data-id]"); await sleep(500);
    ok((await p.textContent("#note-title")) !== t1, "los [[enlaces]] de la nota se pueden pulsar");
    await p.click("#filters button"); await sleep(100);
    ok((await p.evaluate(() => Graph.hidden.size)) === 1, "un filtro oculta un tipo");
    await p.close();
    // rendimiento con 2.500 notas sintéticas
    p = await ctx.newPage();
    const N = 2500, nodes = [], edges = []; let sd = 1; const rnd = () => (sd = (sd * 16807) % 2147483647) / 2147483647;
    for (let i = 0; i < N; i++) nodes.push({ id: "n" + i, title: "Nota " + i, type: "t" + (i % 8), degree: 0 });
    for (let i = 0; i < 6000; i++) { const a = Math.floor(rnd() * N), b = Math.floor(rnd() * N); if (a !== b) { edges.push(["n" + a, "n" + b]); nodes[a].degree++; nodes[b].degree++; } }
    await p.route("**/api/graph", r => r.fulfill({ json: { nodes, edges } }));
    await p.goto(J.url); await p.waitForFunction(() => window.Graph && Graph.nodes.length > 2000);
    const fps = await p.evaluate(async () => { Graph.alpha = 1; const t0 = performance.now(); let f = 0; await new Promise(r => { const g = () => { f++; performance.now() - t0 < 3000 ? requestAnimationFrame(g) : r(); }; g(); }); return f / 3; });
    ok(fps >= 30, `mapa de 2.500 notas fluido (${fps.toFixed(0)} fps, mínimo 30)`);
    await p.close();

    // ------------------------------------------------------------ 2. voz
    console.log("\n== voz (ElevenLabs simulado)");
    p = await newPage();
    await p.evaluate(() => { window.__log = []; let last = ""; setInterval(() => { const s = App.state + "|" + (Mic.rec ? Mic.rec.state : "-") + "|" + (Mic.deaf ? "sordo" : "oye"); if (s !== last) { window.__log.push(s); last = s; } }, 30); });
    await p.click("#btn-mic");
    await p.waitForFunction(() => App.state === "listening", null, { timeout: 10000 });
    ok(true, "pulsar el micrófono → escuchando");
    await p.waitForFunction(() => App.state === "speaking", null, { timeout: 30000 });
    ok(fs.readFileSync(mockLog, "utf8").includes('"STT"') && /claude/.test(await p.textContent("#answer-meta")), "transcribe (Scribe) y responde el cerebro");
    ok((await p.textContent("#answer-card")).includes("¿Qué tengo hoy?"), "muestra el texto que entendió");
    await p.waitForFunction(() => App.state === "listening", null, { timeout: 20000 });
    ok(true, "al terminar de hablar vuelve a escuchar solo");
    const log = await p.evaluate(() => window.__log);
    ok(!log.some(s => s.startsWith("speaking") && (s.includes("recording") || s.includes("oye"))), "el micrófono está sordo mientras habla (0 grabaciones)");
    const mlog = fs.readFileSync(mockLog, "utf8");
    ok(/"model_id": "scribe_v1", "language_code": "es"/.test(mlog), "Scribe con scribe_v1 y es");
    ok(/"model_id": "eleven_flash_v2_5", "language_code": "es"/.test(mlog), "voz con eleven_flash_v2_5 y es");
    ok(!mlog.includes("clave-de-prueba"), "la clave no aparece en los registros");
    ok(!(await p.content()).includes("clave-de-prueba"), "la clave nunca llega al navegador");

    // ------------------------------------------------------------ 2b. conversación por voz larga
    const sttBefore = fs.readFileSync(mockLog, "utf8").split('"STT"').length - 1;
    const t5 = Date.now();
    while ((fs.readFileSync(mockLog, "utf8").split('"STT"').length - 1) - sttBefore < 5 && Date.now() - t5 < 90000) await sleep(500);
    const turns = (fs.readFileSync(mockLog, "utf8").split('"STT"').length - 1) - sttBefore;
    ok(turns >= 5, `5 turnos de voz seguidos sin reiniciar nada (${turns} turnos)`);
    ok(await p.evaluate(() => Mic.conversation && !!Mic.stream), "el modo conversación sigue activo tras varios turnos");
    ok(/claude/.test(await p.textContent("#answer-meta")), "la última respuesta sigue viniendo del cerebro");

    // ------------------------------------------------------------ 3. Esc y Espacio
    console.log("\n== Esc y Espacio");
    await p.waitForFunction(() => App.state === "speaking", null, { timeout: 30000 });
    await p.evaluate(() => document.querySelector("#toast").classList.add("hidden"));
    await p.keyboard.press("Space"); await sleep(600);
    let r = await p.evaluate(() => ({ s: App.state, a: !!Voice.audio, toast: !document.querySelector("#toast").classList.contains("hidden") }));
    ok(!r.a && r.s === "listening" && !r.toast, "Espacio corta la voz y escucha, sin aviso falso");
    await p.waitForFunction(() => App.state === "speaking", null, { timeout: 30000 });
    await p.keyboard.press("Escape"); await sleep(600);
    r = await p.evaluate(() => ({ s: App.state, a: !!Voice.audio, c: Mic.conversation, toast: !document.querySelector("#toast").classList.contains("hidden") }));
    ok(!r.a && r.s === "idle" && !r.c && !r.toast, "Esc corta la voz y termina la conversación, sin aviso falso");

    // ------------------------------------------------------------ 4. PAUSA
    console.log("\n== PAUSA");
    await p.click("#btn-mic");
    await p.waitForFunction(() => App.state === "speaking", null, { timeout: 30000 });
    await sleep(300);
    await p.keyboard.press("p"); await sleep(600);
    r = await p.evaluate(() => ({ s: App.state, a: !!Voice.audio, rec: !!Mic.rec, c: Mic.conversation, banner: !document.querySelector("#pause-banner").classList.contains("hidden") }));
    ok(!r.a, "P corta la voz al instante");
    ok(!r.rec && !r.c, "P apaga el micrófono y sale del modo conversación");
    ok(r.banner && r.s === "paused", "aviso EN PAUSA y reactor «en pausa»");
    ok((await J.status()).pause.on, "el servidor está en pausa");
    const n0 = J.claudeStarts();
    await p.fill("#q", "Recuerda que esto no se guarda"); await p.press("#q", "Enter"); await sleep(600);
    const nmem = fs.existsSync(path.join(TMP, "memory")) ? fs.readdirSync(path.join(TMP, "memory")).length : 0;
    ok(J.claudeStarts() === n0 && nmem === 0, "en pausa una pregunta escrita no llama al cerebro ni guarda memoria");
    await p.click("#btn-mic"); await sleep(400);
    ok(await p.evaluate(() => !Mic.conversation && !Mic.rec), "en pausa el micrófono no arranca");
    // reinicio con la pausa activa
    await J.stop(); await J.start(); await p.reload(); await sleep(1200);
    ok(await p.evaluate(() => App.paused && !document.querySelector("#pause-banner").classList.contains("hidden")), "tras reiniciar el servidor, la página sigue EN PAUSA");
    ok(J.claudeStarts() === n0, "tras reiniciar en pausa no se llama a claude (ni para calentar)");
    await p.click("#btn-unpause"); await sleep(600);
    ok(await p.evaluate(() => App.state === "idle" && !App.paused), "quitar la pausa vuelve a «en espera»");
    await p.close();

    // ------------------------------------------------------------ 5. borradores
    console.log("\n== borradores y autorización humana");
    p = await newPage();
    await p.click("#btn-mute"); // que no hable encima
    await p.fill("#q", "Escribe un borrador de correo para Ricardo"); await p.press("#q", "Enter");
    await p.waitForSelector(".approval button[data-decision='aprobar']", { timeout: 15000 });
    ok(true, "el borrador aparece pendiente de tu sí");
    const pend = (await J.status()).approvals;
    ok(pend.length === 1, "el servidor tiene 1 borrador pendiente");
    const tts0 = fs.readFileSync(mockLog, "utf8").split("TTS").length;
    const nb = J.claudeStarts();
    await J.stop(); await J.start(); await p.reload(); await sleep(1500);
    ok(await p.isVisible(".approval button[data-decision='aprobar']"), "tras reiniciar, el borrador sigue en pantalla pendiente");
    const pend2 = (await J.status()).approvals;
    ok(pend2.length === 1 && pend2[0].id === pend[0].id, "mismo borrador, sin duplicar");
    const after = fs.readFileSync(J.claudeLog, "utf8").split("\n").filter(l => l.startsWith("start")).slice(nb);
    ok(!after.some(l => l.includes("borrador=True")), "al recuperar no se ejecuta el borrador");
    ok(fs.readFileSync(mockLog, "utf8").split("TTS").length === tts0, "al recuperar no se dice nada en voz alta");
    await p.fill("#q", "sí"); await p.press("#q", "Enter"); await sleep(800);
    ok((await J.status()).approvals.length === 1, "decir «sí» no aprueba");
    await p.reload(); await sleep(1200);
    await p.click(".approval button[data-decision='aprobar']"); await sleep(600);
    const st = await p.textContent(".approval .state");
    ok(/APROBADO/.test(st) && /No se ha enviado nada/.test(st), "clic en Aprobar: aprobado y NO enviado");
    ok(/Ricardo/.test(await p.evaluate(() => navigator.clipboard.readText())), "el texto queda copiado para que lo envíes tú");
    ok((await J.status()).approvals.length === 0, "ya no queda pendiente");
    await p.close();
    ok(errs.length === 0, "sin errores de JavaScript " + (errs.length ? JSON.stringify(errs.slice(0, 3)) : ""));
  } catch (e) {
    ok(false, "error inesperado: " + e.message);
  } finally {
    await browser.close(); await J.stop(); mock.kill();
    fs.rmSync(TMP, { recursive: true, force: true });
  }
  const fails = results.filter(r => !r[0]).length;
  console.log(`\n${results.length - fails} de ${results.length} pasan` + (fails ? ` · ${fails} FALLAN` : ""));
  process.exit(fails ? 1 : 0);
}
main();
