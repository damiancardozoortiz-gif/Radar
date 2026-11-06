# JARVIS en tu Android con Termux (sin ordenador)

> **Estado: SIN PROBAR en un teléfono.** Estas instrucciones se escribieron sin acceso a un Android. Si un paso falla, apunta el mensaje de error exacto.

## Cómo encaja

```
Android
├── Termux
│   └── Ubuntu (proot-distro)        ← aquí corren JARVIS (Python) y Claude Code (claude)
│        └── JARVIS en http://localhost:8790
└── Chrome  →  http://localhost:8790  ← la interfaz: micrófono, cámara, voz
```

Todo queda dentro del teléfono. **No hace falta túnel ni acceso remoto.** Chrome permite micrófono y cámara en `localhost`. El acceso remoto de JARVIS sigue desactivado.

**Por qué Ubuntu dentro de Termux.** Anthropic no publica Claude Code para Android. En Termux "a pelo", `npm install -g @anthropic-ai/claude-code` se instala pero el comando `claude` no arranca: su programa está hecho para Linux con glibc y Termux usa la biblioteca de Android. Dentro de un Ubuntu con `proot-distro` sí se puede usar el instalador oficial. **No uses paquetes "parcheados" de terceros**: tendrían acceso a tu sesión de Claude.

## Requisitos

- Android 7 o superior, de 64 bits (ARM64). Casi todos los teléfonos actuales lo son.
- Unos 4 GB libres.
- Tu suscripción de Claude (Pro o Max) y tu cuenta de ElevenLabs.
- **Termux de F-Droid o de GitHub**: la versión de Google Play está anticuada.

## 1. Instalar Termux y Ubuntu

1. Instala **Termux** desde F-Droid (https://f-droid.org/packages/com.termux/).
2. Abre Termux y escribe, de uno en uno:
   ```bash
   pkg update && pkg upgrade -y
   pkg install -y proot-distro
   proot-distro install ubuntu
   termux-wake-lock            # que Android no duerma Termux mientras JARVIS trabaja
   proot-distro login ubuntu
   ```
   A partir de aquí estás **dentro de Ubuntu**. El símbolo cambia a `root@localhost`.

## 2. Instalar lo que necesita JARVIS (dentro de Ubuntu)

```bash
apt update && apt install -y python3 git curl ca-certificates procps nano
curl -fsSL https://claude.ai/install.sh | bash      # instalador oficial de Claude Code
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc && source ~/.bashrc
claude --version
```

## 3. Entrar en Claude con tu suscripción

```bash
claude
```

Sigue el asistente: elige iniciar sesión con tu cuenta de Claude (suscripción, **no** clave de API). Te dará un enlace: ábrelo en Chrome, autoriza y vuelve a Termux. Sal con `/exit`.

Comprueba que responde:
```bash
claude -p "Di hola en una palabra"
```

## 4. Descargar JARVIS

```bash
cd ~
git clone https://github.com/damiancardozoortiz-gif/Radar.git
cd Radar && git checkout android
cd jarvis
```

Si el repositorio es **privado**, git te pedirá usuario y contraseña. La contraseña es un *token* de GitHub, no tu contraseña normal:
- Créalo en GitHub → Settings → Developer settings → Fine-grained tokens.
- Dale solo **lectura** y solo del repositorio `Radar`.

## 5. Tu clave de ElevenLabs

```bash
cp .env.example .env
nano .env          # pon ELEVENLABS_API_KEY=... (y si quieres ELEVENLABS_VOICE_ID=...). Guarda con Ctrl+O, Intro, Ctrl+X
chmod 600 .env
```

Usa una clave **nueva**: la que se pegó en el chat de construcción debe rotarse.

Prueba la voz real antes de nada:
```bash
python3 tests/prueba_voz_real.py
```
Debe decir `PASA` dos veces y guardar un audio en `/tmp/jarvis_prueba_voz.mp3`.

## 6. Arrancar y usar

```bash
./jarvis.sh iniciar
./jarvis.sh estado
```

1. Abre **Chrome** en el teléfono y entra en **http://localhost:8790**.
2. Pulsa el micrófono y permite el acceso cuando Android lo pida.
3. Para tenerlo como app: Chrome ⋮ → **Instalar aplicación** o **Añadir a pantalla de inicio**.

Apagar: `./jarvis.sh parar`.

## 7. Cada día

En Termux:
```bash
termux-wake-lock
proot-distro login ubuntu -- bash -lc "cd ~/Radar/jarvis && ./jarvis.sh iniciar"
```
Después abre la app de JARVIS (o Chrome en `localhost:8790`).

Para apagarlo:
```bash
proot-distro login ubuntu -- bash -lc "cd ~/Radar/jarvis && ./jarvis.sh parar"
```

Para que Android no cierre Termux: Ajustes → Aplicaciones → Termux → Batería → **Sin restricciones**.

## 8. Actualizar

```bash
proot-distro login ubuntu
cd ~/Radar && git pull && cd jarvis && ./jarvis.sh reiniciar
```

## 9. Tus carpetas reales (opcional)

Para que JARVIS lea documentos del teléfono:

1. En Termux, fuera de Ubuntu, ejecuta una vez `termux-setup-storage` y acepta.
2. Entra en Ubuntu con la memoria del teléfono enlazada:
   ```bash
   proot-distro login ubuntu --bind /storage/emulated/0:/sdcard
   ```
3. En `agent/data.py`, pon en `REAL_FOLDERS` las carpetas que quieras. Por ejemplo, `"/sdcard/Documents/Clientes"`.
4. Arranca con `JARVIS_DEMO=0 ./jarvis.sh iniciar`.

JARVIS solo **lee** esas carpetas.

## 10. Comprobar que todo va bien

```bash
./jarvis.sh estado                      # cerebro listo, voz configurada, sin pausa
python3 tests/test_criticos.py          # 32 pruebas de seguridad; sin internet ni suscripción (unos 2 minutos)
python3 tests/integral.py               # prueba completa con Claude y voz reales (unos 4 minutos)
```

Las pruebas de navegador (`navegador*.js`) necesitan Playwright y no son para el teléfono.

## Lo que hay que saber

- **Velocidad.** Ubuntu dentro de Termux añade algo de carga. Las respuestas pueden tardar más que en un ordenador.
- **Pantalla apagada.** El micrófono solo escucha con Chrome abierto y en primer plano. Es una norma de Android para las páginas web. JARVIS no escucha en segundo plano.
- **Batería.** El proceso `claude` se queda abierto entre preguntas para ir rápido, y los que no se usan se cierran a los 10 minutos. Si quieres ahorrar, `./jarvis.sh parar` cuando no lo uses.
- **Seguridad.**
  - El servidor solo escucha en el propio teléfono (`127.0.0.1`). No abras el puerto 8790 a la red.
  - `.env`, `estado/` y `memory/` no salen del teléfono ni van a git.
  - La PAUSA y «JARVIS, detener todo» funcionan igual que en el ordenador.
- **Si `claude` no arranca dentro de Ubuntu**, Anthropic lleva el soporte para Android en su repositorio, issue `anthropics/claude-code#50270`. Mientras tanto, JARVIS funciona en modo respaldo (busca en tus archivos), con un aviso rojo.
