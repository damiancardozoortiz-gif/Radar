#!/usr/bin/env python3
"""Pruebas de los 3 fallos críticos de la auditoría. Reproducibles desde un entorno limpio:
solo librería estándar, sin internet, sin suscripción y sin ElevenLabs reales.

  C1  Host / DNS rebinding: TODAS las rutas (también GET) rechazan un Host desconocido.
  C2  PAUSA inmediata: corta las llamadas en curso y nada escribe después (concurrencia y respuesta tardía).
  C3  PAUSA y borradores persistentes: sobreviven a un reinicio (también a un kill -9) sin ejecutarse.
  C4  Puerto ocupado: mensaje claro, sin traza de Python.

Cada prueba arranca su propio servidor JARVIS en un puerto libre, con estado/ y memory/ temporales,
un `claude` simulado (tests/fake_claude.py) y un ElevenLabs simulado (tests/mock_elevenlabs.py).
No toca tu memory/ ni tu estado/ reales.

Uso:  python3 tests/test_criticos.py          (unos 2 minutos)
"""
import http.client
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
TESTS = ROOT / "tests"
FAKE = TESTS / "fake_claude.py"
MOCK = TESTS / "mock_elevenlabs.py"

# Para las pruebas en el mismo proceso: estado y memoria temporales ANTES de importar los módulos.
_INPROC = Path(tempfile.mkdtemp(prefix="jarvis-inproc-"))
os.environ["JARVIS_STATE_DIR"] = str(_INPROC / "estado")
os.environ["JARVIS_MEMORY_DIR"] = str(_INPROC / "memory")
os.environ["JARVIS_CLAUDE_BIN"] = str(FAKE)  # en el mismo proceso, nunca el claude real
sys.path.insert(0, str(ROOT / "agent"))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def setUpModule():
    if not (ROOT / "data" / "demo" / "meta.json").exists():
        subprocess.run([sys.executable, str(ROOT / "data" / "generate_demo.py")], check=True, capture_output=True)


class Mock:
    def __init__(self, delay=0.0):
        self.port = free_port()
        self.log = Path(tempfile.mkstemp(prefix="mock-el-", suffix=".log")[1])
        env = dict(os.environ, MOCK_DELAY=str(delay), MOCK_TTS_SECONDS="1")
        self.p = subprocess.Popen([sys.executable, str(MOCK), str(self.port), str(self.log)], env=env,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", self.port), 0.2).close()
                break
            except OSError:
                time.sleep(0.1)

    def stop(self):
        self.p.terminate()
        self.p.wait(5)


class Server:
    """Un JARVIS real (agent/main.py) con dependencias externas simuladas."""

    def __init__(self, workdir, sleep=0.2, mock=None, port=None):
        self.work = Path(workdir)
        self.port = port or free_port()
        self.claude_log = self.work / "claude.log"
        self.env = dict(os.environ)
        self.env.update({
            "JARVIS_PORT": str(self.port), "JARVIS_DEMO": "1",
            "JARVIS_STATE_DIR": str(self.work / "estado"), "JARVIS_MEMORY_DIR": str(self.work / "memory"),
            "JARVIS_CLAUDE_BIN": str(FAKE), "FAKE_CLAUDE_LOG": str(self.claude_log), "FAKE_CLAUDE_SLEEP": str(sleep),
            "ELEVENLABS_API_KEY": "clave-de-prueba-no-real",
            "ELEVENLABS_BASE_URL": f"http://127.0.0.1:{mock.port}" if mock else "http://127.0.0.1:9",
        })
        self.p = None

    def start(self, wait=True):
        self.err = self.work / f"server-{time.time_ns()}.err"
        self._errfh = open(self.err, "w")
        self.p = subprocess.Popen([sys.executable, str(ROOT / "agent" / "main.py")], cwd=str(ROOT), env=self.env,
                                  stdout=subprocess.DEVNULL, stderr=self._errfh)
        if wait:
            for _ in range(100):
                try:
                    if self.get("/api/status")[0] == 200:
                        return self
                except OSError:
                    pass
                time.sleep(0.1)
            raise RuntimeError("el servidor no arrancó: " + self.err.read_text())
        return self

    def stop(self, sig=signal.SIGTERM):
        if self.p and self.p.poll() is None:
            self.p.send_signal(sig)
            self.p.wait(10)
        if getattr(self, "_errfh", None):
            self._errfh.close()

    # --- HTTP con control exacto de la cabecera Host
    def request(self, method, path, body=None, host=None, origin=None, ctype="application/json", timeout=60):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        c.putrequest(method, path, skip_host=True, skip_accept_encoding=True)
        if host is not False:
            c.putheader("Host", host if host is not None else f"localhost:{self.port}")
        if origin:
            c.putheader("Origin", origin)
        data = b""
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            c.putheader("Content-Type", ctype)
        c.putheader("Content-Length", str(len(data)))
        c.endheaders()
        if data:
            c.send(data)
        r = c.getresponse()
        raw = r.read()
        c.close()
        return r.status, raw

    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, body, **kw):
        return self.request("POST", path, body, **kw)

    def jget(self, path):
        return json.loads(self.get(path)[1])

    def jpost(self, path, body, **kw):
        code, raw = self.post(path, body, **kw)
        return code, json.loads(raw or b"{}")

    def memory_files(self):
        d = self.work / "memory"
        return sorted(p.name for p in d.glob("*.md")) if d.exists() else []

    def claude_lines(self):
        return self.claude_log.read_text().splitlines() if self.claude_log.exists() else []

    def wait_claude_start(self, n=1, timeout=10):
        t = time.time()
        while time.time() - t < timeout:
            if sum(l.startswith("start") for l in self.claude_lines()) >= n:
                return True
            time.sleep(0.05)
        return False


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="jarvis-test-"))
        self.servers, self.mocks = [], []

    def tearDown(self):
        for s in self.servers:
            s.stop(signal.SIGKILL)
        for m in self.mocks:
            m.stop()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def server(self, **kw):
        s = Server(self.tmp, **kw)
        self.servers.append(s)
        return s

    def mock(self, **kw):
        m = Mock(**kw)
        self.mocks.append(m)
        return m


