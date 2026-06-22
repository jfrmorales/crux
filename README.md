# Crux

**Mando de cruceta para [Kodi](https://kodi.tv/) (app no oficial, sin relación con el
proyecto Kodi).** Convierte un touchpad-mando Bluetooth [CheerTok Air](https://cheerdots.com/)
en un mando de cruceta para Kodi, sin cursor y sin root — y además trae un mando para el
**reloj (Wear OS)**.

Un solo gesto = una acción de Kodi: deslizas y la selección se mueve, tocas y
entra, los botones hacen Atrás/Inicio, y los botones libres puedes asignarlos a lo
que quieras (por ejemplo, **abrir un addon directamente**).

> ### Por qué existe
> Nació para mi padre, que ve Kodi (con el addon Palantir) en unas gafas **VITURE
> Pro XR** conectadas a un **Samsung en modo DeX** mientras está en **diálisis**.
> Manejar Kodi con el cursor del ratón —táctil o touchpad emulado— era un
> suplicio: el cursor se desalineaba y apuntar a una mano, tumbado y con un brazo
> inmovilizado por la fístula, era imposible. Kodi está pensado para **cruceta**,
> no para cursor. Esto resuelve justo eso.

---

## Qué hace

- 🕹️ **Mando de cruceta de verdad**: swipe = mover selección, tap = OK, rueda de
  borde = subir/bajar listas.
- 🚫 **Sin cursor**: "agarra" el mando a nivel kernel (`EVIOCGRAB`), así Android
  deja de mover el puntero del sistema. El cursor desaparece.
- 🔘 **Botones nativos**: Atrás e Inicio del CheerTok mapean a Atrás/Inicio de Kodi.
- ⭐ **Atajos configurables**: los botones laterales (VOL +/−) los asignas desde la
  app a Home, menú, play/pausa, volumen de Kodi o **abrir un addon** (Palantir, etc.).
- 🔓 **Sin root**: usa la **depuración inalámbrica** del propio móvil (estilo
  [LADB](https://github.com/tytydraco/LADB)) embebida en la app. Vinculas una vez y
  luego es **Iniciar / Parar**.
- 📶 **Funciona con datos móviles, sin WiFi**: al vincular (una vez, con WiFi) la app
  pone el `adbd` a escuchar en `127.0.0.1` (`adb tcpip`), así el puente conecta por
  *loopback* y luego **no necesita ninguna WiFi**.
- 🔔 **Servicio en primer plano** con **notificación persistente**: indica si el
  mando está conectado o desconectado y mantiene el puente vivo aunque cierres la
  app; se reconecta solo si la conexión se cae.
- 📺 **Abre Kodi solo** (opcional): al conectar el mando, trae Kodi al primer plano
  automáticamente — enciendes el mando y Kodi aparece listo.
- 📱 **Una sola app**, compatible con cualquier móvil (probado en **OPPO/ColorOS** y
  diseñado para **Samsung/DeX**).

## Cómo funciona

```
 CheerTok Air (Bluetooth HID)
        │  /dev/input/eventN  (ratón relativo + teclas)
        ▼
 libbridge  (binario nativo, corre como uid "shell")
   1. encuentra el CheerTok por nombre (EVIOCGNAME)
   2. lo AGARRA en exclusiva (EVIOCGRAB)  → oculta el cursor del sistema
   3. mapea gestos → cruceta / acciones
   4. POST JSON-RPC ─────────────────────────────►  Kodi  (HTTP localhost:8080)
                                                    Input.Up/Down/Left/Right/Select…
```

El único reto sin root es **conseguir privilegios `shell`** para leer `/dev/input`.
La app lo logra hablando con el **adbd de la depuración inalámbrica del propio
móvil** mediante un `adb` empaquetado — el mismo truco que [LADB](https://github.com/tytydraco/LADB),
pero integrado, así no hace falta instalar nada más ni usar Shizuku.

## Controles

| Gesto / botón del CheerTok | Acción en Kodi |
|---|---|
| Deslizar ↑ ↓ ← → (touchpad) | Mover la selección (cruceta) |
| Rueda de borde | Subir / bajar en listas |
| Tap (clic) | OK / Entrar |
| Botón **Atrás** | Atrás |
| Botón **Inicio** | Pantalla de inicio |
| Botones **VOL + / VOL −** | **Configurables** (p. ej. VOL+ = abrir Palantir) |

> El botón **FN** del CheerTok es un modificador/macro y **no emite tecla por sí
> solo**, así que no se puede usar como atajo directo. Los botones libres útiles
> son **VOL + / VOL −** (códigos 115/114), que además quedan libres si el volumen lo
> llevan las gafas.

## Mando desde el reloj (Wear OS) ⌚

Además del CheerTok, hay un **segundo mando**: una app para el **reloj** (probada en
**OPPO Watch X2**, vale para cualquier Wear OS con Google Play Services) que dibuja una
**cruceta a pantalla completa** y manda las órdenes a Kodi **a través del móvil por
Bluetooth** (Wear Data Layer) — **sin WiFi, sin root, sin ADB**.

- 🕹️ **Cruceta circular** que aprovecha toda la esfera (4 sectores + OK central).
- 🎛️ **Corona** para subir/bajar listas; **mantener pulsado OK** cambia el eje a
  horizontal (↕/↔).
- 🎯 **OK con gestos configurables**: un toque = entrar; **doble y triple toque** hacen lo
  que elijas desde la app del móvil (Atrás, Inicio, OSD, menú, abrir addon…).
- 📃 **Segunda pantalla** (deslizar) con Atrás, Inicio, Play/Pausa, **OSD**, **Abrir
  Kodi** y un atajo a addon (Palantir).
- 📲 **Se instala en el reloj desde el propio móvil**: el APK del reloj va dentro de la app
  y se instala por la depuración inalámbrica del reloj (sin PC, sin Play Store).
- 🔌 Reutiliza el mismo `KodiClient`; el reloj solo envía los tokens que Kodi ya entiende.
- 🔋 **Es el modo que menos batería gasta**: es *event-driven* (sin servicio en primer
  plano ni ADB). Si usas sobre todo el reloj, **deja el puente del CheerTok parado**; si lo
  inicias, se autodetiene tras 15 min sin actividad del CheerTok.

Detalles, decisiones y montaje en **[docs/WATCH.md](docs/WATCH.md)**.

## Instalación rápida

1. **Kodi** → Ajustes → Servicios → Control → activar control HTTP (puerto 8080,
   usuario/contraseña `kodi`/`kodi`).
2. Empareja el **CheerTok** por Bluetooth.
3. Descarga e instala **[`Crux.apk`](https://github.com/jfrmorales/crux/releases/latest/download/Crux.apk)** desde la
   [última release](https://github.com/jfrmorales/crux/releases/latest) (incluye también la app del reloj).
4. Abre la app y sigue **[docs/SETUP.md](docs/SETUP.md)** (vincular una vez por
   depuración inalámbrica → luego Iniciar/Parar).

## Estructura del repo

| Carpeta | Qué es |
|---|---|
| `app-android/` | La app Android (Kotlin). Empaqueta los binarios nativos y orquesta todo. |
| `native/bridge.c` | **El motor**: binario nativo autocontenido (encuentra+agarra el CheerTok, mapea, habla con Kodi). |
| `ondevice/` | Variante en scripts (`evgrab` + `awk` + `nc`), equivalente; útil como referencia o para lanzar a mano vía LADB/Shizuku. |
| `prototype/` | Prototipo en PC (`adb getevent` → JSON-RPC) usado para validar el mapeo. |
| `docs/` | Guía de uso, detalles técnicos y de compilación. |

## Documentación

- **[docs/SETUP.md](docs/SETUP.md)** — montaje paso a paso para el usuario final.
- **[docs/TECHNICAL.md](docs/TECHNICAL.md)** — cómo funciona por dentro, el protocolo
  HID del CheerTok, ocultar el cursor, el adb-inalámbrico, y todos los obstáculos
  resueltos (Shizuku/ColorOS, fdsan, etc.).
- **[docs/BUILD.md](docs/BUILD.md)** — cómo compilar la app y los binarios.
- **[docs/WATCH.md](docs/WATCH.md)** — el mando del reloj (Wear OS) y su instalación.
- **[docs/PUBLICAR-EN-PLAY.md](docs/PUBLICAR-EN-PLAY.md)** — instalar la app del reloj **sin
  ADB**, por la vía oficial de Google Play (recomendado para el usuario final).

## Créditos y licencias

- Mecanismo de depuración inalámbrica inspirado en **[LADB](https://github.com/tytydraco/LADB)** (Apache-2.0).
- Binario `adb` estático para Android arm64 de **[lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools)**.
- Soporte opcional **[Shizuku](https://github.com/RikkaApps/Shizuku)** como vía alternativa.
- `adb` es parte de [Android platform-tools](https://developer.android.com/tools/adb) (Apache-2.0).

Código propio bajo **[MIT](LICENSE)**. Los binarios de terceros empaquetados
conservan sus respectivas licencias.

## Aviso

Proyecto personal/comunitario, sin relación con Cheerdots, Kodi, VITURE ni
ninguna marca. Úsalo bajo tu responsabilidad.

**Sobre la seguridad de la depuración inalámbrica:** hace que el `adbd` del móvil
escuche en su IP de la **red local (WiFi)**, pero está protegido por
**emparejamiento con código** (requiere ver la pantalla del móvil) y **TLS con
llave autorizada**: solo los dispositivos que **ya emparejaste** pueden conectar —
nadie más de la red, aunque vea el puerto. En tu **WiFi de casa** el riesgo es
insignificante; en una **WiFi pública/compartida** la única consideración es que
queda un servicio de depuración visible en esa red (sigue sin ser accesible sin
emparejar). Además **se desactiva sola al reiniciar** el móvil.
