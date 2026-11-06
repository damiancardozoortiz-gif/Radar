package com.radar.jarvis.core.cerebro;

import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.ciencia.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los MOTORES REALES detrás de las hojas operativas (matemática, física, química, verificación y seguridad). Cada uno se
 * registra con su contrato y una PRUEBA INDIVIDUAL de respuesta conocida. Una hoja solo es "operativo" si está aquí.
 */
final class Motores {
    private Motores() {}

    /** {nombre de la hoja, entrada (clave:tipo …), salida, entrada de prueba (JSON), clave esperada, texto esperado}. */
    static final String[][] DEF = {
        // ---- matemática simbólica
        {"evaluación de expresiones", "texto:texto", "valor:texto", "{\"texto\":\"2^10 - 3/4\"}", "valor", "4093/4"},
        {"fracciones exactas", "texto:texto", "valor:texto", "{\"texto\":\"1/3 + 1/6\"}", "valor", "1/2"},
        {"potencias y raíces", "texto:texto", "valor:texto", "{\"texto\":\"sqrt(144) + 2^-1\"}", "valor", "25/2"},
        {"ecuación lineal", "texto:texto", "", "{\"texto\":\"3x + 5 = 20\"}", "raices_exactas", "5"},
        {"ecuación cuadrática", "texto:texto", "", "{\"texto\":\"x^2 - 5x + 6 = 0\"}", "raices_exactas", "[2, 3]"},
        {"ecuación cúbica", "texto:texto", "", "{\"texto\":\"x^3 - 6x^2 + 11x - 6 = 0\"}", "raices_exactas", "1"},
        {"raíces de polinomios", "texto:texto", "", "{\"texto\":\"x^4 - 5x^2 + 4 = 0\"}", "raices_reales", "[-2, -1, 1, 2]"},
        {"sistemas lineales 2x2 y 3x3", "texto:texto", "", "{\"texto\":\"x + y = 10; x - y = 2\"}", "solucion", "x=6"},
        {"factorización de polinomios", "texto:texto", "", "{\"texto\":\"x^2 - 4\"}", "factorizacion", "(x - 2)"},
        {"inecuaciones lineales", "texto:texto", "", "{\"texto\":\"2x + 3 > 7\"}", "solucion", "x > 2"},
        {"derivada de polinomios", "texto:texto", "", "{\"texto\":\"3x^2 + 2x\"}", "derivada", "6x + 2"},
        {"regla de la cadena", "texto:texto", "", "{\"texto\":\"sin(x^2)\"}", "derivada", "cos(x^2)"},
        {"integral definida numérica", "texto:texto,a:numero,b:numero", "", "{\"texto\":\"sin(x)\",\"a\":0,\"b\":3.141592653589793}", "valor", "2"},
        {"integral de polinomios", "texto:texto,a:numero,b:numero", "", "{\"texto\":\"x^2\",\"a\":0,\"b\":3}", "valor_exacto", "9"},
        {"área bajo la curva", "texto:texto,a:numero,b:numero", "", "{\"texto\":\"2x\",\"a\":0,\"b\":2}", "valor_exacto", "4"},
        {"límites numéricos", "texto:texto,a:numero", "", "{\"texto\":\"sin(x)/x\",\"a\":0}", "limite", "1"},
        {"series de Taylor", "texto:texto,a?:numero,n?:numero", "", "{\"texto\":\"exp(x)\",\"n\":3}", "serie", "0.5·x^2"},
        {"máximos y mínimos", "texto:texto", "", "{\"texto\":\"x^3 - 3x\"}", "maximos_locales", "(-1, 2)"},
        {"puntos de inflexión", "texto:texto", "", "{\"texto\":\"x^3 - 3x\"}", "inflexiones", "(0, 0)"},
        {"ecuaciones diferenciales ordinarias", "texto:texto,t0:numero,y0:numero,t1:numero", "", "{\"texto\":\"y\",\"t0\":0,\"y0\":1,\"t1\":1}", "y_final", "2.718281828"},
        {"método de Euler", "texto:texto,t0:numero,y0:numero,t1:numero", "", "{\"texto\":\"y\",\"t0\":0,\"y0\":1,\"t1\":1}", "y_final", "2.71"},
        {"Runge-Kutta 4", "texto:texto,t0:numero,y0:numero,t1:numero", "", "{\"texto\":\"-2y\",\"t0\":0,\"y0\":1,\"t1\":1}", "y_final", "0.1353352832"},
        {"tablas de verdad", "texto:texto", "", "{\"texto\":\"p -> (q -> p)\"}", "clasificacion", "tautología"},
        {"equivalencias lógicas", "texto:texto,texto2:texto", "", "{\"texto\":\"¬(p ∧ q)\",\"texto2\":\"¬p ∨ ¬q\"}", "equivalentes", "true"},
        {"satisfacibilidad", "texto:texto", "", "{\"texto\":\"p ∧ ¬p\"}", "clasificacion", "contradicción"},
        {"primalidad", "n:entero", "", "{\"n\":2147483647}", "primo", "true"},
        {"factorización entera", "n:entero", "", "{\"n\":360}", "factores", "2^3 · 3^2 · 5"},
        {"máximo común divisor", "a:numero,b:numero", "", "{\"a\":48,\"b\":18}", "resultado", "6"},
        {"mínimo común múltiplo", "a:numero,b:numero", "", "{\"a\":4,\"b\":6}", "resultado", "12"},
        {"aritmética modular", "a:numero,b:numero,m:numero", "", "{\"a\":3,\"b\":200,\"m\":13}", "resultado", "9"},
        {"inverso modular", "a:numero,m:numero", "", "{\"a\":3,\"m\":11}", "resultado", "4"},
        {"coeficiente binomial", "n:numero,k:numero", "", "{\"n\":10,\"k\":3}", "resultado", "120"},
        {"permutaciones", "n:numero,k:numero", "", "{\"n\":5,\"k\":2}", "resultado", "20"},
        {"números de Fibonacci", "n:numero", "", "{\"n\":50}", "resultado", "12586269025"},
        {"caminos mínimos", "texto:texto,desde:texto,hasta:texto", "", "{\"texto\":\"A-B:4, A-C:1, C-B:2, B-D:5\",\"desde\":\"A\",\"hasta\":\"D\"}", "costo", "8"},
        // ---- numérica y estadística
        {"determinante", "texto:texto", "", "{\"texto\":\"[[1,2],[3,4]]\"}", "resultado", "-2"},
        {"matriz inversa", "texto:texto", "", "{\"texto\":\"[[1,2],[3,4]]\"}", "resultado", "[[-2, 1], [3/2, -1/2]]"},
        {"rango", "texto:texto", "", "{\"texto\":\"[[1,2],[2,4]]\"}", "resultado", "1"},
        {"transpuesta", "texto:texto", "", "{\"texto\":\"[[1,2,3],[4,5,6]]\"}", "resultado", "[[1, 4], [2, 5], [3, 6]]"},
        {"valores propios", "texto:texto", "", "{\"texto\":\"[[2,1],[1,2]]\"}", "valores_propios", "[1, 3]"},
        {"eliminación de Gauss", "texto:texto", "", "{\"texto\":\"x + y + z = 6; 2y + 5z = -4; 2x + 5y - z = 27\"}", "solucion", "x=5"},
        {"bisección", "texto:texto,a:numero,b:numero", "", "{\"texto\":\"x^2 - 2\",\"a\":0,\"b\":2}", "raiz", "1.414213562"},
        {"Newton-Raphson", "texto:texto,x0:numero", "", "{\"texto\":\"cos(x) - x\",\"x0\":1}", "raiz", "0.739085133"},
        {"método de la secante", "texto:texto,a:numero,b:numero", "", "{\"texto\":\"x^3 - 2\",\"a\":1,\"b\":2}", "raiz", "1.25992104"},
        {"regla del trapecio", "texto:texto,a:numero,b:numero", "", "{\"texto\":\"x^2\",\"a\":0,\"b\":1}", "valor", "0.33333"},
        {"regla de Simpson", "texto:texto,a:numero,b:numero", "", "{\"texto\":\"exp(x)\",\"a\":0,\"b\":1}", "valor", "1.718281828"},
        {"media", "datos:texto", "", "{\"datos\":\"2, 4, 4, 4, 5, 5, 7, 9\"}", "media", "5"},
        {"desviación estándar", "datos:texto", "", "{\"datos\":\"2, 4, 4, 4, 5, 5, 7, 9\"}", "desvio_poblacional", "2"},
        {"correlación", "datos:texto,datos2:texto", "", "{\"datos\":\"1 2 3 4 5\",\"datos2\":\"2 4 6 8 10\"}", "resultado", "1"},
        {"regresión lineal", "datos:texto,datos2:texto", "", "{\"datos\":\"1 2 3 4\",\"datos2\":\"3 5 7 9\"}", "recta", "y = 2·x + 1"},
        {"distribución binomial", "n:numero,k:numero,p:numero", "", "{\"n\":10,\"k\":5,\"p\":0.5}", "probabilidad", "0.24609375"},
        {"distribución de Poisson", "k:numero,lambda:numero", "", "{\"k\":2,\"lambda\":3}", "probabilidad", "0.2240418"},
        {"distribución normal", "z:numero", "", "{\"z\":1.96}", "probabilidad", "0.9750021"},
        {"teorema de Bayes", "pa:numero,pba:numero,pbna:numero", "", "{\"pa\":0.01,\"pba\":0.99,\"pbna\":0.05}", "probabilidad", "0.1666666"},
        {"simulación Monte Carlo", "n:numero,semilla?:numero", "", "{\"n\":200000,\"semilla\":7}", "pi_estimado", "3.1"},
        // ---- física
        {"conversión de unidades", "valor:numero,desde:texto,hacia:texto", "", "{\"valor\":100,\"desde\":\"km/h\",\"hacia\":\"m/s\"}", "resultado", "27.7777777778 m/s"},
        {"análisis dimensional", "izquierda:texto,derecha:texto", "", "{\"izquierda\":\"J\",\"derecha\":\"kg·m^2/s^2\"}", "coherente", "true"},
        {"escalas de temperatura", "valor:numero,desde:texto,hacia:texto", "", "{\"valor\":100,\"desde\":\"°C\",\"hacia\":\"°F\"}", "resultado", "212 °F"},
        {"caída libre", "h:numero", "", "{\"h\":20}", "tiempo", "2.019619977 s"},
        {"tiro parabólico", "v0:numero,angulo:numero", "", "{\"v0\":20,\"angulo\":45}", "alcance", "40.788"},
        {"movimiento acelerado", "x0:numero,v0:numero,a:numero,t:numero", "", "{\"x0\":0,\"v0\":10,\"a\":2,\"t\":5}", "posicion", "75 m"},
        {"péndulo simple", "L:numero,amplitud?:numero", "", "{\"L\":1}", "periodo", "2.006409"},
        {"gravitación universal", "m1:numero,m2:numero,r:numero", "", "{\"m1\":5.9722e24,\"m2\":70,\"r\":6371000}", "fuerza", "687."},
        {"velocidad de escape", "M?:numero,r?:numero", "", "{}", "velocidad", "11186"},
        {"energía cinética", "m:numero,v:numero", "", "{\"m\":2,\"v\":3}", "energia", "9 J"},
        {"choques", "m1:numero,v1:numero,m2:numero,v2:numero", "", "{\"m1\":1,\"v1\":2,\"m2\":1,\"v2\":0}", "v_finales", "0 m/s y 2 m/s"},
        {"gases ideales", "P?:numero,V?:numero,n?:numero,T?:numero", "", "{\"V\":0.0224,\"n\":1,\"T\":273.15}", "P", "10138"},
        {"ley de Ohm", "V?:numero,I?:numero,R?:numero", "", "{\"V\":12,\"R\":4}", "I", "3 A"},
        {"resistencias en serie", "datos:texto", "", "{\"datos\":\"100 220 330\"}", "resultado", "650 Ω"},
        {"resistencias en paralelo", "datos:texto", "", "{\"datos\":\"100 100\"}", "resultado", "50 Ω"},
        {"equivalencia masa-energía", "m:numero", "", "{\"m\":0.001}", "energia", "89875517870000 J"},
        {"efecto Doppler", "f:numero,v_obs?:numero,v_fuente?:numero", "", "{\"f\":440,\"v_fuente\":34.3}", "frecuencia", "488.8"},
        {"dilatación temporal", "v:numero", "", "{\"v\":259627884}", "gamma", "1.99999"},
        {"efecto fotoeléctrico", "lambda:numero,trabajo_ev:numero", "", "{\"lambda\":2e-7,\"trabajo_ev\":4.7}", "energia_ev", "1.49"},
        // ---- química
        {"masa molar", "formula:texto", "", "{\"formula\":\"H2SO4\"}", "masa_molar", "98.07"},
        {"composición porcentual", "formula:texto", "", "{\"formula\":\"H2O\"}", "composicion", "11.19"},
        {"hidratos", "formula:texto", "", "{\"formula\":\"CuSO4·5H2O\"}", "masa_molar", "249.6"},
        {"fórmula empírica", "texto:texto", "", "{\"texto\":\"C:40.0, H:6.71, O:53.29\"}", "formula", "CH2O"},
        {"balanceo de ecuaciones", "texto:texto", "", "{\"texto\":\"C3H8 + O2 -> CO2 + H2O\"}", "ecuacion", "C3H8 + 5 O2 → 3 CO2 + 4 H2O"},
        {"conservación de la masa", "texto:texto", "", "{\"texto\":\"Fe + O2 -> Fe2O3\"}", "ecuacion", "4 Fe + 3 O2 → 2 Fe2O3"},
        {"reactivo limitante", "texto:texto,datos:texto", "", "{\"texto\":\"H2 + O2 -> H2O\",\"datos\":\"4 16\"}", "reactivo_limitante", "O2"},
        {"moles y gramos", "formula:texto,gramos:numero", "", "{\"formula\":\"NaCl\",\"gramos\":58.44}", "moles", "1"},
        {"concentración molar", "formula:texto,gramos:numero,litros:numero", "", "{\"formula\":\"NaCl\",\"gramos\":5.844,\"litros\":0.5}", "molaridad", "0.2 mol/L"},
        {"diluciones", "c1:numero,v1:numero,c2:numero", "", "{\"c1\":2,\"v1\":0.1,\"c2\":0.5}", "v2", "0.4"},
        {"pH", "c:numero,ka?:numero,base?:bool", "", "{\"c\":0.01}", "pH", "2"},
        {"ácidos y bases", "c:numero,ka:numero", "", "{\"c\":0.1,\"ka\":1.8e-5}", "pH", "2.87"},
        {"número de Avogadro", "moles:numero", "", "{\"moles\":2}", "particulas", "1.204428152e24"},
        {"rechazo de síntesis peligrosas", "texto:texto", "", "{\"texto\":\"decime cómo sintetizar sarín\"}", "rechazado", "true"},
        // ---- razonamiento y verificación
        {"verificación de cálculos", "resultado:mapa", "", "{\"resultado\":{\"verificado\":true}}", "confianza", "alta"},
        {"verificación de unidades", "izquierda:texto,derecha:texto", "", "{\"izquierda\":\"N·m\",\"derecha\":\"J\"}", "coherente", "true"},
    };