# =====================================================================================  C1
class C1_Host(Base):
    ROUTES = ["/", "/index.html", "/app.js", "/styles.css", "/favicon.svg", "/favicon.ico", "/api/status",
              "/api/graph", "/api/note?name=" + quote("Logística Delta"), "/api/note?id=" + quote("vault/Clientes/Logística Delta.md"),
              "/api/top", "/api/route?q=hola", "/api/memory", "/no-existe"]
    PRIVATE = [b"Log\xc3\xadstica", b"Sevilla", b"Tel\xc3\xa9fono", b"Renovaciones", b"notes", b"<html", b"J.A.R.V.I.S"]

    def setUp(self):
        super().setUp()
        self.s = self.server().start()

    def assert_rejected(self, code, raw, what):
        self.assertEqual(code, 403, what)
        self.assertEqual(json.loads(raw), {"error": "host u origen no permitido"}, what)
        for word in self.PRIVATE:
            self.assertNotIn(word, raw, f"{what}: se filtró {word!r}")

    def test_todas_las_rutas_get_rechazan_host_ajeno(self):
        for path in self.ROUTES:
            code, raw = self.s.get(path, host="web-mala.example")
            self.assert_rejected(code, raw, f"GET {path} con Host: web-mala.example")

    def test_las_cuatro_rutas_de_la_auditoria(self):
        for path in ["/api/status", "/api/graph", "/api/note?name=" + quote("Logística Delta"), "/api/memory"]:
            code, raw = self.s.get(path, host="web-mala.example")
            self.assert_rejected(code, raw, path)
            ok_code, ok_raw = self.s.get(path)  # el mismo dato SÍ llega con el Host legítimo
            self.assertEqual(ok_code, 200, path)
            self.assertGreater(len(ok_raw), 20, path)

    def test_ataque_exacto_por_socket(self):
        req = (b"GET /api/note?name=Log%C3%ADstica%20Delta HTTP/1.1\r\n"
               b"Host: web-mala.example\r\nConnection: close\r\n\r\n")
        with socket.create_connection(("127.0.0.1", self.s.port), 5) as sock:
            sock.sendall(req)
            resp = b""
            while chunk := sock.recv(65536):
                resp += chunk
        head, _, body = resp.partition(b"\r\n\r\n")
        self.assertTrue(head.startswith(b"HTTP/1.0 403") or head.startswith(b"HTTP/1.1 403"), head[:40])
        self.assert_rejected(403, body, "socket")

    def test_variantes_de_host(self):
        p = self.s.port
        for host in ["web-mala.example", f"web-mala.example:{p}", "localhost", "127.0.0.1", f"localhost:{p + 1}",
                     f"localhost:{p}.web-mala.example", f"evil.localhost:{p}", f"[::1]:{p}", "", "0.0.0.0:%d" % p]:
            code, raw = self.s.get("/api/status", host=host)
            self.assert_rejected(code, raw, f"Host={host!r}")
        code, raw = self.s.get("/api/status", host=False)  # sin cabecera Host
        self.assert_rejected(code, raw, "sin Host")

    def test_hosts_permitidos(self):
        for host in [f"localhost:{self.s.port}", f"127.0.0.1:{self.s.port}"]:
            code, raw = self.s.get("/api/status", host=host)
            self.assertEqual(code, 200, host)
            self.assertEqual(json.loads(raw)["mode"], "demo")

    def test_origin_ajeno_no_basta_con_host_bueno(self):
        code, raw = self.s.get("/api/graph", origin="https://web-mala.example")
        self.assert_rejected(code, raw, "GET con Origin ajeno")
        code, raw = self.s.post("/api/pause", {"on": True}, origin="https://web-mala.example")
        self.assertEqual(code, 403)
        self.assertFalse(self.s.jget("/api/status")["pause"]["on"])

    def test_post_con_host_ajeno(self):
        for path, body in [("/api/pause", {"on": True}), ("/api/ask", {"text": "hola"}),
                           ("/api/approve", {"id": "x", "decision": "aprobar"}), ("/api/speak", {"text": "hola"})]:
            code, raw = self.s.post(path, body, host="web-mala.example")
            self.assert_rejected(code, raw, f"POST {path}")
        self.assertFalse(self.s.jget("/api/status")["pause"]["on"])
        self.assertFalse(any("Mensaje" in l or "pid" in l and "hola" in l for l in self.s.claude_lines()[1:]),
                         "una petición rechazada no debe llegar a claude")


