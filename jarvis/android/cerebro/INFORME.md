# JARVIS 3.0 — Cerebro modular (2.047 módulos): informe por etapas

Estado al 6-nov-2026. Rama `android`. Nada de esto está todavía enchufado a cómo JARVIS responde: es código
nuevo y aislado, con sus pruebas. Enchufarlo cambia el comportamiento de la app y espera su autorización.

## Etapa 1 — Respaldo (hecha)
- Copia completa del repositorio (`git bundle`, verificada) y copia comprimida de `jarvis/android` antes de tocar nada.
- Las copias están en el contenedor de esta sesión, que es temporal: el respaldo permanente es el historial de git.

## Etapa 2 — Diseño del árbol (hecha)
- `cerebro/arbol.py` genera `assets/cerebro-2047.json`: **2.047 módulos en 11 niveles (1, 2, 4 … 1024)**.
- Numeración de montículo: el módulo *i* tiene padre *i/2* e hijos *2i* y *2i+1*.
- Niveles 1–5 escritos a mano (31 módulos); 16 especialidades de nivel 5, cada una con 2 áreas, 4 temas y
  64 hojas. Las 1.024 hojas tienen una responsabilidad concreta y **única**. Los niveles 8–10 agrupan hojas
  consecutivas (son coordinadores, no capacidades nuevas).
- Los 10 dominios pedidos están en el árbol (percepción, lenguaje, memoria, razonamiento, planificación,
  conocimiento, ejecución, aprendizaje, seguridad, coordinación).

## Etapa 3 — Puerta neuronal central (hecha) — `core/cerebro/Puerta.java`
Cada mensaje se controla en este orden: PAUSA → destino válido → conexión permitida → estado del módulo →
activación bajo demanda (máx. 64 en memoria) → permiso (libre / requiere confirmación / prohibido) → circuito
abierto tras 3 fallos seguidos (se recupera solo después de 60 s) → ciclos → límites (12 saltos, 48 mensajes y
15 s por pedido) → contrato de entrada → ejecución medida → contrato de salida → registro (memoria y
`cerebro/actividad.jsonl`, rotado a 512 KB).

## Etapas 4–5 — Árbol vivo y conexiones (hechas) — `Arbol.java`, `Cerebro.java`
- Conexiones permitidas, **sin malla completa**: padre↔hijo, hermanos, cruzadas declaradas (p. ej. todo
  resultado científico → verificación; balanceo químico → eliminación de Gauss; tiro → Runge-Kutta) y
  **aprendidas** (dos módulos usados juntos con éxito 3 veces quedan conectados).
- Memoria compartida por pedido (pizarra) y retroalimentación al módulo de verificación.

## Estado honesto de las 1.024 hojas
- **operativa** (107): código real que se ejecuta por la puerta y una prueba individual que pasa.
- **conectada** (154): la función ya existe en JARVIS (se nombra la clase), pero aún no se llama por la puerta.
- **planificada** (763): definida (nombre, responsabilidad, padre), sin implementación. La puerta lo dice; no inventa.

| id | Especialidad | operativas | conectadas | planificadas |
|---|---|---|---|---|
| 16 | Percepción auditiva | 0 | 19 | 45 |
| 17 | Percepción visual y documentos | 0 | 12 | 52 |
| 18 | Comprensión del lenguaje | 0 | 18 | 46 |
| 19 | Generación del lenguaje | 0 | 10 | 54 |
| 20 | Matemática simbólica | 35 | 0 | 29 |
| 21 | Matemática numérica y estadística | 20 | 0 | 44 |
| 22 | Física | 19 | 0 | 45 |
| 23 | Química | 14 | 0 | 50 |
| 24 | Memoria | 0 | 12 | 52 |
| 25 | Razonamiento | 2 | 3 | 59 |
| 26 | Planificación | 0 | 8 | 56 |
| 27 | Ejecución y herramientas | 0 | 26 | 38 |
| 28 | Aprendizaje supervisado | 0 | 8 | 56 |
| 29 | Evaluación y automejora | 0 | 7 | 57 |
| 30 | Seguridad | 2 | 9 | 53 |
| 31 | Coordinación VULKAN | 15 | 12 | 37 |

