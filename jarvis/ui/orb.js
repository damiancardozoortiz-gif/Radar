/* El orbe: la cara de JARVIS. Un punto de luz dentro de una esfera de partículas, dibujado en un canvas 2D.
   Cada estado tiene su color, su velocidad y su energía; la voz (la tuya y la suya) lo hace latir. */
"use strict";

const Orb = (() => {
  const N = 1300;
  const ESTADOS = {
    //            color RGB          giro  ruido  vel.ruido  brillo
    idle:      { c: [94, 228, 255],  s: 0.10, a: 0.025, v: 0.5, g: 0.55 },
    listening: { c: [120, 240, 255], s: 0.22, a: 0.05,  v: 1.2, g: 0.85 },
    thinking:  { c: [150, 170, 255], s: 0.75, a: 0.10,  v: 2.4, g: 0.80 },
    speaking:  { c: [94, 228, 255],  s: 0.30, a: 0.06,  v: 1.6, g: 1.00 },
    acting:    { c: [94, 255, 196],  s: 0.50, a: 0.07,  v: 1.8, g: 0.90 },
    waiting:   { c: [255, 184, 77],  s: 0.14, a: 0.04,  v: 0.9, g: 0.95 },
    paused:    { c: [255, 96, 96],   s: 0.02, a: 0.008, v: 0.2, g: 0.35 },
  };
  const O = { state: "idle", level: 0 };
  let cv, ctx, w = 0, h = 0, dpr = 1, pts = [], yaw = 0, tNoise = 0, last = 0, sat = 0;
  const cur = { c: [94, 228, 255], s: 0.1, a: 0.025, v: 0.5, g: 0.55, lvl: 0 };
  const calm = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  O.init = canvas => {
    cv = canvas; ctx = cv.getContext("2d");
    const gold = Math.PI * (3 - Math.sqrt(5));
    for (let i = 0; i < N; i++) {            // puntos repartidos por igual sobre la esfera
      const y = 1 - (i / (N - 1)) * 2, r = Math.sqrt(1 - y * y), th = gold * i;
      pts.push({ x: Math.cos(th) * r, y, z: Math.sin(th) * r, k: Math.random() });
    }
    new ResizeObserver(resize).observe(cv);
    resize();
    requestAnimationFrame(frame);
  };

  function resize() {
    dpr = Math.min(2, window.devicePixelRatio || 1);
    w = cv.clientWidth; h = cv.clientHeight;
    cv.width = Math.max(1, Math.round(w * dpr)); cv.height = Math.max(1, Math.round(h * dpr));
  }

  const mix = (a, b, k) => a + (b - a) * k;

  function frame(now) {
    const dt = Math.min(0.05, (now - last) / 1000 || 0.016); last = now;
    const tg = ESTADOS[O.state] || ESTADOS.idle, k = 1 - Math.exp(-dt * 4);
    for (let i = 0; i < 3; i++) cur.c[i] = mix(cur.c[i], tg.c[i], k);
    cur.s = mix(cur.s, tg.s, k); cur.a = mix(cur.a, tg.a, k); cur.v = mix(cur.v, tg.v, k); cur.g = mix(cur.g, tg.g, k);
    cur.lvl = mix(cur.lvl, Math.max(0, Math.min(1, O.level || 0)), 1 - Math.exp(-dt * 14));
    const slow = calm ? 0.25 : 1;
    yaw += dt * cur.s * slow; tNoise += dt * cur.v * slow; sat += dt * (0.6 + cur.s * 2) * slow;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    if (w < 40 || h < 40) return requestAnimationFrame(frame);
    const cx = w / 2, cy = h * 0.40, R = Math.min(w * 0.30, h * 0.24, 250);
    const col = `${cur.c[0] | 0},${cur.c[1] | 0},${cur.c[2] | 0}`;
    const breath = 0.5 + 0.5 * Math.sin(now / 1000 * (O.state === "waiting" ? 3.2 : 1.1));
    const energy = cur.a + cur.lvl * 0.34;

    // base del «proyector»: un haz muy tenue y un anillo en el suelo
    const by = cy + R * 1.52;
    const beam = ctx.createLinearGradient(0, by, 0, cy);
    beam.addColorStop(0, `rgba(${col},${0.10 * cur.g})`); beam.addColorStop(1, `rgba(${col},0)`);
    ctx.fillStyle = beam;
    ctx.beginPath(); ctx.moveTo(cx - R * 0.5, by); ctx.lineTo(cx + R * 0.5, by); ctx.lineTo(cx + R * 0.95, cy); ctx.lineTo(cx - R * 0.95, cy); ctx.closePath(); ctx.fill();
    ctx.strokeStyle = `rgba(${col},${0.35 * cur.g})`; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.ellipse(cx, by, R * 0.5, R * 0.07, 0, 0, Math.PI * 2); ctx.stroke();
    ctx.strokeStyle = `rgba(${col},${0.12 * cur.g})`;
    ctx.beginPath(); ctx.ellipse(cx, by, R * 0.72, R * 0.10, 0, 0, Math.PI * 2); ctx.stroke();

    ctx.globalCompositeOperation = "lighter";

    // halo
    const halo = ctx.createRadialGradient(cx, cy, 0, cx, cy, R * 1.5);
    halo.addColorStop(0, `rgba(${col},${0.20 * cur.g + cur.lvl * 0.15})`);
    halo.addColorStop(0.45, `rgba(${col},${0.05 * cur.g})`); halo.addColorStop(1, `rgba(${col},0)`);
    ctx.fillStyle = halo; ctx.fillRect(cx - R * 1.6, cy - R * 1.6, R * 3.2, R * 3.2);

    // esfera de partículas
    const cyaw = Math.cos(yaw), syaw = Math.sin(yaw), tilt = 0.38 + 0.06 * Math.sin(now / 5200), ct = Math.cos(tilt), st = Math.sin(tilt);
    for (let i = 0; i < N; i++) {
      const p = pts[i];
      const n = (Math.sin(p.x * 3.1 + tNoise * 1.3) + Math.sin(p.y * 2.7 - tNoise * 1.1) + Math.sin(p.z * 3.7 + tNoise * 0.9)) / 3;
      const r = R * (1 + n * energy * 2.2 + (p.k - 0.5) * 0.02);
      const x1 = p.x * cyaw + p.z * syaw, z1 = -p.x * syaw + p.z * cyaw;
      const y2 = p.y * ct - z1 * st, z2 = p.y * st + z1 * ct;
      const depth = (z2 + 1) / 2, persp = 1 / (1.9 - z2 * 0.45) * 1.9 / 1.45;
      const size = (0.7 + depth * 1.5) * (1 + cur.lvl * 0.6);
      ctx.fillStyle = `rgba(${col},${(0.10 + depth * 0.62) * cur.g})`;
      ctx.fillRect(cx + x1 * r * persp - size / 2, cy + y2 * r * persp - size / 2, size, size);
    }

    // anillos orbitales y satélite (cuando actúa, el satélite corre)
    ctx.lineWidth = 1;
    [[1.28, 0.30, -0.42], [1.42, 0.16, 0.5]].forEach(([rx, ry, rot], i) => {
      ctx.strokeStyle = `rgba(${col},${(0.16 + (O.state === "waiting" ? breath * 0.3 : 0)) * cur.g})`;
      ctx.beginPath(); ctx.ellipse(cx, cy, R * rx, R * rx * ry, rot, 0, Math.PI * 2); ctx.stroke();
      const a = sat * (i ? -0.7 : 1) + i * 2;
      const ex = Math.cos(a) * R * rx, ey = Math.sin(a) * R * rx * ry;
      const sx = cx + ex * Math.cos(rot) - ey * Math.sin(rot), sy = cy + ex * Math.sin(rot) + ey * Math.cos(rot);
      const sg = ctx.createRadialGradient(sx, sy, 0, sx, sy, 9);
      sg.addColorStop(0, `rgba(255,255,255,${0.9 * cur.g})`); sg.addColorStop(0.3, `rgba(${col},${0.6 * cur.g})`); sg.addColorStop(1, `rgba(${col},0)`);
      ctx.fillStyle = sg; ctx.fillRect(sx - 9, sy - 9, 18, 18);
    });

    // el puntito: el núcleo
    const core = R * (0.10 + 0.02 * breath + cur.lvl * 0.10);
    const cg = ctx.createRadialGradient(cx, cy, 0, cx, cy, core * 4.5);
    cg.addColorStop(0, `rgba(255,255,255,${0.95 * Math.min(1, cur.g + 0.2)})`);
    cg.addColorStop(0.16, `rgba(${col},${0.85 * cur.g})`);
    cg.addColorStop(0.5, `rgba(${col},${0.16 * cur.g})`);
    cg.addColorStop(1, `rgba(${col},0)`);
    ctx.fillStyle = cg; ctx.beginPath(); ctx.arc(cx, cy, core * 4.5, 0, Math.PI * 2); ctx.fill();

    ctx.globalCompositeOperation = "source-over";
    requestAnimationFrame(frame);
  }

  return O;
})();