# =====================================================================================  C2
class C2_PausaInmediata(Base):
    def ask_async(self, s, text, conv, results, key):
        def run():
            results[key] = s.jpost("/api/ask", {"text": text, "conv": conv}, timeout=90)
        t = threading.Thread(target=run)
        t.start()
        return t

    def test_pausa_corta_recordar_en_curso_sin_escribir_memoria(self):
        s = self.server(sleep=6).start()
        s.wait_claude_start(1)  # calentamiento
        time.sleep(6.5)
        res = {}
        t = self.ask_async(s, "Recuerda que la pausa no debe guardar esto", "c1", res, "a")
        self.assertTrue(s.wait_claude_start(2), "claude no arrancó")
        time.sleep(0.5)
        t0 = time.time()
        code, st = s.jpost("/api/pause", {"on": True})
        pause_ms = (time.time() - t0) * 1000
        self.assertEqual(code, 200)
        self.assertLess(pause_ms, 3000, "la pausa no tuvo prioridad")
        t.join(30)
        code, o = res["a"]
        self.assertTrue(o.get("paused"), o)
        self.assertIsNone(o.get("saved"))
        self.assertEqual(s.memory_files(), [], "se escribió memoria con la pausa activa")
        time.sleep(7)  # el tiempo que habría tardado la respuesta
        self.assertEqual(s.memory_files(), [], "escritura tardía")
        lines = s.claude_lines()
        tag = [l for l in lines if l.startswith("start") and "recuerda=True" in l][0].split()[2]  # turn=pid:n
        self.assertTrue(tag.startswith("turn="))
        self.assertFalse(any(l.startswith("end") and l.split()[2] == tag for l in lines),
                         "el turno de claude no se cortó: terminó igualmente")
        self.assertGreaterEqual(s.jget("/api/status")["brain"]["cancelled"], 1)

    def test_pausa_impide_borrador_tardio(self):
        s = self.server(sleep=4).start()
        time.sleep(4.5)
        res = {}
        t = self.ask_async(s, "Escribe un borrador de correo para Ricardo", "c2", res, "a")
        self.assertTrue(s.wait_claude_start(2))
        time.sleep(0.5)
        s.jpost("/api/pause", {"on": True})
        t.join(30)
        self.assertTrue(res["a"][1].get("paused"))
        self.assertIsNone(res["a"][1].get("approval"))
        self.assertEqual(s.jget("/api/status")["approvals"], [])
        f = s.work / "estado" / "borradores.json"
        self.assertTrue(not f.exists() or json.loads(f.read_text()) == [])

    def test_concurrencia_varias_peticiones(self):
        s = self.server(sleep=4).start()
        time.sleep(4.5)
        res, threads = {}, []
        for i in range(5):
            text = f"Recuerda que la prueba concurrente {i} no se guarda" if i % 2 == 0 else f"Escribe un borrador número {i}"
            threads.append(self.ask_async(s, text, f"conv{i}", res, i))
        self.assertTrue(s.wait_claude_start(6))
        time.sleep(0.3)
        s.jpost("/api/pause", {"on": True})
        for t in threads:
            t.join(30)
        self.assertTrue(all(res[i][1].get("paused") for i in range(5)), res)
        time.sleep(5)
        self.assertEqual(s.memory_files(), [])
        self.assertEqual(s.jget("/api/status")["approvals"], [])
        n_paused = sum(l.startswith("start") for l in s.claude_lines())
        code, o = s.jpost("/api/ask", {"text": "Recuerda que esto tampoco", "conv": "x"})
        self.assertTrue(o.get("paused"))
        time.sleep(1)
        self.assertEqual(sum(l.startswith("start") for l in s.claude_lines()), n_paused, "en pausa no se lanza claude")

    def test_voz_tardia_no_se_entrega(self):
        m = self.mock(delay=3)
        s = self.server(mock=m).start()
        res = {}
        t = threading.Thread(target=lambda: res.setdefault("r", s.post("/api/speak", {"text": "hola"}, timeout=30)))
        t.start()
        time.sleep(1)
        s.jpost("/api/pause", {"on": True})
        t.join(30)
        code, raw = res["r"]
        self.assertEqual(code, 423, "el audio llegó después de la pausa")
        self.assertNotIn(b"RIFF", raw)
        self.assertEqual(s.post("/api/listen", b"\0" * 64, ctype="audio/webm")[0], 423)

    # --- en el mismo proceso: una respuesta que NO se puede cancelar llega tarde
    def test_respuesta_tardia_no_cancelable(self):
        import cerebro
        import estado
        import memory
        from vault import VAULT
        VAULT.load()
        estado.set_pause(False)
        before = sorted(memory.MEMORY.glob("*.md")) if memory.MEMORY.exists() else []

        def late_call(*a, **k):
            estado.set_pause(True, by="prueba")  # la pausa llega mientras "Claude" responde
            return {"result": json.dumps({"decir": "Guardado.", "detalle": "Borrador: hola",
                                          "recordar": "esto no debe guardarse"}), "session_id": "s", "duration_api_ms": 1}
        orig = cerebro.brain_ask
        cerebro.brain_ask = lambda cid, c, message, web=False: late_call()
        try:
            o = cerebro.answer("tarde", "Recuerda que esto no debe guardarse y escribe un borrador")
        finally:
            cerebro.brain_ask = orig
            estado.set_pause(False)
        self.assertTrue(o.get("paused"), o)
        self.assertIsNone(o.get("saved"))
        self.assertIsNone(o.get("approval"))
        self.assertEqual(sorted(memory.MEMORY.glob("*.md")) if memory.MEMORY.exists() else [], before)
        self.assertEqual(estado.pending(), [])

    def test_escrituras_bloqueadas_en_pausa(self):
        import estado
        import memory
        estado.set_pause(True, by="prueba")
        try:
            with self.assertRaises(estado.Paused):
                memory.save("no")
            with self.assertRaises(estado.Paused):
                estado.add_draft("c", "borrador", "q")
            with self.assertRaises(estado.Paused):
                estado.decide("x", "aprobar")
        finally:
            estado.set_pause(False)

    def test_carrera_ninguna_escritura_tras_volver_set_pause(self):
        import estado
        import memory
        estado.set_pause(False)
        stop = threading.Event()
        written = []

        def writer():
            i = 0
            while not stop.is_set():
                try:
                    r = memory.save(f"carrera {i} {time.time_ns()}")
                    written.append((time.perf_counter(), r["file"]))
                except estado.Paused:
                    pass
                i += 1
        ths = [threading.Thread(target=writer) for _ in range(4)]
        for t in ths:
            t.start()
        time.sleep(0.3)
        estado.set_pause(True, by="carrera")
        t_pause = time.perf_counter()
        time.sleep(0.3)
        stop.set()
        for t in ths:
            t.join()
        estado.set_pause(False)
        self.assertGreater(len(written), 0, "la prueba no llegó a escribir antes de la pausa")
        after = [w for w in written if w[0] > t_pause]
        self.assertEqual(after, [], f"{len(after)} escrituras después de la pausa")
        for p in memory.MEMORY.glob("*carrera*.md"):
            p.unlink()


