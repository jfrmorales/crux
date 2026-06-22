# Mando desde el reloj (Wear OS)

Un segundo mando para Kodi, además del CheerTok: una app en el **reloj** que dibuja
una **cruceta** y manda las órdenes a Kodi a través del móvil. Pensado y probado en un
**OPPO Watch X2** (Wear OS 5), pero vale para cualquier reloj Wear OS con Google Play
Services.

```
 Reloj (app Compose: cruceta + corona)
        │  Wear Data Layer (MessageClient) por BLUETOOTH  ← sin WiFi
        ▼
 Móvil: KodiWearListener (WearableListenerService)
        │  reutiliza KodiClient.send(token)
        ▼
 Kodi  (JSON-RPC HTTP en localhost:8080)
```

A diferencia del camino del CheerTok, este **no necesita root, ni ADB, ni binario
nativo, ni leer `/dev/input`, ni el BridgeService**. Es comunicación app-a-app
(Data Layer) + una petición HTTP a `localhost`. Como el transporte es Bluetooth,
funciona **sin WiFi** (igual que el resto del proyecto, pensado para datos móviles).

> **Batería (recomendado).** Por lo anterior, el mando del reloj es el modo que **menos
> batería gasta**: el sistema solo levanta `KodiWearListener` cuando llega un mensaje, no
> hay servicio en primer plano ni conexión ADB viva. Si controlas Kodi sobre todo desde
> el reloj, **deja el puente del CheerTok parado** (basta con tener configurada la sección
> «Conexión con Kodi»). El puente clásico mantiene una conexión ADB 24/7 que impide el
> reposo profundo del móvil; por eso, si lo inicias, **se autodetiene tras 15 min sin que
> se use el CheerTok** y sus reintentos de conexión usan *backoff* para no consumir.

---

## Por qué una app propia y no la función "Control de vídeos"

El Watch X2 trae una función ("**Control de vídeos breves**") que dibuja una cruceta
para pasar TikTok/Shorts desde la muñeca. **No es reaprovechable** para Kodi:

- Está cableada por diseño a **TikTok y YouTube Shorts** (lista blanca propietaria de
  la app OHealth, vía servicio de accesibilidad).
- El reloj **no se expone como dispositivo HID Bluetooth**, así que el truco del
  CheerTok (`EVIOCGRAB` sobre `/dev/input`) no aplica.
- No hay API pública para añadir Kodi a esa función.

Lo que **sí** se aprovecha es que el Watch X2 es **Wear OS**: admite apps propias, y el
**Data Layer de Google funciona** entre reloj y móvil **aunque el emparejamiento sea por
OHealth** (no por la app "Wear OS by Google", que ni siquiera está instalada). Validado
en hardware real: el reloj tiene `com.google.android.gms` + Play Store, y los mensajes
del Data Layer llegan al móvil correctamente.

---

## La interfaz del reloj

Dos pantallas, se cambia **deslizando en horizontal** (puntos indicadores abajo).

### Pantalla 1 — Cruceta (a pantalla completa)

Un DPAD que **rellena toda la superficie de la pantalla, sin huecos** (los sectores son
triángulos que van del centro a las esquinas; el único círculo es el OK):

- **4 sectores** ▲ ▼ ◀ ▶ (arriba/abajo/izquierda/derecha), divididos por las diagonales
  centro→esquina. Se toca el sector para mover la selección; la dirección se decide por el
  **ángulo** del toque respecto al centro.
- **OK central** (único círculo): **un toque = `Input.Select`** (entrar). El **doble y el
  triple toque** envían los gestos `Ok2`/`Ok3`, cuya **acción se configura desde la app del
  móvil** (Atrás, Inicio, OSD, menú contextual, abrir un addon… o nada). Por defecto: doble
  = Atrás, triple = nada. Su **color indica la conexión** con el móvil (azul = conectado,
  gris = sin nodo). El toque se confirma tras una ventana corta (~250 ms) para contar los
  toques; las direcciones, en cambio, se disparan al instante.

> El reloj solo informa del **gesto** (1/2/3 toques); es el **móvil** quien decide la acción
> según tu configuración. Así no hace falta sincronizar ajustes al reloj.
- **Corona giratoria**: sube/baja en listas. Acumula los pulsos y limita la frecuencia
  (~1 paso por muesca, mín. 90 ms) para no inundar el canal BLE.
- **Eje de la corona por gesto**: **mantener pulsado el OK** alterna **↕ (vertical →
  Up/Down)** ⇄ **↔ (horizontal → Left/Right)**, con vibración. El glifo bajo "OK"
  muestra el eje actual.

