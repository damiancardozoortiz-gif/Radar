#!/usr/bin/env python3
"""
El taller de JARVIS: un servidor pequeño que corre en Termux y deja a JARVIS trabajar en SU PROPIO CÓDIGO
sin darle una terminal libre.

- Habla MCP (JSON-RPC sobre HTTP), el protocolo de los «puentes» que JARVIS ya sabe usar.
- Escucha solo en 127.0.0.1 y exige una credencial (cualquier app del teléfono puede llegar a 127.0.0.1).
- Trabaja en una COPIA del proyecto (un clon de git), nunca en la app instalada.
- Solo escribe en ramas «jarvis/…», y antes de tocar un archivo guarda una copia.
- No ejecuta comandos arbitrarios: solo los de una lista cerrada (git y gradlew con objetivos fijos).
- Lo que cambia algo (escribir, commit, compilar, deshacer, subir) se anuncia como «no solo lectura»,
  así que en JARVIS pasa por el freno y espera el toque de la persona.
- Todo queda en un registro (taller.log).

Uso:  python3 taller.py --repo ~/jarvis-taller/Radar [--puerto 8090] [--token-archivo ~/.jarvis-taller/token]
"""
import argparse
import datetime
import hmac
import json
import os
import re
import secrets
import shutil
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION_MCP = "2025-06-18"
MAX_LEER = 10_000          # letras por lectura (el puente corta a 12 000)
MAX_ESCRIBIR = 300_000     # bytes por archivo escrito
MAX_BUSCAR = 60            # coincidencias por búsqueda
RAMA_PREFIJO = "jarvis/"

# Lo que el taller nunca toca: la historia de git, la firma, las credenciales y lo que ejecuta código fuera del proyecto
PROHIBIDO = [
    re.compile(r"(^|/)\.git(/|$)"),
    re.compile(r"(^|/)firma(/|$)"),
    re.compile(r"\.(keystore|jks|p12|pem|key)$", re.I),
    re.compile(r"(^|/)local\.properties$"),
    re.compile(r"(^|/)\.github(/|$)"),
    re.compile(r"(^|/)gradle/wrapper(/|$)"),
    re.compile(r"(^|/)gradlew(\.bat)?$"),
]
# Se pueden cambiar, pero se avisa: son scripts que Gradle ejecuta al compilar
SENSIBLE = re.compile(r"\.gradle(\.kts)?$|(^|/)gradle\.properties$")

OBJETIVOS = {
    "pruebas": ["testDebugUnitTest"],
    "apk": ["assembleDebug"],
    "lint": ["lintDebug"],
}


def ahora():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


