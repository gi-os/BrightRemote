plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gios.lightremote"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.lightremote"
        minSdk = 29
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "1.13.1"

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/lightremote.jks")
            storePassword = "lightremote"
            keyAlias = "lightremote"
            keyPassword = "lightremote"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same committed key as debug, so either APK upgrades over the other.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    testOptions {
        // The protocol layer traces every frame through android.util.Log, and the stub
        // android.jar on the unit-test classpath throws from every method by default. The
        // handshake tests drive that code for real, so let the stubs no-op instead.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // No crypto dependency on purpose. OPACK, TLV8, binary plists, SRP-6a, HKDF,
    // ChaCha20-Poly1305, Ed25519 and X25519 are all in src/main/kotlin, verified against
    // golden vectors generated from pyatv's own libraries (see scripts/genvec.py).
    // BouncyCastle would have added ~8 MB for the dozen operations one pairing performs,
    // and the platform's Ed25519/XDH providers only exist from API 33 and behave
    // differently under Conscrypt than under OpenJDK, which would make the unit tests lie.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}
