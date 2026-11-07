# Agentes de C.R.D.O. (vacío hasta la Etapa 2)

En la Etapa 1 **no se construye ningún agente**. Esta carpeta fija el contrato que deberán cumplir, para que
C.R.D.O. no dependa de ningún proveedor de IA en particular, tampoco de Claude.

## Contrato de un agente

Cada agente es un archivo `agents/<departamento>/<agente>.md` versionado en este repositorio (propiedad de C.R.D.O.):

| Campo | Ejemplo | Regla |
|---|---|---|
| `codigo` | `research.tendencias` | Único; coincide con `org.agents.code` |
| `departamento` | `research` | Uno de `org.departments` |
| `ruta_modelo` | `razonamiento` | Una **ruta** (`integrations.model_routes`), nunca un modelo o proveedor fijo |
| `permisos` | `memory.read`, `task.create` | Los otorga el dueño con TOTP (`org.grant_permission`); nunca R4 |
| `herramientas` | `crdo.memory.search` | Herramientas MCP permitidas (`integrations.mcp_tool_grants`) |
| `instrucciones` | texto | En español, sin dependencias de un modelo concreto |
| `evaluaciones` | `evals/<agente>/*.yaml` | Casos con resultado esperado, para comparar proveedores |

## Reglas de independencia

1. Los agentes **piden acciones al backend**; nunca tienen credenciales de YouTube, Drive, la base de datos ni pagos.
2. Toda llamada a un modelo pasa por `llm_gateway`, que elige proveedor según la ruta, aplica el presupuesto y
   **guarda entrada y salida** (`finance.usage_events` y la memoria). Ese historial es de C.R.D.O. y sirve para
   cambiar de proveedor o entrenar un modelo propio.
3. Las herramientas se exponen por **MCP**, un estándar abierto: cualquier cliente compatible las puede usar.
4. La memoria vive en PostgreSQL propio. Cada embedding guarda el modelo que lo generó (`memory.chunks.embed_model`),
   así se pueden recalcular con otro proveedor.
5. La Dirección es un **rol**, no un proveedor. Hoy puede ocuparla Claude; mañana, un director propio, sin cambiar el esquema.
