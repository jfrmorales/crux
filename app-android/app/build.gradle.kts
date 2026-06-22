plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.cheertok.kodibridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.cheertok.kodibridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        aidl = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // evgrab se empaqueta como libevgrab.so pero es un EJECUTABLE: hay que
    // extraerlo al disco (nativeLibraryDir) para poder lanzarlo con exec.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // El APK del reloj se empaqueta como asset ("wear.apk", generado por la tarea
    // copyWearApk) para que el móvil pueda instalarlo en el reloj con su adb interno.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/wearApk"))
}

// Copia el APK del módulo :wear al directorio de assets generados antes de compilar.
val copyWearApk by tasks.registering(Copy::class) {
    dependsOn(":wear:assembleDebug")
    from(project(":wear").layout.buildDirectory.file("outputs/apk/debug/wear-debug.apk"))
    into(layout.buildDirectory.dir("generated/wearApk"))
    rename { "wear.apk" }
}
tasks.named("preBuild").configure { dependsOn(copyWearApk) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Data Layer: recibe los comandos del mando del reloj (Wear OS).
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
