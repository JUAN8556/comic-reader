// AGP 9: Kotlin integrado (sin org.jetbrains.kotlin.android). jvmTarget sale de compileOptions.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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

    testImplementation("junit:junit:4.13.2")
}
