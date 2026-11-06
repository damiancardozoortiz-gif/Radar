package com.radar.jarvis.core.cerebro;

import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.ciencia.Expr;
import com.radar.jarvis.core.ciencia.Quimica;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CEREBRO MODULAR DE JARVIS: árbol de 2.047 módulos + puerta neuronal + motores reales.
 * Estado HONESTO de cada módulo:
 *  - operativo:   tiene código real que se ejecuta por la puerta y una prueba individual que pasa;
 *  - conectado:   la función existe en JARVIS (se nombra la clase) pero aún no se invoca por la puerta;
 *  - planificado: definido en el árbol (responsabilidad, padre, hijos), sin implementación todavía.
 * Los nodos internos agregan el estado de sus hojas. resolver() reconoce problemas de matemática, física y química,
 * los envía por la puerta al módulo hoja correcto y después al módulo de verificación (conexión cruzada).
 * Todavía NO está enchufado al flujo principal de JARVIS: eso requiere autorización del usuario.
 */
public final class Cerebro {
    final Arbol arbol;
    final Map<Integer, Modulo> modulos = new HashMap<Integer, Modulo>();
    public final Puerta puerta;
    int idVerificacion, idSeguridadQuimica, nTrazas;

    /** Funciones que YA existen en JARVIS (fuera de la puerta): hoja → clase que la implementa. */
    static final String[][] CONECTADOS = {
        {"permiso de micrófono", "MainActivity (permisos)"}, {"cancelación de eco", "Voice (AcousticEchoCanceler)"}, {"calibración del ruido de fondo", "Vad"},
        {"seguimiento del ruido", "Vad"}, {"grabación m4a", "Voice (MediaRecorder)"}, {"grabación PCM", "Voice (AudioRecord)"},
        {"umbral adaptable", "Vad"}, {"modulación silábica", "Vad"}, {"silencio final", "Vad"}, {"interrupción del usuario", "Voice (barge-in)"},
        {"detección de palmadas", "ClapService"}, {"métricas de detección", "VoiceMetrics"},
        {"transcripción ElevenLabs", "Voice (Scribe)"}, {"transcripción Android", "Voice (SpeechRecognizer)"}, {"idioma es-AR", "Voice"}, {"palabras con tiempos", "Voice.transcribeFull"},
        {"transcripción de vídeo", "VideoEditor.ensureWords"}, {"medición del reconocimiento", "VoiceMetrics"}, {"PAUSA hablada", "Router (pausa total)"},
        {"captura de foto", "MainActivity (cámara)"}, {"foto a visión", "Brain (visión)"}, {"descripción de escena", "Brain (visión)"},
        {"lectura de PDF", "Brain.pdf"}, {"resumen de documento", "Brain.pdf"}, {"selector de archivos", "MainActivity (ACTION_OPEN_DOCUMENT)"},
        {"análisis de vídeo", "VideoTools.probe"}, {"miniaturas de vídeo", "VideoTools.thumbnail"}, {"fotogramas clave", "VideoTools.probe"}, {"audio de vídeo", "VideoTools.extractAudio"},
        {"CSV de MT5", "fin (importación CSV)"}, {"archivos de subtítulos", "Subtitles.srt"},
        {"red neuronal de intención", "NeuralNet"}, {"reglas del enrutador", "Router"}, {"confianza mínima de la red", "NeuralCore (0,8)"},
        {"corrección de intención", "NeuralCore.correct"}, {"reentrenamiento de intención", "NeuralCore (cada 10 ejemplos)"},
        {"abrir chat", "UiCommands"}, {"cerrar chat", "UiCommands"}, {"mostrar resultados", "UiCommands"}, {"abrir historial", "UiCommands"}, {"abrir editor de vídeo", "UiCommands"},
        {"abrir panel", "UiCommands"}, {"abrir laboratorio", "UiCommands"}, {"abrir evolución", "UiCommands"}, {"abrir neural core", "UiCommands"}, {"ocultar ventanas", "UiCommands"},
        {"historial de conversación", "Brain (historial.json)"}, {"pedidos compuestos", "NeuralCore.plan"}, {"división de pedidos", "NeuralCore.plan"},
        {"respuesta breve hablada", "Brain"}, {"detalle en markdown", "Brain"}, {"borradores de correo", "Brain (redactar)"}, {"publicaciones para redes", "Brain (redactar)"},
        {"ElevenLabs en streaming", "Voice.streamTts + PcmStream"}, {"ElevenLabs MP3", "Voice"}, {"voz de Android", "Voice (TextToSpeech es-AR)"}, {"métricas de síntesis", "VoiceMetrics"},
        {"tratamiento señor Rafael", "prompt.md / CLAUDE.md"}, {"saludo según la hora", "Greeting"},
        {"historial corto", "Brain"}, {"persistencia del historial", "Brain (historial.json)"}, {"notas del vault", "Vault"}, {"búsqueda en notas", "Vault"},
        {"registro de tareas", "Experience"}, {"éxitos", "Experience"}, {"fallos", "Experience"}, {"aprendizajes financieros", "Discipline (lecciones)"},
        {"aprendizajes de evolución", "Evolution.learn"}, {"recuerdos explícitos", "Brain (recordar)"}, {"corpus de intenciones", "neural-corpus.json"}, {"inventario técnico", "SelfCheck"},
        {"razonamiento con Claude", "Brain.answer"}, {"verificación de fuentes", "Brain.verifySources"}, {"enlaces inventados", "Brain.verifySources"},
        {"plan del día", "Brain (planear_el_dia)"}, {"parte del día", "Brain (parte_del_dia)"}, {"tareas pendientes", "Brain (tareas)"},
        {"misión FÉNIX", "Evolution"}, {"una mejora a la vez", "Evolution"}, {"estados de mejora", "Evolution"}, {"misión pausada", "Evolution"},
        {"límite de llamadas a Claude", "Evolution (límites)"},
        {"investigación web", "Brain (web_search)"}, {"lectura de páginas", "Brain (web_fetch)"}, {"informes", "Brain (informe)"}, {"editor de vídeo", "VideoEditor"},
        {"exportación de vídeo", "VideoTools.export"}, {"subtítulos SRT", "Subtitles"}, {"plan de Short", "ShortPlan"}, {"lectura de MetaApi", "MetaApi (solo lectura)"},
        {"herramientas JavaScript", "Lab"}, {"control de seguridad del código", "Lab.check"}, {"pruebas aisladas", "LabSandbox"}, {"aislamiento WebView", "LabSandbox"},
        {"versiones de herramientas", "Lab"}, {"aprobación de herramientas", "Lab.approve"}, {"restauración de versión", "Lab.rollback"},
        {"abrir apps con confirmación", "MainActivity"}, {"RADAR", "assets/radar"},
        {"gestor de riesgo", "fin"}, {"regla de disciplina", "Discipline"}, {"conexión MT5", "MetaApi"}, {"velas de MT5", "MetaApi.candles"},
        {"importar vídeo", "VideoEditor"}, {"recortar vídeo", "Timeline"}, {"unir vídeos", "VideoTools.export"}, {"vista previa del montaje", "VideoEditor"}, {"proyecto de vídeo", "VideoEditor (proyecto.json)"},
        {"ejemplos semilla", "neural-corpus.json"}, {"correcciones humanas", "NeuralCore.correct"}, {"red de intención MLP", "NeuralNet"}, {"descenso de gradiente", "NeuralNet"},
        {"entropía cruzada", "NeuralNet"}, {"rasgos por hashing", "NeuralNet"}, {"guardado de pesos", "NeuralNet"}, {"carga de pesos", "NeuralNet"},
        {"comprobaciones del teléfono", "SelfCheck"}, {"qué puedo hacer", "SelfCheck"}, {"pruebas automáticas", "build.sh test"}, {"pruebas en emulador", "ci/prueba_emulador.sh"},
        {"pruebas de firma", "ci/prueba_firma.sh"}, {"firma original", "build.sh (certificado esperado)"}, {"versionCode creciente", "ci/prueba_firma.sh"},
        {"aprobación humana", "Vulkan.approve / Lab.approve"}, {"aprobación de borradores", "Brain (borradores)"}, {"autorización de ciclo financiero", "Discipline"},
        {"PAUSA", "MainActivity (botón PAUSA)"}, {"PAUSA TOTAL", "Router / EvoCommands"}, {"bloqueo financiero", "Discipline"},
        {"claves cifradas", "Keystore de Android"}, {"operaciones reales deshabilitadas", "MetaApi.execute (siempre SOLO LECTURA)"}, {"solo cuenta DEMO", "fin"},
        {"recibir misión", "Vulkan"}, {"asignar especialistas", "Vulkan"}, {"agente de arquitectura", "Vulkan"}, {"agente de investigación", "Vulkan"}, {"agente de programación", "Vulkan"},
        {"agente android", "Vulkan"}, {"agente de seguridad", "Vulkan"}, {"agente de calidad", "Vulkan"}, {"agente de memoria", "Vulkan"},
        {"holograma de estado", "Hologram"}, {"panel de evolución", "MainActivity (ventana evo)"}, {"ventana neural", "MainActivity (ventana neural)"},
    };
    /** Hojas implementadas por la propia puerta (se pueden consultar: devuelven su estado real). */
    static final String[] DE_LA_PUERTA = {"enrutamiento de mensajes", "identificación de módulos", "contratos de datos", "control de permisos", "gestión de errores",
            "registro de actividad", "control de ciclos", "límites de ejecución", "recuperación ante fallos", "conexiones jerárquicas", "conexiones horizontales",
            "conexiones cruzadas", "memoria compartida", "activación bajo demanda", "retroalimentación", "bloqueo de bucles infinitos", "límites de mensajes de la puerta"};

