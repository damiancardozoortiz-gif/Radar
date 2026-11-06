package com.radar.jarvis.core.cerebro;

import com.radar.jarvis.core.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ÁRBOL BINARIO DE 2.047 MÓDULOS (11 niveles: 1, 2, 4 … 1024). Numeración de montículo: el módulo i tiene padre i/2 e
 * hijos 2i y 2i+1, así la identidad, el nivel y el parentesco se calculan sin tablas. Los nombres, dominios y
 * responsabilidades vienen de assets/cerebro-2047.json (generado por cerebro/arbol.py). Inmutable y liviano.
 */
public final class Arbol {
    public static final int N = 2047, NIVELES = 11, PRIMERA_HOJA = 1024;
    final String[] nombre = new String[N + 1], dominio = new String[N + 1], resp = new String[N + 1];
    final Map<String, Integer> porNombre = new HashMap<String, Integer>();

    public Arbol(String json) {
        List<Object> ms = Json.arr(Json.obj(Json.parse(json)).get("modulos"));
        if (ms == null || ms.size() != N) throw new IllegalArgumentException("el árbol debe tener " + N + " módulos");
        for (Object o : ms) {
            Map<String, Object> m = Json.obj(o);
            int id = ((Number) m.get("id")).intValue();
            if (id < 1 || id > N || nombre[id] != null) throw new IllegalArgumentException("id inválido o repetido: " + id);
            nombre[id] = (String) m.get("nombre"); dominio[id] = (String) m.get("dominio"); resp[id] = (String) m.get("responsabilidad");
            if (nivel(id) != ((Number) m.get("nivel")).intValue()) throw new IllegalArgumentException("nivel incoherente en " + id);
            if (id >= PRIMERA_HOJA && porNombre.put(nombre[id], id) != null) throw new IllegalArgumentException("hoja repetida: " + nombre[id]);
        }
    }
    public static boolean valido(int id) { return id >= 1 && id <= N; }
    public static int nivel(int id) { return 32 - Integer.numberOfLeadingZeros(id); }
    public static int padre(int id) { return id > 1 ? id / 2 : 0; }
    public static int[] hijos(int id) { return id < PRIMERA_HOJA ? new int[]{2 * id, 2 * id + 1} : new int[0]; }
    public static boolean hoja(int id) { return id >= PRIMERA_HOJA; }
    public static int hermano(int id) { return id > 1 ? id ^ 1 : 0; }
    /** Primera y última hoja bajo id. */
    public static int[] hojas(int id) { int sh = NIVELES - nivel(id); return new int[]{id << sh, ((id + 1) << sh) - 1}; }
    public static boolean ancestro(int a, int d) { while (d > a) d >>= 1; return d == a; }
    /** Especialidad de nivel 5 (16..31) a la que pertenece. */
    public static int especialidad(int id) { int l = nivel(id); return l < 5 ? 0 : id >> (l - 5); }

    public String nombre(int id) { return nombre[id]; }
    public String dominio(int id) { return dominio[id]; }
    public String responsabilidad(int id) { return resp[id]; }
    public int hojaPorNombre(String n) { Integer i = porNombre.get(n); return i == null ? 0 : i; }
    /** Camino desde la raíz: [1, …, id]. */
    public static List<Integer> camino(int id) { List<Integer> o = new ArrayList<Integer>(); for (int x = id; x >= 1; x >>= 1) o.add(0, x); return o; }
}