# =====================================================================================  C3
class C3_Persistencia(Base):
    def test_pausa_sobrevive_reinicio_y_kill9(self):
        s = self.server().start()
        s.jpost("/api/pause", {"on": True})
        f = s.work / "estado" / "pausa.json"
        self.assertTrue(json.loads(f.read_text())["on"])
        self.assertEqual(oct(f.stat().st_mode & 0o777), "0o600")
        self.assertEqual(oct(f.parent.stat().st_mode & 0o777), "0o700")
        for sig in (signal.SIGTERM, signal.SIGKILL):
            s.stop(sig)
            n_before = len(s.claude_lines())
            s.start()
            st = s.jget("/api/status")
            self.assertTrue(st["pause"]["on"], f"la pausa se perdió tras {sig.name}")
            self.assertEqual(st["brain"]["brain"], "en pausa")
            time.sleep(1)
            self.assertEqual(len(s.claude_lines()), n_before, "en pausa no debe calentar ni llamar a claude")
            self.assertTrue(s.jpost("/api/ask", {"text": "hola", "conv": "r"})[1].get("paused"))
        s.jpost("/api/pause", {"on": False})
        s.stop()
        s.start()
        self.assertFalse(s.jget("/api/status")["pause"]["on"], "quitar la pausa también persiste")

    def test_borradores_sobreviven_sin_ejecutarse_ni_duplicarse(self):
        m = self.mock()
        s = self.server(mock=m).start()
        ids = []
        for i in range(2):
            code, o = s.jpost("/api/ask", {"text": f"Escribe un borrador de correo número {i}", "conv": "d"})
            self.assertEqual(code, 200)
            ids.append(o["approval"]["id"])
        self.assertEqual(len(set(ids)), 2)
        mem_before = s.memory_files()
        tts_before = m.log.read_text().count("TTS")
        for sig in (signal.SIGKILL, signal.SIGTERM):
            s.stop(sig)
            n_start = len(s.claude_lines())
            s.start()
            time.sleep(1)
            pend = s.jget("/api/status")["approvals"]
            self.assertEqual(sorted(a["id"] for a in pend), sorted(ids), f"borradores tras {sig.name}")
            self.assertTrue(all(a["state"] == "pendiente" for a in pend))
            new = s.claude_lines()[n_start:]
            self.assertFalse(any("borrador=True" in l for l in new), "un borrador se re-ejecutó al arrancar")
            self.assertEqual(s.memory_files(), mem_before)
            self.assertEqual(m.log.read_text().count("TTS"), tts_before, "se habló al recuperar")
        f = s.work / "estado" / "borradores.json"
        self.assertEqual(oct(f.stat().st_mode & 0o777), "0o600")
        code, d = s.jpost("/api/approve", {"id": ids[0], "decision": "aprobar"})
        self.assertEqual(d["state"], "aprobado")
        self.assertIn("No se ha enviado nada", d["result"])
        s.stop()
        s.start()
        pend = s.jget("/api/status")["approvals"]
        self.assertEqual([a["id"] for a in pend], [ids[1]])
        saved = json.loads(f.read_text())
        self.assertEqual(sorted(a["id"] for a in saved), sorted(ids), "sin pérdidas ni duplicados en disco")
        self.assertEqual([a["state"] for a in saved if a["id"] == ids[0]], ["aprobado"])

    def test_decidir_en_pausa_no_se_permite(self):
        s = self.server().start()
        aid = s.jpost("/api/ask", {"text": "Escribe un borrador", "conv": "p"})[1]["approval"]["id"]
        s.jpost("/api/pause", {"on": True})
        code, o = s.jpost("/api/approve", {"id": aid, "decision": "aprobar"})
        self.assertEqual(code, 423)
        s.jpost("/api/pause", {"on": False})
        self.assertEqual([a["id"] for a in s.jget("/api/status")["approvals"]], [aid])

    def test_estado_ilegible_arranca_en_pausa(self):
        d = self.tmp / "estado"
        d.mkdir(parents=True)
        (d / "pausa.json").write_text("{esto no es json")
        s = self.server().start()
        st = s.jget("/api/status")
        self.assertTrue(st["pause"]["on"], "con estado ilegible debe arrancar en pausa")
        self.assertTrue(any("ilegible" in w for w in st["state_warnings"]))
        self.assertTrue(list(d.glob("pausa.json.ilegible-*")), "el archivo roto se aparta, no se borra")


