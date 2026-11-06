import com.radar.jarvis.core.*;
import java.io.File;
import java.util.*;

/** FÉNIX 80 sin red: estados, una mejora a la vez, misión con intervalos y límites, aprendizaje y respuestas. Arg: carpeta temporal. */
public class EvolutionTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }

    /** Agente simulado: guiones de resultados por paso. */
    static class FakeWorker implements Evolution.Worker {
        boolean paused; List<String> script = new ArrayList<String>(); int diagnoses = 0;
        public boolean paused() { return paused; }
        public int diagnose(Evolution e) { diagnoses++; return 0; }
        public Map<String, Object> step(Evolution e, Map<String, Object> imp) throws Exception {
            String r = script.isEmpty() ? "avance" : script.remove(0);
            String id = String.valueOf(imp.get("id"));
            if ("exito".equals(r)) {
                String st = String.valueOf(imp.get("estado"));
                if (Evolution.ANALISIS.equals(st)) e.transition(id, Evolution.DESARROLLO, "", null, false);
                if (Evolution.DESARROLLO.equals(String.valueOf(e.get(id).get("estado")))) e.transition(id, Evolution.PRUEBAS, "", null, false);
                e.transition(id, Evolution.VERIFICADA, "ok", Json.map("pruebas_ok", true, "regresion_ok", true), false);
            }
            return Json.map("resultado", r, "api", 2L);
        }
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]); dir.mkdirs();
        Evolution evo = new Evolution(dir, new Experience(dir));
        // ---- estados y reglas
        Map<String, Object> m1 = evo.create("Herramienta A", "objetivo A", "lab/a", Evolution.HERRAMIENTA, "laboratorio y pruebas", Json.map("impacto", 2L), "prueba");
        Map<String, Object> m2 = evo.create("Arreglo de seguridad B", "objetivo B", "lab/b", Evolution.HERRAMIENTA, "estabilidad y seguridad", Json.map("impacto", 2L, "seguridad", 3L), "prueba");
        Map<String, Object> mN = evo.create("Cambio nativo C", "objetivo C", "Brain", Evolution.NATIVO, "estabilidad y seguridad", Json.map("impacto", 5L), "prueba");
        check(evo.create("Herramienta A", "otra vez", "lab/a", Evolution.HERRAMIENTA, "laboratorio y pruebas", null, "x").get("id").equals(m1.get("id")), "una mejora igual no se duplica");
        check(m2.get("id").equals(evo.nextExecutable().get("id")), "prioridad: seguridad antes que laboratorio; la nativa no se elige (no se compila en el teléfono)");
        evo.transition((String) m2.get("id"), Evolution.ANALISIS, "", null, false);
        try { evo.transition((String) m1.get("id"), Evolution.ANALISIS, "", null, false); check(false, "dos activas"); }
        catch (Exception e) { check(e.getMessage().contains("una mejora a la vez"), "REGLA: no empieza otra mientras hay una activa"); }
        try { evo.transition((String) m2.get("id"), Evolution.VERIFICADA, "", null, false); check(false, "saltó estados"); }
        catch (Exception e) { check(e.getMessage().contains("no permitida"), "no se saltan estados (análisis → verificada)"); }
        evo.transition((String) m2.get("id"), Evolution.DESARROLLO, "", null, false);
        evo.transition((String) m2.get("id"), Evolution.PRUEBAS, "", null, false);
        try { evo.transition((String) m2.get("id"), Evolution.VERIFICADA, "", Json.map("pruebas_ok", true, "regresion_ok", false), false); check(false, "verificada con regresión"); }
        catch (Exception e) { check(true, "no se verifica con regresiones"); }
        evo.transition((String) m2.get("id"), Evolution.VERIFICADA, "", Json.map("pruebas_ok", true, "regresion_ok", true), false);
        check(evo.active() == null, "verificada: deja de estar activa → puede seguir la siguiente");
        try { evo.transition((String) m2.get("id"), Evolution.APROBADA, "", null, false); check(false, "autoaprobada"); }
        catch (Exception e) { check(e.getMessage().contains("señor Rafael"), "solo el usuario aprueba"); }
        evo.transition((String) m2.get("id"), Evolution.APROBADA, "", null, true);
        evo.transition((String) m2.get("id"), Evolution.INTEGRADA, "", null, true);
        check(Evolution.INTEGRADA.equals(evo.get((String) m2.get("id")).get("estado")) && Json.arr(evo.get((String) m2.get("id")).get("historial")).size() == 7,
                "recorrido completo PENDIENTE → … → INTEGRADA con historial");

        // ---- misión: intervalos, límites, pausa
        FakeWorker w = new FakeWorker();
        long t = 1_000_000L;
        evo.start(t, false);
        String r1 = evo.tick(t, w);
        check(r1.startsWith("seleccionada " + m1.get("id")) && w.diagnoses == 1, "la misión diagnostica y selecciona UNA mejora: " + r1);
        w.script.add("fallo");
        evo.tick(t, w);
        check(evo.waitMs(t) == 60000, "tras un fallo: reintento a 1 minuto (configurable)");
        check(evo.tick(t + 30000, w).equals("esperando"), "no trabaja antes de tiempo");
        w.script.add("exito");
        evo.tick(t + 60000, w);
        check(evo.waitMs(t + 60000) == 120000, "tras un éxito: siguiente a los 2 minutos");
        w.paused = true;
        check(evo.tick(t + 200000, w).contains("pausa") && "PAUSADA".equals(evo.missionState()), "la PAUSA de JARVIS detiene la misión");
        w.paused = false;
        evo.resume(t + 200000);
        check("ACTIVA".equals(evo.missionState()), "se reanuda solo por orden");
        String r2 = evo.tick(t + 200000, w);
        check(r2.contains("sin mejoras ejecutables") && "TERMINADA".equals(evo.missionState()), "sin mejoras ejecutables: termina y lo dice (no inventa trabajo)");
        evo.create("Herramienta D", "d", "lab/d", Evolution.HERRAMIENTA, "programación", null, "prueba");
        evo.config.maxApiCalls = 3;
        evo.start(t, false);
        evo.tick(t, w); w.script.add("avance"); evo.tick(t, w); w.script.add("avance"); evo.tick(t, w);
        String r3 = evo.tick(t, w);
        check(r3.contains("límite de 3 llamadas"), "límite de llamadas a la API: se detiene → " + r3);
        evo.config.maxApiCalls = 40;
        evo.start(t, false);
        evo.stop("orden del usuario");
        check("DETENIDA".equals(evo.missionState()) && evo.tick(t, w).contains("detenida"), "detener la misión");

        // ---- aprendizaje y respuestas con registros reales
        evo.learn("lab/a", "conversión de segundos", "faltaba el relleno con ceros", "p(n)", "3/3", "verificada", 1.0, "Rellenar con ceros a dos cifras.", "hecho comprobado");
        Map<String, Object> l = evo.learnings().get(evo.learnings().size() - 1);
        check(l.keySet().containsAll(Arrays.asList("fecha", "modulo", "problema", "causa", "solucion", "pruebas", "resultado", "confianza", "aprendizaje", "tipo")),
                "experiencia con todos los campos pedidos");
        check(new Experience(dir).relevant("conversión de segundos con relleno", 3).toString().contains("hecho comprobado"), "el aprendizaje se recupera ante tareas parecidas");
        check(evo.learnedToday().contains("Rellenar con ceros"), "«¿qué aprendiste hoy?» → registro real");
        check(evo.newCapabilities().contains("integrada"), "«¿qué capacidades nuevas desarrollaste?» → " + evo.newCapabilities().split("\n")[0]);
        check(evo.missing().contains("Cambio nativo C") && evo.missing().contains("compilar"), "«¿qué te falta?» → lo nativo necesita compilar fuera");
        check(evo.whatImproving().contains("FÉNIX 80"), "«¿qué estás mejorando?» → " + evo.whatImproving());
        // ---- órdenes de voz
        check("start".equals(EvoCommands.parse("JARVIS, trabaja en ti mismo")) && "start".equals(EvoCommands.parse("JARVIS, TRABAJA EN TI MISMO.")), "«trabaja en ti mismo» → iniciar");
        check("pausa_total".equals(EvoCommands.parse("JARVIS, PAUSA TOTAL")), "«PAUSA TOTAL»");
        check("improving".equals(EvoCommands.parse("JARVIS, ¿qué estás mejorando?")) && "learned".equals(EvoCommands.parse("¿Qué aprendiste hoy?"))
                && "fixed".equals(EvoCommands.parse("JARVIS, ¿qué errores corregiste?")) && "new_caps".equals(EvoCommands.parse("¿qué capacidades nuevas desarrollaste?"))
                && "missing".equals(EvoCommands.parse("JARVIS, ¿qué te falta para evolucionar?")), "las 5 preguntas de evolución se reconocen");
        check(EvoCommands.parse("¿qué tiempo hace mañana en Madrid?") == null, "una pregunta normal no se confunde");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
