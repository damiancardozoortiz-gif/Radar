import com.radar.jarvis.core.*;
import java.io.*;
import java.util.*;

/** Prueba del núcleo en el ordenador (sin Android) contra la API simulada. */
public class CoreTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }

    public static void main(String[] a) throws Exception {
        final File assets = new File(a[0]);
        File data = new File(a[1]);
        Vault.Source src = new Vault.Source() {
            public String read(String p) throws IOException { return new String(java.nio.file.Files.readAllBytes(new File(assets, p).toPath()), "UTF-8"); }
            public String[] list(String d) throws IOException { String[] l = new File(assets, d).list(); if (l == null) throw new IOException(d); return l; }
        };
        Brain b = new Brain(data, src);
        b.baseUrl = "http://127.0.0.1:" + a[2];
        check(b.vault.notes.size() > 400, "las notas de JARVIS van dentro de la app (" + b.vault.notes.size() + ")");
        Map<String, Object> r = b.answer("Hola");
        check("respaldo".equals(r.get("brain")) && String.valueOf(r.get("warning")).contains("clave de API"), "sin clave: modo respaldo que lo avisa");
        b.apiKey = "sk-ant-prueba-123";
        r = b.answer("Hola, ¿me escuchas?");
        check("claude".equals(r.get("brain")) && String.valueOf(r.get("say")).startsWith("Respuesta simulada"), "conversación con la API (simulada): " + r.get("say"));
        r = b.answer("¿y la segunda?");
        check(String.valueOf(r.get("say")).contains("número 3"), "envía el historial de la conversación (3 mensajes)");
        r = b.answer("¿Qué pólizas tiene Logística Delta?");
        check("buscar_en_mi_cerebro".equals(r.get("tool")), "herramienta: buscar en tus archivos");
        r = b.answer("¿Qué tengo hoy?");
        check("parte_del_dia".equals(r.get("tool")), "herramienta: parte del día");
        r = b.answer("Recuerda que la cita del dentista es el jueves");
        check(r.get("saved") != null && b.memories().size() == 1, "memoria guardada: " + b.memories());
        r = b.answer("Escribe un borrador de correo para Ricardo");
        Map<String, Object> d = Json.obj(r.get("approval"));
        check(d != null && b.pending().size() == 1, "borrador pendiente de tu sí");
        r = b.answer("sí");
        check("autorizacion".equals(r.get("tool")) && b.pending().size() == 1, "decir «sí» no aprueba");
        Brain b2 = new Brain(data, src);
        check(b2.pending().size() == 1, "el borrador sobrevive a reabrir la app");
        check("aprobado".equals(b.decide(String.valueOf(d.get("id")), true).get("state")) && b.pending().isEmpty(), "aprobar con clic (no envía nada)");
        r = b.vision("¿Qué ves?", new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, 3}, "image/jpeg");
        check("Veo un cuadrado rojo.".equals(r.get("say")), "visión: la imagen va en base64 a la API");
        r = b.answer("busca música relajante en youtube");
        check("dispositivo".equals(r.get("tool")) && String.valueOf(Json.obj(r.get("device")).get("url")).contains("youtube.com/results?search_query=m%C3%BAsica"), "orden del teléfono sin gastar API: " + Json.obj(r.get("device")).get("url"));
        // pausa durante una llamada lenta
        final Brain bb = b;
        final Map<String, Object>[] res = new Map[1];
        Thread t = new Thread(new Runnable() { public void run() { res[0] = bb.answer("Recuerda que esto es LENTO y no debe guardarse"); } });
        t.start(); Thread.sleep(800);
        long t0 = System.currentTimeMillis(); b.setPause(true, "prueba"); long dt = System.currentTimeMillis() - t0;
        t.join(10000);
        check(Boolean.TRUE.equals(res[0].get("paused")) && b.memories().size() == 1 && dt < 3000, "PAUSA corta la llamada en curso (" + dt + " ms) y no escribe memoria");
        check(new Brain(data, src).isPaused(), "la PAUSA sobrevive a reabrir la app");
        r = b.answer("Hola");
        check(Boolean.TRUE.equals(r.get("paused")), "en pausa no se llama a la API");
        b.setPause(false, "");
        r = b.answer("JARVIS, detener todo");
        check(b.isPaused() && "stop_all".equals(Json.obj(r.get("device")).get("action")), "«detener todo» activa la PAUSA");
        b.setPause(false, "");
        String log = new String(java.nio.file.Files.readAllBytes(new File(data, "estado/acciones.jsonl").toPath()), "UTF-8");
        check(!log.contains("dentista") && !log.contains("Ricardo"), "el registro no guarda contenido privado");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
