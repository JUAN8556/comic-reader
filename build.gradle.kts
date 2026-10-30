// AGP 9.4.1 arrastra KGP 2.2.10; el compilador de Compose 2.4.21 exige el mismo Kotlin → se fija aquí (AGP lo permite).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

// Versiones declaradas una sola vez; el módulo :app las aplica sin versión.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
