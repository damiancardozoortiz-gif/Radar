# C.R.D.O.

Empresa virtual de producción de contenido para YouTube, operada desde la nube.

**Estado: Etapa 1, diseño v3 (tope de infraestructura US$ 20/mes todo incluido; IA aparte). Arquitectura aprobada; instalación y gastos NO autorizados. Nada está instalado.**

- Diseño: [`docs/ETAPA-1-DISENO.md`](docs/ETAPA-1-DISENO.md)
- Cambios: [`docs/CAMBIOS-v2.md`](docs/CAMBIOS-v2.md) (revisión técnica), [`docs/CAMBIOS-v3.md`](docs/CAMBIOS-v3.md) (presupuesto)
- Configuración (borradores): [`infra/`](infra/)

## Pruebas

Requieren PostgreSQL 16 + pgvector y restic (Ubuntu 24.04: `postgresql-16 postgresql-16-pgvector restic`).
Crean todo en directorios temporales y no tocan ningún servidor.

```bash
sudo bash tests/run-db-tests.sh       # esquema, seguridad, aprobaciones, cola, pgvector, presupuesto, concurrencia
sudo bash tests/run-backup-tests.sh   # cifrado, respaldo y restauración con los scripts reales
```

## Repositorio propio

Esta carpeta está pensada para convertirse en el repositorio privado `crdo`:
`bash crdo/tools/export-to-private-repo.sh <url-del-repo-vacío>` (desde la raíz de `Radar`).
