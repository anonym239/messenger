plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.notfall.mesh"
    compileSdk = 35
    defaultConfig {
        applicationId = "de.notfall.mesh"
        minSdk = 26
        targetSdk = 35
        // Bei Releases setzt GitHub Actions diese Werte aus dem Tag (z. B. v1.2.0)
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1-dev"
    }

    // Fester Signaturschlüssel aus GitHub-Secrets, damit neue Versionen sich
    // über alte installieren lassen. Ohne Secrets wird mit dem Debug-Schlüssel signiert.
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    // Berechtigungen werden zur Laufzeit in MainActivity angefragt
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Offline-Verbindungen Handy <-> Handy (Bluetooth + WLAN-Direkt, ohne Internet)
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // Kryptografie: X25519 + ChaCha20-Poly1305 (HPKE) und Ed25519-Signaturen
    implementation("com.google.crypto.tink:tink-android:1.15.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
}
