package com.radar.jarvis.core.cerebro;

import java.util.Map;

/** Lo que ejecuta un módulo operativo: recibe datos según su contrato y devuelve un resultado (con "verificado"). */
public interface Motor {
    Map<String, Object> ejecutar(Map<String, Object> entrada) throws Exception;
}
