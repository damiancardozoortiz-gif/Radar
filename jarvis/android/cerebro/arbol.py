#!/usr/bin/env python3
"""Definición del ÁRBOL DE 2.047 MÓDULOS de JARVIS (11 niveles, binario). Genera cerebro-2047.json.
Numeración de montículo: el módulo i tiene hijos 2i y 2i+1; nivel = floor(log2 i) + 1.
Niveles 1-5: escritos a mano (31 módulos). Cada uno de los 16 módulos de nivel 5 tiene: 2 áreas (nivel 6),
4 temas (nivel 7) y 16 hojas por tema (nivel 11). Los niveles 8-10 agrupan hojas consecutivas.
El ESTADO de cada hoja no se decide aquí: lo calcula la app según exista código real (operativo), una función
ya existente de JARVIS (conectado) o nada todavía (planificado)."""
import json, sys

TOP = {  # 1-15: (nombre, dominio, responsabilidad)
 1: ("Cerebro JARVIS", "coordinación", "Recibe cada misión, la reparte por el árbol y devuelve un resultado verificado"),
 2: ("Comprensión y conocimiento", "percepción", "Entender lo que llega y aportar conocimiento"),
 3: ("Acción, aprendizaje y control", "coordinación", "Decidir, ejecutar, aprender y proteger"),
 4: ("Percepción y lenguaje", "percepción", "Convertir voz, imágenes y texto en significado"),
 5: ("Conocimiento científico", "conocimiento", "Cálculo matemático, físico y químico verificable"),
 6: ("Memoria, razonamiento y plan", "razonamiento", "Recordar, razonar y planificar"),
 7: ("Ejecución, aprendizaje y seguridad", "ejecución", "Actuar con herramientas, aprender, proteger y coordinar"),
 8: ("Percepción", "percepción", "Voz, imágenes, documentos y archivos"),
 9: ("Lenguaje", "lenguaje", "Comprender y producir lenguaje"),
 10: ("Matemáticas", "conocimiento", "Matemática simbólica y numérica"),
 11: ("Física y química", "conocimiento", "Física y química computacional"),
 12: ("Memoria y razonamiento", "memoria", "Memoria por niveles y razonamiento verificable"),
 13: ("Planificación y ejecución", "planificación", "Planes, misiones, recursos y herramientas"),
 14: ("Aprendizaje y automejora", "aprendizaje", "Aprendizaje supervisado y mejora controlada"),
 15: ("Seguridad y coordinación", "seguridad", "Control humano, protección y dirección VULKAN"),
}
L = lambda s: [x.strip() for x in s.split(";")]
SUB = {  # 16-31: nombre, dominio, [área A, área B], [tema1..tema4], 4 x 16 hojas
 16: ("Percepción auditiva", "percepción", ["Captura de audio", "Reconocimiento de voz"], ["Micrófono y señal", "Detección de voz", "Transcripción", "Comprensión acústica"], [
  L("permiso de micrófono; apertura del micrófono; frecuencia de muestreo; nivel de amplitud; ganancia automática; cancelación de eco; supresión de ruido; detección de saturación; calibración del ruido de fondo; seguimiento del ruido; medición de volumen; búfer de grabación; liberación del micrófono; grabación m4a; grabación PCM; recuperación ante fallo del micrófono"),
  L("umbral adaptable; modulación silábica; inicio de frase; fin de frase; silencio final; voz mínima; rechazo de golpes; tope de duración; sin voz detectada; interrupción del usuario; detección de palmadas; doble palmada; escucha continua; pausa de escucha; reanudación de escucha; métricas de detección"),
  L("transcripción ElevenLabs; transcripción Android; idioma es-AR; idioma es-ES; palabras con tiempos; eventos de audio; limpieza de texto transcrito; números hablados; puntuación transcrita; nombres propios; confianza de transcripción; reintento de transcripción; transcripción de vídeo; segmentación en frases; medición del reconocimiento; transcripción de respaldo"),
  L("hablante único; ruido ambiente alto; voz baja; eco de la sala; música de fondo; varios hablantes; acento rioplatense; voseo hablado; muletillas; autocorrección del hablante; frases incompletas; órdenes cortas; palabra de activación; PAUSA hablada; emoción en la voz; idioma no esperado")]),
 17: ("Percepción visual y documentos", "percepción", ["Visión", "Documentos y archivos"], ["Cámara", "Análisis de imagen", "Documentos", "Archivos y vídeo"], [
  L("permiso de cámara; apertura de cámara; vista previa de cámara; enfoque; captura de foto; compresión JPEG; rotación de imagen; flash; cámara trasera; cámara delantera; liberación de cámara; foto a visión; error de cámara; resolución de captura; poca luz; foto bloqueada en pausa"),
  L("descripción de escena; texto en imagen; identificación de objetos; lectura de pantallas; lectura de gráficos; tablas en imagen; colores dominantes; conteo de objetos; comparación de imágenes; imágenes de documentos; recibos y facturas; etiquetas de productos; gráficos financieros en imagen; calidad de imagen; imagen ilegible; privacidad en imágenes"),
  L("lectura de PDF; lectura de texto plano; lectura de JSON; resumen de documento; datos clave con página; contradicciones en documento; documentos largos; tablas en PDF; documentos escaneados; idioma del documento; citas del documento; documentos no confiables; límite de tamaño; extracción de fechas; extracción de importes; comparación de documentos"),
  L("selector de archivos; permisos persistentes de archivo; guardar archivo; compartir archivo; análisis de vídeo; miniaturas de vídeo; fotogramas clave; rotación de vídeo; audio de vídeo; formato de vídeo; duración de vídeo; CSV de MT5; importación de velas; archivos de subtítulos; carpeta de la app; limpieza de temporales")]),
 18: ("Comprensión del lenguaje", "lenguaje", ["Intención", "Contenido"], ["Clasificación de intención", "Órdenes de pantalla y teléfono", "Entidades y datos", "Contexto y significado"], [
  L("red neuronal de intención; reglas del enrutador; intención conversación; intención investigar; intención informe; intención recordar; intención tareas; intención correo; intención planear el día; intención parte del día; intención aprender; intención redactar; intención buscar en notas; confianza mínima de la red; corrección de intención; reentrenamiento de intención"),
  L("abrir chat; cerrar chat; mostrar resultados; abrir historial; abrir editor de vídeo; abrir panel; abrir laboratorio; abrir evolución; abrir neural core; ocultar ventanas; volver a la pantalla principal; orden abrir cámara; orden abrir apps; orden ubicación; orden panel financiero; orden detener todo"),
  L("fechas; horas; importes; porcentajes; nombres de personas; empresas; productos; lugares; símbolos financieros; temporalidades; unidades físicas; fórmulas químicas; expresiones matemáticas; direcciones web; correos electrónicos; duraciones"),
  L("historial de conversación; referencias a lo dicho; preguntas de seguimiento; pedidos compuestos; división de pedidos; ambigüedad; falta de información; voseo y tratamiento; tono del usuario; urgencia; negaciones; condicionales; comparaciones pedidas; preguntas abiertas; preguntas cerradas; idioma mixto")]),
 19: ("Generación del lenguaje", "lenguaje", ["Redacción", "Voz de salida"], ["Respuestas", "Contenido y formatos", "Síntesis de voz", "Estilo y personalidad"], [
  L("respuesta breve hablada; detalle en markdown; límite de palabras habladas; limpieza para voz; números para voz; respuesta de respaldo; respuesta en pausa; respuesta de error; confirmaciones; preguntas aclaratorias; listas; tablas; explicación paso a paso; resumen ejecutivo; fuentes citadas; reconocer el desconocimiento"),
  L("publicaciones para redes; guiones de vídeo; títulos; descripciones; hashtags; borradores de correo; informes redactados; propuestas técnicas; documentación; mensajes a clientes; textos publicitarios; subtítulos redactados; traducciones; reescritura; corrección de estilo; adaptación de tono"),
  L("ElevenLabs en streaming; ElevenLabs MP3; voz de Android; voz masculina grave; elección de voz; calidad natural; calidad rápida; volumen de voz; velocidad de voz; estabilidad de voz; pronunciación; pausas habladas; corte por interrupción; sincronía con el holograma; métricas de síntesis; respaldo de voz"),
  L("tratamiento señor Rafael; saludo según la hora; elegancia; concisión; iniciativa sin acciones sensibles; lealtad; honestidad; sin frases vacías; prioridad de lo que mueve dinero; tono profesional; tono tranquilo; avisos claros; malas noticias; humor medido; coherencia de identidad; idioma español")]),
 20: ("Matemática simbólica", "conocimiento", ["Álgebra y análisis", "Lógica y discreta"], ["Aritmética y álgebra", "Cálculo", "Lógica y demostración", "Teoría de números y discreta"], [
  L("evaluación de expresiones; precedencia de operadores; fracciones exactas; potencias y raíces; logaritmos; ecuación lineal; ecuación cuadrática; ecuación cúbica; raíces de polinomios; sistemas lineales 2x2 y 3x3; factorización de polinomios; simplificación algebraica; expansión de productos; inecuaciones lineales; valor absoluto; tabla de una función"),
  L("derivada de polinomios; derivada numérica; regla de la cadena; derivadas parciales; límites numéricos; integral definida numérica; integral de polinomios; área bajo la curva; series de Taylor; sumatorias; máximos y mínimos; puntos de inflexión; gradiente; ecuaciones diferenciales ordinarias; método de Euler; Runge-Kutta 4"),
  L("proposiciones; tablas de verdad; equivalencias lógicas; cuantificadores; operaciones con conjuntos; inducción matemática; contraejemplos; verificación de identidades; demostración por casos; reducción al absurdo; silogismos; lógica de predicados; satisfacibilidad; consistencia lógica; árboles de prueba; verificación numérica de conjeturas"),
  L("primalidad; factorización entera; máximo común divisor; mínimo común múltiplo; aritmética modular; inverso modular; combinatoria; permutaciones; coeficiente binomial; sucesiones; recurrencias; grafos; caminos mínimos; árboles de expansión; conteo; números de Fibonacci")]),
 21: ("Matemática numérica y estadística", "conocimiento", ["Numérico y lineal", "Probabilidad y datos"], ["Álgebra lineal", "Métodos numéricos y optimización", "Probabilidad", "Estadística"], [
  L("vectores; producto escalar; producto vectorial; norma de un vector; matrices; suma de matrices; producto de matrices; determinante; matriz inversa; rango; eliminación de Gauss; transpuesta; valores propios; descomposición LU; sistemas mal condicionados; mínimos cuadrados"),
  L("bisección; Newton-Raphson; método de la secante; interpolación lineal; interpolación polinómica; regla del trapecio; regla de Simpson; error numérico; precisión de coma flotante; descenso por gradiente; búsqueda de óptimos; programación lineal; ajuste de curvas; convergencia; estabilidad numérica; verificación cruzada de cálculos"),
  L("probabilidad clásica; probabilidad condicional; teorema de Bayes; independencia; variables aleatorias; distribución binomial; distribución de Poisson; distribución normal; esperanza; varianza teórica; simulación Monte Carlo; ley de los grandes números; cadenas de Markov; riesgo y probabilidad; intervalos de probabilidad; aleatoriedad reproducible"),
  L("media; mediana; moda; desviación estándar; percentiles; correlación; regresión lineal; intervalos de confianza; pruebas de hipótesis; muestreo; valores atípicos; series temporales; medias móviles; normalización de datos; histogramas; estadística de operaciones")]),
 22: ("Física", "conocimiento", ["Mecánica", "Campos, ondas y física moderna"], ["Unidades y cinemática", "Dinámica y gravitación", "Termodinámica y ondas", "Electromagnetismo y física moderna"], [
  L("sistema internacional; conversión de unidades; análisis dimensional; prefijos del SI; notación científica; cifras significativas; movimiento rectilíneo uniforme; movimiento acelerado; caída libre; tiro parabólico; alcance máximo; movimiento circular; velocidad relativa; gráficas posición-tiempo; simulación de trayectorias; comparación simulación-fórmula"),
  L("leyes de Newton; fuerza neta; rozamiento; plano inclinado; trabajo mecánico; energía cinética; energía potencial; conservación de la energía; potencia mecánica; cantidad de movimiento; choques; péndulo simple; oscilador armónico; gravitación universal; órbitas circulares; velocidad de escape"),
  L("escalas de temperatura; calor específico; calorimetría; gases ideales; primera ley de la termodinámica; procesos termodinámicos; eficiencia de máquinas; entropía; dilatación térmica; ondas mecánicas; frecuencia y periodo; velocidad de una onda; sonido; efecto Doppler; óptica geométrica; lentes y espejos"),
  L("ley de Coulomb; campo eléctrico; potencial eléctrico; ley de Ohm; resistencias en serie; resistencias en paralelo; potencia eléctrica; capacitores; campo magnético; inducción electromagnética; dilatación temporal; contracción de longitudes; equivalencia masa-energía; efecto fotoeléctrico; partícula en una caja; constantes físicas")]),
 23: ("Química", "conocimiento", ["Química general", "Química aplicada"], ["Estructura y fórmulas", "Reacciones y estequiometría", "Fisicoquímica", "Orgánica, electroquímica y seguridad"], [
  L("tabla periódica; masas atómicas; interpretación de fórmulas; paréntesis en fórmulas; hidratos; masa molar; composición porcentual; fórmula empírica; fórmula molecular; configuración electrónica; electronegatividad; tipos de enlace; polaridad; geometría molecular; número de oxidación; isótopos"),
  L("balanceo de ecuaciones; conservación de la masa; tipos de reacción; moles y gramos; reactivo limitante; rendimiento teórico; rendimiento porcentual; concentración molar; diluciones; titulaciones; gases en reacciones; estequiometría de soluciones; conversión de unidades químicas; número de Avogadro; pureza de reactivos; verificación de resultados químicos"),
  L("entalpía de reacción; ley de Hess; energía de Gibbs; entropía química; equilibrio químico; constante de equilibrio; principio de Le Chatelier; pH; ácidos y bases; soluciones amortiguadoras; solubilidad; cinética química; orden de reacción; ecuación de Arrhenius; vida media; catálisis"),
  L("grupos funcionales; nomenclatura orgánica; isomería; hidrocarburos; polímeros; celdas galvánicas; potenciales de electrodo; electrólisis; ecuación de Nernst; corrosión; química computacional; análisis molecular con herramienta externa; seguridad química; sustancias peligrosas solo como información; rechazo de síntesis peligrosas; supervisión humana en química")]),
 24: ("Memoria", "memoria", ["Memoria de trabajo", "Memoria de largo plazo"], ["Inmediata y conversación", "Conocimientos", "Experiencias y errores", "Preferencias y habilidades"], [
  L("historial corto; persistencia del historial; turnos de Claude; contexto de la pregunta; memoria de la misión; caché de respuestas; último resultado; resultados abiertos; ventana de contexto; olvido controlado; borradores pendientes; tareas en curso; estado de pausa; estado de voz; estado de ventanas; recuperación de sesión"),
  L("notas del vault; búsqueda en notas; índice de palabras; notas de clientes; notas de pólizas; correo de demostración; agenda de demostración; informes guardados; investigaciones guardadas; fuentes verificadas; código fuente propio; inventario técnico; pendientes conocidos; corpus de intenciones; documentación del proyecto; conocimiento científico verificado"),
  L("registro de tareas; éxitos; fallos; errores repetidos; causas; soluciones; confianza de la experiencia; hechos comprobados; hipótesis; conclusiones; aprendizajes de investigación; aprendizajes financieros; aprendizajes de evolución; recuperación por similitud; estadísticas de experiencia; olvido de lo obsoleto"),
  L("recuerdos explícitos; perfil del usuario; tratamiento preferido; voz elegida; volumen elegido; límites de riesgo; intervalos de evolución; autorización web; preferencia de streaming; herramientas aprobadas; capacidades declaradas; habilidades del laboratorio; modelo de Claude; idioma preferido; privacidad; borrado a pedido")]),
 25: ("Razonamiento", "razonamiento", ["Inferencia", "Verificación"], ["Análisis de problemas", "Inferencia y decisión", "Verificación y validación", "Explicación"], [
  L("comprensión del problema; datos conocidos; incógnitas; supuestos; restricciones; descomposición en subproblemas; selección de método; analogías; estimación previa; órdenes de magnitud; casos límite; información faltante; reconocer lo que no sé; reformulación; priorización; criterio de éxito"),
  L("razonamiento deductivo; razonamiento inductivo; razonamiento abductivo; razonamiento causal; razonamiento probabilístico; razonamiento temporal; razonamiento espacial; razonamiento cuantitativo; comparación de alternativas; ventajas y desventajas; decisión bajo incertidumbre; árbol de decisiones; razonamiento con Claude; razonamiento simbólico; búsqueda de soluciones; combinación de evidencias"),
  L("verificación de cálculos; segunda vía de cálculo; verificación de unidades; verificación de fuentes; enlaces inventados; contradicciones; coherencia interna; validación de JSON; verificación de rangos; prueba con casos conocidos; sensibilidad; regresiones; revisión independiente; confianza del resultado; verificación de código; auditoría del razonamiento"),
  L("procedimiento paso a paso; por qué de la elección; alternativas descartadas; limitaciones; hipótesis declaradas; nivel de certeza; hechos y opiniones; explicación para no expertos; explicación técnica; ejemplos; contraejemplos explicados; resumen de la decisión; registro del razonamiento; explicación de errores; resultado reproducible; citas")]),
 26: ("Planificación", "planificación", ["Planes", "Gestión"], ["Descomposición", "Planes y agenda", "Misiones", "Recursos y tiempo"], [
  L("pedido compuesto; pasos ordenados; dependencias entre pasos; resultado encadenado; paso crítico; paso opcional; plan mínimo; plan alternativo; replanificación; cancelación de plan; límite de pasos; estimación de pasos; módulos necesarios; módulos innecesarios; confirmación de plan; plan de investigación"),
  L("plan del día; prioridades por dinero; parte del día; tareas pendientes; tareas atrasadas; recordatorios; calendario; reuniones; plazos; seguimiento de tareas; completar tareas; plan semanal; objetivos; hábitos; revisiones periódicas; informe de avance"),
  L("misión FÉNIX; una mejora a la vez; selección por prioridad; criterios de aceptación; estados de mejora; bloqueos; autorización requerida; reintentos; misión terminada; misión pausada; misión detenida; mejoras nativas; propuestas de mejora; inventario de mejoras; historial de misiones; misión de investigación"),
  L("límite de llamadas a Claude; límite de tiempo; límite de mejoras; batería; memoria RAM; CPU; red; coste estimado; colas de trabajo; prioridad de tareas; segundo plano; cancelación; tiempo de respuesta; caché de recursos; conexiones precalentadas; recuperación tras cierre")]),
 27: ("Ejecución y herramientas", "ejecución", ["Herramientas internas", "Acciones en el teléfono"], ["Herramientas de JARVIS", "Laboratorio", "Teléfono y apps", "Finanzas y vídeo"], [
  L("investigación web; lectura de páginas; informes; visión; documentos PDF; gestión de tareas; memoria explícita; borradores; editor de vídeo; exportación de vídeo; subtítulos SRT; plan de Short; análisis técnico; lectura de MetaApi; métricas de voz; neural core"),
  L("herramientas JavaScript; control de seguridad del código; pruebas aisladas; aislamiento WebView; versiones de herramientas; aprobación de herramientas; uso de herramientas aprobadas; restauración de versión; desactivación de herramientas; registro del laboratorio; propuestas nativas; regresión de herramientas; límite de tiempo del laboratorio; herramientas de cálculo; herramientas de texto; herramientas de datos"),
  L("abrir apps con confirmación; YouTube; Maps; WhatsApp; Gmail; Instagram; cámara del teléfono; ubicación; archivos del teléfono; compartir; notificaciones; servicio en segundo plano; palmadas; permisos de Android; ajustes de Android; RADAR"),
  L("análisis multitemporal; indicadores técnicos; gestor de riesgo; regla de disciplina; ticket de orden; registro de operaciones; aprendizaje financiero; conexión MT5; velas de MT5; importar vídeo; recortar vídeo; unir vídeos; vista previa del montaje; guardar vídeo; proyecto de vídeo; recodificación de vídeo")]),
 28: ("Aprendizaje supervisado", "aprendizaje", ["Datos y entrenamiento", "Evaluación del aprendizaje"], ["Datos", "Entrenamiento", "Evaluación", "Transferencia y métodos"], [
  L("ejemplos semilla; ejemplos reales; correcciones humanas; ejemplos aprobados; datos de validación; datos de prueba separados; etiquetado; balance de clases; duplicados; datos privados; versión de datos; calidad de datos; aumento de datos; ejemplos negativos; conjuntos científicos de prueba; registro de datos"),
  L("red de intención MLP; descenso de gradiente; entropía cruzada; inicialización de pesos; épocas; tasa de aprendizaje; rasgos por hashing; softmax; guardado de pesos; carga de pesos; reentrenamiento por umbral; entrenamiento determinista; sobreajuste; regularización; modelos pequeños en el teléfono; límites de cómputo"),
  L("precisión; precisión en datos nuevos; matriz de confusión; acuerdo con las reglas; comparación de versiones; regresiones de aprendizaje; métricas por clase; curva de aprendizaje; umbral de confianza; errores frecuentes; informe de evaluación; evaluación ciega; reproducibilidad; línea de base; mejora significativa; rechazo de modelos peores"),
  L("aprendizaje por refuerzo controlado; entornos simulados; recompensas; ajuste fino con infraestructura externa; transferencia de conocimiento; memoria y recuperación; aprendizaje por demostración; aprendizaje activo; preguntas al usuario; olvido catastrófico; destilación de reglas; ensamble de modelos; calibración; incertidumbre; investigación de métodos; límites declarados del aprendizaje")]),
 29: ("Evaluación y automejora", "aprendizaje", ["Diagnóstico", "Mejora controlada"], ["Autodiagnóstico", "Pruebas", "Mejoras", "Versiones y restauración"], [
  L("inventario técnico vivo; comprobaciones del teléfono; claves guardadas; permisos concedidos; almacenamiento; aislamiento del laboratorio; conexión con Claude; conexión con ElevenLabs; conexión con MetaApi; errores repetidos detectados; qué puedo hacer; qué no puedo hacer; qué está fallando; estado de los módulos; estado de la red neuronal; informe de diagnóstico"),
  L("pruebas automáticas; pruebas de módulos; pruebas de integración; pruebas de la puerta; pruebas científicas; pruebas en emulador; capturas de pantalla; vídeo de prueba; pruebas de firma; pruebas de regresión; pruebas de casos límite; pruebas de seguridad; pruebas de rendimiento; informe de pruebas; pruebas reproducibles; pruebas en el teléfono"),
  L("detectar una limitación; investigar soluciones autorizadas; diseñar una mejora; propuesta técnica; versión de prueba; comparación antes y después; evidencias; solicitud de autorización; integración aprobada; rechazo de mejora; mejora descartada; errores corregidos; aprendizaje de la mejora; compilación externa; ramas fenix; revisión humana"),
  L("respaldo previo; versionado; firma original; versionCode creciente; restauración de herramientas; restauración de datos; historial de cambios; registro de modificaciones; compatibilidad de actualización; paquetes de respaldo; recuperación tras fallo; rama android; commits verificados; artefactos de compilación; notas de versión; actualización sin pérdida de datos")]),
 30: ("Seguridad", "seguridad", ["Control humano", "Protección"], ["Autorización", "Emergencia", "Protección de datos", "Límites y riesgos"], [
  L("aprobación humana; aprobación de borradores; aprobación de herramientas nuevas; aprobación de mejoras; autorización de investigación web; autorización de ciclo financiero; confirmación para abrir apps; confirmación de exportación; permisos de Android pedidos; permisos mínimos; sin autoaprobación; separación de roles; operaciones sensibles; revisión de riesgo; registro de autorizaciones; revocación"),
  L("PAUSA; PAUSA TOTAL; detener todo; pausa persistente; corte de llamadas a Claude; corte de voz; corte de micrófono; detener misión; bloqueo financiero; emergencia financiera; reactivación autorizada; servicio en pausa; notificación de pausa; PAUSA por voz; PAUSA por botón; prioridad de la pausa"),
  L("claves cifradas; almacén seguro de Android; sin claves en registros; sin claves en el repositorio; protección de datos privados; datos de demostración; fuentes no confiables; inyección de instrucciones; aislamiento del código; red bloqueada en el laboratorio; copia de seguridad desactivada; privacidad de ubicación; borrado de datos; tokens limitados; firma protegida; auditoría de seguridad"),
  L("operaciones reales deshabilitadas; solo cuenta DEMO; stop loss obligatorio; límite diario de pérdida; exposición máxima; órdenes duplicadas; límites de recursos; límites de misión; límites de mensajes de la puerta; bloqueo de bucles infinitos; química peligrosa bloqueada; descargas autorizadas; instalaciones autorizadas; cambios de código supervisados; sin permisos propios; límites declarados")]),
 31: ("Coordinación VULKAN", "coordinación", ["Dirección", "Comunicación"], ["VULKAN director", "Agentes especialistas", "Puerta neuronal", "Informes al usuario"], [
  L("recibir misión; analizar el estado; dividir el trabajo; asignar especialistas; coordinar la investigación; revisar resultados; detectar contradicciones entre agentes; solicitar pruebas; consolidar resultados; detectar bloqueos; supervisar dependencias; priorizar trabajo; comparar resultados; pedir revisiones; escalar al usuario; cerrar misión"),
  L("agente de arquitectura; agente de investigación; agente de programación; agente android; agente de seguridad; agente de calidad; agente de memoria; agente de IA; agente de optimización; agente de voz e interfaz; agente de finanzas; registro por agente; salida propia de cada agente; límites por agente; activación de agentes; estado de los agentes"),
  L("enrutamiento de mensajes; identificación de módulos; contratos de datos; control de permisos; gestión de errores; registro de actividad; control de ciclos; límites de ejecución; recuperación ante fallos; conexiones jerárquicas; conexiones horizontales; conexiones cruzadas; retroalimentación; memoria compartida; activación bajo demanda; cola de mensajes"),
  L("estado de la misión; resultados de la misión; pruebas realizadas; capacidades reales; pendientes; recursos necesarios; autorizaciones necesarias; errores informados; holograma de estado; panel de evolución; ventana neural; respuestas por voz; informe final; transparencia; sin resultados inventados; próximos pasos")]),
}

