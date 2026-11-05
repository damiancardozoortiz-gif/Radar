/* El mapa en canvas (no SVG: con SVG se traba pasadas las 1.500 notas).
 * Fuerzas con rejilla espacial (repulsión O(n) aprox.), respiración suave,
 * hover que ilumina vecinos, etiquetas sin solaparse (primero las más conectadas). */
(function () {
  "use strict";

  // Tonos apagados por tipo. El acento (#5cc8d6) se reserva para lo activo.
  const PALETTE = ["#6f8597", "#8a7a9b", "#7d9478", "#a08a6c", "#94707a", "#6c8f8c",
                   "#8b8f6a", "#7a7fa3", "#9c7f6a", "#6f8a7a", "#8f7590", "#7b8a9e"];
  const ACCENT = "#5cc8d6";
  const CELL = 70;

  const G = {
    nodes: [], edges: [], byId: new Map(), adj: new Map(),
    typeColor: {}, hidden: new Set(),
    cam: { x: 0, y: 0, k: 1 }, alpha: 1,
    hover: null, selected: null, onClick: null,
    canvas: null, ctx: null, w: 0, h: 0, dpr: 1, t0: performance.now(),
    labelW: new Map(), dragging: null,
  };

  function hash(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return (h >>> 0) / 4294967296; }

  G.colorFor = function (type) { return G.typeColor[type] || "#6f8597"; };

  G.init = function (canvas, data) {
    G.canvas = canvas; G.ctx = canvas.getContext("2d");
    const types = [...new Set(data.nodes.map(n => n.type))].sort();
    types.forEach((t, i) => G.typeColor[t] = PALETTE[i % PALETTE.length]);
    const typeAngle = {}; types.forEach((t, i) => typeAngle[t] = (i / types.length) * Math.PI * 2);
    const spread = Math.sqrt(data.nodes.length) * 14;
    G.nodes = data.nodes.map(n => {
      const a = typeAngle[n.type] + (hash(n.id) - 0.5) * 1.6;
      const d = spread * (0.35 + 0.65 * hash(n.id + "r"));
      return { ...n, x: Math.cos(a) * d, y: Math.sin(a) * d, vx: 0, vy: 0,
               r: 2.2 + Math.min(Math.sqrt(n.degree) * 1.05, 13), ph: hash(n.id + "p") * 6.28 };
    });
    G.nodes.forEach(n => { G.byId.set(n.id, n); G.adj.set(n.id, new Set()); });
    G.edges = data.edges.map(([a, b]) => [G.byId.get(a), G.byId.get(b)]).filter(e => e[0] && e[1]);
    G.edges.forEach(([a, b]) => { G.adj.get(a.id).add(b.id); G.adj.get(b.id).add(a.id); });
    G.order = [...G.nodes].sort((a, b) => b.degree - a.degree);
    G.alpha = 1;
    resize(); bind();
    for (let i = 0; i < 120; i++) tick(); // pre-asentado para que no "explote" al abrir
    fit();
    requestAnimationFrame(frame);
    window.addEventListener("resize", resize);
  };

  function resize() {
    G.dpr = window.devicePixelRatio || 1;
    G.w = window.innerWidth; G.h = window.innerHeight;
    G.canvas.width = G.w * G.dpr; G.canvas.height = G.h * G.dpr;
  }

  function fit() {
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const n of G.nodes) { minX = Math.min(minX, n.x); maxX = Math.max(maxX, n.x); minY = Math.min(minY, n.y); maxY = Math.max(maxY, n.y); }
    const bw = maxX - minX || 1, bh = maxY - minY || 1;
    const availW = G.w - 660, availH = G.h - 150;
    G.cam.k = Math.max(0.15, Math.min(2, Math.min(Math.max(availW, 300) / bw, availH / bh) * 0.92));
    G.cam.x = (minX + maxX) / 2; G.cam.y = (minY + maxY) / 2;
  }

  // ---------- simulación ----------
  function tick() {
    const nodes = G.nodes, a = G.alpha;
    const grid = new Map();
    for (const n of nodes) {
      const key = Math.floor(n.x / CELL) + "," + Math.floor(n.y / CELL);
      let c = grid.get(key); if (!c) grid.set(key, c = []); c.push(n);
    }
    for (const n of nodes) {
      const cx = Math.floor(n.x / CELL), cy = Math.floor(n.y / CELL);
      for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
        const c = grid.get((cx + dx) + "," + (cy + dy)); if (!c) continue;
        for (const m of c) {
          if (m === n) continue;
          let x = n.x - m.x, y = n.y - m.y, d2 = x * x + y * y;
          if (d2 > CELL * CELL) continue;
          if (d2 < 0.01) { x = hash(n.id + m.id) - 0.5; y = 0.3; d2 = 0.1; }
          const f = (90 + n.r * m.r * 4) / d2 * a;
          n.vx += x * f * 0.5; n.vy += y * f * 0.5;
        }
      }
    }
    for (const [s, t] of G.edges) {
      const x = t.x - s.x, y = t.y - s.y, d = Math.sqrt(x * x + y * y) || 1;
      const len = 26 + (s.r + t.r) * 1.6;
      const f = (d - len) / d * 0.045 * a;
      const ws = 1 / Math.max(1, Math.sqrt(s.degree)), wt = 1 / Math.max(1, Math.sqrt(t.degree));
      s.vx += x * f * ws; s.vy += y * f * ws; t.vx -= x * f * wt; t.vy -= y * f * wt;
    }
    for (const n of nodes) {
      n.vx -= n.x * 0.0035 * a; n.vy -= n.y * 0.0035 * a;
      if (n !== G.dragging) { n.x += n.vx; n.y += n.vy; }
      n.vx *= 0.82; n.vy *= 0.82;
    }
    G.alpha = Math.max(0.012, G.alpha * 0.992);
  }

  // ---------- coordenadas ----------
  function breath() { return 1 + 0.008 * Math.sin((performance.now() - G.t0) / 1700); }
  function toScreen(x, y) { const k = G.cam.k * breath(); return [(x - G.cam.x) * k + G.w / 2, (y - G.cam.y) * k + G.h / 2]; }
  function toWorld(sx, sy) { const k = G.cam.k * breath(); return [(sx - G.w / 2) / k + G.cam.x, (sy - G.h / 2) / k + G.cam.y]; }
  function visible(n) { return !G.hidden.has(n.type); }

  function nodeAt(sx, sy) {
    const [wx, wy] = toWorld(sx, sy);
    let best = null, bd = Infinity;
    for (const n of G.nodes) {
      if (!visible(n)) continue;
      const dx = n.x - wx, dy = n.y - wy, d = dx * dx + dy * dy;
      const rr = Math.max(n.r, 6 / G.cam.k); if (d < rr * rr && d < bd) { bd = d; best = n; }
    }
    return best;
  }

  // ---------- dibujo ----------
  function frame() {
    if (G.alpha > 0.013) tick();
    draw();
    requestAnimationFrame(frame);
  }

  function draw() {
    const ctx = G.ctx, k = G.cam.k * breath(), t = performance.now() - G.t0;
    ctx.setTransform(G.dpr, 0, 0, G.dpr, 0, 0);
    ctx.clearRect(0, 0, G.w, G.h);
    const focus = G.hover || G.selected;
    const near = focus ? G.adj.get(focus.id) : null;

    // aristas: un único path para todas (rápido)
    ctx.lineWidth = Math.max(0.35, 0.6 * Math.min(k, 1.5));
    ctx.strokeStyle = focus ? "rgba(120,135,150,0.05)" : "rgba(120,135,150,0.13)";
    ctx.beginPath();
    for (const [a, b] of G.edges) {
      if (!visible(a) || !visible(b)) continue;
      const [x1, y1] = toScreen(a.x, a.y), [x2, y2] = toScreen(b.x, b.y);
      ctx.moveTo(x1, y1); ctx.lineTo(x2, y2);
    }
    ctx.stroke();
    if (focus) {
      ctx.strokeStyle = "rgba(92,200,214,0.55)"; ctx.lineWidth = 1;
      ctx.beginPath();
      for (const id of near) {
        const b = G.byId.get(id); if (!visible(b)) continue;
        const [x1, y1] = toScreen(focus.x, focus.y), [x2, y2] = toScreen(b.x, b.y);
        ctx.moveTo(x1, y1); ctx.lineTo(x2, y2);
      }
      ctx.stroke();
    }

    // nodos
    for (const n of G.nodes) {
      if (!visible(n)) continue;
      const [x, y] = toScreen(n.x, n.y);
      if (x < -20 || y < -20 || x > G.w + 20 || y > G.h + 20) continue;
      const r = Math.max(1.4, n.r * Math.min(Math.max(k, 0.45), 1.8)) * (1 + 0.04 * Math.sin(t / 900 + n.ph));
      let col = G.colorFor(n.type), al = 0.9;
      if (focus && n !== focus && !near.has(n.id)) al = 0.14;
      if (n === focus || n === G.selected) col = ACCENT, al = 1;
      ctx.globalAlpha = al; ctx.fillStyle = col;
      ctx.beginPath(); ctx.arc(x, y, r, 0, 6.2832); ctx.fill();
      if (n === focus) {
        ctx.globalAlpha = 0.25; ctx.strokeStyle = ACCENT; ctx.lineWidth = 1;
        ctx.beginPath(); ctx.arc(x, y, r + 5, 0, 6.2832); ctx.stroke();
      }
    }
    ctx.globalAlpha = 1;
    drawLabels(focus, near, k);
  }

  function drawLabels(focus, near, k) {
    const ctx = G.ctx;
    ctx.font = "11px ui-sans-serif, -apple-system, Segoe UI, Roboto, sans-serif";
    ctx.textBaseline = "middle";
    const placed = [];
    const max = focus ? 40 : Math.round(18 + 30 * Math.min(k, 2));
    // prioridad: foco, sus vecinos, luego las más conectadas
    const queue = [];
    if (focus) { queue.push(focus); for (const id of near) queue.push(G.byId.get(id)); }
    for (const n of G.order) queue.push(n);
    const seen = new Set();
    let count = 0;
    for (const n of queue) {
      if (count >= max) break;
      if (seen.has(n) || !visible(n)) continue; seen.add(n);
      if (focus && n !== focus && !near.has(n.id) && count > 0) continue;
      if (!focus && n.degree < 2 && k < 1.6) continue;
      const [x, y] = toScreen(n.x, n.y);
      if (x < 0 || y < 0 || x > G.w || y > G.h) continue;
      let w = G.labelW.get(n.id);
      if (w === undefined) { w = ctx.measureText(n.title).width; G.labelW.set(n.id, w); }
      const r = n.r * Math.min(Math.max(k, 0.45), 1.8);
      const box = [x + r + 4, y - 7, w + 4, 14];
      let hit = false;
      for (const p of placed) {
        if (box[0] < p[0] + p[2] && box[0] + box[2] > p[0] && box[1] < p[1] + p[3] && box[1] + box[3] > p[1]) { hit = true; break; }
      }
      if (hit) continue; // se salta la que choca
      placed.push(box); count++;
      ctx.fillStyle = n === focus ? ACCENT : (focus ? "rgba(215,222,228,0.9)" : "rgba(170,180,190,0.72)");
      ctx.fillText(n.title, box[0] + 2, y);
    }
  }

  // ---------- interacción ----------
  function bind() {
    const c = G.canvas;
    let down = null, moved = false;
    c.addEventListener("mousedown", e => {
      down = { x: e.clientX, y: e.clientY, cx: G.cam.x, cy: G.cam.y, node: nodeAt(e.clientX, e.clientY) };
      moved = false;
      if (down.node) { G.dragging = down.node; G.alpha = Math.max(G.alpha, 0.25); }
      c.classList.add("drag");
    });
    window.addEventListener("mousemove", e => {
      if (down) {
        const dx = e.clientX - down.x, dy = e.clientY - down.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) moved = true;
        if (G.dragging) { const [wx, wy] = toWorld(e.clientX, e.clientY); G.dragging.x = wx; G.dragging.y = wy; G.alpha = Math.max(G.alpha, 0.2); }
        else { G.cam.x = down.cx - dx / G.cam.k; G.cam.y = down.cy - dy / G.cam.k; }
        return;
      }
      if (e.target !== c) { if (G.hover) G.hover = null; return; }
      G.hover = nodeAt(e.clientX, e.clientY);
      c.classList.toggle("hover", !!G.hover);
    });
    window.addEventListener("mouseup", e => {
      if (down && !moved && e.target === c) {
        const n = nodeAt(e.clientX, e.clientY);
        if (n && G.onClick) G.onClick(n);
      }
      down = null; G.dragging = null; c.classList.remove("drag");
    });
    c.addEventListener("mouseleave", () => { G.hover = null; });
    c.addEventListener("wheel", e => {
      e.preventDefault();
      const [wx, wy] = toWorld(e.clientX, e.clientY);
      G.cam.k = Math.max(0.08, Math.min(6, G.cam.k * Math.exp(-e.deltaY * 0.0015)));
      const [wx2, wy2] = toWorld(e.clientX, e.clientY);
      G.cam.x += wx - wx2; G.cam.y += wy - wy2;
    }, { passive: false });
  }

  // ---------- API pública ----------
  G.setHidden = function (set) { G.hidden = set; };
  G.select = function (id, center) {
    const n = G.byId.get(id); G.selected = n || null;
    if (n && center) animateTo(n.x, n.y, Math.max(G.cam.k, 1.1));
  };
  function animateTo(x, y, k) {
    const s = { x: G.cam.x, y: G.cam.y, k: G.cam.k }, t0 = performance.now();
    (function step() {
      const p = Math.min(1, (performance.now() - t0) / 450), e = 1 - Math.pow(1 - p, 3);
      G.cam.x = s.x + (x - s.x) * e; G.cam.y = s.y + (y - s.y) * e; G.cam.k = s.k + (k - s.k) * e;
      if (p < 1) requestAnimationFrame(step);
    })();
  }

  window.Graph = G;
})();
