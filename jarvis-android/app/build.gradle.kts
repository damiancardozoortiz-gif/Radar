plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.radar.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radar.jarvis"
        minSdk = 26          // Android 8.0
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Firma fija para instalar la app a mano y poder actualizarla encima. No es una clave secreta:
        // para publicar en una tienda hay que generar una propia y NO subirla al repositorio.
        create("personal") {
            storeFile = rootProject.file("firma/jarvis.keystore")
            storePassword = "android"
            keyAlias = "jarvis"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // en las pruebas de escritorio no hay org.json de Android
}