    public Cerebro(String arbolJson, File dir, Puerta.Reloj reloj, Puerta.Pausa pausa) {
        arbol = new Arbol(arbolJson);
        puerta = new Puerta(arbol, modulos, reloj, pausa, dir == null ? null : new File(dir, "cerebro/actividad.jsonl"));
        for (final String[] def : Motores.DEF) {
            int id = hoja(def[0]);
            Modulo m = new Modulo(id);
            m.estado = Modulo.OPERATIVO;
            m.implementacion = "motor local (core/ciencia)";
            for (String c : def[1].split(",")) if (!c.isEmpty()) { String[] kv = c.split(":"); m.entrada.put(kv[0], kv[1]); }
            m.salida.put("verificado", "bool");
            m.prueba = Json.obj(Json.parse(def[3]));
            m.esperado = new String[]{def[4], def[5]};
            m.motor = new Motor() { public Map<String, Object> ejecutar(Map<String, Object> in) throws Exception { return Motores.run(def[0], in); } };
            modulos.put(id, m);
        }
        for (final String n : DE_LA_PUERTA) {
            int id = hoja(n);
            Modulo m = new Modulo(id);
            m.estado = Modulo.OPERATIVO;
            m.implementacion = "Puerta";
            m.prueba = new LinkedHashMap<String, Object>();
            m.esperado = new String[]{"verificado", "true"};
            m.motor = new Motor() { public Map<String, Object> ejecutar(Map<String, Object> in) { Map<String, Object> o = new LinkedHashMap<String, Object>(puerta.estado()); o.put("componente", n); o.put("verificado", true); return o; } };
            m.salida.put("verificado", "bool");
            modulos.put(id, m);
        }
        for (String[] c : CONECTADOS) {
            int id = hoja(c[0]);
            if (modulos.containsKey(id)) continue;
            Modulo m = new Modulo(id);
            m.estado = Modulo.CONECTADO;
            m.implementacion = c[1];
            modulos.put(id, m);
        }
        idVerificacion = hoja("verificación de cálculos");
        idSeguridadQuimica = hoja("rechazo de síntesis peligrosas");
        // permisos: lo que mueve dinero o toca el sistema exige confirmación; lo deshabilitado queda prohibido
        permiso("operaciones reales deshabilitadas", Modulo.PROHIBIDO);
        permiso("autorización de ciclo financiero", Modulo.CONFIRMAR);
        permiso("abrir apps con confirmación", Modulo.CONFIRMAR);
        permiso("aprobación de herramientas", Modulo.CONFIRMAR);
        // conexiones CRUZADAS declaradas (las que el razonamiento científico usa): todo resultado científico → verificación
        for (String[] d : Motores.DEF) { int id = hoja(d[0]); if (id != idVerificacion) puerta.cruzada(id, idVerificacion); }
        puerta.cruzada(hoja("balanceo de ecuaciones"), hoja("eliminación de Gauss"));        // el balanceo es un sistema lineal
        puerta.cruzada(hoja("tiro parabólico"), hoja("Runge-Kutta 4"));                     // simulación vs fórmula
        puerta.cruzada(hoja("valores propios"), hoja("raíces de polinomios"));              // polinomio característico
        puerta.cruzada(hoja("reactivo limitante"), hoja("masa molar"));
        puerta.cruzada(hoja("conversión de unidades"), hoja("verificación de unidades"));
        puerta.cruzada(idSeguridadQuimica, hoja("balanceo de ecuaciones"));
    }
    int hoja(String n) { int id = arbol.hojaPorNombre(n); if (id == 0) throw new IllegalStateException("la hoja «" + n + "» no está en el árbol"); return id; }
    void permiso(String n, String p) { Modulo m = modulos.get(hoja(n)); if (m == null) { m = new Modulo(hoja(n)); modulos.put(m.id, m); } m.permiso = p; }

