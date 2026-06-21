# Compilar desde cero

## Requisitos
- **JDK 17**
- **Android SDK** (compileSdk 35, build-tools 35) y **NDK** (probado con 28.2)
- **Gradle 8.14** (o el wrapper)
- Dispositivo arm64 (la app empaqueta binarios `arm64-v8a`)

## 1. Binario del puente (`libbridge.so`)
Compila `native/bridge.c` con el clang del NDK para arm64 y colócalo en `jniLibs`:

```sh
NDK=$ANDROID_HOME/ndk/28.2.13676358
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
$CC -O2 -o app-android/app/src/main/jniLibs/arm64-v8a/libbridge.so native/bridge.c
```

> Se empaqueta como `libbridge.so` (aunque sea un ejecutable) para que Android lo
> extraiga a `nativeLibraryDir` con permiso de ejecución. Requiere
> `android:extractNativeLibs="true"` y `useLegacyPackaging = true` (ya están).

## 2. Binario `adb` (`libadb.so`)
Descarga un `adb` **estático para Android arm64** y colócalo como `libadb.so`.
Usa una versión **anterior a la 34** (la 35.x aborta por `fdsan` en Android):

```sh
# desde https://github.com/lzhiyong/android-sdk-tools/releases (33.0.3)
unzip android-sdk-tools-static-aarch64.zip
cp aarch64/platform-tools/adb app-android/app/src/main/jniLibs/arm64-v8a/libadb.so
```

## 3. Compilar la app
```sh
cd app-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle :app:assembleDebug          # o ./gradlew si generas el wrapper
# APK en app/build/outputs/apk/debug/app-debug.apk
```

## 4. Instalar
```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Notas
- Para otras arquitecturas (p. ej. `armeabi-v7a`), compila ambos binarios para esa
  ABI y añádelos a su carpeta `jniLibs/<abi>/`.
- La variante en scripts (`ondevice/`) se compila aparte: `evgrab.c` con el mismo
  clang del NDK; `bridge_num.awk` y `cheertok-bridge.sh` no necesitan compilación.
- El prototipo de PC (`prototype/cheertok_bridge.py`) solo necesita Python 3 y `adb`.