# =====================================================================================  C4
class C4_Puerto(Base):
    def test_puerto_ocupado_mensaje_claro(self):
        s = self.server().start()
        s2 = Server(self.tmp / "otro", port=s.port)
        (self.tmp / "otro").mkdir()
        s2.start(wait=False)
        s2.p.wait(15)
        s2._errfh.close()
        err = s2.err.read_text()
        self.assertEqual(s2.p.returncode, 1)
        self.assertIn("ya está en uso", err)
        self.assertIn(f"JARVIS_PORT={s.port + 1}", err)
        self.assertNotIn("Traceback", err)
        self.assertFalse((self.tmp / "otro" / "claude.log").exists(), "no debe llamar a claude si no arranca")


# =====================================================================================  C5
class C5_ComandoClaude(Base):
    def test_flags_de_seguridad(self):
        s = self.server().start()
        s.jpost("/api/ask", {"text": "Hola", "conv": "f"})
        line = [l for l in s.claude_lines() if l.startswith("start")][-1]
        self.assertIn("tools=''", line)
        self.assertIn("safe_mode=True", line)
        for flag in ["--output-format", "--model", "--effort", "--system-prompt"]:
            self.assertIn(flag, line)
        self.assertNotIn("--json-schema", line)


# =====================================================================================  C6
class C6_Recuperacion(Base):
    def test_respuesta_degenerada_se_corta_y_se_recupera(self):
        s = self.server().start()
        t0 = time.time()
        code, o = s.jpost("/api/ask", {"text": "Haz un bucle con Bash", "conv": "g"})
        secs = time.time() - t0
        self.assertEqual(code, 200)
        self.assertLess(secs, 10, "la respuesta degenerada no se cortó a tiempo")
        self.assertEqual(o["brain"], "respaldo")
        self.assertIn("degenerada", o["warning"])
        self.assertNotIn("<invoke", o["say"])
        code, o2 = s.jpost("/api/ask", {"text": "Hola", "conv": "g"})
        self.assertEqual(o2["brain"], "claude", "tras el fallo, el siguiente turno funciona")
        self.assertEqual(s.jget("/api/status")["brain"]["brain"], "listo")

    def test_proceso_claude_muerto_se_recupera(self):
        s = self.server().start()
        s.jpost("/api/ask", {"text": "Hola", "conv": "k"})
        pids = [l.split()[1] for l in s.claude_lines() if l.startswith("start")]
        for pid in set(pids):  # matamos a mano los procesos claude, como si fallaran
            try:
                os.kill(int(pid.split("=")[1]), signal.SIGKILL)
            except ProcessLookupError:
                pass
        time.sleep(0.5)
        code, o = s.jpost("/api/ask", {"text": "Hola otra vez", "conv": "k"})
        if o["brain"] != "claude":  # el primer intento puede caer en respaldo; el siguiente debe funcionar
            code, o = s.jpost("/api/ask", {"text": "Hola otra vez", "conv": "k"})
        self.assertEqual(o["brain"], "claude")

    def test_sin_huerfanos_tras_apagar(self):
        s = self.server().start()
        for i in range(3):
            s.jpost("/api/ask", {"text": "Hola", "conv": f"o{i}"})
        pids = {int(l.split()[1].split("=")[1]) for l in s.claude_lines() if l.startswith("start")}
        s.stop(signal.SIGTERM)
        time.sleep(1.5)
        alive = [p for p in pids if os.path.exists(f"/proc/{p}")]
        self.assertEqual(alive, [], "quedaron procesos claude vivos tras apagar")

    def test_sin_huerfanos_tras_kill9_del_servidor(self):
        s = self.server().start()
        s.jpost("/api/ask", {"text": "Hola", "conv": "z"})
        pids = {int(l.split()[1].split("=")[1]) for l in s.claude_lines() if l.startswith("start")}
        s.stop(signal.SIGKILL)
        time.sleep(2)
        alive = [p for p in pids if os.path.exists(f"/proc/{p}")]
        self.assertEqual(alive, [], "si el servidor muere de golpe, los claude deben terminar solos")