    // ================================================================== estado
    public String estado(int id) {
        if (Arbol.hoja(id)) { Modulo m = modulos.get(id); return m == null ? Modulo.PLANIFICADO : m.estado; }
        int[] c = conteo(id);
        return c[0] > 0 ? Modulo.OPERATIVO : c[1] > 0 ? Modulo.CONECTADO : Modulo.PLANIFICADO;
    }
    /** {operativos, conectados, planificados} entre las hojas de id. */
    public int[] conteo(int id) {
        int[] r = Arbol.hojas(id), c = new int[3];
        for (int i = r[0]; i <= r[1]; i++) {
            Modulo m = modulos.get(i);
            if (m == null || Modulo.PLANIFICADO.equals(m.estado)) c[2]++; else if (Modulo.OPERATIVO.equals(m.estado)) c[0]++; else c[1]++;
        }
        return c;
    }
    public Map<String, Object> ficha(int id) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("id", id); o.put("nivel", Arbol.nivel(id)); o.put("nombre", arbol.nombre(id)); o.put("dominio", arbol.dominio(id));
        o.put("responsabilidad", arbol.responsabilidad(id)); o.put("padre", Arbol.padre(id));
        List<Object> h = new ArrayList<Object>(); for (int x : Arbol.hijos(id)) h.add(x); o.put("hijos", h);
        o.put("estado", estado(id));
        Modulo m = modulos.get(id);
        if (m != null) {
            if (m.implementacion != null) o.put("implementacion", m.implementacion);
            o.put("permiso", m.permiso);
            if (!m.entrada.isEmpty()) o.put("entradas", m.entrada);
            if (!m.salida.isEmpty()) o.put("salidas", m.salida);
            o.put("llamadas", m.llamadas); o.put("fallos", m.fallos);
            if (m.ultimoError != null) o.put("ultimo_error", m.ultimoError);
            o.put("resultados", m.ultimosResultados());
        }
        if (!Arbol.hoja(id)) { int[] c = conteo(id); o.put("hojas", Json.map("operativas", c[0], "conectadas", c[1], "planificadas", c[2])); }
        return o;
    }
    public Map<String, Object> resumen() {
        int[] c = conteo(1);
        List<Object> esp = new ArrayList<Object>();
        for (int id = 16; id <= 31; id++) { int[] k = conteo(id); esp.add(Json.map("id", id, "nombre", arbol.nombre(id), "operativas", k[0], "conectadas", k[1], "planificadas", k[2])); }
        return Json.map("modulos", Arbol.N, "niveles", Arbol.NIVELES, "hojas", 1024, "hojas_operativas", c[0], "hojas_conectadas", c[1], "hojas_planificadas", c[2],
                "especialidades", esp, "puerta", puerta.estado());
    }
    public Arbol arbol() { return arbol; }
    public Modulo modulo(int id) { return modulos.get(id); }

    /** PRUEBA INDIVIDUAL de un módulo operativo (respuesta conocida), pasando por la puerta como cualquier mensaje. */
    public Map<String, Object> probar(int id) {
        Modulo m = modulos.get(id);
        if (m == null || !Modulo.OPERATIVO.equals(m.estado)) return Json.map("id", id, "paso", false, "motivo", "no es operativo (" + estado(id) + ")");
        Puerta.Traza t = puerta.nueva("prueba-" + id);
        try {
            Map<String, Object> out = puerta.enviar(Puerta.COORDINADOR, id, new LinkedHashMap<String, Object>(m.prueba), t);
            String got = show(out.get(m.esperado[0]));
            boolean ok = got.contains(m.esperado[1]) && Boolean.TRUE.equals(out.get("verificado"));
            return Json.map("id", id, "nombre", arbol.nombre(id), "paso", ok, "obtenido", got, "esperado", m.esperado[1]);
        } catch (Puerta.Rechazo r) {
            return Json.map("id", id, "nombre", arbol.nombre(id), "paso", false, "motivo", r.getMessage());
        }
    }
    public List<Map<String, Object>> probarTodos() {
        List<Map<String, Object>> o = new ArrayList<Map<String, Object>>();
        for (int id = Arbol.PRIMERA_HOJA; id <= Arbol.N; id++) { Modulo m = modulos.get(id); if (m != null && Modulo.OPERATIVO.equals(m.estado)) o.add(probar(id)); }
        return o;
    }

    // ================================================================== razonamiento científico
    /**
     * Reconoce el problema, lo envía por la puerta al módulo hoja correcto y luego a VERIFICACIÓN (conexión cruzada).
     * Devuelve null si no es un problema que los motores locales sepan resolver (lo atendería Claude).
     */
    public Map<String, Object> resolver(String pedido) {
        String p = pedido.trim();
        String l = p.toLowerCase(java.util.Locale.ROOT);
        Puerta.Traza t = puerta.nueva("r" + (++nTrazas));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("pedido", p);
        try {
            // 0) seguridad química primero
            if (l.matches(".*(sintetiz|síntesis|fabric|prepar|elabor|receta|cómo hacer|como hacer|precursor).*")) {
                Map<String, Object> s = puerta.enviar(Puerta.COORDINADOR, idSeguridadQuimica, Json.map("texto", p), t);
                if (Boolean.TRUE.equals(s.get("rechazado"))) {
                    out.put("rechazado", true); out.put("respuesta", "No, señor: " + s.get("motivo") + ". Puedo explicar propiedades, riesgos o la química general.");
                    return fin(out, t);
                }
            }
            Object[] ruta = rutear(p, l);
            if (ruta == null) return null;
            String hojaN = (String) ruta[0];
            @SuppressWarnings("unchecked") Map<String, Object> datos = (Map<String, Object>) ruta[1];
            int id = hoja(hojaN);
            Map<String, Object> r = puerta.enviar(Puerta.COORDINADOR, id, datos, t);
            Map<String, Object> v = puerta.enviar(id, idVerificacion, Json.map("resultado", r), t);  // conexión cruzada
            out.put("modulo", Json.map("id", id, "nombre", hojaN, "camino", caminoNombres(id)));
            out.put("resultado", r);
            out.put("confianza", v.get("confianza"));
            out.put("verificacion", v.get("motivo"));
            out.put("respuesta", texto(hojaN, r, v));
            return fin(out, t);
        } catch (Puerta.Rechazo e) {
            out.put("error", e.getMessage()); out.put("motivo", e.motivo);
            out.put("respuesta", "No pude resolverlo con los motores locales: " + e.getMessage());
            return fin(out, t);
        } catch (RuntimeException e) {
            out.put("error", e.getMessage());
            out.put("respuesta", "No entendí los datos del problema: " + e.getMessage());
            return fin(out, t);
        }
    }
    Map<String, Object> fin(Map<String, Object> out, Puerta.Traza t) {
        List<Object> ids = new ArrayList<Object>(); for (int x : t.visitados) ids.add(x);
        out.put("traza", Json.map("id", t.id, "modulos", ids, "mensajes", t.mensajes));
        return out;
    }
    List<Object> caminoNombres(int id) { List<Object> o = new ArrayList<Object>(); for (int x : Arbol.camino(id)) if (Arbol.nivel(x) <= 7 || x == id) o.add(arbol.nombre(x)); return o; }

    static String texto(String hoja, Map<String, Object> r, Map<String, Object> v) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Object> e : r.entrySet()) {
            String k = e.getKey();
            if ("verificado".equals(k) || "verificacion".equals(k) || "valor".equals(k) && (r.containsKey("valor_exacto") || r.containsKey("resultado") || r.containsKey("masa_molar")) || "residuo_maximo".equals(k) || "residuo".equals(k)) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(k.replace('_', ' ')).append(": ").append(show(e.getValue()));
        }
        if (r.get("verificacion") != null) b.append(". Comprobación: ").append(r.get("verificacion"));
        b.append(". Confianza ").append(v.get("confianza")).append('.');
        return b.toString();
    }

    /** Listas como "a, b, c" y mapas como "k=v, …" (legible y estable para las pruebas). */
    static String show(Object v) {
        if (v instanceof List) { StringBuilder b = new StringBuilder("["); for (Object x : (List<?>) v) { if (b.length() > 1) b.append(", "); b.append(show(x)); } return b.append(']').toString(); }
        if (v instanceof Map) { StringBuilder b = new StringBuilder(); for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) { if (b.length() > 0) b.append(", "); b.append(e.getKey()).append('=').append(show(e.getValue())); } return b.toString(); }
        return String.valueOf(v);
    }

    static final Pattern NUM = Pattern.compile("-?\\d+(?:[.,]\\d+)?(?:e-?\\d+)?");
    static List<Double> nums(String s) {
        List<Double> o = new ArrayList<Double>();
        Matcher m = NUM.matcher(s);
        while (m.find()) o.add(Double.parseDouble(m.group().replace(',', '.')));
        return o;
    }
    static String after(String p, String regex) { Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(p); return m.find() ? p.substring(m.end()).trim() : null; }

    /** {hoja, datos} o null. Reglas explícitas, sin adivinar: si no hay datos suficientes, no se rutea. */
    Object[] rutear(String p, String l) {
        String rest;
        if ((rest = after(p, "balance(?:a|á|ar|ame|eá)?\\s*(?:la ecuaci[oó]n|la reacci[oó]n)?:?")) != null && rest.contains("->") || l.contains("balance") && (p.contains("->") || p.contains("→")))
            return r("balanceo de ecuaciones", "texto", eq(p));
        if ((rest = after(p, "masa molar (?:de|del|de la)?")) != null) return r("masa molar", "formula", formula(rest));
        if ((rest = after(p, "composici[oó]n (?:porcentual|centesimal) (?:de|del)?")) != null) return r("composición porcentual", "formula", formula(rest));
        if (l.matches(".*\\bph\\b.*")) {
            List<Double> n = nums(p.replaceAll("(?i)ph", ""));
            if (n.isEmpty()) return null;
            Map<String, Object> d = Json.map("c", n.get(0));
            if (l.contains("ka") && n.size() > 1) { d.put("ka", n.get(1)); return new Object[]{"ácidos y bases", d}; }
            if (l.contains("base") || l.contains("hidróxido") || l.contains("naoh") || l.contains("koh")) d.put("base", true);
            return new Object[]{"pH", d};
        }
        if (l.contains("tabla de verdad") || l.contains("tautolog")) return r("tablas de verdad", "texto", after(p, "(?:tabla de verdad|tautolog[ií]a)\\s*(?:de|es)?:?\\s*"));
        if ((rest = after(p, "determinante (?:de)?")) != null) return r("determinante", "texto", rest);
        if ((rest = after(p, "(?:matriz )?inversa (?:de)?")) != null && rest.startsWith("[")) return r("matriz inversa", "texto", rest);
        if ((rest = after(p, "valores propios (?:de)?")) != null) return r("valores propios", "texto", rest);
        if (l.contains("primo") && !nums(p).isEmpty()) return new Object[]{"primalidad", Json.map("n", entero(p))};
        if ((rest = after(p, "factoriz(?:a|á|ar)\\s*(?:el n[uú]mero)?")) != null) {
            if (rest.trim().matches("\\d+")) return new Object[]{"factorización entera", Json.map("n", rest.trim())};
            return r("factorización de polinomios", "texto", rest);
        }
        if (l.matches(".*\\b(mcd|máximo común divisor|maximo comun divisor)\\b.*")) { List<Double> n = nums(p); if (n.size() >= 2) return new Object[]{"máximo común divisor", Json.map("a", n.get(0), "b", n.get(1))}; }
        if (l.matches(".*\\b(mcm|mínimo común múltiplo|minimo comun multiplo)\\b.*")) { List<Double> n = nums(p); if (n.size() >= 2) return new Object[]{"mínimo común múltiplo", Json.map("a", n.get(0), "b", n.get(1))}; }
        if (l.contains("caída libre") || l.contains("caida libre") || l.matches(".*(cae|suelto|soltar|dejo caer).*desde.*")) { List<Double> n = nums(p); if (!n.isEmpty()) return new Object[]{"caída libre", Json.map("h", n.get(0))}; }
        if (l.matches(".*(tiro|proyectil|lanz).*") && l.matches(".*(grado|°|º).*")) {
            List<Double> n = nums(p); if (n.size() >= 2) return new Object[]{"tiro parabólico", Json.map("v0", n.get(0), "angulo", n.get(1))};
        }
        if (l.contains("péndulo") || l.contains("pendulo")) { List<Double> n = nums(p); if (!n.isEmpty()) return new Object[]{"péndulo simple", Json.map("L", n.get(0))}; }
        Matcher cv = Pattern.compile("(?i)(?:convert[ií]r?|pas[aá]r?|cu[aá]ntos?|cu[aá]ntas?)\\s*(-?\\d+(?:[.,]\\d+)?)\\s*([^\\s]+(?:/[^\\s]+)?)\\s+(?:a|en)\\s+([^\\s?]+)").matcher(p);
        if (cv.find()) return new Object[]{"conversión de unidades", Json.map("valor", Double.parseDouble(cv.group(1).replace(',', '.')), "desde", cv.group(2), "hacia", cv.group(3))};
        if ((rest = after(p, "deriv(?:ada|ar|á|a)\\s*(?:de\\s+)?")) != null) {
            Expr e = Expr.parse(rest);
            return r(e.poly(e.vars().isEmpty() ? "x" : e.vars().first()) != null ? "derivada de polinomios" : "regla de la cadena", "texto", rest);
        }
        Matcher in = Pattern.compile("(?i)integral\\s*(?:de|definida de)?\\s*(.+?)\\s+(?:de|entre|desde)\\s+(-?[\\d.,]+|pi|π)\\s+(?:a|y|hasta)\\s+(-?[\\d.,]+|pi|π)").matcher(p);
        if (in.find()) {
            Expr f = Expr.parse(in.group(1));
            double a = Expr.parse(in.group(2)).eval(null), b = Expr.parse(in.group(3)).eval(null);
            boolean poly = f.poly(f.vars().isEmpty() ? "x" : f.vars().first()) != null;
            return new Object[]{poly ? "integral de polinomios" : "integral definida numérica", Json.map("texto", in.group(1), "a", a, "b", b)};
        }
        Matcher lim = Pattern.compile("(?i)l[ií]mite\\s*(?:de)?\\s*(.+?)\\s+cuando\\s+\\w\\s*(?:→|->|tiende a)\\s*(-?[\\d.,]+)").matcher(p);
        if (lim.find()) return new Object[]{"límites numéricos", Json.map("texto", lim.group(1), "a", Double.parseDouble(lim.group(2).replace(',', '.')))};
        if ((rest = after(p, "(?:media|promedio|estad[ií]stica[s]?|desv[ií]o)\\s*(?:de)?:?")) != null && nums(rest).size() >= 2) return r("media", "datos", rest);
        // ecuaciones y sistemas
        String eqs = after(p, "(?:resolv[eé]r?|resuelve|calcul[aá]r?|hall[aá]r?|despej[aá]r?)\\s*(?:la ecuaci[oó]n|el sistema|la inecuaci[oó]n)?:?");
        String cand = eqs != null ? eqs : p;
        if (cand.matches(".*[a-zA-Z].*") && cand.matches(".*(=|<|>|≤|≥).*") && !cand.contains("->")) {
            if (cand.contains(";")) return r("sistemas lineales 2x2 y 3x3", "texto", cand);
            if (cand.matches(".*(<|>|≤|≥).*")) return r("inecuaciones lineales", "texto", cand);
            Expr e = Expr.parse(cand.split("=")[0] + "-(" + cand.split("=", 2)[1] + ")");
            if (e.vars().size() != 1) return null;
            com.radar.jarvis.core.ciencia.Q[] pp = e.poly(e.vars().first());
            int g = pp == null ? -1 : pp.length - 1;
            return r(g == 1 ? "ecuación lineal" : g == 2 ? "ecuación cuadrática" : g == 3 ? "ecuación cúbica" : "raíces de polinomios", "texto", cand);
        }
        // cálculo directo
        String expr = after(p, "(?:cu[aá]nto es|calcul[aá]r?|evalu[aá]r?)\\s*:?");
        String cexpr = expr != null ? expr : p;
        cexpr = cexpr.replaceAll("[?¿=]", "").trim();
        if (cexpr.matches("[0-9+\\-*/^().,\\s√πa-z²³·×÷]+") && cexpr.matches(".*\\d.*") && cexpr.matches(".*[+\\-*/^√²³·×÷(].*|.*sqrt.*")) {
            try { Expr e = Expr.parse(cexpr); if (e.vars().isEmpty()) return r("evaluación de expresiones", "texto", cexpr); } catch (RuntimeException ignored) {}
        }
        return null;
    }
    static Object[] r(String hoja, String k, Object v) { return v == null ? null : new Object[]{hoja, Json.map(k, v)}; }
    static String eq(String p) { int i = p.indexOf(':'); String s = i >= 0 && i < p.indexOf("->") ? p.substring(i + 1) : p.replaceFirst("(?i)^.*?balance\\S*\\s*(la ecuaci[oó]n|la reacci[oó]n)?", ""); return s.trim(); }
    static String formula(String rest) { Matcher m = Pattern.compile("([A-Z][A-Za-z0-9()\\[\\]·*.]*)").matcher(rest); return m.find() ? m.group(1).replaceAll("[.]$", "") : null; }
    static String entero(String p) { Matcher m = Pattern.compile("\\d+").matcher(p); return m.find() ? m.group() : "0"; }
    /** Visible para la prueba: detección local de pedidos químicos peligrosos. */
    public static String peligroQuimico(String s) { return Quimica.danger(s); }
}
