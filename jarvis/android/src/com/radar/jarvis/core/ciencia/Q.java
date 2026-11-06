package com.radar.jarvis.core.ciencia;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/** Número RACIONAL exacto (BigInteger/BigInteger, siempre reducido, denominador positivo). Base del cálculo simbólico. */
public final class Q implements Comparable<Q> {
    public final BigInteger n, d;
    public static final Q ZERO = new Q(BigInteger.ZERO, BigInteger.ONE), ONE = new Q(BigInteger.ONE, BigInteger.ONE);

    public Q(BigInteger n, BigInteger d) {
        if (d.signum() == 0) throw new ArithmeticException("división por cero");
        if (d.signum() < 0) { n = n.negate(); d = d.negate(); }
        BigInteger g = n.gcd(d);
        if (!g.equals(BigInteger.ONE) && g.signum() != 0) { n = n.divide(g); d = d.divide(g); }
        if (n.signum() == 0) d = BigInteger.ONE;
        this.n = n; this.d = d;
    }
    public static Q of(long a) { return new Q(BigInteger.valueOf(a), BigInteger.ONE); }
    public static Q of(long a, long b) { return new Q(BigInteger.valueOf(a), BigInteger.valueOf(b)); }
    public static Q of(BigInteger a) { return new Q(a, BigInteger.ONE); }
    /** "3", "-2.75", "1e-3", "3/4" → exacto. */
    public static Q parse(String s) {
        s = s.trim().replace(',', '.');
        int sl = s.indexOf('/');
        if (sl > 0) return parse(s.substring(0, sl)).div(parse(s.substring(sl + 1)));
        BigDecimal b = new BigDecimal(s);
        if (b.scale() <= 0) return of(b.toBigIntegerExact());
        return new Q(b.unscaledValue(), BigInteger.TEN.pow(b.scale()));
    }
    /** Aproximación racional de un double (solo si es "redondo": hasta 12 decimales). */
    public static Q fromDouble(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) throw new ArithmeticException("no es un número finito");
        return parse(new BigDecimal(x).round(new MathContext(15)).stripTrailingZeros().toPlainString());
    }
    public Q add(Q o) { return new Q(n.multiply(o.d).add(o.n.multiply(d)), d.multiply(o.d)); }
    public Q sub(Q o) { return add(o.neg()); }
    public Q mul(Q o) { return new Q(n.multiply(o.n), d.multiply(o.d)); }
    public Q div(Q o) { if (o.isZero()) throw new ArithmeticException("división por cero"); return new Q(n.multiply(o.d), d.multiply(o.n)); }
    public Q neg() { return new Q(n.negate(), d); }
    public Q abs() { return signum() < 0 ? neg() : this; }
    public Q pow(int e) {
        if (e < 0) return ONE.div(pow(-e));
        if (e > 10000) throw new ArithmeticException("exponente demasiado grande");
        return new Q(n.pow(e), d.pow(e));
    }
    public boolean isZero() { return n.signum() == 0; }
    public boolean isInt() { return d.equals(BigInteger.ONE); }
    public int signum() { return n.signum(); }
    public double toDouble() { return new BigDecimal(n).divide(new BigDecimal(d), MathContext.DECIMAL64).doubleValue(); }
    /** Raíz cuadrada exacta si es un cuadrado perfecto racional; si no, null. */
    public Q sqrtExact() {
        if (signum() < 0) return null;
        BigInteger a = isqrt(n), b = isqrt(d);
        return a.multiply(a).equals(n) && b.multiply(b).equals(d) ? new Q(a, b) : null;
    }
    /** Raíz entera (Newton sobre BigInteger; Android 6 no tiene BigDecimal.sqrt). */
    static BigInteger isqrt(BigInteger x) {
        if (x.signum() <= 0) return BigInteger.ZERO;
        BigInteger r = BigInteger.ONE.shiftLeft(x.bitLength() / 2 + 1);
        while (true) {
            BigInteger y = r.add(x.divide(r)).shiftRight(1);
            if (y.compareTo(r) >= 0) break;
            r = y;
        }
        while (r.multiply(r).compareTo(x) > 0) r = r.subtract(BigInteger.ONE);
        return r;
    }
    public int compareTo(Q o) { return n.multiply(o.d).compareTo(o.n.multiply(d)); }
    @Override public boolean equals(Object o) { return o instanceof Q && ((Q) o).n.equals(n) && ((Q) o).d.equals(d); }
    @Override public int hashCode() { return n.hashCode() * 31 + d.hashCode(); }
    @Override public String toString() { return isInt() ? n.toString() : n + "/" + d; }
    /** "3/4 (0.75)" para mostrar exacto y decimal. */
    public String show() { return isInt() ? n.toString() : this + " ≈ " + Num.fmt(toDouble()); }
}
