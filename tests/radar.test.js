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
  const errores = []; const llamadas = [];
  p.on('pageerror', e => errores.push(e.message));
  await p.route(/accounts\.google\.com|ytimg\.com/, r => r.abort());
  await p.route(/googleapis\.com\/youtube\/v3\//, r => {
    const u = new URL(r.request().url()); const que = u.pathname.split('/').pop(); llamadas.push(que);
    const cuerpo = que === 'search' ? { items: videos().map(v => ({ id: { videoId: v.id } })) } : que === 'videos' ? { items: videos() } : { items: canales() };
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

  console.log('Studio');
  await p.click('nav [data-view="studio"]'); await p.click('.s3t [data-p="s3a"]');
  await p.fill('#s3t', 'Cómo entrenar la disciplina: 7 hábitos que funcionan');
  ok(/^\d+\/100$/.test(await p.textContent('#s3sc')) && await p.locator('#s3vr button').count() === 4, 'puntuación y variantes de título');
  ok(!(await p.textContent('#s3vr')).includes('Cómo Cómo'), 'las variantes no repiten "Cómo"');
  await p.click('#s3ptb'); ok((await p.textContent('#s3pt')).includes('más rápido crecen'), 'patrones del Radar');
  await p.click('.s3t [data-p="s3p"]'); ok((await p.textContent('#s3ps')).includes('todavía sin video'), 'pestaña Publicar');
  ok(errores.length === 0, 'sin errores de JavaScript al final: ' + errores.join(' | '));

  await nav.close(); servidor.close();
  console.log(fallos ? `\n${fallos} FALLOS` : '\nTodo bien');
  process.exit(fallos ? 1 : 0);
})().catch(e => { console.error(e); process.exit(1); });