class Taller:
    def __init__(self, repo, proyecto="jarvis-android", datos=None, gradle_extra=None, gradlew=None):
        self.repo = os.path.realpath(os.path.expanduser(repo))
        if not os.path.isdir(os.path.join(self.repo, ".git")):
            raise SystemExit(f"{self.repo} no es un repositorio de git")
        self.proyecto = os.path.join(self.repo, proyecto)
        self.datos = os.path.realpath(os.path.expanduser(datos or "~/.jarvis-taller"))
        os.makedirs(os.path.join(self.datos, "copias"), exist_ok=True)
        os.makedirs(os.path.join(self.datos, "compilaciones"), exist_ok=True)
        self.gradle_extra = gradle_extra or []
        self.gradlew = gradlew or os.path.join(self.proyecto, "gradlew")
        self.cerrojo = threading.Lock()
        self.trabajos = {}          # id -> dict
        self.trabajo_actual = None

    # ------------------------------------------------------------------ utilidades
    def registrar(self, texto):
        with open(os.path.join(self.datos, "taller.log"), "a", encoding="utf-8") as f:
            f.write(f"{ahora()}  {texto}\n")

    def git(self, *args, comprobar=True, tiempo=60):
        r = subprocess.run(["git", "-C", self.repo, *args], capture_output=True, text=True, timeout=tiempo,
                           env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
        if comprobar and r.returncode != 0:
            raise Fallo(f"git {' '.join(args[:2])} falló: {(r.stderr or r.stdout).strip()[:400]}")
        return r.stdout.strip()

    def rama(self):
        return self.git("rev-parse", "--abbrev-ref", "HEAD")

    def ruta(self, relativa, para_escribir=False):
        """Ruta dentro del repositorio; nunca fuera, nunca a lo prohibido (si es para escribir)."""
        rel = (relativa or "").strip().lstrip("/")
        completa = os.path.realpath(os.path.join(self.repo, rel))
        if completa != self.repo and not completa.startswith(self.repo + os.sep):
            raise Fallo("esa ruta está fuera del proyecto")
        rel_norm = os.path.relpath(completa, self.repo).replace(os.sep, "/")
        if any(p.search(rel_norm) for p in PROHIBIDO) and (para_escribir or rel_norm.startswith(".git")):
            raise Fallo(f"«{rel_norm}» está protegido: el taller no lo toca")
        return completa, ("" if rel_norm == "." else rel_norm)

    def exigir_rama_de_trabajo(self):
        r = self.rama()
        if not r.startswith(RAMA_PREFIJO):
            raise Fallo(f"estás en la rama «{r}». Solo se cambia código en una rama «{RAMA_PREFIJO}…»: créala con taller_rama")
        return r

    def copia_de_seguridad(self, completa, rel):
        if os.path.isfile(completa):
            destino = os.path.join(self.datos, "copias", datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f"), rel)
            os.makedirs(os.path.dirname(destino), exist_ok=True)
            shutil.copy2(completa, destino)
            return destino
        return None

    # ------------------------------------------------------------------ lectura (sin permiso)
    def estado(self, _):
        cambios = self.git("status", "--porcelain")
        ultimo = self.git("log", "-1", "--format=%h %s (%cr)")
        herramientas = {
            "java": bool(shutil.which("java")),
            "gradlew": os.path.isfile(self.gradlew),
            "aapt2 propio": any(a.startswith("-Pandroid.aapt2FromMavenOverride") for a in self.gradle_extra),
            "gh (GitHub)": bool(shutil.which("gh")),
        }
        t = self.trabajo_actual and self.trabajos.get(self.trabajo_actual)
        lineas = [
            f"Repositorio: {self.repo}",
            f"Rama: {self.rama()}" + ("" if self.rama().startswith(RAMA_PREFIJO) else "  (no es de trabajo: crea una con taller_rama antes de cambiar nada)"),
            f"Último commit: {ultimo}",
            "Cambios sin guardar:\n" + (cambios or "ninguno"),
            "Herramientas: " + ", ".join(f"{k} {'sí' if v else 'no'}" for k, v in herramientas.items()),
        ]
        if t:
            lineas.append(f"Compilación {t['id']} ({t['objetivo']}): {t['estado']}")
        return "\n".join(lineas)

    def listar(self, a):
        completa, rel = self.ruta(a.get("ruta", ""))
        if not os.path.isdir(completa):
            raise Fallo("no es una carpeta")
        filas = []
        for nombre in sorted(os.listdir(completa)):
            if nombre in (".git", "build", ".gradle"):
                continue
            p = os.path.join(completa, nombre)
            filas.append(nombre + "/" if os.path.isdir(p) else f"{nombre}  ({os.path.getsize(p)} bytes)")
        return f"{rel or '.'}:\n" + "\n".join(filas[:300])

    def leer(self, a):
        completa, rel = self.ruta(a.get("ruta", ""))
        if not os.path.isfile(completa):
            raise Fallo(f"no existe el archivo «{rel}»")
        with open(completa, encoding="utf-8", errors="replace") as f:
            lineas = f.read().split("\n")
        desde = max(1, int(a.get("desde") or 1))
        hasta = min(len(lineas), int(a.get("hasta") or len(lineas)))
        salida, letras, ultima = [], 0, desde - 1
        for n in range(desde, hasta + 1):
            l = f"{n:5d}  {lineas[n - 1]}"
            if letras + len(l) > MAX_LEER:
                break
            salida.append(l); letras += len(l) + 1; ultima = n
        sigue = f"\n(… sigue: pide desde={ultima + 1}; el archivo tiene {len(lineas)} líneas)" if ultima < hasta else ""
        return f"{rel} · líneas {desde}–{ultima} de {len(lineas)}\n" + "\n".join(salida) + sigue

    def buscar(self, a):
        patron = a.get("patron") or ""
        try:
            rx = re.compile(patron)
        except re.error as e:
            raise Fallo(f"el patrón no es válido: {e}")
        base, rel = self.ruta(a.get("ruta", ""))
        hallados = []
        for raiz, carpetas, archivos in os.walk(base):
            carpetas[:] = [c for c in carpetas if c not in (".git", "build", ".gradle", "firma")]
            for nombre in archivos:
                if not re.search(r"\.(kt|kts|java|xml|md|py|sh|properties|json|txt|yml)$", nombre):
                    continue
                p = os.path.join(raiz, nombre)
                try:
                    with open(p, encoding="utf-8", errors="replace") as f:
                        for n, linea in enumerate(f, 1):
                            if rx.search(linea):
                                hallados.append(f"{os.path.relpath(p, self.repo)}:{n}: {linea.strip()[:200]}")
                                if len(hallados) >= MAX_BUSCAR:
                                    return "\n".join(hallados) + f"\n(… solo las primeras {MAX_BUSCAR})"
                except OSError:
                    pass
        return "\n".join(hallados) or "Ninguna coincidencia."

    def historial(self, a):
        n = min(50, int(a.get("cuantos") or 15))
        return self.git("log", f"-{n}", "--format=%h  %ad  %s", "--date=format:%Y-%m-%d %H:%M")

    def diferencias(self, a):
        args = ["diff", "--stat"] if a.get("resumen") else ["diff"]
        if a.get("contra"):
            args.append(str(a["contra"]))
        if a.get("ruta"):
            args += ["--", self.ruta(a["ruta"])[1]]
        d = self.git(*args)
        return (d[:MAX_LEER] + ("\n(… cortado)" if len(d) > MAX_LEER else "")) or "Sin diferencias."

    def puntos(self, _):
        etiquetas = self.git("tag", "--list", "respaldo/*", "--sort=-creatordate", "--format=%(refname:short)  %(objectname:short)  %(contents:subject)")
        return "Puntos de respaldo (para taller_deshacer):\n" + (etiquetas or "ninguno todavía") + "\n\nÚltimos commits:\n" + self.historial({"cuantos": 8})

    def resultado(self, a):
        tid = a.get("trabajo") or self.trabajo_actual
        t = self.trabajos.get(tid)
        if not t:
            raise Fallo("no hay ninguna compilación con ese id")
        espera = min(75, max(0, int(a.get("esperar_segundos") or 0)))
        t["hecho"].wait(espera)
        if t["estado"] == "en curso":
            return f"Compilación {tid} ({t['objetivo']}): sigue en curso ({int(time.time() - t['inicio'])} s). Vuelve a preguntar con esperar_segundos."
        return t["resumen"]

    def github(self, _):
        if not shutil.which("gh"):
            raise Fallo("no está instalado gh (GitHub CLI) en Termux")
        r = subprocess.run(["gh", "run", "list", "--branch", self.rama(), "--limit", "3", "--json", "status,conclusion,displayTitle,url,createdAt"],
                           cwd=self.repo, capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            raise Fallo((r.stderr or r.stdout).strip()[:300])
        filas = json.loads(r.stdout or "[]")
        return "\n".join(f"{x['createdAt']}  {x['status']}/{x.get('conclusion') or '-'}  {x['displayTitle']}  {x['url']}" for x in filas) or "Ninguna compilación en GitHub para esta rama."

    # ------------------------------------------------------------------ cambios (esperan el sí de la persona en JARVIS)
    def crear_rama(self, a):
        nombre = re.sub(r"[^a-z0-9._-]+", "-", (a.get("nombre") or "").lower()).strip("-.")
        if len(nombre) < 3:
            raise Fallo("dale a la rama un nombre de al menos 3 letras")
        if self.git("status", "--porcelain"):
            raise Fallo("hay cambios sin guardar en la rama actual; haz commit o deshazlos antes")
        rama = RAMA_PREFIJO + nombre
        base = self.git("rev-parse", "--short", "HEAD")
        existe = self.git("rev-parse", "--verify", "--quiet", rama, comprobar=False)
        self.git("checkout", rama) if existe else self.git("checkout", "-b", rama)
        etiqueta = self.respaldo(f"inicio de {rama}")
        self.registrar(f"RAMA {rama} desde {base} · respaldo {etiqueta}")
        return f"Trabajando en «{rama}» (desde {base}). Punto de respaldo: {etiqueta}."

    def respaldo(self, motivo):
        base = "respaldo/" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        etiqueta, n = base, 1
        while self.git("rev-parse", "--verify", "--quiet", f"refs/tags/{etiqueta}", comprobar=False):
            n += 1; etiqueta = f"{base}-{n}"           # dos respaldos en el mismo segundo no chocan
        self.git("-c", "user.name=JARVIS", "-c", "user.email=jarvis@localhost", "tag", "-a", etiqueta, "-m", motivo[:120])
        return etiqueta

    def _guardar(self, completa, rel, contenido):
        datos = contenido.encode("utf-8")
        if len(datos) > MAX_ESCRIBIR:
            raise Fallo("el archivo es demasiado grande")
        copia = self.copia_de_seguridad(completa, rel)
        os.makedirs(os.path.dirname(completa), exist_ok=True)
        tmp = completa + ".taller-tmp"
        with open(tmp, "wb") as f:
            f.write(datos)
        os.replace(tmp, completa)
        aviso = " AVISO: es un script de Gradle; se ejecuta al compilar." if SENSIBLE.search(rel) else ""
        self.registrar(f"ESCRITO {rel} ({len(datos)} bytes) copia={copia}")
        return copia, aviso

    def escribir(self, a):
        rama = self.exigir_rama_de_trabajo()
        completa, rel = self.ruta(a.get("ruta", ""), para_escribir=True)
        contenido = a.get("contenido")
        if contenido is None:
            raise Fallo("falta el contenido")
        nuevo = not os.path.exists(completa)
        copia, aviso = self._guardar(completa, rel, contenido)
        return f"{'Creado' if nuevo else 'Reescrito'} {rel} en {rama} ({contenido.count(chr(10)) + 1} líneas)." + \
            (f" Copia del anterior: {copia}." if copia else "") + aviso

    def reemplazar(self, a):
        rama = self.exigir_rama_de_trabajo()
        completa, rel = self.ruta(a.get("ruta", ""), para_escribir=True)
        if not os.path.isfile(completa):
            raise Fallo(f"no existe «{rel}»")
        buscar, nuevo = a.get("buscar") or "", a.get("reemplazar")
        if len(buscar) < 3 or nuevo is None:
            raise Fallo("hace falta «buscar» (el fragmento exacto) y «reemplazar»")
        with open(completa, encoding="utf-8") as f:
            texto = f.read()
        veces = texto.count(buscar)
        if veces != 1:
            raise Fallo(f"el fragmento aparece {veces} veces en {rel}; tiene que aparecer exactamente una (lee el archivo y copia más contexto)")
        copia, aviso = self._guardar(completa, rel, texto.replace(buscar, nuevo, 1))
        return f"Cambiado un fragmento de {rel} en {rama}. Copia del anterior: {copia}.{aviso}"

    def commit(self, a):
        rama = self.exigir_rama_de_trabajo()
        mensaje = (a.get("mensaje") or "").strip()
        if len(mensaje) < 8:
            raise Fallo("el mensaje del commit debe explicar el cambio (al menos 8 letras)")
        if not self.git("status", "--porcelain"):
            return "No hay cambios que guardar."
        self.git("add", "-A")
        self.git("-c", "user.name=JARVIS", "-c", "user.email=jarvis@localhost", "commit", "-q", "-m", mensaje + "\n\nHecho por JARVIS desde el taller.")
        h = self.git("rev-parse", "--short", "HEAD")
        self.registrar(f"COMMIT {h} en {rama}: {mensaje[:120]}")
        return f"Guardado el commit {h} en {rama}: {mensaje}\n" + self.git("show", "--stat", "--format=", "HEAD")

    def deshacer(self, a):
        rama = self.exigir_rama_de_trabajo()
        punto = (a.get("punto") or "").strip()
        if not punto:
            raise Fallo("di a qué punto volver (una etiqueta respaldo/… o un commit); míralos con taller_puntos")
        destino = self.git("rev-parse", "--verify", "--quiet", punto + "^{commit}", comprobar=False)
        if not destino:
            raise Fallo(f"no existe el punto «{punto}»")
        # solo hacia atrás dentro de la historia de esta rama: nunca a otra rama
        if subprocess.run(["git", "-C", self.repo, "merge-base", "--is-ancestor", destino, "HEAD"]).returncode != 0:
            raise Fallo("ese punto no está en la historia de esta rama")
        antes = self.git("rev-parse", "--short", "HEAD")
        guardado = self.respaldo(f"antes de deshacer a {punto}")
        self.git("stash", "push", "--include-untracked", "-m", f"taller: cambios sin guardar antes de deshacer a {punto}", comprobar=False)
        self.git("reset", "--hard", destino)
        self.registrar(f"DESHACER {rama}: {antes} -> {punto} (respaldo {guardado})")
        return f"{rama} vuelve a {punto}. Lo de antes ({antes}) queda en el respaldo {guardado}; los cambios sin guardar, en git stash."

    def subir(self, _):
        rama = self.exigir_rama_de_trabajo()
        r = subprocess.run(["git", "-C", self.repo, "push", "-u", "origin", rama], capture_output=True, text=True, timeout=180,
                           env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
        self.registrar(f"SUBIR {rama}: código {r.returncode}")
        if r.returncode != 0:
            raise Fallo("no se pudo subir: " + (r.stderr or r.stdout).strip()[-400:])
        return f"Rama {rama} subida a GitHub. Allí se compila y se prueba sola (mira el resultado con taller_github)."

    def nota(self, a):
        texto = (a.get("texto") or "").strip()
        if len(texto) < 10:
            raise Fallo("la nota es demasiado corta")
        self.exigir_rama_de_trabajo()
        completa, rel = self.ruta("jarvis-android/MEMORIA-TECNICA.md", para_escribir=True)
        previo = open(completa, encoding="utf-8").read() if os.path.exists(completa) else "# Memoria técnica de JARVIS\n\nLa escribe JARVIS mientras trabaja en su propio código.\n"
        self._guardar(completa, rel, previo.rstrip() + f"\n\n## {ahora()} · {self.rama()}\n{texto}\n")
        return "Nota añadida a la memoria técnica (jarvis-android/MEMORIA-TECNICA.md). Entra en el próximo commit."

    # ------------------------------------------------------------------ compilar (en segundo plano)
    def compilar(self, a):
        objetivo = a.get("objetivo") or "pruebas"
        if objetivo not in OBJETIVOS:
            raise Fallo("objetivo desconocido: pruebas, apk o lint")
        if not os.path.isfile(self.gradlew):
            raise Fallo("no encuentro gradlew en el proyecto")
        with self.cerrojo:
            if self.trabajo_actual and self.trabajos[self.trabajo_actual]["estado"] == "en curso":
                raise Fallo(f"ya hay una compilación en curso ({self.trabajo_actual}); espera su resultado")
            tid = uuid.uuid4().hex[:8]
            log = os.path.join(self.datos, "compilaciones", f"{tid}-{objetivo}.log")
            t = {"id": tid, "objetivo": objetivo, "estado": "en curso", "inicio": time.time(), "log": log,
                 "hecho": threading.Event(), "resumen": "", "commit": self.git("rev-parse", "--short", "HEAD")}
            self.trabajos[tid] = t
            self.trabajo_actual = tid
        threading.Thread(target=self._compilar, args=(t,), daemon=True).start()
        self.registrar(f"COMPILAR {tid} {objetivo} en {self.rama()}@{t['commit']}")
        return f"Compilación {tid} ({objetivo}) en marcha sobre {t['commit']}. Pide el resultado con taller_resultado (esperar_segundos hasta 75)."

    def _compilar(self, t):
        cmd = [self.gradlew, *OBJETIVOS[t["objetivo"]], "--console=plain", "--max-workers=2", *self.gradle_extra]
        try:
            with open(t["log"], "w", encoding="utf-8") as salida:
                p = subprocess.run(cmd, cwd=self.proyecto, stdout=salida, stderr=subprocess.STDOUT, timeout=3600)
            codigo = p.returncode
        except subprocess.TimeoutExpired:
            codigo = -1
        except OSError as e:
            codigo = -2
            with open(t["log"], "a", encoding="utf-8") as salida:
                salida.write(f"\nNo se pudo ejecutar gradlew: {e}\n")
        t["resumen"] = self._resumir(t, codigo)
        t["estado"] = "bien" if codigo == 0 else "mal"
        t["hecho"].set()
        self.registrar(f"COMPILADO {t['id']} {t['objetivo']}: {t['estado']} ({int(time.time() - t['inicio'])} s)")

    def _resumir(self, t, codigo):
        texto = open(t["log"], encoding="utf-8", errors="replace").read()
        segundos = int(time.time() - t["inicio"])
        partes = [f"Compilación {t['id']} ({t['objetivo']}) sobre {t['commit']}: {'BIEN' if codigo == 0 else 'FALLÓ'} en {segundos} s."]
        errores = [l.strip() for l in texto.splitlines() if re.match(r"^(e:|error:|ERROR:|FAILURE:|\* What went wrong)", l.strip())]
        if errores:
            partes.append("Errores:\n" + "\n".join(errores[:25]))
        if t["objetivo"] == "pruebas":
            total, fallos = 0, []
            carpeta = os.path.join(self.proyecto, "app", "build", "test-results", "testDebugUnitTest")
            for nombre in (os.listdir(carpeta) if os.path.isdir(carpeta) else []):
                if not nombre.endswith(".xml"):
                    continue
                try:
                    raiz = ET.parse(os.path.join(carpeta, nombre)).getroot()
                except ET.ParseError:
                    continue
                if os.path.getmtime(os.path.join(carpeta, nombre)) < t["inicio"]:
                    continue                      # resultados viejos de otra compilación
                for caso in raiz.iter("testcase"):
                    total += 1
                    f = caso.find("failure") if caso.find("failure") is not None else caso.find("error")
                    if f is not None:
                        fallos.append(f"{caso.get('classname', '').split('.')[-1]}.{caso.get('name')}: {(f.get('message') or '').strip()[:200]}")
            partes.append(f"Pruebas: {total} ejecutadas, {len(fallos)} fallidas." if total else "Pruebas: no se generaron resultados.")
            if fallos:
                partes.append("Fallidas:\n" + "\n".join(fallos[:20]))
        if t["objetivo"] == "apk" and codigo == 0:
            apk = os.path.join(self.proyecto, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
            if os.path.isfile(apk):
                partes.append(f"APK: {apk} ({os.path.getsize(apk) // 1024} KB). NO está instalada: instalarla es decisión de la persona.")
        if codigo != 0 and not errores:
            partes.append("Final del registro:\n" + "\n".join(texto.splitlines()[-25:]))
        partes.append(f"Registro completo: {t['log']}")
        return "\n".join(partes)


class Fallo(Exception):
    pass


def esquema(propiedades=None, obligatorias=None):
    return {"type": "object", "properties": propiedades or {}, "required": obligatorias or []}


S = {"type": "string"}
I = {"type": "integer"}

# nombre, descripción, esquema, solo lectura, método
HERRAMIENTAS = [
    ("taller_estado", "Estado del taller: rama, último commit, cambios sin guardar, herramientas disponibles y compilación en curso.", esquema(), True, "estado"),
    ("taller_listar", "Lista una carpeta del proyecto (sin build ni .git).", esquema({"ruta": S}), True, "listar"),
    ("taller_leer", "Lee un archivo del proyecto con números de línea, por tramos (desde/hasta).", esquema({"ruta": S, "desde": I, "hasta": I}, ["ruta"]), True, "leer"),
    ("taller_buscar", "Busca una expresión regular en el código (kt, kts, xml, md…) y devuelve archivo:línea.", esquema({"patron": S, "ruta": S}, ["patron"]), True, "buscar"),
    ("taller_historial", "Los últimos commits.", esquema({"cuantos": I}), True, "historial"),
    ("taller_diferencias", "Lo que ha cambiado (git diff): sin guardar, o contra un commit o etiqueta.", esquema({"contra": S, "ruta": S, "resumen": {"type": "boolean"}}), True, "diferencias"),
    ("taller_puntos", "Los puntos de respaldo y los últimos commits, para saber a dónde se puede volver.", esquema(), True, "puntos"),
    ("taller_resultado", "El resultado de una compilación: espera hasta esperar_segundos (máx. 75) y devuelve errores y pruebas fallidas.", esquema({"trabajo": S, "esperar_segundos": I}), True, "resultado"),
    ("taller_github", "Cómo van las compilaciones de esta rama en GitHub (si gh está instalado y la rama se subió).", esquema(), True, "github"),
    ("taller_rama", "Crea (o vuelve a) una rama de trabajo jarvis/<nombre> y deja un punto de respaldo. Obligatorio antes de cambiar código.", esquema({"nombre": S}, ["nombre"]), False, "crear_rama"),
    ("taller_escribir", "Crea o reescribe un archivo entero (guarda copia del anterior). Para cambios pequeños usa taller_reemplazar.", esquema({"ruta": S, "contenido": S}, ["ruta", "contenido"]), False, "escribir"),
    ("taller_reemplazar", "Cambia un fragmento exacto de un archivo (debe aparecer una sola vez) por otro. Guarda copia del anterior.", esquema({"ruta": S, "buscar": S, "reemplazar": S}, ["ruta", "buscar", "reemplazar"]), False, "reemplazar"),
    ("taller_commit", "Guarda los cambios en un commit de la rama de trabajo.", esquema({"mensaje": S}, ["mensaje"]), False, "commit"),
    ("taller_compilar", "Compila en el teléfono, en segundo plano: objetivo pruebas (tests), apk (APK de depuración, sin instalar) o lint.", esquema({"objetivo": {"type": "string", "enum": list(OBJETIVOS)}}, ["objetivo"]), False, "compilar"),
    ("taller_deshacer", "Vuelve la rama de trabajo a un punto anterior (etiqueta respaldo/… o commit). Lo de antes queda respaldado.", esquema({"punto": S}, ["punto"]), False, "deshacer"),
    ("taller_nota", "Añade una entrada a la memoria técnica del proyecto (qué se cambió, por qué, resultado y pendientes).", esquema({"texto": S}, ["texto"]), False, "nota"),
    ("taller_subir", "Sube la rama de trabajo a GitHub (allí se compila y prueba). Solo ramas jarvis/…", esquema(), False, "subir"),
]


def servidor(taller, token, puerto):
    sesiones = set()
    tabla = {h[0]: h for h in HERRAMIENTAS}

    class Manejador(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def responder(self, codigo, obj=None, sesion=None):
            cuerpo = b"" if obj is None else json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(codigo)
            if obj is not None:
                self.send_header("Content-Type", "application/json; charset=utf-8")
            if sesion:
                self.send_header("Mcp-Session-Id", sesion)
            self.send_header("Content-Length", str(len(cuerpo)))
            self.end_headers()
            self.wfile.write(cuerpo)

        def do_GET(self):
            if self.path == "/salud":
                return self.responder(200, {"estado": "ok", "rama": taller.rama()})
            self.responder(404, {"error": "no existe"})

        def do_POST(self):
            if self.path != "/mcp":
                return self.responder(404, {"error": "no existe"})
            dada = self.headers.get("Authorization", "")
            if not hmac.compare_digest(dada.encode(), f"Bearer {token}".encode()):
                taller.registrar("RECHAZADO: credencial incorrecta")
                return self.responder(401, {"error": "credencial incorrecta"})
            largo = int(self.headers.get("Content-Length") or 0)
            try:
                m = json.loads(self.rfile.read(largo) or b"{}")
            except ValueError:
                return self.responder(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "JSON no válido"}})
            metodo, mid, params = m.get("method"), m.get("id"), m.get("params") or {}
            if mid is None:                                   # notificación
                return self.responder(202)
            if metodo == "initialize":
                s = secrets.token_hex(8); sesiones.add(s)
                return self.responder(200, {"jsonrpc": "2.0", "id": mid, "result": {
                    "protocolVersion": VERSION_MCP, "capabilities": {"tools": {}},
                    "serverInfo": {"name": "taller-jarvis", "version": "1.0"}}}, sesion=s)
            if metodo == "tools/list":
                return self.responder(200, {"jsonrpc": "2.0", "id": mid, "result": {"tools": [
                    {"name": n, "description": d, "inputSchema": e, "annotations": {"readOnlyHint": l}} for n, d, e, l, _ in HERRAMIENTAS]}})
            if metodo == "tools/call":
                nombre, args = params.get("name"), params.get("arguments") or {}
                h = tabla.get(nombre)
                if not h:
                    return self.responder(200, {"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": f"no existe la herramienta {nombre}"}})
                try:
                    texto, error = getattr(taller, h[4])(args), False
                except Fallo as e:
                    texto, error = f"No se pudo: {e}", True
                except subprocess.TimeoutExpired:
                    texto, error = "No se pudo: la operación tardó demasiado.", True
                except Exception as e:                        # nunca se cae el taller por una herramienta
                    texto, error = f"Error interno en {nombre}: {type(e).__name__}: {e}", True
                taller.registrar(f"{'FALLO' if error else 'ok'}  {nombre}  {json.dumps(args, ensure_ascii=False)[:200]}")
                return self.responder(200, {"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": texto}], "isError": error}})
            return self.responder(200, {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"método desconocido {metodo}"}})

    return ThreadingHTTPServer(("127.0.0.1", puerto), Manejador)


