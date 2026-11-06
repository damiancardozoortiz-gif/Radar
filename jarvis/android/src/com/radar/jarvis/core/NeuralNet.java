package com.radar.jarvis.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * RED NEURONAL REAL (pequeña) para entender la INTENCIÓN de cada pedido.
 * Perceptrón multicapa: texto → rasgos (palabras, raíces de 5 letras y pares de palabras, por «hashing» en 1024
 * posiciones) → 24 neuronas ocultas (ReLU) → una salida por intención (softmax). Entrenamiento con descenso de gradiente
 * y entropía cruzada, en el propio teléfono, en menos de un segundo. Aprende de los ejemplos semilla y de los pedidos
 * reales del señor Rafael (y de sus correcciones). No es un modelo de lenguaje: solo clasifica la intención; el
 * razonamiento sigue siendo de Claude.
 */
public final class NeuralNet {
    public static final int D = 1024, H = 24;
    public final String[] labels;
    private final float[][] w1 = new float[D][H];
    private final float[] b1 = new float[H];
    private final float[][] w2;
    private final float[] b2;
    public int trainedOn;
    public double validationAccuracy = -1;

    public NeuralNet(String[] labels) {
        this.labels = labels;
        w2 = new float[H][labels.length];
        b2 = new float[labels.length];
        init(new Random(7));
    }

    private void init(Random r) {
        double s1 = Math.sqrt(2.0 / 16), s2 = Math.sqrt(2.0 / H);  // He: ~16 rasgos activos por frase
        for (float[] row : w1) for (int j = 0; j < H; j++) row[j] = (float) (r.nextGaussian() * s1 * 0.5);
        for (float[] row : w2) for (int k = 0; k < row.length; k++) row[k] = (float) (r.nextGaussian() * s2);
        Arrays.fill(b1, 0); Arrays.fill(b2, 0);
    }

