import com.radar.jarvis.core.*;
import java.io.File;
import java.util.*;

/** Laboratorio de automejora y aprendizaje por experiencia. Arg: carpeta temporal. */
public class LabTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }
    static List<Object> tests(Object... kv) { List<Object> l = new ArrayList<Object>(); for (int i = 0; i < kv.length; i += 2) l.add(Json.map("entrada", kv[i], "esperado", kv[i + 1])); return l; }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]); dir.mkdirs();
        Lab lab = new Lab(dir);
        NodeSandbox sb = new NodeSandbox();
        // ---- control de seguridad del código
        check(Lab.check("function run(e){ return e.x * 2; } // works with numbers").isEmpty(), "código limpio (una palabra prohibida en un comentario no cuenta)");
        check(Lab.check("function run(e){ return fetch('https://x.org'); }").toString().contains("fetch"), "usar la red: rechazado");
        check(Lab.check("function run(e){ return ({}).constructor.constructor('return 1')(); }").toString().contains("constructor"), "escapar por constructor: rechazado");
        check(Lab.check("function run(e){ var o = {}; return o['const' + 'ructor']; }").toString().contains("corchetes"), "acceso por texto entre corchetes: rechazado");
        check(Lab.check("var x = 1;").toString().contains("run(entrada)"), "sin función run: rechazado");

        // ---- candidata → pruebas aisladas → aprobación humana → uso
        String srt = "function run(entrada){ function t(s){ var h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=Math.floor(s%60),ms=Math.round((s-Math.floor(s))*1000);"
                + " function p(n,w){ n=String(n); while(n.length<w) n='0'+n; return n; } return p(h,2)+':'+p(m,2)+':'+p(x,2)+','+p(ms,3); }"
                + " var out=''; for (var i=0;i<entrada.length;i++){ out+=(i+1)+'\\n'+t(entrada[i].inicio)+' --> '+t(entrada[i].fin)+'\\n'+entrada[i].texto+'\\n\\n'; } return out; }";
        List<Object> ts = tests(
                Json.list(Json.map("inicio", 0.0, "fin", 1.5, "texto", "Hola")), "1\n00:00:00,000 --> 00:00:01,500\nHola\n\n",
                Json.list(Json.map("inicio", 3661.25, "fin", 3662.0, "texto", "Uno")), "1\n01:01:01,250 --> 01:01:02,000\nUno\n\n",
                new ArrayList<Object>(), "");
        Map<String, Object> c = lab.addCandidate("subtitulos-srt", "Subtítulos SRT", "convierte [{inicio, fin, texto}] en texto SRT", srt, ts, "formato SRT estándar", "prueba");
        check(Json.arr(c.get("problemas")).isEmpty() && "candidata".equals(lab.meta("subtitulos-srt", 1).get("estado")), "nueva versión guardada como CANDIDATA (inactiva)");
        try { lab.approve("subtitulos-srt", 1, true); check(false, "aprobar sin probar"); } catch (Exception e) { check(true, "no se aprueba sin pasar las pruebas"); }
        Map<String, Object> t = lab.test("subtitulos-srt", 1, sb);
        check(Boolean.TRUE.equals(t.get("ok")) && Long.valueOf(3).equals(t.get("pasan")), "pruebas aisladas: " + t.get("pasan") + "/" + t.get("total"));
        try { lab.approve("subtitulos-srt", 1, false); check(false, "aprobación automática"); } catch (Exception e) { check(true, "JARVIS no puede aprobarse solo: " + e.getMessage()); }
        lab.approve("subtitulos-srt", 1, true);
        check(lab.active().containsKey("subtitulos-srt") && lab.promptList().contains("subtitulos-srt"), "aprobada por el usuario: activa y visible para Claude");
        Map<String, Object> u = lab.use("subtitulos-srt", Json.list(Json.map("inicio", 1.0, "fin", 2.0, "texto", "Prueba")), sb);
        check(Boolean.TRUE.equals(u.get("ok")) && String.valueOf(u.get("salida")).contains("00:00:01,000 --> 00:00:02,000"), "uso de la herramienta aprobada");
        try { lab.use("no-existe", 1L, sb); check(false, "herramienta sin aprobar usada"); } catch (Exception e) { check(true, "una herramienta no aprobada no se puede usar"); }

        // ---- versión con error: falla y no se puede aprobar; la buena sí; volver atrás
        lab.addCandidate("subtitulos-srt", "Subtítulos SRT", "v2 con error", "function run(entrada){ return entrada.length; }", ts, "", "prueba");
        Map<String, Object> t2 = lab.test("subtitulos-srt", 2, sb);
        check(!Boolean.TRUE.equals(t2.get("ok")) && "fallida".equals(lab.meta("subtitulos-srt", 2).get("estado")), "versión con error: FALLIDA (" + t2.get("pasan") + "/" + t2.get("total") + ")");
        try { lab.approve("subtitulos-srt", 2, true); check(false, "aprobada una fallida"); } catch (Exception e) { check(true, "una versión fallida no se puede aprobar"); }
        lab.addCandidate("subtitulos-srt", "Subtítulos SRT", "v3", srt.replace("var out=''", "var out = ''"), ts, "", "prueba");
        lab.test("subtitulos-srt", 3, sb);
        lab.approve("subtitulos-srt", 3, true);
        check(((Number) Json.obj(lab.active().get("subtitulos-srt")).get("version")).intValue() == 3, "v3 aprobada y activa");
        check("v1".equals(lab.rollback("subtitulos-srt")) && ((Number) Json.obj(lab.active().get("subtitulos-srt")).get("version")).intValue() == 1, "restaurar la versión anterior: vuelve a v1");
        check("desactivada".equals(lab.rollback("subtitulos-srt")) && !lab.active().containsKey("subtitulos-srt"), "sin versión anterior: se desactiva");
        File[] backups = new File(dir, "lab/respaldos").listFiles();
        check(backups != null && backups.length >= 4, "respaldo antes de cada cambio (" + (backups == null ? 0 : backups.length) + ")");
        check(lab.log(50).size() >= 10, "registro de todas las modificaciones (" + lab.log(50).size() + " entradas)");

        // ---- aislamiento
        lab.addCandidate("bucle", "Bucle", "no termina", "function run(e){ while(true){} }", tests(1L, 1L, 2L, 2L), "", "prueba");
        Map<String, Object> t3 = lab.test("bucle", 1, sb);
        check(!Boolean.TRUE.equals(t3.get("ok")) && String.valueOf(t3.get("error")).contains("tiempo agotado"), "bucle infinito: cortado por tiempo → " + t3.get("error"));
        Map<String, Object> bad1 = lab.addCandidate("red", "Red", "intenta salir", "function run(e){ return fetch('https://evil.example'); }", tests(1L, 1L, 2L, 2L), "", "prueba");
        check(!Json.arr(bad1.get("problemas")).isEmpty() && "rechazada por seguridad".equals(lab.meta("red", 1).get("estado")), "herramienta que usa la red: rechazada por seguridad");
        try { lab.test("red", 1, sb); check(false, "se probó código inseguro"); } catch (Exception e) { check(true, "el código inseguro ni siquiera se ejecuta"); }
        lab.addCandidate("global", "Global", "busca el ámbito global", "function run(e){ var g = (function(){ return this; })(); return g === undefined ? 'aislado' : 'fuera'; }",
                tests(1L, "aislado", 2L, "aislado"), "", "prueba");
        check(Boolean.TRUE.equals(lab.test("global", 1, sb).get("ok")), "sin acceso al ámbito global (modo estricto)");
        check(lab.addProposal("módulo nativo", "Editar vídeo", "MediaCodec…").endsWith(".md") && lab.proposals().size() == 1, "propuesta guardada para el desarrollador");

        // ---- experiencia
        Experience ex = new Experience(dir);
        ex.record("investigar precio del seguro de hogar", "investigar_en_la_web", true, null, 1200);
        ex.record("investigar precio del seguro de coche", "investigar_en_la_web", false, "API 529: overloaded", 900);
        ex.record("investigar precio del seguro de moto", "investigar_en_la_web", false, "API 529: overloaded", 950);
        ex.learn("investigacion", "precio del seguro de hogar", "Comparar al menos tres aseguradoras: los precios varían mucho por código postal.");
        List<String> rel = ex.relevant("investigá el precio del seguro de hogar en Madrid", 5);
        check(rel.size() >= 2 && rel.toString().contains("tres aseguradoras") && rel.toString().contains("Fallo anterior"), "aprendizajes y fallos parecidos se recuperan: " + rel.size());
        check(ex.relevant("receta de tortilla de patatas", 5).isEmpty(), "nada parecido: no se inventa contexto");
        Map<String, Object> st = ex.stats();
        check(Long.valueOf(3).equals(st.get("tareas")) && Long.valueOf(2).equals(st.get("fallos")) && st.get("errores_repetidos").toString().contains("×2"), "estadística: 3 tareas, 2 fallos, error repetido detectado");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