def principal():
    p = argparse.ArgumentParser(description="El taller de JARVIS")
    p.add_argument("--repo", required=True, help="el clon de git del proyecto (una copia, no la app instalada)")
    p.add_argument("--puerto", type=int, default=8090)
    p.add_argument("--datos", default="~/.jarvis-taller")
    p.add_argument("--token-archivo", default="~/.jarvis-taller/token")
    p.add_argument("--aapt2", default="", help="ruta del aapt2 de Termux (para compilar en el teléfono)")
    a = p.parse_args()
    archivo = os.path.expanduser(a.token_archivo)
    os.makedirs(os.path.dirname(archivo), exist_ok=True)
    if not os.path.isfile(archivo):
        with open(archivo, "w") as f:
            f.write(secrets.token_urlsafe(24))
        os.chmod(archivo, 0o600)
    token = open(archivo).read().strip()
    extra = [f"-Pandroid.aapt2FromMavenOverride={a.aapt2}"] if a.aapt2 else []
    taller = Taller(a.repo, datos=a.datos, gradle_extra=extra)
    srv = servidor(taller, token, a.puerto)
    taller.registrar(f"ARRANCA en 127.0.0.1:{a.puerto} sobre {taller.repo} (rama {taller.rama()})")
    print(f"Taller de JARVIS en http://127.0.0.1:{a.puerto}/mcp · repo {taller.repo} · rama {taller.rama()}")
    print(f"Credencial (cópiala en JARVIS → Ajustes → Puentes): {token}")
    srv.serve_forever()


if __name__ == "__main__":
    principal()
