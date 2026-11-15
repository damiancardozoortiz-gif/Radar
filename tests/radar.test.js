/* Prueba de punta a punta de Radar YouTube, con la API de YouTube simulada.
   Uso: node tests/radar.test.js   (necesita Playwright con Chromium: npm i playwright && npx playwright install chromium) */
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');

const RAIZ = path.join(__dirname, '..');
const TIPOS = { '.html': 'text/html', '.js': 'text/javascript', '.png': 'image/png', '.webmanifest': 'application/manifest+json' };
const servidor = http.createServer((req, res) => {
  const f = path.join(RAIZ, decodeURIComponent(req.url.split('?')[0]).replace(/^\/$/, '/index.html'));
  if (!f.startsWith(RAIZ) || !fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': TIPOS[path.extname(f)] || 'application/octet-stream' }); res.end(fs.readFileSync(f));
});

let fallos = 0;
const ok = (cond, nombre) => { console.log((cond ? '  ✓ ' : '  ✗ ') + nombre); if (!cond) fallos++; };

/* Ocho videos; el 3 pertenece a un canal pequeño y por eso es el que más supera a su canal. */
let ronda = 0;
const videos = () => Array.from({ length: 8 }, (_, i) => ({
  id: 'vid0000000' + i,
  snippet: { title: `Video ${i} <b>con</b> "comillas"`, channelTitle: 'Canal ' + i, channelId: 'canal' + i, publishedAt: new Date(Date.now() - 3600e3 * (i + 2)).toISOString() },
  statistics: { viewCount: String(40000 * (i + 1) + ronda * 9000 * (i + 1)), likeCount: String(1000 * (8 - i)) },
  contentDetails: { duration: i % 2 ? 'PT45S' : 'PT12M5S' },
}));
const canales = () => Array.from({ length: 8 }, (_, i) => ({ id: 'canal' + i, statistics: i === 5 ? { hiddenSubscriberCount: true } : { subscriberCount: String(i === 3 ? 2000 : 500000) } }));

(async () => {
  await new Promise(r => servidor.listen(0, r));
  const base = 'http://localhost:' + servidor.address().port;
  const nav = await chromium.launch();
  const p = await nav.newPage({ viewport: { width: 390, height: 844 } });
  const errores = []; const llamadas = []; const pedidos = [];
  p.on('pageerror', e => errores.push(e.message));
  await p.route(/accounts\.google\.com|ytimg\.com/, r => r.abort());
  await p.route(/googleapis\.com\/youtube\/v3\//, r => {
    const u = new URL(r.request().url()); const que = u.pathname.split('/').pop(); llamadas.push(que);
    if (que === 'playlistItems') { pedidos.push(u.searchParams.get('playlistId')); return r.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: videos().slice(0, 2).map(v => ({ contentDetails: { videoId: v.id } })) }) }); }
    if (que === 'channels' && (u.searchParams.get('forHandle') || u.searchParams.get('part') === 'snippet')) {
      pedidos.push(u.searchParams.get('forHandle') || u.searchParams.get('id'));
      const existe = u.searchParams.get('forHandle') !== '@noexiste';
      return r.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: existe ? [{ id: 'UC' + 'x'.repeat(22), snippet: { title: 'Canal Rival' } }] : [] }) });
    }
    const cuerpo = que === 'search' ? { items: videos().map(v => ({ id: { videoId: v.id } })) } : que === 'videos' ? { items: videos().filter(v => (u.searchParams.get('id') || '').split(',').includes(v.id)) } : { items: canales() };
    r.fulfill({ contentType: 'application/json', body: JSON.stringify(cuerpo) });
  });

  console.log('Arranque');
  await p.goto(base + '/index.html'); await p.waitForTimeout(500);
  ok(errores.length === 0, 'sin errores de JavaScript: ' + errores.join(' | '));
  ok(await p.locator('nav button').count() === 6, 'las seis pestañas, con el Copiloto');

  console.log('Búsqueda');
  await p.click('nav [data-view="settings"]'); await p.fill('#apiKey', 'clave-de-prueba');
  p.once('dialog', d => d.accept()); await p.click('#saveKey');
  await p.click('nav [data-view="radar"]'); await p.fill('#topic', 'estoicismo'); await p.click('#search');
  await p.waitForFunction(() => document.querySelectorAll('#results .video').length === 8);
  ok(llamadas.join() === 'search,videos,channels', 'pide búsqueda, videos y canales: ' + llamadas.join());
  ok(!(await p.innerHTML('#results')).includes('<b>con</b>'), 'los títulos no se interpretan como HTML');
  ok((await p.textContent('#results')).includes('Suscriptores ocultos'), 'canal con suscriptores ocultos');
  await p.selectOption('#sort', 'outlier');
  const primero = await p.textContent('#results .video:first-child');
  ok(primero.includes('Video 3') && primero.includes('80× su canal'), 'el que más supera a su canal va primero: ' + primero.replace(/\s+/g, ' ').slice(0, 90));
  await p.selectOption('#sort', 'engage');
  ok((await p.textContent('#results .video:first-child')).includes('Video 0'), 'orden por "me gusta" por vista');

  console.log('Crecimiento medido');
  await p.evaluate(() => { const s = JSON.parse(localStorage.radar_snaps); for (const k in s) { s[k].t -= 3600e3; s[k].h = [[s[k].t - 3600e3, s[k].views - 5000], [s[k].t, s[k].views]]; } localStorage.radar_snaps = JSON.stringify(s); });
  await p.reload(); ronda = 1; await p.fill('#topic', 'estoicismo'); await p.click('#search');
  await p.waitForFunction(() => document.querySelector('#results')?.textContent.includes('Cambio medido'));
  ok(await p.locator('#results svg.spark').count() === 8, 'mini gráfico de evolución en cada tarjeta');
  ok(/\+(8\.99\d|9\.000) vistas\/h/.test(await p.textContent('#results')), 'crecimiento por hora calculado');

  if (process.env.CAPTURA) await p.screenshot({ path: process.env.CAPTURA, fullPage: true });
  console.log('Radares guardados y accesos directos');
  await p.click('#saveRadar'); await p.click('#saveRadar');
  ok(await p.locator('#savedRadars .chip').count() === 1, 'se guarda una sola vez');
  await p.fill('#topic', 'otra cosa'); const antes = llamadas.length; await p.click('#savedRadars [data-radar]');
  await p.waitForFunction(() => !document.querySelector('#search').disabled); await p.waitForTimeout(200);
  ok(await p.inputValue('#topic') === 'estoicismo' && llamadas.length > antes, 'tocar el radar guardado repite la búsqueda');
  await p.click('#results [data-fav]');
  await p.goto(base + '/index.html?view=saved'); await p.waitForTimeout(300);
  ok(await p.isVisible('#favorites') && await p.locator('#favResults .video').count() === 1, '?view=saved abre Favoritos con el video guardado');

  await p.click('nav [data-view="studio"]'); await p.click('.s3t [data-p="s3a"]'); await p.click('#s3ptb');
  ok((await p.textContent('#s3pt')).includes('más rápido crecen'), 'patrones del Radar en Studio');
  console.log('Canales que sigo');
  await p.goto(base + '/index.html'); await p.waitForTimeout(300);
  ok(await p.isDisabled('#chanScan'), 'sin canales, el botón de ver videos está apagado');
  await p.fill('#chanInput', 'cualquier cosa'); await p.click('#chanAdd');
  ok((await p.textContent('#radarStatus')).includes('No reconozco'), 'un texto que no es un canal se rechaza sin gastar cuota');
  await p.fill('#chanInput', 'https://www.youtube.com/@noexiste'); await p.click('#chanAdd');
  await p.waitForFunction(() => document.querySelector('#radarStatus').textContent.includes('no encontró'));
  ok(await p.locator('#chanList .chip').count() === 0, 'un canal que no existe no se guarda');
  await p.fill('#chanInput', 'https://www.youtube.com/@CanalRival/videos'); await p.press('#chanInput', 'Enter');
  await p.waitForFunction(() => document.querySelectorAll('#chanList .chip').length === 1);
  ok(pedidos.includes('@CanalRival') && (await p.textContent('#chanList')).includes('Canal Rival'), 'se sigue un canal por su @usuario');
  await p.fill('#chanInput', '@CanalRival'); await p.click('#chanAdd');
  await p.waitForFunction(() => document.querySelector('#radarStatus').textContent.includes('Ya seguías'));
  ok(await p.locator('#chanList .chip').count() === 1, 'el mismo canal no se guarda dos veces');
  await p.click('#results [data-follow="canal2"]');
  ok(await p.locator('#chanList .chip').count() === 2 && (await p.textContent('#results [data-follow="canal2"]')).includes('Siguiendo'), 'seguir un canal desde una tarjeta');
  await p.click('#results [data-follow="canal2"]');
  ok(await p.locator('#chanList .chip').count() === 1, 'y dejar de seguirlo desde la misma tarjeta');
  const antesCanales = llamadas.length; await p.click('#chanScan');
  await p.waitForFunction(() => document.querySelector('#radarStatus').textContent.includes('Últimos 2 videos'));
  ok(pedidos.includes('UU' + 'x'.repeat(22)) && !llamadas.slice(antesCanales).includes('search'), 'lee la lista de subidas del canal sin usar la búsqueda');
  ok(await p.locator('#results .video').count() === 2, 'el ranking muestra los videos de los canales seguidos');
  await p.reload(); ok(await p.locator('#chanList .chip').count() === 1, 'los canales seguidos se conservan al recargar');

  console.log('Studio: exportar un recorte de verdad');
  await p.click('nav [data-view="studio"]');
  ok(await p.evaluate(async () => {            // un video de 2 s hecho en el propio navegador, en horizontal
    const c = document.createElement('canvas'); c.width = 640; c.height = 360; const x = c.getContext('2d');
    const rec = new MediaRecorder(c.captureStream(30), { mimeType: 'video/webm' }); const trozos = [];
    rec.ondataavailable = e => trozos.push(e.data);
    const fin = new Promise(r => rec.onstop = r); rec.start(); const t0 = performance.now();
    await new Promise(r => (function d() { const t = performance.now() - t0; x.fillStyle = `hsl(${t / 6},80%,50%)`; x.fillRect(0, 0, 640, 360); t < 2000 ? requestAnimationFrame(d) : r(); })());
    rec.stop(); await fin;
    const dt = new DataTransfer(); dt.items.add(new File(trozos, 'prueba.webm', { type: 'video/webm' }));
    const f = document.getElementById('s3f'); f.files = dt.files; f.dispatchEvent(new Event('change', { bubbles: true }));
    const v = document.getElementById('s3vid'); await new Promise(r => v.readyState >= 1 ? r() : v.addEventListener('loadedmetadata', r, { once: true }));
    return v.videoWidth === 640;
  }), 'se carga un video en el editor');
  await p.evaluate(() => { document.getElementById('s3vid').currentTime = 1.2; }); await p.waitForTimeout(300); await p.click('#s3bi');
  await p.evaluate(() => { document.getElementById('s3vid').currentTime = 0.4; }); await p.waitForTimeout(300); await p.click('#s3bo');
  await p.click('#s3ex');
  ok((await p.textContent('#s3st')).includes('después del inicio'), 'un tramo al revés no se exporta');
  await p.click('#s3bi'); await p.evaluate(() => { document.getElementById('s3vid').currentTime = 1.4; }); await p.waitForTimeout(300); await p.click('#s3bo');
  await p.selectOption('#s3fm', 'v'); await p.fill('#s3tx', 'Hola');
  const [bajada] = await Promise.all([p.waitForEvent('download', { timeout: 20000 }), p.click('#s3ex')]);
  const recorte = await bajada.path();
  ok(/^radar-recorte\.(webm|mp4)$/.test(bajada.suggestedFilename()) && fs.statSync(recorte).size > 2000, 'el recorte se exporta y se descarga (' + fs.statSync(recorte).size + ' bytes)');
  await p.waitForFunction(() => document.querySelector('#s3st').textContent.startsWith('Listo'));
  ok(!(await p.isDisabled('#s3ex')), 'el botón de exportar vuelve a estar disponible');
  const medidas = await p.evaluate(async bytes => {
    const v = document.createElement('video'); v.src = URL.createObjectURL(new Blob([new Uint8Array(bytes)], { type: 'video/webm' }));
    await new Promise((r, n) => { v.onloadedmetadata = r; v.onerror = () => n(new Error('no se puede leer')); });
    return v.videoWidth + 'x' + v.videoHeight;
  }, [...fs.readFileSync(recorte)]);
  ok(medidas === '202x360', 'el recorte vertical sale en 9:16 sin deformar: ' + medidas);
  const [mini] = await Promise.all([p.waitForEvent('download', { timeout: 10000 }), p.click('#s3th')]);
  const png = fs.readFileSync(await mini.path());
  ok(png.readUInt32BE(16) === 1280 && png.readUInt32BE(20) === 720, 'la miniatura es un PNG de 1280×720');

  console.log('Studio');
  await p.click('nav [data-view="studio"]'); await p.click('.s3t [data-p="s3a"]');
  await p.fill('#s3t', 'Cómo entrenar la disciplina: 7 hábitos que funcionan');
  ok(/^\d+\/100$/.test(await p.textContent('#s3sc')) && await p.locator('#s3vr button').count() === 4, 'puntuación y variantes de título');
  ok(!(await p.textContent('#s3vr')).includes('Cómo Cómo'), 'las variantes no repiten "Cómo"');
  await p.click('#s3ptb'); ok((await p.textContent('#s3pt')).includes('al menos 5'), 'con 2 videos en el Radar, los patrones piden más');
  await p.click('.s3t [data-p="s3p"]'); ok((await p.textContent('#s3ps')).includes('video cargado'), 'la pestaña Publicar reconoce el video cargado');
  ok(errores.length === 0, 'sin errores de JavaScript al final: ' + errores.join(' | '));

  await nav.close(); servidor.close();
  console.log(fallos ? `\n${fallos} FALLOS` : '\nTodo bien');
  process.exit(fallos ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
