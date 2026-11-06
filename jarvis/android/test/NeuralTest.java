import com.radar.jarvis.core.*;
import java.io.File;
import java.util.*;

/** Neural Core: red neuronal de intención (real, entrenada aquí), enrutador, plan, nodos, memoria y aprendizaje.
 *  Args: carpeta temporal, assets, puerto del Claude SIMULADO, mock.log, corpus. */
public class NeuralTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }
    static String nodes(Map<String, Object> r) { StringBuilder b = new StringBuilder(); for (Object o : Json.arr(Json.obj(r.get("neural")).get("nodos"))) b.append(Json.obj(o).get("nodo")).append(' '); return b.toString().trim(); }

    public static void main(String[] a) throws Exception {
        File data = new File(a[0]); data.mkdirs();
        final File assets = new File(a[1]);
        String corpus = new String(java.nio.file.Files.readAllBytes(new File(a[4]).toPath()), "UTF-8");
        Vault.Source src = new Vault.Source() {
            public String read(String p) throws java.io.IOException { return new String(java.nio.file.Files.readAllBytes(new File(assets, p).toPath()), "UTF-8"); }
            public String[] list(String d) { String[] l = new File(assets, d).list(); return l == null ? new String[0] : l; }
        };
        Brain b = new Brain(data, src);
        b.apiKey = "sk-ant-prueba"; b.baseUrl = "http://127.0.0.1:" + a[2];
        NeuralCore nc = new NeuralCore(b, data, corpus);
        Map<String, Object> red = Json.obj(nc.status().get("red"));
        check(((Number) red.get("entrenada_con")).intValue() >= 110 && red.get("precision_validacion") != null, "red neuronal entrenada en el teléfono: " + red);

        // ---- precisión con frases NUEVAS (escritas para la prueba, no están en los ejemplos de entrenamiento)
        String[][] hold = {
            {"che jarvis, todo bien?","conversacion"},{"qué pensás de mi idea","conversacion"},{"decime algo gracioso","conversacion"},
            {"buscá en internet cuánto sale un iphone","investigar_en_la_web"},{"averiguá qué dicen del nuevo auto de Toyota","investigar_en_la_web"},{"investigá el precio del alquiler en Palermo","investigar_en_la_web"},
            {"haceme un informe de la competencia en seguros","informe"},{"necesito un reporte completo del trimestre","informe"},{"armá un informe sobre el mercado inmobiliario","informe"},
            {"recordá que el martes viene el plomero","recordar"},{"anotá que mi suegra cumple el 3","recordar"},{"acordate de que no me gusta el picante","recordar"},
            {"agregá una tarea: mandar la factura","tareas"},{"cuáles son mis tareas","tareas"},{"marcá hecha la tarea de la factura","tareas"},
            {"leeme los correos de hoy","leer_correo"},{"me llegó algún mail del contador","leer_correo"},{"revisá los emails","leer_correo"},
            {"organizame la tarde","planear_el_dia"},{"qué hago primero hoy","planear_el_dia"},{"armame un plan para la semana","planear_el_dia"},
            {"qué tengo en la agenda mañana","parte_del_dia"},{"tengo alguna reunión hoy","parte_del_dia"},{"qué compromisos tengo","parte_del_dia"},
            {"aprendé a calcular porcentajes","aprender"},{"quiero que aprendas a resumir textos","aprender"},{"creá una herramienta para convertir fechas","aprender"},
            {"escribí un posteo para LinkedIn","redactar"},{"redactá un mensaje de bienvenida para clientes","redactar"},{"armá un guion para un reel","redactar"}};
        NeuralNet net = new NeuralNet(NeuralCore.INTENTS);
        List<String[]> ex = new ArrayList<String[]>();
        Map<String, Object> c = Json.obj(Json.parse(corpus));
        for (String k : c.keySet()) for (Object o : Json.arr(c.get(k))) ex.add(new String[]{String.valueOf(o), k});
        Collections.shuffle(ex, new Random(3));
        net.train(ex, 80, 0.1f);
        int good = 0;
        for (String[] h : hold) if (h[1].equals(net.predict(h[0]).get("intencion"))) good++;
        check(good >= 26, "red sola con 30 frases nuevas: " + good + "/30 aciertos (" + Math.round(good * 100.0 / 30) + " %)");

        // ---- persistencia de los pesos
        NeuralCore nc2 = new NeuralCore(b, data, corpus);
        check(nc2.decide("fijate qué onda el precio del alquiler en Palermo").toString().equals(nc.decide("fijate qué onda el precio del alquiler en Palermo").toString()),
                "los pesos se guardan y se cargan: misma decisión tras reabrir");
        // ---- enrutador: reglas primero; la red solo cuando las reglas no reconocen y está segura
        check("reglas".equals(nc.decide("JARVIS, investigá este producto").get("fuente")), "lo que ya reconocen las reglas no cambia");
        Map<String, Object> d = nc.decide("fijate qué onda el precio del alquiler en Palermo");
        check("investigar_en_la_web".equals(d.get("ruta")) && "red neuronal".equals(d.get("fuente")), "frase que las reglas no entienden → la red la envía a investigar (" + d.get("confianza") + ")");
        Map<String, Object> low = nc.decide("qué pensás de mi idea");
        check(!"red neuronal".equals(low.get("fuente")) && !"parte_del_dia".equals(low.get("ruta")),
                "red poco segura (" + Json.obj(low.get("red")).get("confianza") + " a «" + Json.obj(low.get("red")).get("intencion") + "») → no decide la red (ruta: " + low.get("ruta") + ")");
        check("conversacion".equals(nc.decide("asdf qwerty zxcv").get("ruta")), "texto sin sentido → conversación");
        check("redactar".equals(nc.decide("redactá un mensaje de bienvenida").get("ruta")), "corregida una regla vieja: «redactá un mensaje» ya no va a leer correo");
        // ---- corrección humana
        String odd = "buscame reseñas del nuevo Samsung";
        String before = String.valueOf(nc.decide(odd).get("ruta"));
        nc.correct(odd, "investigar_en_la_web");
        Map<String, Object> after = nc.decide(odd);
        check(!"investigar_en_la_web".equals(before) && "investigar_en_la_web".equals(after.get("ruta")), "corrección humana: antes «" + before + "», después «" + after.get("ruta") + "» (" + after.get("confianza") + ")");
        // ---- plan de varios pasos
        List<Map<String, Object>> plan = nc.plan("JARVIS, investigá este producto y prepará una publicación para Instagram");
        check(plan.size() == 2 && "investigar_en_la_web".equals(Json.obj(plan.get(0).get("decision")).get("ruta")) && "redactar".equals(Json.obj(plan.get(1).get("decision")).get("ruta")),
                "pedido compuesto → 2 pasos: investigar y redactar");
        check(nc.plan("buscá el precio y la disponibilidad del iPhone").size() == 1, "«y» sin un segundo verbo de acción no parte el pedido");

        // ---- proceso completo con Claude SIMULADO
        Map<String, Object> r = nc.process("JARVIS, investigá este producto y prepará una publicación para Instagram");
        String ns = nodes(r);
        check(ns.contains("planificacion") && ns.contains("investigacion") && ns.indexOf("percepcion") == 0 && ns.endsWith("respuesta"),
                "nodos activados en orden: " + ns);
        check(String.valueOf(r.get("detail")).contains("## Paso 1") && String.valueOf(r.get("detail")).contains("## Paso 2"), "el resultado reúne los 2 pasos");
        String ml = new String(java.nio.file.Files.readAllBytes(new File(a[3]).toPath()), "UTF-8");
        check(ml.contains("\"paso_anterior\": true") && ml.contains("Herramienta que ya ejecut\\u00f3 la app: redactar"), "el paso 2 recibe el resultado del paso 1 y va a redactar");
        Map<String, Object> s1 = nc.process("hola, ¿cómo estás?");
        check(!nodes(s1).contains("planificacion") && !nodes(s1).contains("investigacion"), "pedido simple: no activa módulos innecesarios (" + nodes(s1) + ")");
        Map<String, Object> st = Json.obj(nc.status().get("nodos"));
        check("inactivo".equals(Json.obj(st.get("ejecucion")).get("estado")) && "listo".equals(Json.obj(st.get("razonamiento")).get("estado")), "estados reales de los nodos (ejecución inactiva, razonamiento listo)");
        // sin clave: razonamiento en error, validación lo detecta
        b.apiKey = "";
        Map<String, Object> nk = nc.process("contame un chiste");
        st = Json.obj(nc.status().get("nodos"));
        check("error".equals(Json.obj(st.get("validacion")).get("estado")) && "respaldo".equals(nk.get("brain")), "sin cerebro: la validación lo marca como error (no lo disimula)");
        nc.setConnected("razonamiento", false, "falta la clave de Claude");
        check("desconectado".equals(Json.obj(Json.obj(nc.status().get("nodos")).get("razonamiento")).get("estado")), "nodo desconectado se muestra desconectado");
        b.apiKey = "sk-ant-prueba";
        nc.setConnected("razonamiento", true, "clave guardada");
        // PAUSA
        b.setPause(true, "prueba");
        Map<String, Object> pz = nc.process("investigá el dólar");
        check(Boolean.TRUE.equals(pz.get("paused")), "en PAUSA el Neural Core no ejecuta nada");
        b.setPause(false, "prueba");
        // aprendizaje
        int exBefore = nc.examples().size();
        nc.process("buscá en internet el precio del café");
        check(nc.examples().size() == exBefore + 1, "cada pedido resuelto queda como ejemplo para reentrenar la red");
        // memoria por niveles y conversación persistente
        Map<String, Object> lv = NeuralMemory.levels(b);
        check(lv.keySet().size() == 8 && ((Number) lv.get("inmediata")).intValue() > 0, "memoria en 8 niveles: " + lv);
        Brain b2 = new Brain(data, src);
        check(b2.historySize() == b.historySize() && b2.historySize() > 0, "la conversación reciente sobrevive al cerrar la app (" + b2.historySize() + " turnos)");
        check(Json.obj(nc.status().get("red")).get("acuerdo_con_reglas") != null, "métrica real: acuerdo de la red con las reglas " + Json.obj(nc.status().get("red")).get("acuerdo_con_reglas") + " %");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
