import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
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
        versionName = "1.19.0"

        // LightReport.install reads this at startup; light-common has its own BuildConfig,
        // so the app's key has to be handed in rather than looked up across the boundary.
        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")

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
            isMinifyEnabled = true
            isShrinkResources = true
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
    // buildConfig carries REPORT_TOKEN into the app; see the reportToken block above.
    buildFeatures {
        compose = true
        buildConfig = true
    }

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
    // AnimatedVisibility, for the report chip's fade. Arrives transitively through foundation;
    // named explicitly because a transitive dependency is not a promise.
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The wheel keys, shake-to-report and the LightSync backup provider, shared with every
    // other Light* app. Comes with its own R8 keep rules and a baseline profile.
    implementation("com.gios:light-common:1.2.1")
    // What makes the AAR's baseline profile actually get applied — below API 31 nothing reads
    // a profile on its own, and the LPIII is where a slow cold start is most obvious.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // No crypto dependency on purpose. OPACK, TLV8, binary plists, SRP-6a, HKDF,
    // ChaCha20-Poly1305, Ed25519 and X25519 are all in src/main/kotlin, verified against
    // golden vectors generated from pyatv's own libraries (see scripts/genvec.py).
    // BouncyCastle would have added ~8 MB for the dozen operations one pairing performs,
    // and the platform's Ed25519/XDH providers only exist from API 33 and behave
    // differently under Conscrypt than under OpenJDK, which would make the unit tests lie.

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.1.0")
}
