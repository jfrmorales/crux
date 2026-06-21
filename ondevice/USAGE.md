# Cómo activar el mando CheerTok → Kodi (OPPO y Samsung, sin PC)

El puente vive en 3 ficheros dentro del móvil (`/data/local/tmp/`):
`evgrab` (oculta el cursor agarrando el CheerTok), `bridge_num.awk` (traduce a
Kodi) y `cheertok-bridge.sh` (lanzador que resuelve todo y reinicia solo).

Para arrancarlo hace falta una **shell con privilegios** una vez por encendido.
Sin root, la forma compatible con **ambos** móviles (OPPO incluido, donde ColorOS
bloquea Shizuku) es **LADB**.

## Requisitos previos (una sola vez)
1. **Kodi**: Ajustes → Servicios → Control → "Permitir control remoto por HTTP",
   puerto **8080**, usuario/contraseña **kodi/kodi**. Y Ajustes → Sistema →
   Entrada → desactivar "soporte de ratón".
2. **CheerTok** emparejado por Bluetooth.
3. Tener los 3 ficheros en `/data/local/tmp/` (en el OPPO ya están; para el Samsung
   se copian una vez por adb desde el PC).

## Activar con LADB (cada vez que se reinicia el móvil)
1. Instala **LADB** (Play Store / F-Droid).
2. Ajustes → Opciones de desarrollador → activa **Depuración inalámbrica**.
3. Abre **LADB**: te pedirá emparejar (te guía con el código de "Depuración
   inalámbrica → Emparejar dispositivo con código").
4. En la consola de LADB escribe:
   ```
   sh /data/local/tmp/cheertok-bridge.sh
   ```
5. Deja LADB abierto en segundo plano. ¡Ya puedes manejar Kodi con el CheerTok!

## Mapa de controles
| Gesto / botón CheerTok | Acción en Kodi |
|---|---|
| Deslizar ↑↓←→ (touchpad) | Mover selección (cruceta) |
| Rueda de borde | Subir/bajar en listas |
| Tap (clic) | OK / Entrar |
| Botón **Back** | Atrás |
| Botón **Home** | Inicio |
| Botones **Volumen +/−** | Volumen de Kodi |

## Alternativa en el Samsung: la app + Shizuku
En el Samsung (sin la limitación de ColorOS) también sirve la app **"CheerTok →
Kodi"** con **Shizuku** activado por depuración inalámbrica. En el OPPO esa vía no
funciona (ColorOS le quita a `shell` el permiso para conceder permisos), por eso
ahí se usa LADB + este lanzador.

## Ajustes
Variables al lanzar (opcional), p. ej. sensibilidad de swipe:
```
STEP=200 sh /data/local/tmp/cheertok-bridge.sh
```
`PORT` (8080), `AUTH` (base64 de user:pass), `STEP` (160, mayor = menos sensible),
`WHEEL` (240), `TAPMOVE` (40).
