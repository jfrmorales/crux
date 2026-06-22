plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.cheertok.kodibridge.wear"
    compileSdk = 35

    defaultConfig {
        // El applicationId DEBE ser idéntico al del móvil: el Data Layer solo
        // enlaza app de móvil y de reloj con el mismo applicationId y la misma firma.
        applicationId = "org.cheertok.kodibridge"
        minSdk = 30
        targetSdk = 34
        // Súbelo cada vez que cambie la app del reloj: el móvil compara este número con
        // el que le anuncia el reloj para avisar si la app del reloj está desactualizada.
        versionCode = 3
        versionName = "0.3"
    }

    buildFeatures {
        compose = true
        buildConfig = true  // para leer BuildConfig.VERSION_CODE y anunciárselo al móvil
    }
    composeOptions {
        // Kotlin 1.9.24 -> Compose Compiler 1.5.14 (debe coincidir con la versión de Kotlin).
        kotlinCompilerExtensionVersion = "1.5.14"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Data Layer (MessageClient / CapabilityClient) para hablar con el móvil.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Compose for Wear OS (UI redonda + soporte de corona rotatoria).
    val composeUi = "1.6.8"
    implementation("androidx.compose.ui:ui:$composeUi")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeUi")
    implementation("androidx.compose.foundation:foundation:$composeUi") // HorizontalPager
    debugImplementation("androidx.compose.ui:ui-tooling:$composeUi")
    val wearCompose = "1.3.1"
    implementation("androidx.wear.compose:compose-material:$wearCompose")
    implementation("androidx.wear.compose:compose-foundation:$wearCompose")
}
