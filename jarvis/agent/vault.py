"""Las carpetas convertidas en un mapa que se puede buscar.

Solo lectura: pide los archivos a data.py y nunca escribe nada.
- Lee .md, .markdown, .txt y PDF con texto (los escaneados se saltan y se avisa).
- Salta node_modules, .git y archivos de más de 2 MB.
- [[enlaces]] -> aristas del mapa.
"""
import re
import threading
import unicodedata
import zlib
from pathlib import Path

import data

MAX_BYTES = 2 * 1024 * 1024
SKIP_DIRS = {"node_modules", ".git", ".obsidian", ".trash", "__pycache__"}
TEXT_EXT = {".md", ".markdown", ".txt"}

STOPWORDS = set("""
a al algo algun alguna algunas alguno algunos ante antes asi aun bien cada como con contra cual cuales
cuando cuanto cuanta cuantos cuantas de del desde donde dos el ella ellas ellos en entre era eres es esa
esas ese eso esos esta estan estas este esto estos estoy fue ha han has hay he la las le les lo los mas me
mi mis mucho muy nada ni no nos nosotros o os otra otro para pero poco por porque puede que quien quienes
se sea ser si sin sobre solo son su sus tambien te tengo ti tiene tienen todo todos tu tus un una unas uno
unos usted ustedes va vamos y ya yo dime dame decir sabes saber quiero hola oye jarvis buenas buenos dias
favor gracias puedes podrias hacer tal ver mira cosa cosas mio mia
""".split())

LINK_RE = re.compile(r"\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]+))?\]\]")
FRONT_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n", re.S)


