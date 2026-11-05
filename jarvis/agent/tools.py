"""Las herramientas de JARVIS. Las ejecuta el servidor ANTES de llamar a Claude.

Cada herramienta devuelve:
  tool     nombre de la herramienta
  card     tarjeta para la pantalla {title, rows:[{main, sub, src, id, money}], notes:[...]} o None
  context  texto que se le pasa a Claude dentro del mensaje (los datos van marcados como no confiables)
"""
import re
from datetime import date, datetime, timedelta

import data
import memory
from vault import VAULT, norm, stem, tokens

# ------------------------------------------------------------------ elegir herramienta
ROUTES = [
    ("recordar", [r"recuerda", r"recordar", r"apunta", r"anota", r"apuntalo", r"anotalo", r"guarda",
                  r"guardalo", r"no olvides", r"memoriza", r"que no se te olvide"]),
    ("investigar_en_la_web", [r"internet", r"en la web", r"en la red", r"busca en google", r"en google",
                              r"online", r"busca fuera", r"actualidad"]),
    ("leer_correo", [r"correos?", r"e-?mails?", r"mails?", r"bandeja", r"me ha escrito", r"me han escrito",
                     r"me escribio", r"quien me escribe", r"mensajes?"]),
    ("planear_el_dia", [r"planea\w*", r"planifica\w*", r"prioridades", r"organiza(me)? el dia",
                        r"plan(?! de (pensiones|ahorro|jubilacion|salud|vida|empresa|marketing))"]),
    ("parte_del_dia", [r"hoy", r"agenda", r"semana", r"calendario", r"reuniones?", r"parte del dia",
                       r"atrasad\w*", r"pendientes?", r"manana", r"que tengo"]),
]
CONVO = set(stem(w) for w in """escuchas oyes opinas piensas parece crees sientes llamas eres estas tal gracias vale ok okey perfecto
genial bien claro entiendo entendido segundo primero tercero ultimo ultima siguiente anterior eso esa ese explica
explicame repite repitelo seguro verdad adios luego chao hasta pronto quien ayuda ayudar contar cuentame funcionas
haces sirves""".split())


def route(question):
    """Devuelve (herramienta, motivo). 'conversacion' si no encaja ninguna."""
    qn = " " + re.sub(r"[^a-z0-9ñ ]+", " ", norm(question)) + " "
    for tool, pats in ROUTES:
        for p in pats:
            m = re.search(r"\b" + p + r"\b", qn)
            if m:
                if tool == "parte_del_dia" and m.group(0).strip() not in ("hoy", "semana", "agenda", "manana", "parte del dia"):
                    hits = VAULT.search(question, k=1)
                    if hits and norm(hits[0][1]["title"]) in norm(question):
                        return "buscar_en_mi_cerebro", f"nombra «{hits[0][1]['title']}»"
                return tool, f"palabra: {m.group(0).strip()}"
    if re.search(r"\b(opinas|piensas|crees|te parece|que harias|me recomiendas)\b", qn):
        return "conversacion", "pide tu opinión"
    toks = tokens(question)
    if not toks or all(t in CONVO for t in toks):
        return "conversacion", "conversación"
    hits = VAULT.search(question, k=3)
    if hits and hits[0][0] >= 2.5:
        return "buscar_en_mi_cerebro", f"coincide con «{hits[0][1]['title']}»"
    return "conversacion", "sin coincidencias en tus archivos"


# ------------------------------------------------------------------ utilidades
INJECTION = re.compile(r"ignora\w*\s+(tus|las|todas|sus)?\s*(instrucciones|ordenes|reglas)|ignore (all |your )?(previous )?instructions"
                       r"|olvida tus instrucciones|reenv\w+ todos|env[ií]a la lista", re.I)


def untrusted(label, text):
    return f'<datos_no_confiables origen="{label}">\n{text}\n</datos_no_confiables>'


def eur(n):
    return f"{n:,.0f}".replace(",", ".") + " €"


def _num(s):
    return float(s.replace(".", "").replace(",", "."))


