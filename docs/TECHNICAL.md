# Detalles técnicos

Cómo funciona por dentro, qué se descubrió y todos los obstáculos resueltos.
Útil si quieres adaptarlo a otro mando, otra app o entender la integración
adb-inalámbrico.

## 1. El problema

Kodi se navega con **cruceta** (arriba/abajo/izq/der/OK). El CheerTok Air es un
**touchpad que mueve un cursor de ratón**. Manejar la UI de Kodi con un cursor,
a una mano y sin precisión, es horrible; además la pantalla táctil / el "touchpad
emulado" de Samsung DeX hacían un **mapeo absoluto** del rectángulo del móvil
(~20:9) sobre el escritorio de las gafas (16:9), descolocando el cursor.

Solución: convertir los gestos del touchpad en pulsaciones de cruceta y enviárselas
a Kodi por su API, ocultando el cursor.

## 2. El CheerTok Air por dentro (HID)

Visto con `adb shell getevent`, el CheerTok aparece como **dos interfaces HID**:

| Device | Nombre | Emite |
|---|---|---|
| ratón | `CheerTok Air Mouse` | `REL_X`, `REL_Y`, `REL_WHEEL(_HI_RES)`, `REL_HWHEEL(_HI_RES)`, `BTN_MOUSE/RIGHT/MIDDLE` |
| teclas | `CheerTok Air Consumer Control` | `KEY_BACK`(158), `KEY_MENU`(139), `KEY_HOMEPAGE`(172), `KEY_PLAYPAUSE`(164), `KEY_VOLUMEUP`(115), `KEY_VOLUMEDOWN`(114)… |

Magnitudes medidas (capturas reales):
- **Swipe deliberado**: 200–700 unidades acumuladas en el eje dominante (deltas
  sueltos de ±10–100).
- **Rueda**: `REL_WHEEL` ±2 por muesca; `REL_WHEEL_HI_RES` ±240 por muesca.
- **Tap**: `BTN_MOUSE` 1→0 en ~90 ms con movimiento casi nulo.
- Botones **FN** y **Cámara**: el manual lo confirma — **FN es modificador/macro y
  NO emite tecla suelto**; Cámara hace foto. Los botones útiles para atajos son
  **VOL +/−** (115/114).
- Gotcha: con `getevent -l` los valores de tecla son **simbólicos** (`DOWN`/`UP`),
  no hex; los `REL` sí son hex con signo (`ffffffff` = −1). El número de
  `/dev/input/eventN` **cambia al reconectar** → hay que resolver por **nombre**
  (`EVIOCGNAME`), no por número.

## 3. El mapeo de gestos

Acumuladores con umbral (ver `native/bridge.c`):
- `REL_X`/`REL_Y` se acumulan; al cruzar **STEP** (≈160) en el eje dominante se
  emite `Input.Left/Right/Up/Down` y se reinician ambos. Un swipe largo = varios pasos.
- `REL_WHEEL_HI_RES` / `REL_HWHEEL_HI_RES` se acumulan; cada **240** (una muesca) =
  un paso de cruceta.
- `BTN_MOUSE` press→release en < `TAPMS` y con < `TAPMOVE` de movimiento = `Input.Select`.
- Teclas → su acción de Kodi. VOL +/− → acción **configurable** (token).

## 4. Ocultar el cursor: `EVIOCGRAB`

Para que Android **deje de ver** el ratón (y no dibuje el puntero), el binario abre
los devices del CheerTok y llama `ioctl(fd, EVIOCGRAB, 1)`: agarra el dispositivo en
**exclusiva**. Desde ese momento el sistema no recibe sus eventos → el cursor
desaparece y los botones tampoco disparan acciones del SO; todo va a nuestro puente.

## 5. Hablar con Kodi: JSON-RPC