def norm(s):
    s = unicodedata.normalize("NFD", s.lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def stem(t):
    """Singular sencillo: siniestros -> siniestro, renovaciones -> renovacion."""
    if len(t) > 5 and t.endswith("ones"):
        return t[:-2]
    if len(t) > 4 and t.endswith("s") and not t.endswith("ss"):
        return t[:-1]
    return t


def tokens(s):
    return [stem(t) for t in re.findall(r"[a-z0-9]+", norm(s)) if len(t) > 1 and t not in STOPWORDS]


# ---------------- PDF con la librería estándar ----------------
def pdf_text(raw):
    """Extrae texto de PDF con texto. Devuelve '' si no hay (escaneado)."""
    out = []
    for m in re.finditer(rb"<<(.*?)>>\s*stream\r?\n(.*?)\r?\nendstream", raw, re.S):
        head, body = m.group(1), m.group(2)
        if b"/Image" in head:
            continue
        if b"FlateDecode" in head:
            try:
                body = zlib.decompress(body)
            except zlib.error:
                try:
                    body = zlib.decompressobj().decompress(body)
                except zlib.error:
                    continue
        for t in re.finditer(rb"\[(.*?)\]\s*TJ|\((.*?)(?<!\\)\)\s*(?:Tj|'|\")", body, re.S):
            if t.group(1) is not None:
                parts = re.findall(rb"\((.*?)(?<!\\)\)", t.group(1), re.S)
                s = b"".join(parts)
            else:
                s = t.group(2)
            s = s.replace(rb"\(", b"(").replace(rb"\)", b")").replace(rb"\\", b"\\")
            out.append(s.decode("latin-1", errors="replace"))
    txt = "\n".join(out)
    return txt if sum(c.isalpha() for c in txt) > 20 else ""


# ---------------- El mapa ----------------
class Vault:
    def __init__(self):
        self.notes = {}       # id -> nota
        self.by_name = {}     # nombre normalizado -> id
        self.edges = []
        self.skipped = []     # [(ruta, motivo)]
        self.lock = threading.Lock()

    def load(self):
        notes, skipped = {}, []
        for folder in data.folders():
            folder = Path(folder)
            if not folder.exists():
                skipped.append((str(folder), "carpeta no encontrada"))
                continue
            for path in sorted(self._walk(folder, skipped)):
                ext = path.suffix.lower()
                try:
                    size = path.stat().st_size
                    if size > MAX_BYTES:
                        skipped.append((str(path), "más de 2 MB"))
                        continue
                    raw = data.read_text(path)
                except Exception as e:  # permisos, etc.
                    skipped.append((str(path), f"no se pudo leer ({e.__class__.__name__})"))
                    continue
                if ext == ".pdf":
                    text = pdf_text(raw)
                    if not text:
                        skipped.append((str(path), "PDF escaneado (sin texto)"))
                        continue
                    kind = "pdf"
                else:
                    text = raw.decode("utf-8", errors="replace")
                    kind = "texto" if ext == ".txt" else "markdown"
                rel = path.relative_to(folder)
                nid = f"{folder.name}/{rel.as_posix()}"
                title = path.stem
                tipo = None
                fm = FRONT_RE.match(text)
                if fm:
                    for line in fm.group(1).splitlines():
                        if ":" in line:
                            k, v = line.split(":", 1)
                            if k.strip().lower() in ("tipo", "type"):
                                tipo = v.strip().strip("'\"").lower()
                    body = text[fm.end():]
                else:
                    body = text
                if not tipo:
                    tipo = norm(rel.parts[0]) if len(rel.parts) > 1 else kind
                links = [m.group(1).strip() for m in LINK_RE.finditer(body)]
                notes[nid] = {"id": nid, "title": title, "type": tipo, "format": kind,
                              "path": str(path), "body": body, "links": links,
                              "tokens": set(tokens(title + " " + body)),
                              "title_tokens": set(tokens(title))}
        by_name = {}
        for nid, n in notes.items():
            by_name.setdefault(norm(n["title"]), nid)
        edges, seen = [], set()
        for nid, n in notes.items():
            n["out"] = []
            for l in n["links"]:
                tid = by_name.get(norm(l.split("/")[-1]))
                if tid and tid != nid:
                    n["out"].append(tid)
                    key = tuple(sorted((nid, tid)))
                    if key not in seen:
                        seen.add(key)
                        edges.append(key)
        degree = {nid: 0 for nid in notes}
        for a, b in edges:
            degree[a] += 1
            degree[b] += 1
        for nid, n in notes.items():
            n["degree"] = degree[nid]
        with self.lock:
            self.notes, self.by_name, self.edges, self.skipped = notes, by_name, edges, skipped
        return self

    def _walk(self, folder, skipped):
        for p in folder.rglob("*"):
            if any(part in SKIP_DIRS for part in p.relative_to(folder).parts):
                continue
            if p.is_file() and (p.suffix.lower() in TEXT_EXT or p.suffix.lower() == ".pdf"):
                yield p

    # ---- consultas ----
    def summary(self):
        by_type, by_format = {}, {}
        for n in self.notes.values():
            by_type[n["type"]] = by_type.get(n["type"], 0) + 1
            by_format[n["format"]] = by_format.get(n["format"], 0) + 1
        return {"notes": len(self.notes), "edges": len(self.edges), "by_type": by_type,
                "by_format": by_format, "skipped": self.skipped,
                "top": [(n["title"], n["type"], n["degree"]) for n in self.top(10)]}

    def top(self, k=10):
        return sorted(self.notes.values(), key=lambda n: (-n["degree"], n["title"]))[:k]

    def graph(self):
        nodes = [{"id": n["id"], "title": n["title"], "type": n["type"], "degree": n["degree"]}
                 for n in self.notes.values()]
        return {"nodes": nodes, "edges": [list(e) for e in self.edges]}

    def note(self, nid):
        n = self.notes.get(nid)
        if not n:
            return None
        links = []
        for l in n["links"]:
            tid = self.by_name.get(norm(l.split("/")[-1]))
            links.append({"name": l, "id": tid})
        backlinks = sorted({o["id"] for o in self.notes.values() if nid in o.get("out", [])})
        return {"id": nid, "title": n["title"], "type": n["type"], "body": n["body"],
                "path": n["path"], "links": links, "degree": n["degree"],
                "backlinks": [{"id": b, "title": self.notes[b]["title"]} for b in backlinks]}

    def resolve(self, name):
        return self.by_name.get(norm(name))

    def search(self, query, k=5):
        """Busca por palabras con significado (sin 'que', 'de'...). Devuelve [(score, nota)]."""
        q = tokens(query)
        if not q:
            return []
        results = []
        qn = norm(query)
        for n in self.notes.values():
            score = 0.0
            for t in q:
                if t in n["title_tokens"]:
                    score += 3
                elif t in n["tokens"]:
                    score += 1
            if len(n["title"]) > 3 and norm(n["title"]) in qn:
                score += 6  # el nombre completo aparece en la pregunta
            if score:
                results.append((score + min(n["degree"], 20) * 0.02, n))
        results.sort(key=lambda x: -x[0])
        return results[:k]

    def snippet(self, n, query, width=420):
        body = re.sub(r"\s+", " ", n["body"]).strip()
        q = tokens(query)
        nb = norm(body)
        pos = min([nb.find(t) for t in q if nb.find(t) >= 0] or [0])
        start = max(0, pos - 80)
        return ("…" if start else "") + body[start:start + width] + ("…" if start + width < len(body) else "")


VAULT = Vault()


if __name__ == "__main__":
    import json
    s = Vault().load().summary()
    print(json.dumps(s, ensure_ascii=False, indent=1))