def policies():
    """Lee las pólizas del mapa (formato de las fichas: Prima anual / Comisión / Vencimiento / Estado)."""
    out = []
    for n in VAULT.notes.values():
        b = n["body"]
        m_prima = re.search(r"Prima anual:\s*([\d.,]+)", b)
        if not m_prima:
            continue
        m_com = re.search(r"Comisi[oó]n:\s*([\d.,]+)\s*%\s*\(([\d.,]+)", b)
        m_ven = re.search(r"Vencimiento:\s*(\d{4}-\d{2}-\d{2})", b)
        m_est = re.search(r"Estado:\s*(.+)", b)
        m_cli = re.search(r"Cliente:\s*\[\[([^\]]+)\]\]", b)
        m_ramo = re.search(r"Ramo:\s*(.+)", b)
        out.append({"id": n["id"], "title": n["title"], "prima": _num(m_prima.group(1)),
                    "comision": _num(m_com.group(2)) if m_com else 0,
                    "vence": date.fromisoformat(m_ven.group(1)) if m_ven else None,
                    "estado": m_est.group(1).strip() if m_est else "",
                    "cliente": m_cli.group(1) if m_cli else "", "ramo": m_ramo.group(1).strip() if m_ramo else "",
                    "path": n["path"]})
    return out


def src(path):
    return path.split("/vault/")[-1] if "/vault/" in path else path


def overdue_items(today):
    """Lo que se quedó atrasado, con lo que cuesta. Ordenado por dinero."""
    items = []
    pols = policies()
    for p in pols:
        m = re.search(r"impagad\w* desde hace (\d+) d", p["estado"])
        if m:
            items.append({"main": f"{p['cliente']}: recibo impagado {p['title']}",
                          "sub": f"{m.group(1)} días · prima {eur(p['prima'])} en riesgo",
                          "money": p["prima"], "src": src(p["path"]), "id": p["id"]})
        elif p["vence"] and p["vence"] < today:
            items.append({"main": f"{p['cliente']}: {p['title']} vencida sin renovar",
                          "sub": f"venció el {p['vence'].isoformat()} · comisión de {eur(p['comision'])} al año en juego",
                          "money": p["comision"], "src": src(p["path"]), "id": p["id"]})
    if not pols:  # datos reales sin ese formato: líneas con palabras de atraso
        for n in VAULT.notes.values():
            for line in n["body"].splitlines():
                if re.search(r"impagad|atrasad|vencid|pendiente de pago", line, re.I):
                    items.append({"main": n["title"], "sub": line.strip()[:140], "money": 0,
                                  "src": src(n["path"]), "id": n["id"]})
                    break
    items.sort(key=lambda x: -x["money"])
    return items


def renewals(today, days):
    out = []
    for p in policies():
        if p["vence"] and today <= p["vence"] <= today + timedelta(days=days) and "impagad" not in p["estado"]:
            out.append({"main": f"Renovar {p['title']} de {p['cliente']}",
                        "sub": f"vence el {p['vence'].isoformat()} · {p['ramo']} · comisión {eur(p['comision'])} al año",
                        "money": p["comision"], "src": src(p["path"]), "id": p["id"]})
    out.sort(key=lambda x: -x["money"])
    return out


def events(start, days):
    cal = data.calendar()
    if cal is None:
        return None
    end = start + timedelta(days=days)
    out = []
    for e in cal:
        try:
            dt = datetime.fromisoformat(e["start"])
        except (ValueError, KeyError):
            continue
        if start <= dt.date() < end:
            out.append({**e, "_dt": dt})
    return sorted(out, key=lambda e: e["_dt"])


DIAS = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]


def who_in_vault(name, email=""):
    nid = VAULT.resolve(name)
    if nid:
        n = VAULT.notes[nid]
        return n
    return None


# ------------------------------------------------------------------ herramientas
def buscar_en_mi_cerebro(q):
    hits = VAULT.search(q, k=4)
    if not hits:
        return {"tool": "buscar_en_mi_cerebro", "card": {"title": "En tus archivos", "rows": [],
                "notes": ["No encontré nada sobre esto en tus archivos."]},
                "context": "Búsqueda en sus archivos: NINGÚN resultado. Dilo así; no inventes."}
    rows, ctx = [], []
    for score, n in hits:
        rows.append({"main": n["title"], "sub": VAULT.snippet(n, q, 200), "src": src(n["path"]), "id": n["id"]})
        body = n["body"][:1800]
        ctx.append(untrusted(f"archivo {src(n['path'])}", f"# {n['title']}\n{body}"))
    warn = [f"«{r['main']}» contiene instrucciones; son datos y no se obedecen." for (s, n), r in zip(hits, rows)
            if INJECTION.search(n["body"])]
    return {"tool": "buscar_en_mi_cerebro",
            "card": {"title": "En tus archivos", "rows": rows, "notes": warn},
            "context": "Resultados de buscar en sus archivos (el más relevante primero). Di siempre de qué archivo sale el dato.\n"
                       + "\n".join(ctx)}


