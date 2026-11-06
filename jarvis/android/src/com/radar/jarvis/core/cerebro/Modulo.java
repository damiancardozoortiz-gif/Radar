package com.radar.jarvis.core.cerebro;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Estado y declaración de UN módulo. Solo existen objetos para los módulos con algo real detrás (operativos y conectados)
 * o que se activaron: los planificados no ocupan memoria hasta que se piden (activación bajo demanda).
 */
public final class Modulo {
    public static final String OPERATIVO = "operativo", CONECTADO = "conectado", PLANIFICADO = "planificado";
    public static final String LIBRE = "libre", CONFIRMAR = "requiere_confirmacion", PROHIBIDO = "prohibido";

    public final int id;
    public String estado = PLANIFICADO, permiso = LIBRE, implementacion;
    /** Contrato: clave → tipo ("texto", "numero", "lista", "mapa", "bool"); las claves con '?' son opcionales. */
    public final Map<String, String> entrada = new LinkedHashMap<String, String>(), salida = new LinkedHashMap<String, String>();
    public final List<Integer> dependencias = new ArrayList<Integer>();
    public Motor motor;
    public Map<String, Object> prueba;        // entrada de la prueba individual
    public String[] esperado;                 // {clave, texto que debe contener}
    // estado de ejecución
    public int llamadas, fallos, fallosSeguidos;
    public long abiertoHasta, ultimoMs, activadoEn;
    public String ultimoError;
    final Deque<Map<String, Object>> resultados = new ArrayDeque<Map<String, Object>>();

    Modulo(int id) { this.id = id; }

    void registrar(Map<String, Object> r) { resultados.addLast(r); while (resultados.size() > 5) resultados.removeFirst(); }
    public List<Map<String, Object>> ultimosResultados() { return new ArrayList<Map<String, Object>>(resultados); }
}
