# Cloudflare (plan gratuito) — preparado, se activa cuando exista un dominio

Cloudflare DNS, la protección y Access necesitan un **dominio propio agregado a Cloudflare**. Mientras no se
compre el dominio, el panel se usa por **Tailscale**. No hace falta reconstruir nada para pasar a Cloudflare.

## Activación (cuando el dueño lo autorice)

1. Comprar el dominio. Debe entrar en el presupuesto: `finance.set_cost_item(…, 'domain', <precio anual>, true)`
   lo rechaza si invadiría la reserva.
2. Agregar el dominio a Cloudflare (plan Free) y cambiar los nameservers en el registrador.
3. En Zero Trust (plan Free, *verificar si pide un medio de pago*), crear **dos túneles**, `crdo-prod` y `crdo-staging`:
   - `crdo-prod`: `panel.<dominio>` → `http://api:8000`
   - `crdo-staging`: `staging.<dominio>` → `http://api:8000`. Cada túnel corre dentro de la red de su propio
     entorno, así que el de staging no puede alcanzar producción.
4. Guardar cada token en `/opt/crdo/secrets/tunnel-prod.env` y `tunnel-staging.env` (`TUNNEL_TOKEN=…`, chmod 600).
5. Crear dos aplicaciones de **Access** (una por hostname). Política: permitir solo el email del dueño; login por
   código al email o con Google; sesión de 24 h.
6. `crdo deploy <versión> --edge` (prod y staging). La API valida además el JWT de Access (`Cf-Access-Jwt-Assertion`).
7. Probar desde Android **sin Tailscale**: el panel pide el login de Access, luego el login y el TOTP de C.R.D.O.

Ventaja en Android: no ocupa la VPN del teléfono (Android permite una sola).
Tailscale se mantiene igual para SSH y emergencias.
