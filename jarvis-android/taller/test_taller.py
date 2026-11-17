"""Pruebas del taller. Usan un clon real del repositorio en una carpeta temporal.

    python3 -m unittest -v test_taller            # rápidas (gradlew falso)
    TALLER_REAL=1 python3 -m unittest -v test_taller.CompilacionReal   # compila de verdad el proyecto clonado
"""
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.request

import taller as T

AQUI = os.path.dirname(os.path.abspath(__file__))
REPO = subprocess.run(["git", "-C", AQUI, "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip()


def clonar(destino):
    subprocess.run(["git", "clone", "-q", "--no-hardlinks", REPO, destino], check=True)
    subprocess.run(["git", "-C", destino, "checkout", "-q", subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()], check=True)
    subprocess.run(["git", "-C", destino, "checkout", "-q", "-b", "principal"], check=True)


class ClienteMcp:
    """Como el de JARVIS: initialize, tools/list y tools/call con la credencial."""
    def __init__(self, url, token):
        self.url, self.token, self.n, self.sesion = url, token, 0, None

    def rpc(self, metodo, params=None, token=None):
        self.n += 1
        cuerpo = json.dumps({"jsonrpc": "2.0", "id": self.n, "method": metodo, "params": params or {}}).encode()
        r = urllib.request.Request(self.url, data=cuerpo, headers={"Content-Type": "application/json",
                                   "Authorization": f"Bearer {token or self.token}", **({"Mcp-Session-Id": self.sesion} if self.sesion else {})})
        with urllib.request.urlopen(r, timeout=120) as resp:
            self.sesion = resp.headers.get("Mcp-Session-Id") or self.sesion
            return json.loads(resp.read())

    def llamar(self, _herramienta, **args):
        r = self.rpc("tools/call", {"name": _herramienta, "arguments": args})["result"]
        return r["content"][0]["text"], r["isError"]


class Base(unittest.TestCase):
    gradlew = None

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="taller-")
        self.repo = os.path.join(self.tmp, "Radar")
        clonar(self.repo)
        self.datos = os.path.join(self.tmp, "datos")
        self.taller = T.Taller(self.repo, datos=self.datos, gradlew=self.gradlew)
        self.srv = T.servidor(self.taller, "secreto-de-prueba", 0)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.mcp = ClienteMcp(f"http://127.0.0.1:{self.srv.server_address[1]}/mcp", "secreto-de-prueba")
        self.mcp.rpc("initialize", {"protocolVersion": T.VERSION_MCP})

    def tearDown(self):
        self.srv.shutdown()
        shutil.rmtree(self.tmp, ignore_errors=True)


class Protocolo(Base):
    def test_lista_herramientas_con_lectura_marcada(self):
        h = {t["name"]: t for t in self.mcp.rpc("tools/list")["result"]["tools"]}
        self.assertTrue(h["taller_leer"]["annotations"]["readOnlyHint"])
        for cambia in ("taller_escribir", "taller_reemplazar", "taller_commit", "taller_compilar", "taller_deshacer", "taller_subir", "taller_rama"):
            self.assertFalse(h[cambia]["annotations"]["readOnlyHint"], cambia)
        self.assertNotIn("taller_terminal", h)            # no hay terminal libre

    def test_sin_credencial_no_entra(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.mcp.rpc("tools/list", token="otra")
        self.assertEqual(401, e.exception.code)

    def test_solo_escucha_en_el_propio_telefono(self):
        self.assertEqual("127.0.0.1", self.srv.server_address[0])


class Lectura(Base):
    def test_estado_leer_buscar(self):
        texto, err = self.mcp.llamar("taller_estado")
        self.assertFalse(err); self.assertIn("Rama: principal", texto); self.assertIn("no es de trabajo", texto)
        texto, err = self.mcp.llamar("taller_leer", ruta="jarvis-android/app/build.gradle.kts", desde=1, hasta=5)
        self.assertFalse(err); self.assertIn("    1  plugins {", texto)
        texto, _ = self.mcp.llamar("taller_buscar", patron="class LocalAIClient", ruta="jarvis-android/app/src")
        self.assertIn("LocalAIClient.kt:", texto)

    def test_no_sale_del_proyecto(self):
        for ruta in ("../../etc/passwd", "/etc/passwd/../../.."):
            texto, err = self.mcp.llamar("taller_leer", ruta=ruta)
            self.assertTrue(err, ruta)
        texto, err = self.mcp.llamar("taller_leer", ruta=".git/config")
        self.assertTrue(err)


class Cambios(Base):
    def test_no_cambia_nada_fuera_de_una_rama_jarvis(self):
        texto, err = self.mcp.llamar("taller_reemplazar", ruta="jarvis-android/README.md", buscar="# JARVIS para Android", reemplazar="# X")
        self.assertTrue(err); self.assertIn("jarvis/", texto)
        self.assertEqual("", subprocess.run(["git", "-C", self.repo, "status", "--porcelain"], capture_output=True, text=True).stdout)

    def test_flujo_completo_rama_cambio_commit_y_deshacer(self):
        texto, err = self.mcp.llamar("taller_rama", nombre="Saludo nuevo")
        self.assertFalse(err, texto); self.assertIn("jarvis/saludo-nuevo", texto)
        ruta = "jarvis-android/app/src/main/kotlin/com/radar/jarvis/nucleo/CerebroLocal.kt"
        original = open(os.path.join(self.repo, ruta), encoding="utf-8").read()
        texto, err = self.mcp.llamar("taller_reemplazar", ruta=ruta, buscar="Hablas en español, corto y claro", reemplazar="Hablas en español, muy corto y claro")
        self.assertFalse(err, texto)
        copia = texto.split("Copia del anterior: ")[1].rstrip(".")
        self.assertEqual(original, open(copia, encoding="utf-8").read())          # copia de seguridad del archivo
        texto, err = self.mcp.llamar("taller_commit", mensaje="Respuestas más cortas del cerebro local")
        self.assertFalse(err, texto); self.assertIn("CerebroLocal.kt", texto)
        puntos, _ = self.mcp.llamar("taller_puntos")
        etiqueta = puntos.split("\n")[1].split()[0]
        self.assertTrue(etiqueta.startswith("respaldo/"))
        texto, err = self.mcp.llamar("taller_deshacer", punto=etiqueta)
        self.assertFalse(err, texto)
        self.assertEqual(original, open(os.path.join(self.repo, ruta), encoding="utf-8").read())   # rollback comprobado
        # y lo deshecho no se pierde: queda en otro respaldo
        self.assertIn("antes de deshacer", subprocess.run(["git", "-C", self.repo, "tag", "-n1", "--list", "respaldo/*"], capture_output=True, text=True).stdout)

    def test_fragmento_ambiguo_no_se_cambia(self):
        self.mcp.llamar("taller_rama", nombre="prueba")
        texto, err = self.mcp.llamar("taller_reemplazar", ruta="jarvis-android/README.md", buscar="JARVIS", reemplazar="X")
        self.assertTrue(err); self.assertIn("veces", texto)

    def test_lo_protegido_no_se_toca(self):
        self.mcp.llamar("taller_rama", nombre="prueba")
        for ruta in ("jarvis-android/firma/jarvis.keystore", "jarvis-android/local.properties", ".github/workflows/android.yml",
                     "jarvis-android/gradlew", "jarvis-android/gradle/wrapper/gradle-wrapper.properties", ".git/hooks/pre-commit"):
            texto, err = self.mcp.llamar("taller_escribir", ruta=ruta, contenido="x")
            self.assertTrue(err, ruta); self.assertIn("protegido", texto)

    def test_script_de_gradle_se_puede_pero_avisa(self):
        self.mcp.llamar("taller_rama", nombre="prueba")
        texto, err = self.mcp.llamar("taller_reemplazar", ruta="jarvis-android/app/build.gradle.kts", buscar='versionName = "2.4"', reemplazar='versionName = "2.4.1"')
        self.assertFalse(err, texto); self.assertIn("script de Gradle", texto)

    def test_nota_en_la_memoria_tecnica(self):
        self.mcp.llamar("taller_rama", nombre="prueba")
        texto, err = self.mcp.llamar("taller_nota", texto="Se acortaron las respuestas del cerebro local; pruebas en verde.")
        self.assertFalse(err, texto)
        self.assertIn("pruebas en verde", open(os.path.join(self.repo, "jarvis-android/MEMORIA-TECNICA.md"), encoding="utf-8").read())

    def test_queda_registro(self):
        self.mcp.llamar("taller_estado")
        self.assertIn("taller_estado", open(os.path.join(self.datos, "taller.log"), encoding="utf-8").read())


class CompilacionFalsa(Base):
    """gradlew de mentira: deja ver cómo se resume una compilación sin esperar a Gradle."""
    def setUp(self):
        self.tmpg = tempfile.mkdtemp()
        self.gradlew = os.path.join(self.tmpg, "gradlew")
        with open(self.gradlew, "w") as f:
            f.write("#!/bin/sh\nsleep 1\necho 'e: file:///x/Agente.kt:12:5 Unresolved reference: foo'\necho 'BUILD FAILED in 1s'\nexit 1\n")
        os.chmod(self.gradlew, 0o755)
        super().setUp()

    def test_compila_en_segundo_plano_y_resume_errores(self):
        texto, err = self.mcp.llamar("taller_compilar", objetivo="pruebas")
        self.assertFalse(err, texto)
        texto2, err2 = self.mcp.llamar("taller_compilar", objetivo="apk")
        self.assertTrue(err2); self.assertIn("ya hay una compilación", texto2)       # una a la vez
        r, _ = self.mcp.llamar("taller_resultado", esperar_segundos=20)
        self.assertIn("FALLÓ", r); self.assertIn("Unresolved reference: foo", r)

    def test_objetivo_desconocido_no_ejecuta_nada(self):
        texto, err = self.mcp.llamar("taller_compilar", objetivo="rm -rf /")
        self.assertTrue(err)


@unittest.skipUnless(os.environ.get("TALLER_REAL"), "pon TALLER_REAL=1 para compilar de verdad")
class CompilacionReal(Base):
    def test_cambio_real_compila_y_pasan_las_pruebas(self):
        self.mcp.llamar("taller_rama", nombre="real")
        ruta = "jarvis-android/app/src/main/kotlin/com/radar/jarvis/nucleo/CerebroLocal.kt"
        self.assertFalse(self.mcp.llamar("taller_reemplazar", ruta=ruta, buscar="const val MAX_HISTORIAL = 8", reemplazar="const val MAX_HISTORIAL = 10")[1])
        self.mcp.llamar("taller_compilar", objetivo="pruebas")
        r = ""
        for _ in range(40):
            r, _ = self.mcp.llamar("taller_resultado", esperar_segundos=75)
            if "sigue en curso" not in r:
                break
        print("\n" + r)
        self.assertIn("BIEN", r); self.assertIn("0 fallidas", r)


if __name__ == "__main__":
    unittest.main()