## Laboratorios científicos (primera versión, hechos) — `core/ciencia/`
Java puro, funciona sin internet en el teléfono. Cada resultado trae **cómo se verificó**.
- **Matemática**: fracciones exactas; analizador de expresiones; ecuaciones de cualquier grado (raíces
  racionales exactas, cuadráticas con radicales, el resto numérico pulido con Newton); inecuaciones lineales;
  sistemas lineales (Gauss-Jordan exacto); determinante, inversa, rango, polinomio característico y valores
  propios; derivada simbólica (cadena, producto, cociente, x^x); integrales (primitiva exacta en polinomios,
  Simpson adaptativo contrastado con Gauss-Legendre); límites; Taylor; extremos; bisección, Newton, secante;
  Euler y Runge-Kutta 4; estadística (Welford, percentiles, regresión, R²); binomial, Poisson, normal
  (error < 1e-12), Bayes, Monte Carlo con semilla; primos (Miller-Rabin determinista 64 bits), factorización
  (rho de Pollard), MCD/MCM, inverso modular, combinatoria; tablas de verdad y equivalencias; Dijkstra.
- **Física**: unidades del SI con análisis dimensional (rechaza convertir kg a m), temperaturas, cinemática,
  tiro parabólico con **simulación comparada con la fórmula**, péndulo (también amplitud grande), energía,
  choques (conservación comprobada), gravitación, escape, gas ideal, Ohm, resistencias, Doppler, relatividad,
  fotoeléctrico. Constantes CODATA 2018.
- **Química**: masas atómicas (IUPAC convencionales), fórmulas con paréntesis, corchetes, hidratos y cargas,
  masa molar, composición, fórmula empírica, **balanceo por álgebra lineal exacta** (incluye redox e iónicas
  con conservación de carga), reactivo limitante y rendimiento, pH (fuertes con el agua incluida; débiles con
  la cuadrática exacta), diluciones, molaridad.
- **Seguridad química**: los pedidos de síntesis o preparación de sustancias peligrosas se rechazan; la
  información legítima (p. ej. la masa molar del TNT) sí se responde. Es un filtro por palabras clave: es
  conservador y no es infalible.
- `Cerebro.resolver("resolvé x^2 - 5x + 6 = 0")` → reconoce el problema → módulo hoja correcto → módulo de
  verificación → respuesta con confianza y traza de módulos. Lo que no es ciencia devuelve `null` (lo atiende Claude).

## Pruebas
- `./build.sh test`: **370 de 370 pasan** (264 anteriores + 106 nuevas del cerebro), entre ellas la prueba
  individual de cada uno de los 107 módulos operativos, todas las reglas de la puerta y 26 pedidos en español
  resueltos de punta a punta. El APK sin firmar compila.
- Lo que NO se probó: en un teléfono real (rendimiento y memoria), y nada de esto se usa todavía desde la voz.

## Lo que no es posible (o no todavía) y por qué
- **SymPy, NumPy, SciPy, RDKit** no corren dentro de un APK Java sin empaquetar un intérprete de Python
  (decenas de MB). Alternativa real: la herramienta de ejecución de código de Claude (sandbox con Python) para
  problemas que excedan a los motores locales — tiene costo por uso y requiere su autorización.
- **Inteligencia general**: no. Son motores verificables más el modelo de lenguaje; lo único entrenado en el
  teléfono es la red de intención (10 clases).

## Próximas etapas (propuestas)
6. Enchufar `Cerebro.resolver` al flujo de JARVIS (voz y chat), antes de Claude, y una ventana "Cerebro" con el
   árbol, estados, registro de la puerta y pruebas. **Requiere su autorización** (cambia cómo responde).
7. Pasar funciones "conectadas" a "operativas" (memoria, voz, planificación) llamándolas por la puerta.
8. Aprendizaje supervisado con separación entrenamiento/validación/prueba y comparación de versiones (la red de
   intención es el primer candidato).
9. Investigación científica con fuentes y, si lo autoriza, ejecución de código en el sandbox de Claude.