Kodi expone JSON-RPC por HTTP. El puente hace `POST /jsonrpc` (socket propio en C,
o `nc`/HTTP según la variante) con Basic Auth y cuerpos como:
`{"jsonrpc":"2.0","method":"Input.Down","id":1}`. Acciones usadas:
`Input.Up/Down/Left/Right/Select/Back/Home/ContextMenu`, `Player.PlayPause`,
`Application.SetVolume`, y para abrir un addon **`Addons.ExecuteAddon`** con su
`addonid` (p. ej. `plugin.video.palantir3`). También se desactiva el cursor de Kodi
con `Settings.SetSettingValue` `input.enablemouse=false`.

## 6. El reto sin root: conseguir privilegios `shell`

Leer `/dev/input` y hacer `EVIOCGRAB` requiere pertenecer al grupo `input` → en la
práctica, uid **`shell`** (2000) o root. Una app normal (uid de app) no puede. Tres
formas de conseguir uid `shell` SIN root, en orden de cómo evolucionó el proyecto:

### a) Un PC por adb (solo desarrollo)
`adb shell` ya es uid `shell`. Sirvió para prototipar (`prototype/`) pero no vale
para el uso diario sin PC.

### b) Shizuku
[Shizuku](https://github.com/RikkaApps/Shizuku) deja a una app ejecutar código como
`shell`. Funciona en la mayoría de móviles (Samsung incluido). La app lo soporta como
**alternativa** (sección Avanzado). **Pero en OPPO/ColorOS falla**: ColorOS le quita
al usuario `shell` el permiso `GRANT_RUNTIME_PERMISSIONS`, así que Shizuku no puede
conceder su permiso a la app (`SecurityException`). Por eso no podía ser la vía única.

### c) Depuración inalámbrica embebida (la vía principal) ⭐
Como [LADB](https://github.com/tytydraco/LADB), pero **integrado en la app**: se
empaqueta un binario `adb` y la app se conecta a la **depuración inalámbrica del
propio móvil** (adbd local). El adbd autentica con la llave de la app y le da una
shell **uid `shell`**, que lanza el binario del puente. Funciona en cualquier móvil
(incluido OPPO) y **no necesita instalar nada más ni Shizuku**.

Flujo: `adb start-server` → `adb pair IP:puerto código` (una vez) → `adb connect
IP:puerto` → `adb -s IP:puerto shell <binario del puente>`.

## 7. Obstáculos resueltos (gotchas)

Lista para ahorrarte el dolor si lo replicas:

- **Buffering de stdout perdía acciones.** Al matar el proceso con SIGTERM, el buffer
  de stdout no se vaciaba → parecía que no enviaba nada. Solución: `flush`/`python -u`
  (prototipo); en C, `fflush(stdout)` por acción.
- **`getevent -l` da valores de tecla simbólicos** (`DOWN`/`UP`), no hex. Hay que
  interpretarlos aparte de los `REL` (hex con signo).
- **Resolver devices por nombre**, no por `eventN` (cambia al reconectar). En C:
  `ioctl(fd, EVIOCGNAME(...))`.
- **El proceso de la APP necesita `android:usesCleartextTraffic="true"`** para hablar
  HTTP con Kodi en localhost (el selector de addons corre en la app). El puente, al
  correr como `shell`, no lo necesita.
- **`Spinner` dispara `onItemSelected` también en la selección programática** → al
  cargar prefs se auto-abría el selector de addons. Solución: guarda por **toque
  real** (`setOnTouchListener`).
- **Barra de título duplicada**: el `Theme` con ActionBar mostraba el nombre de la
  app y además el título del layout. Solución: tema `…NoActionBar`.
- **El `adb` estático moderno (35.x) aborta en Android por `fdsan`** (sanitizador de
  descriptores) al iniciar el servidor. Solución: usar una versión anterior
  (**33.0.3** estático de lzhiyong), que arranca sin ese fallo.
- **Colisión del servidor adb.** Otro servidor adb en el puerto 5037 (de pruebas o
  de otro uid) rompía el `pair`. Solución: la app usa un **puerto de servidor adb
  propio** (`ANDROID_ADB_SERVER_PORT`) y `ADB_MDNS=0` (el cliente mDNS de adb falla
  en Android y rompía `pair` con "Unable to start pairing client").
- **Conectar a `127.0.0.1` no funciona**; adbd de la depuración inalámbrica escucha
  en la **IP de la WiFi**, no en loopback. Hay que `connect`/`pair` a la IP real.
- **El puerto de emparejamiento ≠ el de conexión.** El de emparejar sale en el
  recuadro «Vincular con código»; el de conexión, en la pantalla principal. El de
  conexión **cambia** cada vez que reactivas la depuración inalámbrica.
- **ColorOS cierra el recuadro de emparejamiento al cambiar de app** → el código
  caduca antes de pegarlo. Solución de usuario: **pantalla dividida**. En Android de
  serie (Samsung) el recuadro se queda abierto.
- **Vincular es de UNA vez.** El `pair` autoriza la llave de la app permanentemente;
  en adelante basta `connect` (sin código). Solo tras reiniciar hay que reconectar
  con la **nueva** IP:puerto de conexión.

## 8. Servicio en primer plano, notificación y auto-abrir Kodi

El puente corre dentro de un **foreground service** (`BridgeService`) para que
sobreviva aunque se cierre la app y para mostrar una **notificación persistente**:

- **Estado del mando**: el servicio lee la salida del binario — `READY` = mando
  agarrado (🎮 conectado); las líneas de re-escaneo ("esperando/desconectado") = sin
  mando (⚠️). La notificación se actualiza en consecuencia. Botón **Parar** incluido.
- **Reconexión**: si la conexión adb se cae, el servicio reconecta y relanza solo.
- **Auto-abrir Kodi** (opcional): en la transición desconectado→conectado, el
  servicio trae Kodi al primer plano (`getLaunchIntentForPackage("org.xbmc.kodi")`).

Gotchas de esta parte:
- En Android 14+ el foreground service necesita **tipo** (`foregroundServiceType`,
  aquí `specialUse`) y el permiso `POST_NOTIFICATIONS` (runtime) para ver la
  notificación.
- **Abrir Kodi desde segundo plano** choca con la restricción de *background activity
  launch*. Solución: permiso **«mostrar sobre otras apps»** (`SYSTEM_ALERT_WINDOW`).
  Gotcha doble: ese permiso hay que **declararlo en el manifest** para que la app
  **aparezca** en la lista de ajustes (sin declararlo no sale), y se concede con
  interruptor manual (no por `pm grant`). Además, para *localizar* Kodi en
  Android 11+ hace falta un `<queries><package android:name="org.xbmc.kodi"/></queries>`.

### OSD y anti-rebote del tap
- Las "opciones de reproducción" (rebobinar, subtítulos, ajustes…) que salen al
  tocar la pantalla durante un vídeo son el **OSD**: `Input.ShowOSD` (no
  `Input.ContextMenu`).
- En Kodi a pantalla completa **`Input.Select` (OK) = play/pausa**. Al pulsar un
  botón físico se puede rozar el touchpad y generar un tap falso → play/pausa
  indeseado. El binario lo evita con un **anti-rebote**: ignora el tap si coincide
  (±300 ms) con la pulsación de un botón.

## 9. Arquitectura de la app

- `MainActivity.kt` — UI, persistencia, atajos (selector de addons vía
  `Addons.GetAddons`), y orquestación de los dos modos (inalámbrico / Shizuku).
- `AdbWireless.kt` — empaqueta el `adb` y hace pair/connect/shell + descubrimiento
  mDNS opcional (`NsdManager`).
- `BridgeService.kt` — foreground service: mantiene el puente, la notificación de
  estado, la reconexión y el auto-abrir Kodi.
- `BridgeUserService.kt` + `IUserService.aidl` — vía Shizuku (alternativa).
- `KodiClient.kt` — JSON-RPC a Kodi (acciones + listar addons).
- `native/bridge.c` → `libbridge.so` — el motor (lo lanza cualquiera de los dos
  modos). `libadb.so` = el `adb` estático empaquetado.

## 10. Variante en scripts (`ondevice/`)

Antes del binario único en C, el puente se hizo con piezas de sistema:
`evgrab.c` (agarra) → `bridge_num.awk` (mapea) → `nc` (HTTP). Equivalente y útil para
lanzar a mano por LADB/Shizuku, o como referencia didáctica.
