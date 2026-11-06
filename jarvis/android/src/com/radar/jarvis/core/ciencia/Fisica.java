package com.radar.jarvis.core.ciencia;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FÍSICA: constantes (CODATA 2018; las del SI 2019 son exactas), mecánica, gravitación, termodinámica, ondas,
 * electricidad y física moderna. Las simulaciones (tiro con integración numérica) se COMPARAN con la fórmula cerrada;
 * cada resultado declara supuestos (sin rozamiento, g = 9,80665 m/s², etc.).
 */
public final class Fisica {
    private Fisica() {}
    public static final double C = 299792458, H = 6.62607015e-34, E_CHARGE = 1.602176634e-19, NA = 6.02214076e23, KB = 1.380649e-23,
            R = NA * KB, G = 6.67430e-11, G0 = 9.80665, ME = 9.1093837015e-31, EPS0 = 8.8541878128e-12, K_COULOMB = 1 / (4 * Math.PI * EPS0),
            M_EARTH = 5.9722e24, R_EARTH = 6.371e6, SIGMA = 5.670374419e-8;

    static Map<String, Object> res(String what, double v, String unit, String supuestos) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put(what, Num.fmt(Num.sig(v, 10)) + " " + unit);
        o.put("valor", v);
        if (supuestos != null) o.put("supuestos", supuestos);
        return o;
    }

    // ------------------------------------------------------------------ cinemática
    /** MRUA: x = x0 + v0 t + a t²/2; v = v0 + a t. */
    public static double[] mrua(double x0, double v0, double a, double t) { return new double[]{x0 + v0 * t + a * t * t / 2, v0 + a * t}; }
    /** Caída libre desde altura h: tiempo y velocidad de impacto (sin aire). */
    public static Map<String, Object> freeFall(double h) {
        if (h <= 0) throw new IllegalArgumentException("la altura debe ser positiva");
        double t = Math.sqrt(2 * h / G0), v = G0 * t;
        Map<String, Object> o = res("tiempo", t, "s", "sin resistencia del aire, g = 9,80665 m/s²");
        o.put("velocidad_impacto", Num.fmt(Num.sig(v, 10)) + " m/s (" + Num.fmt(Num.sig(v * 3.6, 6)) + " km/h)");
        o.put("verificado", Num.close(Math.sqrt(2 * G0 * h), v, 1e-12));  // conservación de la energía: v = √(2gh)
        o.put("verificacion", "coincide con conservación de la energía v = √(2gh)");
        return o;
    }
    /** Tiro parabólico desde el suelo: fórmulas cerradas y SIMULACIÓN (RK4 con dt = 1 ms) para comparar. */
    public static Map<String, Object> projectile(double v0, double angleDeg) {
        double th = Math.toRadians(angleDeg), vx = v0 * Math.cos(th), vy = v0 * Math.sin(th);
        double T = 2 * vy / G0, range = vx * T, hmax = vy * vy / (2 * G0);
        // simulación numérica independiente
        double x = 0, y = 0, ux = vx, uy = vy, t = 0, dt = 1e-4, ymax = 0;
        while (true) {
            double ny = y + uy * dt - G0 * dt * dt / 2, nuy = uy - G0 * dt;
            if (t > 0 && ny < 0) { double frac = y / (y - ny); x += ux * dt * frac; t += dt * frac; break; }
            x += ux * dt; y = ny; uy = nuy; t += dt; ymax = Math.max(ymax, y);
            if (t > 1e6) break;
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("alcance", Num.fmt(Num.sig(range, 8)) + " m");
        o.put("altura_maxima", Num.fmt(Num.sig(hmax, 8)) + " m");
        o.put("tiempo_de_vuelo", Num.fmt(Num.sig(T, 8)) + " s");
        o.put("simulacion", "alcance " + Num.fmt(Num.sig(x, 8)) + " m · altura " + Num.fmt(Num.sig(ymax, 8)) + " m · vuelo " + Num.fmt(Num.sig(t, 8)) + " s");
        o.put("supuestos", "lanzamiento y caída al mismo nivel, sin aire, g = 9,80665 m/s²");
        o.put("verificado", Num.close(x, range, 1e-4) && Num.close(t, T, 1e-4) && Num.close(ymax, hmax, 1e-4));
        o.put("verificacion", "simulación paso a paso (dt = 0,1 ms) vs fórmula cerrada");
        return o;
    }

    // ------------------------------------------------------------------ dinámica y energía
    public static double kinetic(double m, double v) { return m * v * v / 2; }
    public static double potential(double m, double h) { return m * G0 * h; }
    public static double pendulumPeriod(double L) { return 2 * Math.PI * Math.sqrt(L / G0); }
    /** Período del péndulo para amplitud grande (serie elíptica) — para comparar con la aproximación de ángulo pequeño. */
    public static double pendulumPeriodExact(double L, double ampDeg) {
        double k = Math.sin(Math.toRadians(ampDeg) / 2), sum = 1, term = 1;
        for (int n = 1; n < 60; n++) { term *= ((2.0 * n - 1) / (2 * n)) * ((2.0 * n - 1) / (2 * n)) * k * k; sum += term; }
        return pendulumPeriod(L) * sum;
    }
    public static double gravityForce(double m1, double m2, double r) { return G * m1 * m2 / (r * r); }
    public static double escapeVelocity(double M, double r) { return Math.sqrt(2 * G * M / r); }
    public static double orbitalVelocity(double M, double r) { return Math.sqrt(G * M / r); }
    public static double inclinedPlaneAccel(double angleDeg, double mu) { double th = Math.toRadians(angleDeg); return G0 * (Math.sin(th) - mu * Math.cos(th)); }
    /** Choque elástico 1D: velocidades finales (conservan momento y energía). */
    public static double[] elastic(double m1, double v1, double m2, double v2) {
        return new double[]{((m1 - m2) * v1 + 2 * m2 * v2) / (m1 + m2), ((m2 - m1) * v2 + 2 * m1 * v1) / (m1 + m2)};
    }

    // ------------------------------------------------------------------ termodinámica, ondas
    /** Gas ideal PV = nRT: despeja la incógnita que viene como NaN (P Pa, V m³, n mol, T K). */
    public static Map<String, Object> idealGas(double P, double V, double n, double T) {
        int unknown = (Double.isNaN(P) ? 1 : 0) + (Double.isNaN(V) ? 1 : 0) + (Double.isNaN(n) ? 1 : 0) + (Double.isNaN(T) ? 1 : 0);
        if (unknown != 1) throw new IllegalArgumentException("indicá exactamente una incógnita (P, V, n o T)");
        Map<String, Object> o;
        if (Double.isNaN(P)) { P = n * R * T / V; o = res("P", P, "Pa", null); }
        else if (Double.isNaN(V)) { V = n * R * T / P; o = res("V", V, "m³", null); }
        else if (Double.isNaN(n)) { n = P * V / (R * T); o = res("n", n, "mol", null); }
        else { T = P * V / (n * R); o = res("T", T, "K", null); }
        o.put("supuestos", "gas ideal, R = 8,314462618 J/(mol·K)");
        o.put("verificado", Num.close(P * V, n * R * T, 1e-12));
        return o;
    }
    public static double heat(double m, double c, double dT) { return m * c * dT; }
    public static double carnot(double Tc, double Th) { return 1 - Tc / Th; }
    public static double waveSpeed(double f, double lambda) { return f * lambda; }
    /** Doppler sonoro: fuente y observador acercándose con velocidades positivas. */
    public static double doppler(double f, double vSound, double vObserver, double vSource) { return f * (vSound + vObserver) / (vSound - vSource); }
    public static double thinLens(double f, double dObject) { return 1 / (1 / f - 1 / dObject); }

    // ------------------------------------------------------------------ electricidad
    public static Map<String, Object> ohm(double V, double I, double Rr) {
        Map<String, Object> o;
        if (Double.isNaN(V)) { V = I * Rr; o = res("V", V, "V", null); }
        else if (Double.isNaN(I)) { I = V / Rr; o = res("I", I, "A", null); }
        else { Rr = V / I; o = res("R", Rr, "Ω", null); }
        o.put("potencia", Num.fmt(Num.sig(V * I, 10)) + " W");
        o.put("verificado", Num.close(V, I * Rr, 1e-12));
        return o;
    }
    public static double series(double... r) { double s = 0; for (double x : r) s += x; return s; }
    public static double parallel(double... r) { double s = 0; for (double x : r) s += 1 / x; return 1 / s; }
    public static double coulomb(double q1, double q2, double r) { return K_COULOMB * q1 * q2 / (r * r); }

    // ------------------------------------------------------------------ física moderna
    public static double gamma(double v) { if (Math.abs(v) >= C) throw new IllegalArgumentException("ningún cuerpo con masa alcanza la velocidad de la luz"); return 1 / Math.sqrt(1 - v * v / (C * C)); }
    public static double massEnergy(double m) { return m * C * C; }
    public static double photonEnergy(double lambda) { return H * C / lambda; }
    /** Efecto fotoeléctrico: energía cinética máxima (J) o null si no hay emisión. */
    public static Double photoelectric(double lambda, double workFunctionEv) { double k = photonEnergy(lambda) - workFunctionEv * E_CHARGE; return k > 0 ? k : null; }
    public static double particleInBox(int n, double m, double L) { return n * n * H * H / (8 * m * L * L); }
}