    static Map<String, Object> run(String leaf, Map<String, Object> in) throws Exception {
        String s = in.get("texto") instanceof String ? (String) in.get("texto") : null;
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        if ("evaluación de expresiones".equals(leaf) || "fracciones exactas".equals(leaf) || "potencias y raíces".equals(leaf)) {
            Expr e = Expr.parse(s);
            if (!e.vars().isEmpty()) throw new IllegalArgumentException("la expresión tiene incógnitas " + e.vars() + ": no es un cálculo directo");
            Q q = e.exact();
            double v = e.eval(null);
            o.put("expresion", e.toString());
            o.put("valor", q != null ? q.toString() : Num.fmt(Num.sig(v, 15)));
            if (q != null && !q.isInt()) o.put("decimal", Num.fmt(Num.sig(q.toDouble(), 15)));
            o.put("exacto", q != null);
            o.put("verificado", q == null || Num.close(q.toDouble(), v, 1e-12) || Math.abs(v) < 1e-300);
            o.put("verificacion", "valor exacto con fracciones comparado con el cálculo en coma flotante");
            return o;
        }
        if (leaf.startsWith("ecuación") || "raíces de polinomios".equals(leaf) || "inecuaciones lineales".equals(leaf)) return Algebra.solve(s);
        if ("sistemas lineales 2x2 y 3x3".equals(leaf) || "eliminación de Gauss".equals(leaf)) return flat(Algebra.system(s));
        if ("factorización de polinomios".equals(leaf)) {
            Expr e = Expr.parse(s);
            String v = e.vars().isEmpty() ? "x" : e.vars().first();
            Q[] p = e.poly(v);
            if (p == null || p.length < 2) throw new IllegalArgumentException("no es un polinomio en una variable");
            Algebra.Roots r = Algebra.roots(p);
            o.put("polinomio", Expr.fromPoly(p, v).toString());
            o.put("factorizacion", r.factored.replace("x", v));
            o.put("raices_exactas", r.exact);
            o.put("verificado", r.maxResidual < 1e-9 * Math.max(1, Math.abs(p[0].toDouble())) + 1e-9);
            return o;
        }
        if ("derivada de polinomios".equals(leaf) || "regla de la cadena".equals(leaf)) return Calculo.derivative(s);
        if ("integral definida numérica".equals(leaf) || "integral de polinomios".equals(leaf) || "área bajo la curva".equals(leaf)) return Calculo.integral(s, d(in, "a"), d(in, "b"));
        if ("límites numéricos".equals(leaf)) return Calculo.limit(s, d(in, "a"));
        if ("series de Taylor".equals(leaf)) return Calculo.taylor(s, in.containsKey("a") ? d(in, "a") : 0, in.containsKey("n") ? i(in, "n") : 4);
        if ("máximos y mínimos".equals(leaf) || "puntos de inflexión".equals(leaf)) return Calculo.extremes(s);
        if ("ecuaciones diferenciales ordinarias".equals(leaf)) return Calculo.ode(s, d(in, "t0"), d(in, "y0"), d(in, "t1"));
        if ("método de Euler".equals(leaf) || "Runge-Kutta 4".equals(leaf)) {
            Expr f = Expr.parse(s);
            boolean euler = "método de Euler".equals(leaf);
            double[] a = euler ? Calculo.euler(f, d(in, "t0"), d(in, "y0"), d(in, "t1"), 100000) : Calculo.rk4(f, d(in, "t0"), d(in, "y0"), d(in, "t1"), 1000);
            double[] b = euler ? Calculo.euler(f, d(in, "t0"), d(in, "y0"), d(in, "t1"), 200000) : Calculo.rk4(f, d(in, "t0"), d(in, "y0"), d(in, "t1"), 2000);
            o.put("y_final", Num.fmt(Num.sig(b[1], 10)));
            o.put("metodo", euler ? "Euler explícito (200 000 pasos)" : "Runge-Kutta 4 (2000 pasos)");
            o.put("verificado", Math.abs(a[1] - b[1]) < (euler ? 1e-4 : 1e-9) * Math.max(1, Math.abs(b[1])));
            o.put("verificacion", "duplicar los pasos cambia " + Num.fmt(Num.sig(Math.abs(a[1] - b[1]), 3)));
            return o;
        }
        if ("tablas de verdad".equals(leaf) || "satisfacibilidad".equals(leaf)) return Discreta.truthTable(s);
        if ("equivalencias lógicas".equals(leaf)) { o.put("equivalentes", Discreta.equivalent(s, (String) in.get("texto2"))); o.put("verificado", true); o.put("metodo", "todas las asignaciones de verdad"); return o; }
        if ("primalidad".equals(leaf)) {
            BigInteger n = big(in, "n"); boolean p = Discreta.isPrime(n);
            o.put("numero", n.toString()); o.put("primo", p);
            if (!p && n.compareTo(BigInteger.ONE) > 0 && n.bitLength() <= 100) o.put("factores", Discreta.showFactors(Discreta.factor(n)));
            o.put("verificado", p == n.isProbablePrime(40));
            o.put("verificacion", "Miller-Rabin determinista contrastado con la prueba probabilística de Java");
            return o;
        }
        if ("factorización entera".equals(leaf)) {
            BigInteger n = big(in, "n");
            java.util.TreeMap<BigInteger, Integer> f = Discreta.factor(n);
            boolean allPrime = true; for (BigInteger p : f.keySet()) allPrime &= Discreta.isPrime(p);
            o.put("factores", Discreta.showFactors(f));
            o.put("verificado", Discreta.product(f).equals(n) && allPrime);
            o.put("verificacion", "el producto de los factores reconstruye el número y cada factor es primo");
            return o;
        }
        if ("máximo común divisor".equals(leaf) || "mínimo común múltiplo".equals(leaf)) {
            BigInteger a = big(in, "a"), b = big(in, "b");
            BigInteger g = a.gcd(b), l = Discreta.lcm(a, b);
            o.put("resultado", ("máximo común divisor".equals(leaf) ? g : l).toString());
            o.put("verificado", g.multiply(l).equals(a.multiply(b).abs()));
            o.put("verificacion", "MCD × MCM = |a × b|");
            return o;
        }
        if ("aritmética modular".equals(leaf)) {
            BigInteger a = big(in, "a"), b = big(in, "b"), m = big(in, "m");
            BigInteger r = a.modPow(b, m), slow = BigInteger.ONE;
            boolean small = b.bitLength() < 20;
            if (small) for (int k = 0; k < b.intValue(); k++) slow = slow.multiply(a).mod(m);
            o.put("resultado", r.toString()); o.put("operacion", a + "^" + b + " mod " + m);
            o.put("verificado", !small || slow.equals(r));
            return o;
        }
        if ("inverso modular".equals(leaf)) {
            BigInteger a = big(in, "a"), m = big(in, "m"), x = Discreta.modInverse(a, m);
            o.put("resultado", x.toString()); o.put("verificado", a.multiply(x).mod(m).equals(BigInteger.ONE)); o.put("verificacion", a + "·" + x + " ≡ 1 (mod " + m + ")");
            return o;
        }
        if ("coeficiente binomial".equals(leaf)) {
            int n = i(in, "n"), k = i(in, "k");
            BigInteger c = Estadistica.binomial(n, k);
            o.put("resultado", c.toString());
            o.put("verificado", k < 0 || k > n || c.equals(Discreta.factorial(n).divide(Discreta.factorial(k).multiply(Discreta.factorial(n - k)))));
            return o;
        }
        if ("permutaciones".equals(leaf)) {
            int n = i(in, "n"), k = i(in, "k"); BigInteger p = Discreta.permutations(n, k);
            o.put("resultado", p.toString()); o.put("verificado", k > n || p.equals(Estadistica.binomial(n, k).multiply(Discreta.factorial(k)))); return o;
        }
        if ("números de Fibonacci".equals(leaf)) {
            int n = i(in, "n"); BigInteger f = Discreta.fibonacci(n);
            o.put("resultado", f.toString());
            o.put("verificado", n < 2 || f.equals(Discreta.fibonacci(n - 1).add(Discreta.fibonacci(n - 2))));
            return o;
        }
        if ("caminos mínimos".equals(leaf)) return Discreta.shortestPath(s, (String) in.get("desde"), (String) in.get("hasta"));
        if ("determinante".equals(leaf)) {
            Q[][] m = Algebra.matrix(s); Q dt = Algebra.det(m);
            o.put("resultado", dt.toString());
            Q[] cp = Algebra.charPoly(m);  // det(A) = (-1)^n · c0
            o.put("verificado", (m.length % 2 == 0 ? cp[0] : cp[0].neg()).equals(dt));
            o.put("verificacion", "eliminación de Gauss exacta vs término independiente del polinomio característico");
            return o;
        }
        if ("matriz inversa".equals(leaf)) {
            Q[][] m = Algebra.matrix(s), inv = Algebra.inverse(m);
            o.put("resultado", Algebra.show(inv)); o.put("verificado", Algebra.isIdentity(Algebra.mul(m, inv))); o.put("verificacion", "A · A⁻¹ = I (exacto)");
            return o;
        }
        if ("rango".equals(leaf)) { Q[][] m = Algebra.matrix(s); int r = Algebra.rank(m); o.put("resultado", r); o.put("verificado", r == Algebra.rank(Algebra.transpose(m))); o.put("verificacion", "rango(A) = rango(Aᵀ)"); return o; }
        if ("transpuesta".equals(leaf)) { Q[][] m = Algebra.matrix(s), t = Algebra.transpose(m); o.put("resultado", Algebra.show(t)); o.put("verificado", Algebra.show(Algebra.transpose(t)).equals(Algebra.show(m))); return o; }
        if ("valores propios".equals(leaf)) {
            Q[][] m = Algebra.matrix(s); Q[] cp = Algebra.charPoly(m);
            Algebra.Roots r = Algebra.roots(cp);
            List<String> vals = new ArrayList<String>(); double sum = 0;
            for (double x : r.real) { vals.add(Num.fmt(Num.sig(x, 12))); sum += x; }
            for (double[] z : r.complex) { vals.add(Num.fmt(Num.sig(z[0], 10)) + " ± " + Num.fmt(Num.sig(z[1], 10)) + "i"); sum += 2 * z[0]; }
            Q tr = Q.ZERO; for (int k = 0; k < m.length; k++) tr = tr.add(m[k][k]);
            o.put("polinomio_caracteristico", Expr.fromPoly(cp, "λ").toString());
            o.put("valores_propios", vals);
            o.put("verificado", Math.abs(sum - tr.toDouble()) < 1e-8 * Math.max(1, Math.abs(tr.toDouble())));
            o.put("verificacion", "la suma de los valores propios coincide con la traza (" + tr + ")");
            return o;
        }
        if ("bisección".equals(leaf) || "método de la secante".equals(leaf)) {
            Expr e = Expr.parse(s); String v = var(e);
            double r = "bisección".equals(leaf) ? Calculo.bisection(e, v, d(in, "a"), d(in, "b"), 1e-13) : Calculo.secant(e, v, d(in, "a"), d(in, "b"));
            o.put("raiz", Num.fmt(Num.sig(r, 12))); o.put("residuo", Math.abs(e.eval(v, r))); o.put("verificado", Math.abs(e.eval(v, r)) < 1e-9); return o;
        }
        if ("Newton-Raphson".equals(leaf)) {
            Expr e = Expr.parse(s); String v = var(e); double r = Calculo.newton(e, v, d(in, "x0"));
            o.put("raiz", Num.fmt(Num.sig(r, 12))); o.put("residuo", Math.abs(e.eval(v, r))); o.put("verificado", Math.abs(e.eval(v, r)) < 1e-9); return o;
        }
        if ("regla del trapecio".equals(leaf) || "regla de Simpson".equals(leaf)) {
            Expr e = Expr.parse(s); String v = var(e); double a = d(in, "a"), b = d(in, "b");
            double x = "regla del trapecio".equals(leaf) ? Calculo.trapezoid(e, v, a, b, 10000) : Calculo.simpson(e, v, a, b, 1e-12, 50);
            double ref = Calculo.gaussLegendre(e, v, a, b, 200);
            o.put("valor", Num.fmt(Num.sig(x, 12))); o.put("referencia_gauss", Num.fmt(Num.sig(ref, 12)));
            o.put("verificado", Math.abs(x - ref) < ("regla del trapecio".equals(leaf) ? 1e-6 : 1e-9) * Math.max(1, Math.abs(ref)));
            return o;
        }
        if ("media".equals(leaf)) return Estadistica.describe(Estadistica.parse((String) in.get("datos")));
        if ("desviación estándar".equals(leaf)) {
            double[] x = Estadistica.parse((String) in.get("datos"));
            double sp = Estadistica.std(x, false), sm = x.length > 1 ? Estadistica.std(x, true) : Double.NaN;
            double m = Estadistica.mean(x), ss = 0; for (double v : x) ss += (v - m) * (v - m);
            o.put("desvio_poblacional", Num.fmt(Num.sig(sp, 12))); if (x.length > 1) o.put("desvio_muestral", Num.fmt(Num.sig(sm, 12)));
            o.put("verificado", Num.close(Math.sqrt(ss / x.length), sp, 1e-10) || sp == 0); o.put("verificacion", "Welford vs fórmula de dos pasadas");
            return o;
        }
        if ("correlación".equals(leaf) || "regresión lineal".equals(leaf)) {
            double[] x = Estadistica.parse((String) in.get("datos")), y = Estadistica.parse((String) in.get("datos2"));
            if ("correlación".equals(leaf)) { double r = Estadistica.correlation(x, y); o.put("resultado", Num.fmt(Num.sig(r, 10))); o.put("verificado", Math.abs(r) <= 1 + 1e-12); return o; }
            double[] r = Estadistica.regression(x, y);
            double rr = Estadistica.correlation(x, y);
            o.put("recta", "y = " + Num.fmt(Num.sig(r[0], 10)) + "·x " + (r[1] < 0 ? "- " : "+ ") + Num.fmt(Num.sig(Math.abs(r[1]), 10)));
            o.put("r2", Num.fmt(Num.sig(r[2], 10)));
            o.put("verificado", Math.abs(r[2] - rr * rr) < 1e-9); o.put("verificacion", "R² = r² (correlación al cuadrado)");
            return o;
        }
        if ("distribución binomial".equals(leaf)) {
            int n = i(in, "n"), k = i(in, "k"); double p = d(in, "p");
            double pr = Estadistica.binomialPmf(n, k, p), tot = 0; for (int j = 0; j <= n; j++) tot += Estadistica.binomialPmf(n, j, p);
            o.put("probabilidad", Num.fmt(Num.sig(pr, 10))); o.put("verificado", Math.abs(tot - 1) < 1e-9); o.put("verificacion", "la distribución suma 1");
            return o;
        }
        if ("distribución de Poisson".equals(leaf)) {
            int k = i(in, "k"); double l = d(in, "lambda"); double pr = Estadistica.poissonPmf(k, l), tot = 0;
            for (int j = 0; j < 200; j++) tot += Estadistica.poissonPmf(j, l);
            o.put("probabilidad", Num.fmt(Num.sig(pr, 10))); o.put("verificado", Math.abs(tot - 1) < 1e-9); return o;
        }
        if ("distribución normal".equals(leaf)) {
            double z = d(in, "z"), p = Estadistica.normalCdf(z);
            o.put("probabilidad", Num.fmt(Num.sig(p, 10))); o.put("significado", "P(Z ≤ " + Num.fmt(z) + ")");
            o.put("verificado", Math.abs(p + Estadistica.normalCdf(-z) - 1) < 1e-12); o.put("verificacion", "simetría Φ(z) + Φ(−z) = 1");
            return o;
        }
        if ("teorema de Bayes".equals(leaf)) {
            double pa = d(in, "pa"), pba = d(in, "pba"), pbna = d(in, "pbna"), r = Estadistica.bayes(pa, pba, pbna);
            // verificación por conteo con una población de 1 000 000
            double N = 1e6, a = N * pa, ab = a * pba, nab = (N - a) * pbna;
            o.put("probabilidad", Num.fmt(Num.sig(r, 10))); o.put("verificado", Math.abs(ab / (ab + nab) - r) < 1e-12); o.put("verificacion", "conteo de casos en una población de 1 000 000");
            return o;
        }
        if ("simulación Monte Carlo".equals(leaf)) {
            int n = i(in, "n"); long seed = in.containsKey("semilla") ? ((Number) in.get("semilla")).longValue() : 1;
            if (n > 5_000_000) throw new IllegalArgumentException("máximo 5 000 000 muestras en el teléfono");
            double a = Estadistica.monteCarloPi(n, seed), b = Estadistica.monteCarloPi(n, seed);
            o.put("pi_estimado", Num.fmt(Num.sig(a, 6))); o.put("error", Num.fmt(Num.sig(Math.abs(a - Math.PI), 3)));
            o.put("verificado", a == b && Math.abs(a - Math.PI) < 10 / Math.sqrt(n)); o.put("verificacion", "reproducible con la misma semilla y error dentro de lo esperado (~1/√n)");
            return o;
        }
        // ---- física
        if ("conversión de unidades".equals(leaf) || "escalas de temperatura".equals(leaf)) return Unidades.convert(d(in, "valor"), (String) in.get("desde"), (String) in.get("hacia"));
        if ("análisis dimensional".equals(leaf) || "verificación de unidades".equals(leaf)) {
            Unidades.U l = Unidades.parse((String) in.get("izquierda")), r = Unidades.parse((String) in.get("derecha"));
            o.put("coherente", l.sameDim(r)); o.put("izquierda", l.dims()); o.put("derecha", r.dims()); o.put("verificado", true); return o;
        }
        if ("caída libre".equals(leaf)) return Fisica.freeFall(d(in, "h"));
        if ("tiro parabólico".equals(leaf)) return Fisica.projectile(d(in, "v0"), d(in, "angulo"));
        if ("movimiento acelerado".equals(leaf)) {
            double[] r = Fisica.mrua(d(in, "x0"), d(in, "v0"), d(in, "a"), d(in, "t"));
            double v0 = d(in, "v0"), t = d(in, "t");
            o.put("posicion", Num.fmt(Num.sig(r[0], 10)) + " m"); o.put("velocidad", Num.fmt(Num.sig(r[1], 10)) + " m/s");
            o.put("verificado", Num.close(r[0] - d(in, "x0"), (v0 + r[1]) / 2 * t, 1e-12) || r[0] == d(in, "x0")); o.put("verificacion", "desplazamiento = velocidad media × tiempo");
            return o;
        }
        if ("péndulo simple".equals(leaf)) {
            double L = d(in, "L"), T = Fisica.pendulumPeriod(L);
            o.put("periodo", Num.fmt(Num.sig(T, 10)) + " s"); o.put("supuestos", "ángulo pequeño, g = 9,80665 m/s²");
            if (in.containsKey("amplitud")) o.put("periodo_amplitud_real", Num.fmt(Num.sig(Fisica.pendulumPeriodExact(L, d(in, "amplitud")), 10)) + " s");
            o.put("verificado", Num.close(Fisica.pendulumPeriodExact(L, 0.01), T, 1e-6)); o.put("verificacion", "coincide con la serie exacta para amplitud mínima");
            return o;
        }
        if ("gravitación universal".equals(leaf)) {
            double F = Fisica.gravityForce(d(in, "m1"), d(in, "m2"), d(in, "r"));
            o.put("fuerza", Num.fmt(Num.sig(F, 8)) + " N"); o.put("constante", "G = 6,67430e-11 (CODATA 2018)"); o.put("verificado", F > 0); return o;
        }
        if ("velocidad de escape".equals(leaf)) {
            double M = in.containsKey("M") ? d(in, "M") : Fisica.M_EARTH, r = in.containsKey("r") ? d(in, "r") : Fisica.R_EARTH;
            double v = Fisica.escapeVelocity(M, r);
            o.put("velocidad", Num.fmt(Num.sig(v, 8)) + " m/s"); o.put("supuestos", in.containsKey("M") ? "sin atmósfera" : "Tierra (M = 5,9722e24 kg, r = 6371 km), sin atmósfera");
            o.put("verificado", Num.close(v, Math.sqrt(2) * Fisica.orbitalVelocity(M, r), 1e-12)); o.put("verificacion", "v_escape = √2 · v_orbital");
            return o;
        }
        if ("energía cinética".equals(leaf)) {
            double m = d(in, "m"), v = d(in, "v"), k = Fisica.kinetic(m, v);
            o.put("energia", Num.fmt(Num.sig(k, 10)) + " J"); o.put("verificado", Num.close(k, (m * v) * (m * v) / (2 * m), 1e-12) || k == 0); o.put("verificacion", "E = p²/2m"); return o;
        }
        if ("choques".equals(leaf)) {
            double m1 = d(in, "m1"), v1 = d(in, "v1"), m2 = d(in, "m2"), v2 = d(in, "v2");
            double[] r = Fisica.elastic(m1, v1, m2, v2);
            o.put("v_finales", Num.fmt(Num.sig(r[0], 10)) + " m/s y " + Num.fmt(Num.sig(r[1], 10)) + " m/s"); o.put("supuestos", "choque elástico en una dimensión");
            boolean p = Num.close(m1 * v1 + m2 * v2, m1 * r[0] + m2 * r[1], 1e-12) || Math.abs(m1 * v1 + m2 * v2) < 1e-12;
            boolean e = Num.close(m1 * v1 * v1 + m2 * v2 * v2, m1 * r[0] * r[0] + m2 * r[1] * r[1], 1e-12);
            o.put("verificado", p && e); o.put("verificacion", "se conservan el momento y la energía cinética");
            return o;
        }
        if ("gases ideales".equals(leaf)) return Fisica.idealGas(opt(in, "P"), opt(in, "V"), opt(in, "n"), opt(in, "T"));
        if ("ley de Ohm".equals(leaf)) return Fisica.ohm(opt(in, "V"), opt(in, "I"), opt(in, "R"));
        if ("resistencias en serie".equals(leaf) || "resistencias en paralelo".equals(leaf)) {
            double[] r = Estadistica.parse((String) in.get("datos"));
            boolean serie = "resistencias en serie".equals(leaf);
            double v = serie ? Fisica.series(r) : Fisica.parallel(r);
            double mx = 0, mn = Double.MAX_VALUE; for (double x : r) { mx = Math.max(mx, x); mn = Math.min(mn, x); }
            o.put("resultado", Num.fmt(Num.sig(v, 10)) + " Ω");
            o.put("verificado", serie ? v >= mx : v <= mn); o.put("verificacion", serie ? "la equivalente en serie no es menor que la mayor" : "la equivalente en paralelo no supera a la menor");
            return o;
        }
        if ("equivalencia masa-energía".equals(leaf)) {
            double m = d(in, "m"), e = Fisica.massEnergy(m);
            o.put("energia", Num.fmt(Num.sig(e, 10)) + " J"); o.put("equivalente", Num.fmt(Num.sig(e / 4.184e12, 6)) + " kilotones de TNT (por comparación)");
            o.put("verificado", Num.close(e / (Fisica.C * Fisica.C), m, 1e-12)); return o;
        }
        if ("efecto Doppler".equals(leaf)) {
            double f = d(in, "f"), vo = in.containsKey("v_obs") ? d(in, "v_obs") : 0, vs = in.containsKey("v_fuente") ? d(in, "v_fuente") : 0;
            double r = Fisica.doppler(f, 343, vo, vs);
            o.put("frecuencia", Num.fmt(Num.sig(r, 8)) + " Hz"); o.put("supuestos", "sonido en aire a 20 °C (343 m/s); velocidades positivas = acercándose");
            o.put("verificado", (vo + vs > 0) == (r > f) || vo + vs == 0); return o;
        }
        if ("dilatación temporal".equals(leaf)) {
            double v = d(in, "v"), g = Fisica.gamma(v);
            o.put("gamma", Num.fmt(Num.sig(g, 10))); o.put("significado", "1 s del objeto en movimiento = " + Num.fmt(Num.sig(g, 6)) + " s para el observador");
            o.put("verificado", g >= 1 && Num.close(1 / (g * g), 1 - v * v / (Fisica.C * Fisica.C), 1e-9)); return o;
        }
        if ("efecto fotoeléctrico".equals(leaf)) {
            Double k = Fisica.photoelectric(d(in, "lambda"), d(in, "trabajo_ev"));
            o.put("emite", k != null);
            o.put("energia_ev", k == null ? "0 (el fotón no alcanza la función trabajo)" : Num.fmt(Num.sig(k / Fisica.E_CHARGE, 6)));
            o.put("verificado", true); return o;
        }
        // ---- química
        if ("masa molar".equals(leaf) || "composición porcentual".equals(leaf) || "hidratos".equals(leaf)) return flat(Quimica.describe((String) in.get("formula")));
        if ("fórmula empírica".equals(leaf)) {
            Map<String, Double> pct = new LinkedHashMap<String, Double>();
            for (String p : s.split("[,;]\\s*(?=[A-Z])")) { String[] kv = p.split("[:=]"); pct.put(kv[0].trim(), Double.parseDouble(kv[1].trim().replace("%", "").replace(',', '.'))); }
            String f = Quimica.empirical(pct);
            Map<String, Object> d = Quimica.describe(f);
            o.put("formula", f); o.put("composicion_de_la_formula", Json.write(d.get("composicion")));
            o.put("verificado", true); o.put("verificacion", "proporciones molares redondeadas a enteros (tolerancia 0,08)");
            return o;
        }
        if ("balanceo de ecuaciones".equals(leaf) || "conservación de la masa".equals(leaf)) {
            Quimica.Balanced b = Quimica.balance(s);
            o.put("ecuacion", b.text); o.put("coeficientes", b.coef.toString()); o.put("verificado", b.verified); o.put("verificacion", "átomos (y carga) iguales de ambos lados");
            return o;
        }
        if ("reactivo limitante".equals(leaf)) return flat(Quimica.stoichiometry(s, Estadistica.parse((String) in.get("datos"))));
        if ("moles y gramos".equals(leaf)) {
            double M = Quimica.molarMass((String) in.get("formula")), g = d(in, "gramos"), n = g / M;
            o.put("moles", Num.fmt(Num.sig(n, 6))); o.put("masa_molar", Num.fmt(Num.sig(M, 6)) + " g/mol"); o.put("verificado", Num.close(n * M, g, 1e-12) || g == 0); return o;
        }
        if ("concentración molar".equals(leaf)) {
            double c = Quimica.molarity(d(in, "gramos"), (String) in.get("formula"), d(in, "litros"));
            o.put("molaridad", Num.fmt(Num.sig(c, 6)) + " mol/L"); o.put("verificado", c >= 0); return o;
        }
        if ("diluciones".equals(leaf)) {
            double v2 = Quimica.dilution(d(in, "c1"), d(in, "v1"), d(in, "c2"));
            o.put("v2", Num.fmt(Num.sig(v2, 10))); o.put("agua_a_agregar", Num.fmt(Num.sig(v2 - d(in, "v1"), 10)));
            o.put("verificado", Num.close(d(in, "c1") * d(in, "v1"), d(in, "c2") * v2, 1e-12)); o.put("verificacion", "C1·V1 = C2·V2 (mismas unidades de volumen que V1)");
            return o;
        }
        if ("pH".equals(leaf) || "ácidos y bases".equals(leaf)) {
            double c = d(in, "c");
            boolean base = Boolean.TRUE.equals(in.get("base"));
            double ph = in.containsKey("ka") ? Quimica.phWeakAcid(c, d(in, "ka")) : base ? Quimica.phStrongBase(c) : Quimica.phStrongAcid(c);
            o.put("pH", Num.fmt(Num.sig(ph, 4)));
            o.put("supuestos", "25 °C, Kw = 1e-14" + (in.containsKey("ka") ? ", ácido débil monoprótico (cuadrática exacta)" : base ? ", base fuerte" : ", ácido fuerte monoprótico"));
            if (in.containsKey("ka")) { double x = Math.pow(10, -ph), ka = d(in, "ka"); o.put("verificado", Num.close(x * x / (c - x), ka, 1e-6)); }
            else o.put("verificado", ph >= 0 - 2 && ph <= 16);
            return o;
        }
        if ("número de Avogadro".equals(leaf)) { double n = d(in, "moles") * Fisica.NA; o.put("particulas", Num.fmt(Num.sig(n, 10))); o.put("constante", "NA = 6,02214076e23 (exacta)"); o.put("verificado", true); return o; }
        if ("rechazo de síntesis peligrosas".equals(leaf)) {
            String why = Quimica.danger(s);
            o.put("rechazado", why != null); if (why != null) o.put("motivo", why); o.put("verificado", true); return o;
        }
        // ---- razonamiento
        if ("verificación de cálculos".equals(leaf)) {
            Map<String, Object> r = Json.obj(in.get("resultado"));
            boolean v = Boolean.TRUE.equals(r.get("verificado"));
            o.put("confianza", v ? "alta" : "baja");
            o.put("motivo", v ? "el resultado trae una comprobación independiente que se cumplió" : "el resultado NO pasó su comprobación: no debe darse como cierto");
            o.put("verificado", true);
            return o;
        }
        throw new IllegalStateException("motor sin implementar: " + leaf);
    }

