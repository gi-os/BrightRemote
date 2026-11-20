plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull

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
        versionName = "1.31.0"

        // LightReport.install reads this at startup; light-common has its own BuildConfig,
        // so the app's key has to be handed in rather than looked up across the boundary.

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    val releaseSigning = if (!keystoreFile.isNullOrBlank() && !keystorePassword.isNullOrBlank()
        && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreFile)
            storePassword = keystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
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
    // MediaSessionCompat, so BrightControl's lock face gets a transport row over this remote.
    // The one new dependency the media session needs; framework MediaSession would work too but
    // the compat class carries the pre-33 behaviour the LPIII still runs on.
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The wheel keys, shake-to-report and the LightSync backup provider, shared with every
    // other Light* app. Comes with its own R8 keep rules and a baseline profile.
    implementation("com.gios:light-common:1.11.0")
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