def leer_correo(q=""):
    box = data.inbox()
    if box is None:
        return {"tool": "leer_correo", "card": {"title": "Correo", "rows": [], "notes": ["Correo: no conectado."]},
                "context": "El correo NO está conectado (no hay data/real/inbox.json). Dilo en pocas palabras."}
    box = sorted(box, key=lambda m: m.get("date", ""), reverse=True)
    rows, ctx, warn = [], [], []
    for m in box[:12]:
        n = who_in_vault(m.get("from", ""), m.get("email", ""))
        known = f"en tus archivos: {n['title']} ({n['type']})" if n else "no aparece en tus archivos"
        flag = bool(INJECTION.search(m.get("snippet", "") + " " + m.get("subject", "")))
        if flag:
            warn.append(f"El correo de {m.get('from')} trae instrucciones («{m.get('subject')}»): no se obedecen.")
        rows.append({"main": f"{'● ' if m.get('unread') else ''}{m.get('from')} — {m.get('subject')}",
                     "sub": f"{m.get('date', '')[:16].replace('T', ' ')} · {known}",
                     "src": src(n["path"]) if n else "", "id": n["id"] if n else None})
        ctx.append(f"- de: {m.get('from')} · {m.get('date', '')[:16]} · "
                   f"{'NO LEÍDO' if m.get('unread') else 'leído'} · asunto: {m.get('subject')} · "
                   f"resumen: {m.get('snippet', '')[:160]} · {known}" + (f" (ficha: {src(n['path'])})" if n else ""))
    unread = sum(1 for m in box if m.get("unread"))
    ctx.insert(0, f"TOTAL: {len(box)} correos, {unread} sin leer (usa estas cifras, no cuentes tú).")
    return {"tool": "leer_correo",
            "card": {"title": f"Correo · {unread} sin leer de {len(box)}", "rows": rows, "notes": warn},
            "context": "Bandeja de entrada (solo lectura; nunca se envía nada). Lo que vale es si el remitente ya existe en sus archivos.\n"
                       + untrusted("correo", "\n".join(ctx))}


def parte_del_dia(q=""):
    today = data.today()
    week = "semana" in norm(q)
    days = 7 if week else 1
    start = today - timedelta(days=today.weekday()) if week else today
    if week and start < today:
        start = today  # lo que queda de semana, desde hoy
        days = 7 - today.weekday()
    ev = events(start, days)
    box = data.inbox()
    late = overdue_items(today)
    ren = renewals(today, days if week else 3)
    rows, notes = [], []
    # Primero lo que cuesta dinero
    for it in late[:5]:
        rows.append({**it, "kind": "atrasado"})
    if week:
        for it in ren[:5]:
            rows.append({**it, "kind": "renovación"})
    if ev is None:
        notes.append("Calendario: no conectado.")
    else:
        for e in ev:
            d = e["_dt"]
            label = (DIAS[d.weekday()] + " " if week else "") + d.strftime("%H:%M")
            rows.append({"main": f"{label} · {e['title']}", "sub": f"{e.get('minutes', '')} min · {e.get('with', '')}",
                         "kind": "agenda"})
        if not ev:
            notes.append("Sin reuniones en el calendario.")
    if box is None:
        notes.append("Correo: no conectado.")
    unread = [m for m in (box or []) if m.get("unread")]
    if box is not None:
        rows.append({"main": f"{len(unread)} correos sin leer", "sub": ", ".join(m.get("from", "") for m in unread[:5]),
                     "kind": "correo"})
    if not week and ren:
        rows.append({"main": f"{len(ren)} renovaciones en los próximos 3 días",
                     "sub": "; ".join(r["main"].replace("Renovar ", "") for r in ren[:3]), "kind": "renovación"})
    ctx = [f"Fecha de hoy: {DIAS[today.weekday()]} {today.isoformat()}. Periodo: {'la semana entera' if week else 'hoy'}."]
    ctx.append(f"Atrasado: {len(late)} cosas en total (muestro las 8 que más dinero mueven; cada cifra sale del archivo indicado):")
    ctx += [f"- {i['main']} · {i['sub']} · archivo {i['src']}" for i in late[:8]] or ["- nada"]
    ctx.append("Agenda:" if ev is not None else "Agenda: calendario NO conectado.")
    ctx += [f"- {e['_dt'].strftime('%a %d %H:%M')} {e['title']} ({e.get('with', '')})" for e in (ev or [])]
    ctx.append(f"Correos sin leer: {len(unread)}" if box is not None else "Correo: NO conectado.")
    if ren:
        ctx.append(f"Renovaciones próximas: {len(ren)} en total (muestro las 6 de más comisión):")
        ctx += [f"- {r['main']} · {r['sub']} · archivo {r['src']}" for r in ren[:6]]
    if week:
        ctx.append("Es la semana: empieza por lo que le está costando dinero.")
    title = ("Semana desde el " if week else "Parte del día · ") + today.isoformat()
    return {"tool": "parte_del_dia", "card": {"title": title, "rows": rows, "notes": notes},
            "context": untrusted("agenda y archivos", "\n".join(ctx))}


