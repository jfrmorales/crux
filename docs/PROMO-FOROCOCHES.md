# Hilo para Forocoches (borrador)

> Borrador listo para copiar/pegar. No publica nada por sí solo.
> Antes de subirlo, lee las **notas de uso** del final y decide cuánto detalle
> personal quieres compartir y si enlazas el repo o solo el APK.

---

## Título (elige uno)

- `Con ayuda de la IA le he resuelto a mi padre, que está en diálisis, cómo manejar Kodi`
- `Mi padre está en diálisis y no podía manejar Kodi. Con la IA le monté una solución (os la cuento)`
- `Le he dado a mi padre (en diálisis) una forma de manejar Kodi gracias a la IA — y la comparto`

---

## Cuerpo del hilo

Buenas, FC.

Vengo a contaros algo personal, y de paso lo que la IA me ha ayudado a sacar
adelante. Mi padre está en **diálisis**: muchas horas a la semana enchufado a la
máquina, tumbado y con un brazo inmovilizado por la fístula. Para hacérselo más
llevadero ve **Kodi** en unas gafas de vídeo conectadas a un móvil.

El problema: Kodi está pensado para manejarse con **cruceta** (arriba/abajo/OK), y
con el **cursor** del ratón —tumbado y con una sola mano útil— le era imposible. El
puntero se desalineaba y apuntar era un sufrimiento. No encontré ninguna app que lo
resolviera bien.

Yo no soy desarrollador de Android. Y aquí viene lo que quería compartir: **me he
apoyado en la IA** para diseñarlo y programarlo de principio a fin. Le iba
explicando el problema real (el mando, cómo lo "ve" Android, cómo habla Kodi por su
API, por qué fallaba en su móvil…) y entre las dos hemos ido resolviendo cada
obstáculo hasta que ha quedado algo que **funciona de verdad** y que mi padre usa a
diario. Para mí ese es el titular: una persona normal, con un problema concreto de
un familiar, puede hoy construir una solución completa con esta ayuda.

**Qué he acabado montando** (esto ya es lo secundario): cojo un touchpad-mando
bluetooth barato (un **CheerTok Air**) y lo convierto en un **mando de cruceta de
verdad** para Kodi —deslizas y se mueve la selección, tocas y entra, botones de
Atrás/Inicio—, **sin cursor y sin root**. Y como extra hice también un **mando
desde el reloj (Wear OS)**, que es lo más cómodo para él: con el reloj puesto
controla todo sin buscar nada.

Por si hay manitas, por debajo hay su miga (funciona con datos móviles sin WiFi,
depuración inalámbrica embebida, un binario nativo propio que lee el mando y habla
con Kodi…), pero no quiero aburrir: está todo explicado en el repo.

Es **gratis y open source (MIT)**. No vendo nada, no hay anuncios, no recojo datos.
Lo comparto por si a alguien le sirve —para un familiar mayor, para accesibilidad, o
simplemente porque os toca las narices manejar Kodi con cursor— y también para
animar a quien tenga un problema parecido: con la IA al lado, se puede.

Repo y descarga: **[ENLACE]**

Cualquier duda la respondo por aquí. Y si lo probáis, contadme qué móvil/mando
usáis, que me viene bien para mejorarlo.

---

## Notas de uso del hilo (no copiar al foro)

- **Sustituye `[ENLACE]`** por la URL del repo de GitHub o, si prefieres no exponer
  el repo, por un enlace directo al `Crux.apk`. Ojo: el README del repo cuenta la
  historia personal (padre, diálisis, marca de las gafas) — si enlazas el repo, eso
  queda público. Decide si quieres ese nivel de detalle o si recortas el README
  antes de compartirlo.
- **Cultura FC:** publícalo una vez, responde dudas con naturalidad y no
  "recauchutes" el hilo a la fuerza. Un hilo honesto con historia real suele
  funcionar mejor que cualquier copy de marketing — por eso el gancho es personal.
- **Mantén el foco en el mando.** Es lo legítimo y lo que aporta valor. Evita
  convertirlo en un hilo de "qué addons instalar" o recomendaciones de contenido:
  eso cambia el tema y te puede traer problemas de moderación. El hilo va de
  accesibilidad y cacharreo, no de listas de addons.
- **Subforos posibles:** General o el de manitas/cacharreo, según donde tenga más
  encaje en ese momento.
- Tienes tres titulares arriba, todos centrados en el ángulo principal: **la IA
  ayudándote a resolver el problema de tu padre en diálisis**. La solución técnica
  (mando, reloj, sin root) va en segundo plano dentro del cuerpo.
- Aviso por experiencia de FC: el ángulo "IA" levanta debate (a favor y en contra).
  Es buen gancho, pero prepárate para responder tanto al "qué grande" como al
  cuñadismo anti-IA; el hecho de que sea algo **real, funcionando y para tu padre**
  es tu mejor respuesta.
