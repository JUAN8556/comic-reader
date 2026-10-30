// AGP 9: Kotlin integrado (sin org.jetbrains.kotlin.android). jvmTarget sale de compileOptions.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// La contraseña del keystore la pone el Makefile desde el llavero de macOS; nunca vive en el repo.
val keystorePass = providers.environmentVariable("COMICS_KEYSTORE_PASS")

android {
    namespace = "mx.jano.comics"
    compileSdk = 37

    defaultConfig {
        applicationId = "mx.jano.comics"
        minSdk = 35          // la tablet: Android 15
        targetSdk = 36       // escrito como si fuera 36: sin bloquear orientación, atrás por callbacks, edge-to-edge
        versionCode = 1
        versionName = "0.0.1"
        resValue("string", "app_name", "Cómics")
    }

    signingConfigs {
        create("comics") {
            storeFile = file(System.getProperty("user.home") + "/.android/comics.jks")
            storePassword = keystorePass.orNull
            keyAlias = "comics"
            keyPassword = keystorePass.orNull
        }
    }

    buildTypes {
        debug {
            // Build de desarrollo: convive con la diaria, con su propio cliente OAuth (misma huella SHA-1).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Cómics dev")
            signingConfig = signingConfigs.getByName("comics")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("comics")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Drive: token con Play Services + REST con OkHttp y kotlinx.serialization (sin google-api-client)
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // Caché: prefetch del N+1 con WorkManager (los libros fijados van con JobScheduler UIDT, sin dependencia)
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Archivos: junrar (RAR4, RAR4 sólido, RAR5); ZIP con java.util.zip; PDF con android.graphics.pdf
    implementation("com.github.junrar:junrar:8.1.2")

    // Lector: Coil 3 + Telephoto (zoom con teselado)
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit4:5.5.1")
}
