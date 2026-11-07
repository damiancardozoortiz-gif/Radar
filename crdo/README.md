# C.R.D.O.

Empresa virtual de producción de contenido para YouTube, operada desde la nube.

**Estado: Etapa 1 — v3.1.**
- **1A, cierre técnico local:** cumplida, salvo correr el prototipo en el teléfono del dueño.
- **1B, instalación en la nube:** pendiente de autorización de gasto, de la API y del panel.
- Tope de infraestructura: US$ 20/mes todo incluido. La IA tiene presupuesto aparte.
- Nada está instalado ni contratado.

| Documento | Para qué |
|---|---|
| [`docs/CIERRE-ETAPA-1.md`](docs/CIERRE-ETAPA-1.md) | Qué está cerrado, inventario de pruebas, criterios de finalización |
| [`docs/ETAPA-1-DISENO.md`](docs/ETAPA-1-DISENO.md) | Diseño (base oficial v3 + v3.1) |
| [`docs/INFORME-SEGURIDAD.md`](docs/INFORME-SEGURIDAD.md) | Controles, evidencia, defectos corregidos, riesgos residuales |
| [`docs/PROTOTIPO-LOCAL.md`](docs/PROTOTIPO-LOCAL.md) | Correr el prototipo en Android (Termux) o en una PC |
| [`docs/runbooks/INSTALACION.md`](docs/runbooks/INSTALACION.md) · [`RESTAURACION.md`](docs/runbooks/RESTAURACION.md) | Procedimientos para el servidor futuro |
| [`docs/CAMBIOS-v2.md`](docs/CAMBIOS-v2.md) · [`CAMBIOS-v3.md`](docs/CAMBIOS-v3.md) | Historial |

## Pruebas

Requieren PostgreSQL 16 + pgvector, restic y python3 (Ubuntu 24.04: `postgresql-16 postgresql-16-pgvector restic`).
Crean todo en directorios temporales y no tocan ningún servidor ni cuenta.

```bash
bash tests/run-all.sh          # 283 verificaciones: base de datos, respaldo y recuperación, prototipo, configuración
bash tools/crdo-local doctor   # qué se puede ejecutar en este dispositivo
```

## Repositorio propio

Esta carpeta está pensada para convertirse en el repositorio privado `crdo`:
`bash crdo/tools/export-to-private-repo.sh <url-del-repo-vacío>` (desde la raíz de `Radar`).