> **¿Por qué un gesto y no detección automática?** Kodi **no expone la orientación de la
> lista** por JSON-RPC de forma fiable: `Container.Orientation` viene **vacío** incluso
> en ventanas estándar (comprobado en vivo con el skin del usuario y el addon Palantir).
> Por eso el eje se cambia a mano con un gesto, en lugar de adivinarlo.

### Pantalla 2 — Acciones

Lista de botones (chips) para lo que no es dirección:

| Chip | Acción |
|---|---|
| ↩ Atrás | `Input.Back` |
| ⌂ Inicio | `Input.Home` |
| ⏯ Play / Pausa | `Player.PlayPause` |
| ⓘ OSD reproducción | `Input.ShowOSD` |
| ▶ Abrir Kodi | trae Kodi al primer plano en el móvil |
| ★ Palantir | `addon:plugin.video.palantir3` |
| ⏻ Salir de Kodi | `Application.Quit` — **cierra Kodi por completo**; pide confirmación (pulsar dos veces) para no cerrarlo por error |

---

## Los botones físicos del reloj (lo que se puede y no se puede)

Capturando los eventos crudos (`getevent`) del Watch X2 (modelo **OWWE251**):

| Dispositivo | Genera | Uso |
|---|---|---|
| `oplus_crown` (event3) | `REL_WHEEL` | **Giro de la corona** → scroll de listas ✅ |
| `qpnp_pon` (event1) | `KEY_POWER` | **Pulsar la corona** = botón de encendido del sistema ❌ |
| `qpnp_pon` (event1) | `KEY_VOLUMEDOWN` | Botón inferior (también del sistema) ❌ |

**La pulsación de la corona es `KEY_POWER`**: Android la reserva al sistema
(apagar/encender pantalla, ir al inicio) y **una app no puede interceptarla**. Por eso el
OK es un botón en pantalla y no la pulsación de la corona. El **giro** sí es libre (lo
usamos para el scroll).

---

## Cómo está montado (código)

Módulo nuevo **`:wear`** + un listener en el módulo `:app` del móvil. Reutiliza
`KodiClient` tal cual (el vocabulario de tokens del reloj es el que ya entiende).

| Fichero | Qué es |
|---|---|
| `app-android/wear/build.gradle.kts` | Módulo de la app del reloj (Compose for Wear OS). |
| `app-android/wear/src/main/AndroidManifest.xml` | App Wear (`standalone=false`: depende del móvil). |
| `wear/.../wear/MainActivity.kt` | Activity Compose; mantiene pantalla encendida; arranca el messenger. |
| `wear/.../wear/RemoteScreen.kt` | La UI: DPAD circular (Canvas), corona, páginas, acciones. |
| `wear/.../wear/KodiMessenger.kt` | Data Layer: descubre el nodo del móvil (CapabilityClient) y envía por `MessageClient`. |
| `app/.../KodiWearListener.kt` | En el móvil: recibe los tokens y los pasa a `KodiClient` (o abre Kodi); traduce los gestos `Ok2`/`Ok3` a la acción configurada. |
| `app/src/main/res/values/wear.xml` | Declara la capability `cheertok_kodi_phone` para que el reloj descubra el móvil. |
| `app/src/main/AndroidManifest.xml` | Registra el service con el intent-filter `MESSAGE_RECEIVED` (path `/kodi`). |
| `app/.../AdbWireless.kt` (`install`) | Instala el APK del reloj desde el móvil reutilizando su adb interno. |
| `app/build.gradle.kts` (`copyWearApk`) | Empaqueta `wear-debug.apk` como asset `wear.apk` del móvil. |
| `app/.../MainActivity.kt` (`installWatchApp`) | Flujo de la UI: empareja + conecta + instala en el reloj. |

**Contrato del mensaje**: path `/kodi/cmd`, payload = el token en UTF-8. La mayoría son
**acciones** que `KodiClient.send(token)` ya entiende (`Input.Up`, `Input.Select`,
`Player.PlayPause`, `Input.ShowOSD`, `addon:<id>`…). Hay además dos tipos especiales:

- `OpenKodi` no es JSON-RPC: el listener lanza `org.xbmc.kodi` (igual que
  `BridgeService.launchKodi`, usando el permiso *mostrar sobre otras apps*).
- `Ok2` / `Ok3` son **gestos**, no acciones: el listener los traduce leyendo las prefs
  `ok_double` / `ok_triple` (configurables desde la UI del móvil) y entonces ejecuta la
  acción resultante (que puede ser a su vez `OpenKodi`, un `addon:<id>`, etc.). Valor
  `none` o vacío = no hace nada.

---

## Compilar e instalar

Requiere el reloj emparejado con el móvil (por OHealth o la app de Google, da igual) y
que el reloj tenga Google Play Services.