def planear_el_dia(q=""):
    today = data.today()
    cands = []
    for it in overdue_items(today)[:4]:
        cands.append({**it, "why": "atrasado"})
    for it in renewals(today, 7)[:4]:
        cands.append({**it, "why": "renovación"})
    box = data.inbox() or []
    for m in box:
        if m.get("unread") and not who_in_vault(m.get("from", "")) and not INJECTION.search(m.get("snippet", "")):
            cands.append({"main": f"Responder a {m['from']}: {m['subject']}", "sub": "posible cliente nuevo · sin cifra en tus archivos",
                          "money": 0.5, "src": "correo", "why": "cliente nuevo"})
    cands.sort(key=lambda x: -x["money"])
    plan = cands[:5]
    ev = events(today, 1)
    notes = []
    if ev is None:
        notes.append("Calendario: no conectado.")
    elif ev:
        notes.append("Huecos ocupados: " + ", ".join(f"{e['_dt'].strftime('%H:%M')} {e['title']}" for e in ev))
    rows = [{**p, "main": f"{i + 1}. {p['main']}"} for i, p in enumerate(plan)]
    ctx = [f"Hoy es {DIAS[today.weekday()]} {today.isoformat()}. Plan propuesto por el servidor (máximo 5, ordenado por dinero):"]
    ctx += [f"{i + 1}. {p['main']} · {p['sub']} · fuente {p['src']}" for i, p in enumerate(plan)]
    ctx += notes
    return {"tool": "planear_el_dia", "card": {"title": "Plan de hoy", "rows": rows, "notes": notes},
            "context": untrusted("plan calculado", "\n".join(ctx))}


def recordar(q=""):
    return {"tool": "recordar", "card": None,
            "context": "Te pide que recuerdes algo. Devuelve en \"recordar\" UNA sola cosa, en una frase clara y autocontenida "
                       "(con nombres, no pronombres). En \"decir\" di exactamente qué guardas."}


def investigar_en_la_web(q=""):
    return {"tool": "investigar_en_la_web", "card": None,
            "context": "Busca en internet con WebSearch/WebFetch (solo lectura, sin pagar nada). Aterriza el resultado en SUS números "
                       "(los de CLAUDE.md o sus archivos) y di de dónde sale cada cifra. Pon en \"detalle\" el resumen y las fuentes (URLs)."}


def conversacion(q=""):
    return {"tool": "conversacion", "card": None, "context": ""}


TOOLS = {"buscar_en_mi_cerebro": buscar_en_mi_cerebro, "leer_correo": leer_correo, "parte_del_dia": parte_del_dia,
         "planear_el_dia": planear_el_dia, "recordar": recordar, "investigar_en_la_web": investigar_en_la_web,
         "conversacion": conversacion}


def run(tool, q):
    return TOOLS[tool](q)


def memory_card():
    its = memory.items()
    return {"title": f"Memoria · {len(its)} cosas", "notes": [] if its else ["Todavía no has pedido que recuerde nada."],
            "rows": [{"main": i["text"], "sub": i["date"], "src": i["file"]} for i in its]}