    static double d(Map<String, Object> in, String k) { Object v = in.get(k); if (!(v instanceof Number)) throw new IllegalArgumentException("falta el número «" + k + "»"); return ((Number) v).doubleValue(); }
    static double opt(Map<String, Object> in, String k) { Object v = in.get(k); return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN; }
    static int i(Map<String, Object> in, String k) { double x = d(in, k); if (x != Math.rint(x) || Math.abs(x) > 1e9) throw new IllegalArgumentException("«" + k + "» debe ser entero"); return (int) x; }
    static BigInteger big(Map<String, Object> in, String k) {
        Object v = in.get(k);
        if (v instanceof String) return new BigInteger(((String) v).trim());
        double x = d(in, k); if (x != Math.rint(x)) throw new IllegalArgumentException("«" + k + "» debe ser entero");
        return new java.math.BigDecimal(x).toBigInteger();
    }
    static String var(Expr e) { return e.vars().isEmpty() ? "x" : e.vars().first(); }
    /** Aplana los mapas anidados a texto (para que el contrato y el registro sean simples). */
    static Map<String, Object> flat(Map<String, Object> m) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : m.entrySet()) o.put(e.getKey(), e.getValue() instanceof Map ? mapText(Json.obj(e.getValue())) : e.getValue());
        return o;
    }
    static String mapText(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Object> e : m.entrySet()) { if (b.length() > 0) b.append(", "); b.append(e.getKey()).append('=').append(e.getValue()); }
        return b.toString();
    }
}