    // ================================================================== rasgos
    static String norm(String s) {
        String t = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Índices y valores de los rasgos activos (vector disperso, norma 1). */
    static Object[] features(String text) {
        String[] w = norm(text).split(" ");
        List<String> f = new ArrayList<String>();
        String prev = null;
        for (String t : w) {
            if (t.length() < 2) { prev = null; continue; }
            f.add("w:" + t);
            if (t.length() > 5) f.add("p:" + t.substring(0, 5));
            if (prev != null) f.add("b:" + prev + "_" + t);
            prev = t;
        }
        Map<Integer, Float> m = new LinkedHashMap<Integer, Float>();
        for (String x : f) { int i = (x.hashCode() & 0x7fffffff) % D; m.put(i, m.containsKey(i) ? m.get(i) + 1f : 1f); }
        int[] idx = new int[m.size()]; float[] val = new float[m.size()];
        double norm = 0; int k = 0;
        for (Map.Entry<Integer, Float> e : m.entrySet()) { idx[k] = e.getKey(); val[k] = e.getValue(); norm += val[k] * val[k]; k++; }
        norm = Math.sqrt(Math.max(norm, 1e-9));
        for (int i = 0; i < val.length; i++) val[i] /= norm;
        return new Object[]{idx, val};
    }

    // ================================================================== red
    private float[] hidden(int[] idx, float[] val) {
        float[] h = b1.clone();
        for (int i = 0; i < idx.length; i++) { float[] row = w1[idx[i]]; for (int j = 0; j < H; j++) h[j] += val[i] * row[j]; }
        for (int j = 0; j < H; j++) if (h[j] < 0) h[j] = 0;
        return h;
    }

    private float[] output(float[] h) {
        int K = labels.length;
        float[] z = b2.clone();
        for (int j = 0; j < H; j++) if (h[j] != 0) for (int k = 0; k < K; k++) z[k] += h[j] * w2[j][k];
        float max = z[0]; for (float v : z) max = Math.max(max, v);
        double sum = 0; for (int k = 0; k < K; k++) { z[k] = (float) Math.exp(z[k] - max); sum += z[k]; }
        for (int k = 0; k < K; k++) z[k] /= sum;
        return z;
    }

    /** Probabilidad de cada intención. */
    public synchronized float[] probabilities(String text) {
        Object[] f = features(text);
        return output(hidden((int[]) f[0], (float[]) f[1]));
    }

    /** La intención más probable: {etiqueta, probabilidad}. */
    public Map<String, Object> predict(String text) {
        float[] p = probabilities(text);
        int best = 0; for (int k = 1; k < p.length; k++) if (p[k] > p[best]) best = k;
        return Json.map("intencion", labels[best], "confianza", Math.round(p[best] * 1000) / 1000.0);
    }

    /** Entrena desde cero (determinista). examples: {texto, etiqueta}. Separa 1 de cada 5 para medir la precisión real. */
    public synchronized Map<String, Object> train(List<String[]> examples, int epochs, float lr) {
        init(new Random(7));
        List<Object[]> tr = new ArrayList<Object[]>(), va = new ArrayList<Object[]>();
        List<String> lab = Arrays.asList(labels);
        int n = 0;
        for (String[] e : examples) {
            int y = lab.indexOf(e[1]);
            if (y < 0) continue;
            Object[] f = features(e[0]);
            (n++ % 5 == 4 ? va : tr).add(new Object[]{f[0], f[1], y});
        }
        Random r = new Random(11);
        for (int ep = 0; ep < epochs; ep++) {
            java.util.Collections.shuffle(tr, r);
            for (Object[] s : tr) step((int[]) s[0], (float[]) s[1], (Integer) s[2], lr);
        }
        int okV = 0;
        for (Object[] s : va) if (argmax(output(hidden((int[]) s[0], (float[]) s[1]))) == (Integer) s[2]) okV++;
        int okT = 0;
        for (Object[] s : tr) if (argmax(output(hidden((int[]) s[0], (float[]) s[1]))) == (Integer) s[2]) okT++;
        validationAccuracy = va.isEmpty() ? -1 : okV / (double) va.size();
        // con la medida hecha, se reentrena con TODOS los ejemplos (validación incluida) para usar todo lo aprendido
        tr.addAll(va);
        init(new Random(7));
        for (int ep = 0; ep < epochs; ep++) { java.util.Collections.shuffle(tr, r); for (Object[] s : tr) step((int[]) s[0], (float[]) s[1], (Integer) s[2], lr); }
        trainedOn = tr.size();
        return Json.map("ejemplos", (long) tr.size(), "precision_entrenamiento", okT / (double) Math.max(1, tr.size() - va.size()),
                "precision_validacion", validationAccuracy, "validacion", (long) va.size());
    }

    private void step(int[] idx, float[] val, int y, float lr) {
        int K = labels.length;
        float[] h = hidden(idx, val), p = output(h);
        float[] dz = new float[K];
        for (int k = 0; k < K; k++) dz[k] = p[k] - (k == y ? 1 : 0);
        float[] dh = new float[H];
        for (int j = 0; j < H; j++) {
            if (h[j] > 0) for (int k = 0; k < K; k++) dh[j] += w2[j][k] * dz[k];
            for (int k = 0; k < K; k++) w2[j][k] -= lr * h[j] * dz[k];
        }
        for (int k = 0; k < K; k++) b2[k] -= lr * dz[k];
        for (int i = 0; i < idx.length; i++) { float[] row = w1[idx[i]]; for (int j = 0; j < H; j++) row[j] -= lr * val[i] * dh[j]; }
        for (int j = 0; j < H; j++) b1[j] -= lr * dh[j];
    }

    private static int argmax(float[] p) { int b = 0; for (int k = 1; k < p.length; k++) if (p[k] > p[b]) b = k; return b; }

    // ================================================================== guardar / cargar pesos
    public synchronized void save(File f) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
        o.writeInt(D); o.writeInt(H); o.writeInt(labels.length);
        for (String l : labels) o.writeUTF(l);
        for (float[] row : w1) for (float v : row) o.writeFloat(v);
        for (float v : b1) o.writeFloat(v);
        for (float[] row : w2) for (float v : row) o.writeFloat(v);
        for (float v : b2) o.writeFloat(v);
        o.writeInt(trainedOn); o.writeDouble(validationAccuracy);
        o.close();
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IOException("no pude guardar la red"); }
    }

    /** Carga pesos guardados; null si no existen o no corresponden a estas intenciones. */
    public static NeuralNet load(File f, String[] labels) {
        if (!f.isFile()) return null;
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
            try {
                if (in.readInt() != D || in.readInt() != H || in.readInt() != labels.length) return null;
                for (String l : labels) if (!l.equals(in.readUTF())) return null;
                NeuralNet n = new NeuralNet(labels);
                for (float[] row : n.w1) for (int j = 0; j < H; j++) row[j] = in.readFloat();
                for (int j = 0; j < H; j++) n.b1[j] = in.readFloat();
                for (float[] row : n.w2) for (int k = 0; k < row.length; k++) row[k] = in.readFloat();
                for (int k = 0; k < n.b2.length; k++) n.b2[k] = in.readFloat();
                n.trainedOn = in.readInt(); n.validationAccuracy = in.readDouble();
                return n;
            } finally { in.close(); }
        } catch (IOException e) { return null; }
    }
}