```bash
cd app-android

# Compilar el móvil ya empaqueta dentro el APK del reloj (asset "wear.apk")
./gradlew :app:assembleDebug

# Instalar el del móvil
adb -s <MÓVIL> install -r app/build/outputs/apk/debug/app-debug.apk
```

> **¿Sin ADB, de forma oficial?** La instalación por ADB (esta sección) es el método de
> **desarrollo**. La vía oficial sin ADB es **Google Play** (instalar desde la Play Store del
> propio reloj). Ver **[PUBLICAR-EN-PLAY.md](PUBLICAR-EN-PLAY.md)**.

### Instalar la app del reloj desde el propio móvil (recomendado)

El APK del reloj va **empaquetado dentro de la app del móvil** (`assets/wear.apk`, lo copia
la tarea Gradle `copyWearApk` desde `:wear`). La app del móvil lo instala en el reloj
**reutilizando su `adb` interno** (el mismo de la depuración inalámbrica), así que no hace
falta un PC:

1. En el **reloj**: Ajustes → Opciones de desarrollador → **Depuración inalámbrica** (en la
   misma WiFi que el móvil).
2. En la **app del móvil** → sección **«Instalar la app en el reloj»**: la 1ª vez rellena
   *IP:puerto de emparejamiento* + *código* (los muestra el reloj en «Emparejar con código»)
   y la *IP:puerto de conexión*; las siguientes veces basta la IP de conexión.
3. Pulsa **«Instalar en reloj»**. El móvil empareja, conecta e instala. Para **actualizar**
   la app del reloj, repite el paso 3 (sin código).

> Por qué así y no la instalación automática de Google: el reloj está emparejado por
> **OHealth**, no por la app «Watch» de Google, de modo que el mecanismo de app embebida que
> instala el companion **no aplica**. El `adb` interno del móvil sí funciona (es la misma
> técnica que usa el proyecto para el puente del CheerTok).

### Aviso automático de "app del reloj desactualizada"

Como la app del móvil y la del reloj se actualizan por separado, al actualizar solo el
móvil la del reloj se queda atrás. Para detectarlo:

- El reloj **anuncia su `versionCode`** al móvil al conectarse (mensaje Data Layer en
  `/kodi/version`); el móvil lo guarda (`KodiWearListener` → pref `watch_app_version`).
- La app del móvil compara esa versión con la del **`wear.apk` que lleva empaquetado**
  (lee su `versionCode` del asset) y, si la del reloj es menor (o nunca la ha anunciado y
  hay un reloj conectado), muestra un **aviso** en la tarjeta «Mando del reloj» invitando a
  pulsar «Instalar en reloj». El aviso desaparece tras reinstalar.

> ⚠️ **Regla de mantenimiento:** cada vez que cambie algo de la app del reloj, **sube
> `versionCode` en `app-android/wear/build.gradle.kts`**. Si no, el móvil no puede saber que
> la del reloj quedó vieja y el aviso no aparece.

### Alternativa: instalar el reloj desde un PC

```bash
adb pair <IP-RELOJ>:<PUERTO-EMPAREJAMIENTO> <CÓDIGO>
adb connect <IP-RELOJ>:<PUERTO-CONEXIÓN>
adb -s <IP-RELOJ>:<PUERTO-CONEXIÓN> install -r app-android/wear/build/outputs/apk/debug/wear-debug.apk
```

> **Importante**: ambos APK deben compartir el **mismo `applicationId`** (lo hacen:
> `org.cheertok.kodibridge`) y estar firmados con la **misma clave**. Si los instalas
> desde la misma máquina con la clave debug, ya cumple. Si las firmas difieren, los
> mensajes del Data Layer **se pierden en silencio** (sin error).

Luego, en el reloj, abre la app **«Kodi Control»**. El punto/OK azul indica que ve al móvil.

---

## Qué se ha validado en hardware real

- ✅ El Data Layer **funciona** entre Watch X2 (emparejado por OHealth) y móvil OPPO.
- ✅ Pipeline completo: cada botón del reloj → móvil → Kodi responde `200` (`ok=true`).
- ✅ Mapeo de los 9 controles + corona (vertical y horizontal).
- ✅ Render correcto de las dos pantallas.
- ⏳ Probado sin WiFi: el transporte es Bluetooth, debería ir; conviene confirmarlo
  apagando el WiFi de ambos.

## Pendiente / ideas

- Hacer el **atajo ★** (hoy fijo a Palantir) configurable desde la UI del móvil,
  reutilizando el selector de addons que ya existe.
- El reloj, **al dormirse y volver**, reaparece en la pantalla de cruceta (se reinicia el
  paginador). Es razonable para un mando; se puede fijar la última página si se prefiere.
- La **detección automática de orientación** no es viable con el JSON-RPC actual de Kodi
  (ver arriba); si en el futuro el skin la expone, se puede automatizar.
