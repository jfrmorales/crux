# Guía de montaje (usuario final)

Objetivo: dejarlo funcionando para que el día a día sea **abrir la app → Iniciar**,
o incluso nada si el móvil se queda siempre encendido. Pensado para montarlo **una
vez** (p. ej. en casa de un familiar).

La app no necesita root ni instalar nada más: usa la **depuración inalámbrica** del
propio móvil para obtener los permisos que hacen falta.

---

## 1. Preparar Kodi (una vez)
- Kodi → **Ajustes → Servicios → Control**:
  - **«Permitir el control remoto vía HTTP»** → ON · Puerto **8080**
  - Usuario **kodi** · Contraseña **kodi**
  - **«Permitir el control remoto desde otros sistemas»** → ON
- (La app desactiva el cursor del ratón de Kodi automáticamente.)

## 2. Emparejar el CheerTok (una vez)
- Enciende el CheerTok (interruptor a la izquierda) y empareja **«CheerTok Air»**
  por Bluetooth con el móvil.

## 3. Instalar la app
- Copia **`KodiControl.apk`** al móvil e instálalo (permite «instalar apps
  desconocidas» si lo pide). Incluye también la app del reloj.

## 4. Vincular por depuración inalámbrica (una vez)
1. **Ajustes → Acerca del teléfono → Información de software** → toca **«Número de
   compilación» 7 veces** (activa Opciones de desarrollador).
2. **Opciones de desarrollador → Depuración inalámbrica** → **ON**.
3. En la app, copia en **«IP:puerto de conexión»** la dirección que aparece en la
   pantalla principal de Depuración inalámbrica (ej. `192.168.1.185:46853`).
4. En Depuración inalámbrica entra en **«Vincular dispositivo con código»**. Copia
   en la app **su** IP:puerto (otra distinta) y el **código de 6 dígitos**, y pulsa
   **«Vincular»**.
   - **Si el recuadro se cierra al cambiar de app (típico en OPPO/ColorOS):** usa
     **pantalla dividida** (app arriba, Ajustes abajo) para verlos a la vez. En
     Samsung y Android de serie el recuadro se queda abierto y no hace falta.
5. El estado debe poner **«vinculado ✓»** y luego **«ACTIVO (cursor oculto)»**.

> **Funciona luego SIN WiFi (datos móviles):** al vincular, la app deja el adbd del
> móvil escuchando también en `127.0.0.1` y cambia la «IP:puerto de conexión» a
> **`127.0.0.1:5555`** (fija). Desde ese momento el puente conecta por *loopback*,
> así que **funciona con datos móviles, sin ninguna WiFi**. (Solo el paso de vincular
> necesita WiFi una vez.)

## 5. Uso diario
- **No hay que volver a vincular.** El emparejamiento se recuerda y la dirección de
  conexión queda fija en **`127.0.0.1:5555`**.
- Si el puente está parado: abre la app y pulsa **«▶ Iniciar»** (sin código, sin
  WiFi). Para apagarlo, **«■ Parar»**.
- Si el móvil **se queda siempre encendido**, se queda funcionando solo: el familiar
  no toca nada, solo usa el CheerTok.

> **Tras un reinicio:** el modo TCP se desactiva. Hay que repetir el paso de
> **vincular** una vez (con WiFi); luego vuelve a funcionar con datos móviles. Por
> eso conviene dejar el móvil **siempre encendido**.

### Abrir Kodi automáticamente (opcional, recomendado)
Marca **«Abrir Kodi automáticamente al conectar el mando»**. La primera vez te
pedirá el permiso **«mostrar sobre otras apps»** (concédelo). A partir de ahí, al
**encender el CheerTok** Kodi se abre solo en primer plano — ideal para que el
familiar solo tenga que encender el mando.

### Notificación de estado
Mientras el puente está activo verás una **notificación persistente** que indica si
el mando está **conectado** (🎮) o **desconectado** (⚠️), con un botón **Parar**.

## 6. Atajos de los botones VOL +/−
En la app, sección **«Atajos de los botones laterales»**, asigna a cada botón:
Nada / Inicio / Menú contextual / Play-Pausa / Volumen de Kodi /
**Opciones de reproducción (OSD)** / **Abrir un addon…** (elige de la lista que
lee Kodi en vivo, p. ej. **Palantir**) → **Guardar y aplicar**.

> **«Opciones de reproducción (OSD)»** es lo que sale al tocar la pantalla durante
> un vídeo: rebobinar, avanzar, subtítulos, audio, ajustes de reproducción… Ideal
> para un botón, ya que con las gafas puestas no se puede tocar la pantalla.

---

## Controles
| Gesto / botón | Acción en Kodi |
|---|---|
| Deslizar ↑↓←→ | Mover selección |
| Tap | OK / Entrar |
| Botón Atrás / Inicio | Atrás / Inicio |
| Botones VOL +/− | Lo que tú configures |
| Rueda de borde | Subir/bajar listas |

## Problemas frecuentes
- **No se mueve Kodi:** revisa que Kodi tenga el control HTTP ON (paso 1) y que el
  estado diga «Kodi OK». Comprueba que el CheerTok está conectado por Bluetooth.
- **«no conecta»:** la «IP:puerto de conexión» tiene que ser la **actual** (cambia
  cada vez que reactivas la depuración inalámbrica). Asegúrate de estar en la misma
  WiFi y de haber vinculado al menos una vez.
- **Se ve el cursor:** el estado debe decir «cursor oculto». Si no, el puente no
  está agarrando el mando: pulsa Parar e Iniciar de nuevo.
- **El recuadro de emparejar se cierra (OPPO):** usa pantalla dividida (paso 4).

## Alternativa: Shizuku
Si ya usas [Shizuku](https://github.com/RikkaApps/Shizuku), la app también puede
arrancar el puente por ahí (sección «Avanzado»). En la mayoría de móviles no hace
falta; en algunos OPPO/ColorOS Shizuku está limitado, por eso la vía principal es la
depuración inalámbrica.