def build():
    mods = {}
    for i, (n, dom, r) in TOP.items():
        mods[i] = {"nombre": n, "dominio": dom, "responsabilidad": r}
    names = set()
    for i, (n, dom, areas, temas, hojas) in SUB.items():
        assert len(areas) == 2 and len(temas) == 4 and len(hojas) == 4, i
        mods[i] = {"nombre": n, "dominio": dom, "responsabilidad": "Especialidad de " + n.lower() + ": " + ", ".join(temas).lower()}
        for a in range(2):  # nivel 6
            mods[2 * i + a] = {"nombre": areas[a], "dominio": dom, "responsabilidad": n + " → " + areas[a] + ": " + temas[2 * a] + " y " + temas[2 * a + 1]}
        for t in range(4):  # nivel 7
            tid = 4 * i + t
            leaves = hojas[t]
            assert len(leaves) == 16, (n, temas[t], len(leaves))
            mods[tid] = {"nombre": temas[t], "dominio": dom, "responsabilidad": temas[t] + ": " + ", ".join(leaves[:4]) + "…"}
            # niveles 8-11 bajo el tema tid: hojas en ids tid*16 .. tid*16+15
            for k, leaf in enumerate(leaves):
                lid = tid * 16 + k
                assert leaf not in names, ("hoja repetida", leaf)
                names.add(leaf)
                mods[lid] = {"nombre": leaf, "dominio": dom, "responsabilidad": leaf[0].upper() + leaf[1:] + " (" + temas[t] + ", " + n + ")"}
            for lvl, width in ((10, 2), (9, 4), (8, 8)):  # agrupaciones de hojas consecutivas
                base = tid * (2 ** (lvl - 7))
                for j in range(2 ** (lvl - 7)):
                    g = leaves[j * width:(j + 1) * width]
                    mods[base + j] = {"nombre": temas[t] + " · " + g[0] + " … " + g[-1], "dominio": dom,
                                      "responsabilidad": "Coordina " + str(width) + " especialidades: " + ", ".join(g)}
    assert sorted(mods) == list(range(1, 2048)), "faltan módulos"
    out = []
    for i in range(1, 2048):
        m = mods[i]
        lvl = i.bit_length()
        out.append({"id": i, "nivel": lvl, "padre": i // 2 if i > 1 else None, "hijos": [2 * i, 2 * i + 1] if lvl < 11 else [],
                    "nombre": m["nombre"], "dominio": m["dominio"], "responsabilidad": m["responsabilidad"]})
    return out

if __name__ == "__main__":
    mods = build()
    json.dump({"version": 1, "modulos": mods}, open(sys.argv[1], "w"), ensure_ascii=False)
    print(len(mods), "módulos;", sum(1 for m in mods if m["nivel"] == 11), "hojas")
