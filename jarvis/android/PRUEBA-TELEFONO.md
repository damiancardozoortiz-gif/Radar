# Prueba real de JARVIS + FÉNIX 80 en el teléfono

Para cada punto: haga la acción, compare con «Debe pasar» y anote **PASA**, **FALLA** (con lo que vio) o **NO PROBADO**.
Envíeme la lista completa (o una captura de cada FALLA). Nada de esta lista está verificado hasta tener sus resultados.

## 0. Antes de actualizar
- Ajustes de Android → Aplicaciones → JARVIS: anote la **versión** que tiene ahora.
- Abra JARVIS → ⚙ Panel: compruebe que Claude y ElevenLabs dicen «guardada (cifrada)». Anote cuántos recuerdos y tareas tiene.

## 1. Instalación como actualización
| # | Acción | Debe pasar |
|---|---|---|
| 1.1 | Abra la APK descargada → «Actualizar» | Android dice **«¿Quieres actualizar esta aplicación?»** (no «instalar»). Si pide desinstalar o dice «conflicto con un paquete existente», **pare y avíseme**: no desinstale. |
| 1.2 | Ajustes → Aplicaciones → JARVIS | Versión nueva (la que indique el informe). |
| 1.3 | Abra JARVIS → ⚙ Panel | Las claves siguen «guardadas»; los recuerdos y tareas son los mismos que en el paso 0. |

## 2. Arranque, voz y conexiones
| # | Acción | Debe pasar |
|---|---|---|
| 2.1 | Abrir JARVIS | Holograma a pantalla completa; ninguna ventana abierta. |
| 2.2 | Escuchar el saludo | «Buen día / Buenas tardes / Buenas noches, señor Rafael…» con la voz de ElevenLabs; si algo falla, lo nombra. |
| 2.3 | Mientras habla | La boca y los anillos del holograma se mueven con la voz. |
| 2.4 | 🎤 Hablar: «¿Qué hora es en Tokio?» | Responde con voz (Claude conectado). |
| 2.5 | Hablarle mientras responde | Se calla y le escucha (interrupción). |
| 2.6 | «Recordá que mi color favorito es el azul» → cerrar la app (quitarla de recientes) → abrir → «¿Cuál es mi color favorito?» | Responde azul (memoria persistente). |

## 3. FÉNIX 80: panel, diagnóstico y agentes
| # | Acción | Debe pasar |
|---|---|---|
| 3.1 | Icono 🔥 Evolución | Ventana flotante: misión INACTIVA, «Qué me falta» con pendientes (pérdida del día, posiciones, datos de demo…), agentes de VULKAN listados. |
| 3.2 | Decir «JARVIS, ¿qué está fallando?» | Lista comprobaciones reales (o «ninguna comprobación falla»). |
| 3.3 | Decir «JARVIS, ¿qué podés hacer?» y «¿qué no podés hacer?» | Responde con su inventario (sin inventar). |

## 4. FÉNIX 80: una misión real
| # | Acción | Debe pasar |
|---|---|---|
| 4.1 | Pedirle algo de cálculo que no sabe: «JARVIS, convertí esta lista de tiempos en subtítulos SRT» | Dice que no puede y lo anota como capacidad que falta. |
| 4.2 | «JARVIS, trabaja en ti mismo» | Dice que inicia la misión con sus límites. En el chat (💬) aparecen líneas «FÉNIX · …». |
| 4.3 | Esperar (varios minutos; puede dejar la app abierta) | Pasos: seleccionada → analizada → versión escrita → pruebas → (corrección si falla) → verificada. Al terminar lo dice. |
| 4.4 | 🔥: «Verificadas: esperan su aprobación» | La mejora aparece con sus pruebas (p. ej. 3/3) y regresión ok. **No está activa todavía.** |
| 4.5 | «JARVIS, ¿qué aprendiste hoy?» · «¿qué errores corregiste?» · «¿qué capacidades nuevas desarrollaste?» | Responde con lo que pasó en 4.3 (hechos comprobados / hipótesis). |
| 4.6 | 🔥 → «Código» y luego «Aprobar e integrar» → «Apruebo» | Pasa a INTEGRADA. En 🧪 Laboratorio aparece la herramienta ACTIVA. |
| 4.7 | 🧪 → herramienta activa → «Versión anterior» | Vuelve atrás o se desactiva (restauración). |

## 5. Seguridad
| # | Acción | Debe pasar |
|---|---|---|
| 5.1 | Iniciar otra misión y decir «JARVIS, PAUSA TOTAL» | Se calla, la misión queda DETENIDA y la pantalla dice PAUSA ACTIVA. Nada avanza hasta quitar la pausa. |
| 5.2 | 🧪 Laboratorio → comprobación «laboratorio» en «¿qué está fallando?» | El aislamiento funciona (si falla, avíseme: el laboratorio no se puede usar en su teléfono). |
| 5.3 | Ajustes de Android → JARVIS → Permisos | Los mismos permisos que antes; FÉNIX no añadió ninguno. |
| 5.4 | 🔥 Registro | Cada paso de la misión registrado con fecha. |

## 6. Interrupción y persistencia
| # | Acción | Debe pasar |
|---|---|---|
| 6.1 | Con una misión ACTIVA, cerrar JARVIS (quitarla de recientes) y volver a abrirla | La misión sigue ACTIVA y continúa (o, si Android la detuvo, el panel muestra en qué mejora y estado quedó). |
| 6.2 | Reiniciar el teléfono y abrir JARVIS | Claves, memoria, laboratorio, registro de FÉNIX y PAUSA (si estaba) se conservan. |

## Volver atrás si algo va mal
- Si la instalación falla, Android **no cambia nada**: sigue la versión anterior con todos sus datos.
- Si la versión nueva funciona mal: **no la desinstale**. Android no permite instalar una versión anterior encima de una
  nueva sin desinstalar, y desinstalar BORRA claves, memoria y registros (la app no admite copia de seguridad de Android).
  La solución segura es una versión de corrección con un número mayor, que se instala encima conservando todo.
- Dentro de la app: PAUSA TOTAL detiene todo; 🧪 «Versión anterior»/«Desactivar» revierte cualquier herramienta integrada.