# =====================================================================  C7
PNG_1x1 = ("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


class C7_Dispositivo(Base):
    def test_ordenes_del_telefono_no_llaman_a_claude(self):
        s = self.server().start()
        time.sleep(0.5)
        n0 = sum(l.startswith("start") for l in s.claude_lines())
        cases = {"abre la cámara": "camera_open", "hazme una foto": "camera_capture", "¿dónde estoy?": "location",
                 "busca recetas en youtube": "open_app", "escribe por whatsapp diciendo hola": "open_app"}
        for text, action in cases.items():
            code, o = s.jpost("/api/ask", {"text": text, "conv": "d"})
            self.assertEqual(o["tool"], "dispositivo", text)
            self.assertEqual(o["device"]["action"], action, text)
        self.assertEqual(sum(l.startswith("start") for l in s.claude_lines()), n0, "una orden del teléfono no gasta Claude")
        o = s.jpost("/api/ask", {"text": "escribe por whatsapp diciendo hola", "conv": "d"})[1]
        self.assertTrue(o["device"]["url"].startswith("https://wa.me/"), "solo enlace oficial; nada se envía solo")

    def test_detener_todo_pausa_el_servidor(self):
        s = self.server().start()
        code, o = s.jpost("/api/ask", {"text": "JARVIS, detener todo", "conv": "d"})
        self.assertTrue(o.get("paused"))
        self.assertEqual(o["device"]["action"], "stop_all")
        st = s.jget("/api/status")
        self.assertTrue(st["pause"]["on"])
        self.assertEqual(st["pause"]["by"], "detener todo")

    def test_vision_valida_y_respeta_la_pausa(self):
        s = self.server().start()
        code, o = s.jpost("/api/vision", {"image": "data:text/html;base64,PGgxPg==", "question": "x"})
        self.assertEqual(code, 400, "solo imágenes")
        code, o = s.jpost("/api/vision", {"image": PNG_1x1, "question": "¿Qué ves?"})
        self.assertEqual(code, 200, o)
        self.assertEqual(o["brain"], "claude")
        s.jpost("/api/pause", {"on": True})
        code, o = s.jpost("/api/vision", {"image": PNG_1x1, "question": "¿Qué ves?"})
        self.assertEqual(code, 423, "en pausa no se analiza nada")
        code, o = s.jpost("/api/archivo", {"name": "a.txt", "text": "hola", "question": "resume"})
        self.assertEqual(code, 423)

    def test_pausa_corta_una_vision_en_curso(self):
        s = self.server(sleep=5).start()
        time.sleep(5.5)
        res = {}
        th = threading.Thread(target=lambda: res.update(r=s.jpost("/api/vision", {"image": PNG_1x1, "question": "¿Qué ves?"}, timeout=60)))
        th.start()
        self.assertTrue(s.wait_claude_start(2))
        time.sleep(0.5)
        t0 = time.time()
        s.jpost("/api/pause", {"on": True})
        self.assertLess(time.time() - t0, 3)
        th.join(30)
        self.assertEqual(res["r"][0], 423, res)

    def test_registro_de_acciones_sin_contenido_privado(self):
        s = self.server().start()
        s.jpost("/api/dispositivo/evento", {"tipo": "foto", "detalle": "capturada 640x480"})
        s.jpost("/api/archivo", {"name": "secreto.txt", "text": "CONTRASEÑA-SUPERSECRETA-123", "question": "resume"})
        s.jpost("/api/vision", {"image": PNG_1x1, "question": "¿Qué ves?"})
        f = s.work / "estado" / "acciones.jsonl"
        raw = f.read_text()
        self.assertNotIn("SUPERSECRETA", raw)
        self.assertNotIn("iVBOR", raw, "nunca se guarda la imagen")
        self.assertEqual(oct(f.stat().st_mode & 0o777), "0o600")
        acts = s.jget("/api/dispositivo")["acciones"]
        self.assertTrue(any(a["tipo"] == "vision" for a in acts) and any(a["tipo"] == "archivo" for a in acts))
        cat = s.jget("/api/dispositivo")
        self.assertFalse(cat["remoto"]["activo"], "el acceso remoto está desactivado por defecto")
        for k in ("camera", "microphone", "geolocation", "notifications", "files"):
            self.assertTrue(cat["permisos"][k]["para"] and cat["permisos"][k]["android"], k)
        wa = [i for i in cat["integraciones"] if i["id"] == "whatsapp"][0]
        self.assertTrue(any("Leer tus mensajes" in x for x in wa["no_puede"]), "no se promete leer WhatsApp")


class C8_AccesoRemoto(Base):
    HOST = "mi-ordenador.ejemplo.ts.net"
    TOKEN = "t" * 20 + "0123456789abcdefABCDEF"  # 42 caracteres

    def remote(self, token=None):
        s = self.server()
        s.env.update(JARVIS_REMOTE_HOST=self.HOST, JARVIS_ACCESS_TOKEN=token or self.TOKEN)
        return s.start()

    def test_sin_token_rechaza(self):
        s = self.remote()
        code, raw = s.get("/api/status", host=self.HOST)
        self.assertEqual(code, 403)
        code, raw = s.get("/?token=incorrecto", host=self.HOST)
        self.assertEqual(code, 403)

    def test_emparejar_y_usar_con_cookie(self):
        s = self.remote()
        c = http.client.HTTPConnection("127.0.0.1", s.port, timeout=10)
        c.putrequest("GET", "/?token=" + self.TOKEN, skip_host=True); c.putheader("Host", self.HOST); c.endheaders()
        r = c.getresponse(); r.read()
        self.assertEqual(r.status, 303)
        ck = r.getheader("Set-Cookie")
        for attr in ("HttpOnly", "Secure", "SameSite=Strict"):
            self.assertIn(attr, ck)
        cookie = ck.split(";")[0]
        c = http.client.HTTPConnection("127.0.0.1", s.port, timeout=10)
        c.putrequest("GET", "/api/status", skip_host=True); c.putheader("Host", self.HOST); c.putheader("Cookie", cookie); c.endheaders()
        r = c.getresponse(); body = r.read()
        self.assertEqual(r.status, 200)
        self.assertTrue(json.loads(body)["remote"])
        for origin, expect in ((f"https://{self.HOST}", 200), (f"http://{self.HOST}", 403), ("https://web-mala.example", 403)):
            c = http.client.HTTPConnection("127.0.0.1", s.port, timeout=10)
            c.putrequest("POST", "/api/pause", skip_host=True); c.putheader("Host", self.HOST); c.putheader("Cookie", cookie)
            c.putheader("Origin", origin); c.putheader("Content-Type", "application/json"); data = b'{"on": false}'
            c.putheader("Content-Length", str(len(data))); c.endheaders(); c.send(data)
            r = c.getresponse(); r.read()
            self.assertEqual(r.status, expect, origin)
        code, raw = s.get("/api/status", host="otro.ts.net")
        self.assertEqual(code, 403, "solo el host configurado")

    def test_token_corto_no_activa_el_acceso(self):
        s = self.remote(token="corto")
        code, raw = s.get("/?token=corto", host=self.HOST)
        self.assertEqual(code, 403)
        self.assertFalse(s.jget("/api/status")["remote"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
