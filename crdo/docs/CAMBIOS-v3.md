# C.R.D.O. — Etapa 1: cambios v2 → v3 (actualización presupuestaria oficial)

Fecha: 7 de noviembre de 2026. **Nada instalado, contratado ni conectado.**

## 1. Pedidos y resultado

| # | Pedido | Resultado |
|---|---|---|
| 1 | Tope absoluto US$ 20 | Inviolable en la base de datos (CHECK + función). Reserva del 15 %: tope operativo US$ 17 |
| 2 | Hetzner CX33 Europa | Servidor por defecto. Entra en el tope aun en el peor escenario: **US$ 16,61** brutos. Respaldo en CX23 si el precio final no entra |
| 3 | Europa | Sí |
| 4 | Stack | Sin cambios |
| 5 | Cloudflare gratis | Preparado (perfil `edge`, un túnel por entorno). **Necesita dominio**: decisión pendiente P1 |
| 6 | R2/B2 solo si hacen falta | B2 solo para respaldos (indispensable, dentro de la cuota gratis). R2 inactivo |
| 7 | Dominio opcional | Partida inactiva. Pagando en pesos, el control **rechaza** activarlo porque invadiría la reserva |
| 8 | Staging aislado en el mismo servidor | Permanente, con topes que caben en 8 GB; túnel propio |
| 9 | Repo privado | Sin cambios (script probado en la v2) |
| 10 | Panel Android | Tailscale hoy; Cloudflare Access con dominio (evita el límite de una VPN en Android) |
| — | Costos fijos y variables, IPv4, impuestos, comisiones | Tablas §B.2–B.4 con factor bruto 1,54 (pesos) / 1,24 (dólares propios) |
| — | Reserva, alertas, detención automática | Guardián horario + niveles 50/75 % + pausa de opcionales al invadir la reserva |
| — | IA fuera de los US$ 20 | Categoría `ai` separada, bloqueada hasta que fijes su tope |

## 2. Hallazgos durante la revisión

- **Cloudflare para el panel sin dominio no es posible**: DNS y Access requieren un dominio en Cloudflare.
  Queda Tailscale hasta comprarlo (P1).
- **Pagando en pesos, el margen es de solo US$ 0,39**: la percepción del 30 % pesa más que cualquier opcional.
  Pagar con dólares propios lo sube a US$ 3,62.
- **Si Hetzner cobrara IVA alemán**, el CX33 quedaría en ≈ US$ 19,77 e invadiría la reserva: regla de respaldo a CX23.
- Encontré y cerré una vía para "liberar" presupuesto de IA: la app podía borrar o cargar costos negativos en
  `finance.usage_events`. Ahora el registro es append-only y no admite montos negativos (probado).
- Caddy ya no hace falta: `cloudflared` llega directo a la API de su entorno. Se eliminó `infra/optional/edge`.

## 3. Pruebas

| Suite | v2 | v3 |
|---|---|---|
| Base de datos | 83 | **112** (+29 de presupuesto), 0 fallas |
| Respaldo/restauración | 18 | **22** (+ medidor B2 y `budget-guard` real), 0 fallas |
| Compose | 2 variantes | **4** variantes (± staging, ± edge) |

## 4. Archivos

Nuevos:
- `infra/db/init/04-budget.sql`
- `infra/scripts/budget-guard`
- `infra/systemd/crdo-budget-guard.{service,timer}`
- `infra/cloudflared/README.md`
- `tests/db/50_presupuesto.sql`
- `docs/CAMBIOS-v3.md`

Modificados:
- `infra/compose.yml` y `infra/compose.staging.yml`
- `infra/.env.example`
- `infra/scripts/backup.sh`, `infra/scripts/crdo` y `infra/scripts/bootstrap-server.sh`
- `tests/run-backup-tests.sh`
- `.github/workflows/ci.yml`
- `docs/ETAPA-1-DISENO.md` y `README.md`

Eliminados:
- `infra/optional/edge/`
