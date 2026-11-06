import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.cerebro.*;
import com.radar.jarvis.core.ciencia.*;

import java.io.File;
import java.math.BigInteger;
import java.util.*;

/** Cerebro modular: árbol de 2.047 módulos, puerta neuronal, motores científicos y razonamiento verificado.
 *  Args: assets/cerebro-2047.json, carpeta temporal. */
public class CerebroTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }
    static long now = 1_000_000;
    static boolean paused = false;

    public static void main(String[] a) throws Exception {
        String json = new String(java.nio.file.Files.readAllBytes(new File(a[0]).toPath()), "UTF-8");
        File dir = new File(a[1]); dir.mkdirs();
        Puerta.Reloj reloj = new Puerta.Reloj() { public long ahora() { return now; } };
        Puerta.Pausa pausa = new Puerta.Pausa() { public boolean activa() { return paused; } };
        Cerebro c = new Cerebro(json, dir, reloj, pausa);
        Arbol t = c.arbol();

        // ================= 1. ÁRBOL
        int[] porNivel = new int[12];
        Set<String> nombresHoja = new HashSet<String>();
        boolean padres = true, vacios = false;
        for (int id = 1; id <= Arbol.N; id++) {
            porNivel[Arbol.nivel(id)]++;
            if (id > 1 && !(Arbol.hijos(Arbol.padre(id))[0] == id || Arbol.hijos(Arbol.padre(id))[1] == id)) padres = false;
            if (t.nombre(id) == null || t.nombre(id).isEmpty() || t.responsabilidad(id) == null || t.responsabilidad(id).isEmpty() || t.dominio(id) == null) vacios = true;
            if (Arbol.hoja(id)) nombresHoja.add(t.nombre(id));
        }
        boolean niveles = true; for (int l = 1; l <= 11; l++) niveles &= porNivel[l] == (1 << (l - 1));
        check(niveles, "11 niveles con 1, 2, 4 … 1024 módulos (total " + Arbol.N + ")");
        check(padres, "cada módulo es hijo de su padre (2i, 2i+1)");
        check(!vacios, "ningún módulo vacío: todos tienen nombre, dominio y responsabilidad");
        check(nombresHoja.size() == 1024, "1024 hojas con responsabilidad única (" + nombresHoja.size() + ")");
        Set<String> dominios = new HashSet<String>(); for (int id = 1; id <= Arbol.N; id++) dominios.add(t.dominio(id));
        check(dominios.containsAll(Arrays.asList("percepción", "lenguaje", "memoria", "razonamiento", "planificación", "conocimiento", "ejecución", "aprendizaje", "seguridad", "coordinación")),
                "los 10 dominios pedidos están distribuidos en el árbol: " + dominios);
        check(Arbol.hojas(16)[0] == 1024 && Arbol.hojas(16)[1] == 1087 && Arbol.especialidad(1100) == 17 && Arbol.ancestro(5, 1300), "rangos de hojas y ancestros calculados sin tablas");

        // ================= 2. ESTADOS HONESTOS
        Map<String, Object> res = c.resumen();
        int op = ((Number) res.get("hojas_operativas")).intValue(), con = ((Number) res.get("hojas_conectadas")).intValue(), plan = ((Number) res.get("hojas_planificadas")).intValue();
        check(op + con + plan == 1024 && op >= 100 && con >= 100 && plan > 0, "estados: " + op + " operativas, " + con + " conectadas, " + plan + " planificadas (sin inflar)");
        System.out.println("      por especialidad: " + res.get("especialidades"));
        check(Modulo.PLANIFICADO.equals(c.estado(t.hojaPorNombre("emoción en la voz"))), "una hoja sin código figura como planificada");
        check(Modulo.CONECTADO.equals(c.estado(t.hojaPorNombre("red neuronal de intención"))), "una función existente de JARVIS figura como conectada (NeuralNet)");
        check(Modulo.OPERATIVO.equals(c.estado(20)) && Modulo.OPERATIVO.equals(c.estado(1)), "los nodos internos agregan el estado de sus hojas");

        // ================= 3. PRUEBA INDIVIDUAL DE CADA MÓDULO OPERATIVO
        int pasan = 0, total = 0;
        for (Map<String, Object> r : c.probarTodos()) {
            total++;
            if (Boolean.TRUE.equals(r.get("paso"))) pasan++;
            else System.out.println("      ✗ " + r);
        }
        check(total == op && pasan == total, "prueba individual de cada módulo operativo: " + pasan + "/" + total);

        // ================= 4. PUERTA NEURONAL
        Puerta p = c.puerta;
        int masa = t.hojaPorNombre("masa molar"), verif = t.hojaPorNombre("verificación de cálculos"), emocion = t.hojaPorNombre("emoción en la voz");
        Puerta.Traza tr = p.nueva("t1");
        check(rej(p, 0, 5000, Json.map(), tr).equals("destino"), "destino inexistente (5000) → rechazado");
        check(rej(p, 0, emocion, Json.map(), tr).equals("planificado"), "módulo planificado → la puerta lo dice, no inventa");
        check(rej(p, 0, t.hojaPorNombre("red neuronal de intención"), Json.map(), tr).equals("conectado"), "módulo conectado → avisa que aún no se invoca por la puerta");
        check(rej(p, 0, masa, Json.map("formula", 42), tr).equals("contrato"), "contrato de datos: tipo incorrecto → rechazado");
        check(rej(p, 0, masa, Json.map(), tr).equals("contrato"), "contrato de datos: falta un campo → rechazado");
        check(rej(p, t.hojaPorNombre("caída libre"), t.hojaPorNombre("primalidad"), Json.map("n", 7), tr).equals("conexion"), "sin malla completa: caída libre → primalidad no tiene conexión");
        check(p.conectados(masa, verif) && "cruzada".equals(p.tipoConexion(masa, verif)), "conexión cruzada declarada: masa molar → verificación");
        check("jerárquica".equals(p.tipoConexion(20, 40)) && "horizontal".equals(p.tipoConexion(20, 21)), "conexiones jerárquicas y horizontales");
        check(rej(p, 0, t.hojaPorNombre("operaciones reales deshabilitadas"), Json.map(), tr).equals("conectado") || rej(p, 0, t.hojaPorNombre("operaciones reales deshabilitadas"), Json.map(), tr).equals("permiso"),
                "operaciones reales: nunca se ejecutan por la puerta");
        // permisos con un módulo de prueba que exige confirmación
        Modulo conf = c.modulo(t.hojaPorNombre("pH"));
        conf.permiso = Modulo.CONFIRMAR;
        Puerta.Traza tp = p.nueva("perm");
        check(rej(p, 0, conf.id, Json.map("c", 0.01), tp).equals("permiso"), "permiso: requiere confirmación humana y no la tiene → bloqueado");
        tp.autorizados.add(conf.id);
        check(send(p, 0, conf.id, Json.map("c", 0.01), tp) != null, "con la confirmación concedida → pasa");
        conf.permiso = Modulo.LIBRE;
        // PAUSA
        paused = true;
        check(rej(p, 0, masa, Json.map("formula", "H2O"), p.nueva("pausa")).equals("pausa"), "PAUSA bloquea todos los mensajes");
        paused = false;
        // errores, corte del circuito y recuperación
        int bis = t.hojaPorNombre("bisección");
        for (int i = 0; i < 3; i++) rej(p, 0, bis, Json.map("texto", "x^2 + 1", "a", 0, "b", 1), p.nueva("err" + i));
        check(rej(p, 0, bis, Json.map("texto", "x^2 - 2", "a", 0, "b", 2), p.nueva("err4")).equals("circuito"), "3 fallos seguidos → el módulo se corta (circuito abierto)");
        now += Puerta.ENFRIAMIENTO_MS + 1;
        check(send(p, 0, bis, Json.map("texto", "x^2 - 2", "a", 0, "b", 2), p.nueva("rec")) != null && c.modulo(bis).fallosSeguidos == 0, "tras el enfriamiento se recupera solo y vuelve a funcionar");
        // ciclos, saltos y límites: módulos de prueba que se llaman entre sí
        final Puerta pp = p;
        final int A = t.hojaPorNombre("media"), B = t.hojaPorNombre("desviación estándar");
        Modulo ma = c.modulo(A), mb = c.modulo(B);
        Motor origA = ma.motor, origB = mb.motor;
        final Puerta.Traza[] cur = new Puerta.Traza[1];
        ma.motor = new Motor() { public Map<String, Object> ejecutar(Map<String, Object> in) throws Exception { return pp.enviar(A, B, in, cur[0]); } };
        mb.motor = new Motor() { public Map<String, Object> ejecutar(Map<String, Object> in) throws Exception { return pp.enviar(B, A, in, cur[0]); } };
        p.cruzada(A, B);
        cur[0] = p.nueva("ciclo");
        check(rej(p, 0, A, Json.map("datos", "1 2 3"), cur[0]).equals("ciclo"), "ciclo A → B → A detectado y cortado");
        ma.motor = origA; mb.motor = origB;
        Puerta.Traza lim = p.nueva("lim");
        String last = "";
        for (int i = 0; i < Puerta.MAX_MENSAJES + 2; i++) last = rejOrOk(p, 0, masa, Json.map("formula", "H2O"), lim);
        check(last.equals("limite"), "límite de mensajes por pedido (" + Puerta.MAX_MENSAJES + ")");
        Puerta.Traza lent = p.nueva("lento");
        now += Puerta.MAX_MS_TRAZA + 1;
        check(rej(p, 0, masa, Json.map("formula", "H2O"), lent).equals("limite"), "límite de tiempo por pedido");
        // memoria compartida, registro y aprendizaje de conexiones
        Puerta.Traza sh = p.nueva("compartida");
        send(p, 0, masa, Json.map("formula", "NaCl"), sh);
        check(sh.pizarra.containsKey(String.valueOf(masa)), "memoria compartida: la salida queda en la pizarra de la traza");
        check(!p.registro(5).isEmpty() && new File(dir, "cerebro/actividad.jsonl").length() > 0, "registro de actividad en memoria y en archivo");
        int x1 = t.hojaPorNombre("caída libre"), x2 = t.hojaPorNombre("primalidad");
        for (int i = 0; i < 3; i++) { Puerta.Traza q = p.nueva("apr" + i); send(p, 0, x1, Json.map("h", 5), q); send(p, 0, x2, Json.map("n", 13), q); }
        check(p.pesoAprendido(x1, x2) >= 3 && p.conectados(x1, x2) && "aprendida".equals(p.tipoConexion(x1, x2)), "conexión dinámica: usadas juntas 3 veces → conexión aprendida");
        check(p.activos().size() <= Puerta.MAX_ACTIVOS, "activación bajo demanda: " + p.activos().size() + " módulos en memoria (máx. " + Puerta.MAX_ACTIVOS + "), nunca los 2047");

        // ================= 5. RAZONAMIENTO DE PUNTA A PUNTA (pedido en español → módulo → verificación)
        String[][] casos = {
            {"resolvé x^2 - 5x + 6 = 0", "ecuación cuadrática", "2, 3"},
            {"resolvé 2x + 3 = 11", "ecuación lineal", "4"},
            {"resolvé el sistema: x + y = 10; x - y = 2", "sistemas lineales 2x2 y 3x3", "x=6"},
            {"resolvé x^2 + 1 = 0", "ecuación cuadrática", "i"},
            {"resolvé x^2 = 2", "ecuación cuadrática", "√2"},
            {"cuánto es 3/4 + 5/6", "evaluación de expresiones", "19/12"},
            {"derivada de x^3 + 2x", "derivada de polinomios", "3x^2 + 2"},
            {"derivá sin(x)*x", "regla de la cadena", "cos"},
            {"integral de x^2 de 0 a 3", "integral de polinomios", "9"},
            {"integral de sin(x) entre 0 y pi", "integral definida numérica", "2"},
            {"límite de sin(x)/x cuando x tiende a 0", "límites numéricos", "1"},
            {"masa molar de Ca(OH)2", "masa molar", "74.09"},
            {"balanceá la ecuación: Al + O2 -> Al2O3", "balanceo de ecuaciones", "4 Al + 3 O2 → 2 Al2O3"},
            {"pH de HCl 0,001", "pH", "3"},
            {"convertí 5 km a mi", "conversión de unidades", "3.10685596119 mi"},
            {"pasá 72 km/h a m/s", "conversión de unidades", "20 m/s"},
            {"una piedra cae desde 45 m, caída libre", "caída libre", "3.029"},
            {"tiro con 30 m/s a 60 grados", "tiro parabólico", "79.47"},
            {"es primo 1000003?", "primalidad", "true"},
            {"factorizá 2024", "factorización entera", "2^3 · 11 · 23"},
            {"factorizá x^3 - x", "factorización de polinomios", "(x - 1)"},
            {"mcd de 84 y 36", "máximo común divisor", "12"},
            {"determinante de [[2,1,0],[1,3,1],[0,1,4]]", "determinante", "18"},
            {"valores propios de [[4,1],[2,3]]", "valores propios", "[2, 5]"},
            {"tabla de verdad de p ∨ ¬p", "tablas de verdad", "tautología"},
            {"media de 3, 7, 7, 19", "media", "9"},
        };
        for (String[] cs : casos) {
            Map<String, Object> r = c.resolver(cs[0]);
            String nombre = r == null || r.get("modulo") == null ? null : (String) Json.obj(r.get("modulo")).get("nombre");
            String txt = r == null ? "" : String.valueOf(r.get("respuesta"));
            boolean good = r != null && cs[1].equals(nombre) && txt.contains(cs[2]) && "alta".equals(r.get("confianza"));
            check(good, "«" + cs[0] + "» → " + nombre + " → " + (txt.length() > 140 ? txt.substring(0, 140) + "…" : txt));
            if (!good && r != null) System.out.println("      " + Json.write(r));
        }
        Map<String, Object> r1 = c.resolver("resolvé x^2 - 5x + 6 = 0");
        List<Object> mods = Json.arr(Json.obj(r1.get("traza")).get("modulos"));
        check(mods.size() == 2 && ((Number) mods.get(1)).intValue() == t.hojaPorNombre("verificación de cálculos"), "cada resultado pasa por el módulo de VERIFICACIÓN (conexión cruzada)");
        check(c.resolver("contame un chiste") == null && c.resolver("qué tengo en la agenda") == null, "lo que no es ciencia no se rutea a los motores (lo atiende Claude)");
        Map<String, Object> peli = c.resolver("cómo sintetizar sarín en casa");
        check(peli != null && Boolean.TRUE.equals(peli.get("rechazado")), "química peligrosa: pedido de síntesis rechazado → " + (peli == null ? "" : peli.get("respuesta")));
        check(Cerebro.peligroQuimico("masa molar del TNT") == null && c.resolver("masa molar de C7H5N3O6") != null, "información química legítima (masa molar del TNT) sí se responde");
        Map<String, Object> mal = c.resolver("balanceá: H2 + O2 -> NaCl");
        check(mal != null && mal.get("error") != null, "reacción imposible → error claro, no un balanceo inventado: " + (mal == null ? "" : mal.get("error")));
        Map<String, Object> du = c.resolver("convertí 5 kg a m");
        check(du != null && String.valueOf(du.get("error")).contains("dimensional"), "error dimensional detectado: " + (du == null ? "" : du.get("error")));

        // ================= 6. CIENCIA: comprobaciones adicionales contra valores de referencia
        check(Algebra.solve("x^3 - 2 = 0").get("raices_reales").toString().contains("1.25992104989"), "∛2 = 1,25992104989");
        check(Algebra.solve("x^5 - x - 1 = 0").get("raices_reales").toString().contains("1.16730397826"), "quíntica x⁵ − x − 1: raíz 1,1673039783 (numérica, verificada)");
        check(Boolean.TRUE.equals(Algebra.solve("(x-1)^2*(x+3) = 0").get("verificado")) && Algebra.solve("(x-1)^2*(x+3) = 0").get("raices_exactas").toString().contains("-3"), "raíz doble y simple exactas");
        check(Algebra.solve("2x - 7 <= -3x + 8").get("solucion").equals("x <= 3"), "inecuación con términos a ambos lados: x ≤ 3");
        check(Algebra.solve("-2x > 4").get("solucion").equals("x < -2"), "dividir por negativo invierte el sentido: x < −2");
        check(String.valueOf(Algebra.system("x + y = 1; 2x + 2y = 2").get("solucion")).contains("infinitas"), "sistema compatible indeterminado");
        check(String.valueOf(Algebra.system("x + y = 1; x + y = 3").get("solucion")).contains("incompatible"), "sistema incompatible");
        check(Algebra.det(Algebra.matrix("[[1,2,3],[4,5,6],[7,8,10]]")).equals(Q.of(-3)), "determinante 3×3 = −3 (exacto)");
        check(Algebra.show(Algebra.inverse(Algebra.matrix("[[4,7],[2,6]]"))).equals("[[3/5, -7/10], [-1/5, 2/5]]"), "inversa exacta con fracciones");
        try { Algebra.inverse(Algebra.matrix("[[1,2],[2,4]]")); check(false, "matriz singular"); } catch (ArithmeticException e) { check(true, "matriz singular: " + e.getMessage()); }
        check(Calculo.derivative("x^x").get("verificado").equals(true), "derivada de x^x (exponente variable) verificada numéricamente: " + Calculo.derivative("x^x").get("derivada"));
        check(Calculo.derivative("ln(x^2+1)").get("verificado").equals(true) && Calculo.derivative("exp(2x)*cos(x)").get("verificado").equals(true), "regla de la cadena y del producto verificadas");
        check(Math.abs(Double.parseDouble((String) Calculo.integral("exp(-x^2)", -5, 5).get("valor")) - Math.sqrt(Math.PI)) < 1e-9, "∫ e^(−x²) de −5 a 5 ≈ √π");
        check(Math.abs(Double.parseDouble((String) Calculo.integral("1/x", 1, Math.E).get("valor")) - 1) < 1e-10, "∫ 1/x de 1 a e = 1");
        check(String.valueOf(Calculo.limit("1/x", 0).get("limite")).contains("no existe"), "límite de 1/x en 0: no existe");
        check(String.valueOf(Calculo.limit("(1+x)^(1/x)", 0).get("limite")).startsWith("2.71828"), "límite (1+x)^(1/x) → e");
        check(Boolean.TRUE.equals(Calculo.taylor("sin(x)", 0, 7).get("verificado")) && String.valueOf(Calculo.taylor("sin(x)", 0, 5).get("serie")).contains("- 0.1666666667·x^3"), "Taylor de sin(x): x − x³/6 + …");
        check(Math.abs(Calculo.rk4(Expr.parse("t"), 0, 0, 2, 100)[1] - 2) < 1e-12, "RK4 integra y' = t exacto (y(2) = 2)");
        check(Discreta.isPrime(new BigInteger("18446744073709551557")) && !Discreta.isPrime(new BigInteger("18446744073709551559")), "primalidad de 64 bits (2^64 − 59 es primo)");
        check(Discreta.showFactors(Discreta.factor(new BigInteger("600851475143"))).equals("71 · 839 · 1471 · 6857"), "factorización 600851475143 = 71·839·1471·6857");
        check(Discreta.showFactors(Discreta.factor(new BigInteger("1000000016000000063"))).equals("1000000007 · 1000000009"), "rho de Pollard: producto de dos primos grandes");
        check(Discreta.equivalent("p -> q", "¬q -> ¬p") && !Discreta.equivalent("p -> q", "q -> p"), "contrarrecíproco equivalente; recíproco no");
        check(String.valueOf(Discreta.shortestPath("A-B:7, A-C:9, A-F:14, B-C:10, B-D:15, C-D:11, C-F:2, D-E:6, E-F:9", "A", "E").get("costo")).equals("20"), "Dijkstra (grafo clásico): A → E = 20");
        double[] d = Estadistica.parse("2, 4, 4, 4, 5, 5, 7, 9");
        check(Estadistica.std(d, false) == 2.0 && Math.abs(Estadistica.std(d, true) - 2.138089935) < 1e-9, "desvío poblacional 2 y muestral 2,138");
        check(Math.abs(Estadistica.percentile(Estadistica.parse("1 2 3 4"), 25) - 1.75) < 1e-12, "percentil 25 (R-7 / Excel) = 1,75");
        check(Math.abs(Estadistica.normalCdf(1) - 0.841344746068543) < 1e-12 && Math.abs(Estadistica.normalCdf(-3) - 0.00134989803163) < 1e-13, "Φ(1) y Φ(−3) con error < 1e-12");
        check(Math.abs(Estadistica.parse("1,5; 2,5")[1] - 2.5) < 1e-12, "datos con coma decimal separados por ';'");
        check(Unidades.convert(1, "atm", "torr").get("resultado").toString().startsWith("760 ") && Unidades.convert(1, "atm", "mmHg").get("resultado").toString().startsWith("759.9998"), "1 atm = 760 torr exactos = 759,9999 mmHg (no son idénticos)");
        check(Unidades.convert(1, "kWh", "J").get("resultado").toString().startsWith("3600000 "), "1 kWh = 3,6 MJ");
        check(Unidades.convert(-40, "°C", "°F").get("resultado").toString().startsWith("-40 "), "−40 °C = −40 °F");
        check(Unidades.coherent("N", "kg·m/s^2") && !Unidades.coherent("J", "W"), "análisis dimensional: N = kg·m/s²; J ≠ W");
        check(Math.abs(Fisica.pendulumPeriodExact(1, 60) / Fisica.pendulumPeriod(1) - 1.07318) < 1e-4, "péndulo a 60°: 7,3 % más lento que la aproximación");
        check(Math.abs(Fisica.escapeVelocity(Fisica.M_EARTH, Fisica.R_EARTH) - 11186) < 5, "velocidad de escape terrestre ≈ 11,19 km/s");
        check(Boolean.TRUE.equals(Fisica.projectile(50, 30).get("verificado")), "tiro: simulación paso a paso = fórmula cerrada");
        check(Math.abs(Quimica.molarMass("C6H12O6") - 180.156) < 0.01 && Math.abs(Quimica.molarMass("K4[Fe(CN)6]") - 368.35) < 0.02, "masas molares: glucosa 180,16 · ferrocianuro de potasio 368,35");
        check(Quimica.balance("KMnO4 + HCl -> KCl + MnCl2 + H2O + Cl2").text.equals("2 KMnO4 + 16 HCl → 2 KCl + 2 MnCl2 + 8 H2O + 5 Cl2"), "balanceo redox clásico (KMnO4 + HCl)");
        check(Quimica.balance("MnO4^- + Fe^2+ + H^+ -> Mn^2+ + Fe^3+ + H2O").text.equals("MnO4^- + 5 Fe^2+ + 8 H^+ → Mn^2+ + 5 Fe^3+ + 4 H2O"), "balanceo iónico con conservación de la CARGA");
        check(Math.abs(Quimica.phStrongAcid(1e-8) - 6.978) < 0.001, "pH de HCl 1e-8 M ≈ 6,98 (no 8: se incluye el agua)");
        check(Math.abs(Quimica.phWeakAcid(0.1, 1.8e-5) - 2.874) < 0.002, "pH de ácido acético 0,1 M ≈ 2,87");
        check(Quimica.empirical(new LinkedHashMap<String, Double>() {{ put("Fe", 69.94); put("O", 30.06); }}).equals("Fe2O3"), "fórmula empírica Fe2O3 desde % en masa");
        try { Quimica.parse("Xy2"); check(false, "elemento inventado"); } catch (IllegalArgumentException e) { check(true, "elemento inventado rechazado: " + e.getMessage()); }
        check(Q.parse("0.1").add(Q.parse("0.2")).equals(Q.parse("0.3")), "fracciones exactas: 0,1 + 0,2 = 0,3 (sin error de coma flotante)");

        System.out.println("\n" + ok + " pasan, " + bad + " fallan");
        System.exit(bad == 0 ? 0 : 1);
    }

    static Map<String, Object> send(Puerta p, int a, int b, Map<String, Object> d, Puerta.Traza t) { try { return p.enviar(a, b, d, t); } catch (Puerta.Rechazo e) { System.out.println("      rechazo inesperado: " + e.getMessage()); return null; } }
    static String rej(Puerta p, int a, int b, Map<String, Object> d, Puerta.Traza t) { try { p.enviar(a, b, d, t); return "ok"; } catch (Puerta.Rechazo e) { return e.motivo; } }
    static String rejOrOk(Puerta p, int a, int b, Map<String, Object> d, Puerta.Traza t) { return rej(p, a, b, d, t); }
}
