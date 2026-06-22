# Instalar la app del reloj sin ADB (vía oficial: Google Play)

Hoy la app del reloj se instala/actualiza por **ADB / depuración inalámbrica** (el módulo
`:wear` va empaquetado dentro del móvil y se empuja con el `libadb.so` interno). Funciona,
pero es friccional: hay que **emparejar la clave adb** con el reloj, los **puertos rotan**,
y los errores son confusos (`failed to connect` cuando en realidad falta emparejar).

Este documento explica la alternativa **oficial y sin ADB**, qué hay que cambiar en el repo
para llegar a ella, y los pasos en Google Play. **Estado actual: solo documentación**; los
cambios de firma/icono/manifest están como checklist, no aplicados.

---

## 1. Vías de instalación en Wear OS

| Vía | ¿Oficial? | ¿Sin ADB? | ¿Funciona en el OPPO Watch X2 (emparejado por OHealth)? |
|---|---|---|---|
| **ADB / depuración inalámbrica** (lo actual) | No | No | Sí, pero con fricción (lo que ya sufrimos) |
| **Play – entrega por companion** (instalas el móvil desde Play y Play empuja el del reloj) | Sí | Sí | ⚠️ **No fiable**: depende del emparejamiento con la app «Wear OS by Google», y el X2 está emparejado por **OHealth** |
| **Play – instalar desde la Play Store del propio reloj** | Sí | **Sí** | ✅ **Sí**: el X2 tiene Play Store en el reloj (ver [WATCH.md](WATCH.md)) |
| Firebase App Distribution u otras | — | — | ❌ No dan una instalación limpia en el reloj |

> No existe "instalar desde fichero" en Wear OS: cualquier *sideload* implica ADB. La única
> vía oficial sin ADB es **Google Play**.

---

## 2. Recomendación

**Publicar en una pista de prueba interna/cerrada** (privada, no producción pública) e
**instalar desde la Play Store del propio reloj**, con la cuenta Google del reloj añadida
como *tester*.

Por qué encaja con este proyecto:
- Funciona **aunque el reloj esté emparejado por OHealth** (no usa el companion de Google).
- Es **reproducible para el usuario final** (p. ej. un familiar): abre Play Store en el
  reloj y actualiza, sin PC ni ADB.
- Las **actualizaciones llegan solas** al reloj (Play las gestiona).

---

## 3. Checklist de cambios en el repo (pendiente)

Cuando se aborde la migración, esto es lo que hay que tocar:

- [ ] **Firma de release + Play App Signing.** Hoy `:app` y `:wear` se firman **solo con la
  clave debug** (no hay `signingConfig` de release en `app-android/app/build.gradle.kts` ni
  en `app-android/wear/build.gradle.kts`). Para Play se sube un **AAB** y Google gestiona la
  clave (Play App Signing).
  - **Beneficio extra clave:** resuelve de forma permanente el requisito de **misma firma
    entre móvil y reloj** (la causa de varios fallos: el Data Layer se pierde en silencio si
    las firmas difieren). Con Play App Signing ambos quedan firmados por la misma clave
    gestionada por Google.
- [ ] **Icono propio.** Ambos manifests usan el placeholder `@android:drawable/ic_media_play`
  (`app-android/wear/src/main/AndroidManifest.xml` y el del `:app`). Play exige **icono
  adaptativo** propio + assets de ficha.
- [ ] **Decidir `standalone` del reloj.** Hoy
  `com.google.android.wearable.standalone=false` (`app-android/wear/src/main/AndroidManifest.xml`).
  Para que el reloj **encuentre e instale la app por sí mismo** desde su Play Store puede
  hacer falta `standalone=true`. La app ya **degrada bien** sin móvil (el botón OK aparece
  gris / "sin nodo"), así que `standalone=true` es asumible.
  - *Trade-off:* `false` es semánticamente correcto (necesita el móvil) y prioriza la entrega
    por companion; `true` habilita la instalación directa en el reloj, que es justo lo que
    necesitamos con OHealth. **Recomendado: probar `standalone=true`.**
- [ ] **Publicar móvil y reloj juntos.** Comparten `applicationId`
  (`org.cheertok.kodibridge`); Play los entrega como app **multi-APK** (teléfono + Wear) bajo
  una misma ficha.
- [ ] **`targetSdk` al día.** `:wear` está en `targetSdk = 34`; comprobar el mínimo que exija
  Play al publicar y subirlo si hace falta (`:app` ya está en 35).
- [ ] **`versionCode`.** Súbelo en cada cambio del reloj (regla ya vigente, ver más abajo);
  Play exige que cada subida tenga un `versionCode` mayor que el anterior.
- [ ] **Ficha mínima + privacidad.** Título, descripción corta/larga, capturas (incluida una
  de reloj) y **política de privacidad** (obligatoria, aun en pista interna).

> Regla de versionado (ya documentada): cada vez que cambie la app del reloj, sube
> `versionCode`/`versionName` en `app-android/wear/build.gradle.kts`. Sin esto, ni el aviso
> de "reloj desactualizado" del móvil ni Play detectan el cambio.

---

## 4. Pasos en Play Console (resumen)

1. **Crear la app** en Play Console (nombre «Kodi Control», idioma, tipo *app*, gratuita).
2. **Activar Play App Signing** (al subir el primer AAB).
3. **Generar los AAB** de móvil y reloj (`bundleRelease`) y subirlos a la **pista de prueba
   interna**.
4. Completar lo mínimo obligatorio: ficha, clasificación de contenido, política de
   privacidad, público objetivo.
5. **Añadir testers**: la cuenta Google del reloj (y la del móvil) en la lista de la pista
   interna; aceptar la invitación desde el enlace de *opt-in*.
6. En el **reloj**: abrir **Play Store → buscar «Kodi Control» → Instalar**. Para
   **actualizar** en el futuro: Play lo hace solo o desde Play Store del reloj. **Sin ADB.**

---

## 5. Qué resuelve frente a ADB

- Sin **emparejar claves adb** con el reloj ni perseguir **puertos que rotan**.
- Sin depender de **una máquina concreta** ni de su clave debug.
- **Firma estable** vía Play App Signing → adiós a los fallos de "firmas distintas" del Data
  Layer.
- **Actualizaciones automáticas** al reloj.

> ADB sigue siendo útil como **método de desarrollo** (iterar rápido sin pasar por Play). La
> vía Play es para la instalación/actualización "de verdad" del usuario final.
