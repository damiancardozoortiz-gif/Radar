# C.R.D.O. — Progreso

| Etapa | Estado | Evidencia |
|---|---|---|
| 1 · Diseño (v1 → v3, base oficial) | ✅ Aprobado | `docs/ETAPA-1-DISENO.md` |
| 1A · Cierre técnico local | ⏳ **Pendiente: prueba en el teléfono** (criterio A9) | `docs/CIERRE-ETAPA-1.md`, `docs/INFORME-PRUEBA-ANDROID-ETAPA-1A.md` |
| 1A · Pruebas en Ubuntu 24.04 (VM en la nube) | ✅ 283 aprobadas, 0 fallidas, 0 omitidas | `docs/CIERRE-ETAPA-1.md` §2 |
| 1A · Pruebas en Android (Termux) | ❌ **Sin ejecutar.** El asistente no tiene acceso al teléfono | `docs/INFORME-PRUEBA-ANDROID-ETAPA-1A.md` |
| 1B · Instalación en la nube | ⛔ No autorizada (sin gastos) | `docs/runbooks/INSTALACION.md` |

**Presupuesto vigente:** infraestructura US$ 20/mes como máximo, reserva del 15 %, IA US$ 0. No hay gastos autorizados.

## Último registro

- **7/11/2026:**
  - Se intentó ejecutar la prueba Android de la Etapa 1A. Quedó **BLOQUEADA para el asistente**: corre en una VM x86_64 en la nube, sin Termux ni acceso al teléfono.
  - Se agregó `tools/evidencia-android.sh` para capturar la evidencia en el teléfono.
  - **Siguiente paso:** el dueño ejecuta `bash tools/evidencia-android.sh` y F1–F8 en el teléfono y comparte el informe generado.
