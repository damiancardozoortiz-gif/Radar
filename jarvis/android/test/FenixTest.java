import com.radar.jarvis.core.*;
import java.io.File;
import java.util.*;

/** Misión real de FÉNIX 80 con VULKAN, Claude SIMULADO (mock) y aislamiento Node. Args: carpeta temporal, assets, puerto, mock.log */
public class FenixTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }

    public static void main(String[] a) throws Exception {
        File data = new File(a[0]); data.mkdirs();
        final File assets = new File(a[1]);
        Brain b = new Brain(data, new Vault.Source() {
            public String read(String p) throws java.io.IOException { return new String(java.nio.file.Files.readAllBytes(new File(assets, p).toPath()), "UTF-8"); }
            public String[] list(String d) { String[] l = new File(assets, d).list(); return l == null ? new String[0] : l; }
        });
        b.apiKey = "sk-ant-prueba"; b.baseUrl = "http://127.0.0.1:" + a[2];
        NodeSandbox sb = new NodeSandbox();
        // la app ya reconoció dos cosas que no sabe y hay un error repetido
        b.lab.addProposal("capacidad que falta", "convertir segundos a formato reloj", "Pedido: …");
        b.experience.record("pregunta", "conversacion", false, "API 529: overloaded", 100);
        b.experience.record("pregunta", "conversacion", false, "API 529: overloaded", 100);
        List<Map<String, Object>> known = new ArrayList<Map<String, Object>>();
        known.add(Json.map("nombre", "Pérdida del día real en el control de órdenes", "objetivo", "usar la pérdida real", "modulo", "FinanceActivity",
                "tipo", "nativo", "categoria", "estabilidad y seguridad", "puntos", Json.map("impacto", 4L, "seguridad", 5L)));
        Evolution evo = new Evolution(data, b.experience);
        evo.config.retryMs = 0; evo.config.nextMs = 0;  // en la prueba no se espera
        Vulkan vk = new Vulkan(b, sb, new Vulkan.Probe() { public List<Map<String, Object>> run() {
            return Arrays.asList(Json.map("modulo", "claude", "ok", true, "detalle", "clave guardada"), Json.map("modulo", "micrófono", "ok", false, "detalle", "sin permiso de micrófono", "tipo", "humano"));
        }}, known, evo.dir());

        evo.start(0, false);
        List<String> trace = new ArrayList<String>();
        for (int i = 0; i < 30 && "ACTIVA".equals(evo.missionState()); i++) trace.add(evo.tick(0, vk));
        for (String s : trace) System.out.println("      · " + s);
        Map<String, Object> m = null;
        for (Map<String, Object> x : evo.all()) if (String.valueOf(x.get("nombre")).contains("segundos")) m = x;
        check(m != null && Evolution.VERIFICADA.equals(m.get("estado")), "la capacidad que faltaba llega a VERIFICADA");
        List<String> states = new ArrayList<String>();
        for (Object h : Json.arr(m.get("historial"))) states.add(String.valueOf(Json.obj(h).get("estado")));
        check(states.equals(Arrays.asList("PENDIENTE", "EN ANÁLISIS", "EN DESARROLLO", "EN PRUEBAS", "CORRIGIENDO", "EN PRUEBAS", "VERIFICADA")),
                "recorrido real: la 1.ª versión falla, se corrige con los fallos y pasa → " + states);
        check(Json.arr(m.get("errores_corregidos")).size() == 1 && Json.arr(m.get("errores_corregidos")).get(0).toString().contains("01:01:01"),
                "error corregido registrado con el caso real que fallaba");
        Map<String, Object> ev = Json.obj(Json.arr(m.get("evidencias")).get(0));
        check(Boolean.TRUE.equals(ev.get("pruebas_ok")) && Boolean.TRUE.equals(ev.get("regresion_ok")) && ev.get("antes") != null && ev.get("despues") != null,
                "evidencias: pruebas, regresión y comparación antes/después");
        check(b.lab.active().isEmpty(), "verificada NO es integrada: nada se activó sin el usuario");
        Map<String, Object> nat = null, hum = null;
        for (Map<String, Object> x : evo.all()) { if (String.valueOf(x.get("nombre")).startsWith("Error repetido")) nat = x; if ("micrófono".equals(x.get("modulo"))) hum = x; }
        check(nat != null && Evolution.BLOQUEADA.equals(nat.get("estado")) && x(nat.get("propuesta")), "error repetido → agente android redacta la propuesta nativa → BLOQUEADA (compilar fuera)");
        check(hum != null && Evolution.PENDIENTE.equals(hum.get("estado")) && evo.missing().contains("micrófono"), "comprobación fallida del teléfono → necesita al usuario (no se toca sola)");
        check(evo.missing().contains("Pérdida del día"), "pendiente conocido de la auditoría visible en «qué te falta»");
        check("TERMINADA".equals(evo.missionState()), "la misión termina sola cuando no quedan mejoras ejecutables: " + evo.mission().get("motivo"));
        check(((Number) evo.mission().get("api")).intValue() == 5, "llamadas a Claude contadas: " + evo.mission().get("api") + " (arquitectura ×2, android, programación ×2)");
        Map<String, Object> ag = vk.agentsStatus();
        int withLog = 0; for (Object v : ag.values()) if (((Number) Json.obj(v).get("acciones")).intValue() > 0) withLog++;
        check(withLog >= 6, "agentes con registro propio y diferenciado: " + withLog + " (arquitectura, android, programación, seguridad, calidad, memoria)");
        String mock = new String(java.nio.file.Files.readAllBytes(new File(a[3]).toPath()), "UTF-8");
        check(mock.contains("\"agente\": \"arquitectura\"") && mock.contains("\"agente\": \"programacion\"") && mock.contains("\"agente\": \"android\"") && !mock.contains("\"agente\": \"investigacion\""),
                "cada agente hace su propia consulta; investigación NO (la web no estaba autorizada)");
        check(new File(evo.dir(), "inventario.json").isFile(), "inventario técnico actualizado tras la misión");
        Map<String, Object> inv = SelfCheck.inventory(b, evo, vk, vk.lastProbes());
        check(inv.get("fallando").toString().contains("micrófono") && inv.get("fallando").toString().contains("overloaded"), "autodiagnóstico: sabe qué está fallando");
        check(evo.learnedToday().contains("hecho comprobado") && evo.learnedToday().contains("hipótesis"), "distingue hechos comprobados e hipótesis en lo aprendido");
        check(evo.errorsFixed().contains("01:01:01"), "«¿qué errores corregiste?» responde con el caso real");
        // aprobación humana → integración, con respaldo y vuelta atrás
        Vulkan.approve(evo, b.lab, String.valueOf(m.get("id")));
        String tool = String.valueOf(evo.get(String.valueOf(m.get("id"))).get("herramienta"));
        check(Evolution.INTEGRADA.equals(evo.get(String.valueOf(m.get("id"))).get("estado")) && b.lab.active().containsKey(tool), "tras su aprobación: INTEGRADA y activa (" + tool + ")");
        Map<String, Object> use = b.lab.use(tool, 3725L, sb);
        check("01:02:05".equals(use.get("salida")), "la capacidad nueva funciona: 3725 s → " + use.get("salida"));
        check(b.lab.regression(sb).get("ok").equals(true), "regresión de todas las herramientas activas: ok");
        check(new File(data, "lab/respaldos").listFiles().length >= 1, "respaldo antes de integrar");
        // PAUSA TOTAL
        evo.start(0, false);
        b.setPause(true, "PAUSA TOTAL");
        check(evo.tick(0, vk).contains("pausa") && "PAUSADA".equals(evo.missionState()), "PAUSA TOTAL: la misión se detiene y no hace nada");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
    static boolean x(Object o) { return o != null && new File(String.valueOf(o)).isFile(); }
}
