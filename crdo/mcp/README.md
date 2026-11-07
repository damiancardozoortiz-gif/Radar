# MCP en C.R.D.O. (preparado, sin conectar)

Nada en esta carpeta está activo en la Etapa 1. `sys.settings.mcp_gateway_enabled = false`.

## Dos papeles

| Papel | Qué es | Cuándo |
|---|---|---|
| **Servidor MCP propio** (`crdo-mcp`) | Expone operaciones del backend (buscar en memoria, crear tarea, leer métricas, pedir aprobación) | Etapa 2 |
| **Cliente MCP** | Los agentes usan servidores MCP de terceros (Drive, YouTube) **a través** del `mcp_gateway` | Etapa 2+, con tu autorización por cuenta |

## Reglas

- `crdo-mcp` **nunca** expone SQL directo, secretos ni operaciones R4. Las R3 solo pueden *pedir* aprobación.
- Transporte *Streamable HTTP* dentro de la red Docker `mcp` (interna, sin Internet). Si un cliente externo
  (por ejemplo Claude) tiene que conectarse, entra por Tailscale con un token propio por cliente que se puede revocar.
- Cada herramienta tiene un nivel de riesgo (`integrations.mcp_tool_grants.risk`) y se otorga por agente.
- Los servidores de terceros corren en contenedores propios, con versión fija, y solo salen a sus dominios.
- Un servidor MCP de un tercero recibe credenciales **del backend**, nunca del agente.
