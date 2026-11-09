import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing: put storeFile/storePassword/keyAlias/keyPassword in keystore.properties
// (gitignored, keystore kept outside the repo). Without it, release falls back to the debug key,
// which is fine for the contest APK but not for Pleos App Market.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "dev.chagok.app"
    compileSdk = 35
    // AAOS car APIs: CarPropertyManager for the gear
    useLibrary("android.car")

    defaultConfig {
        applicationId = "dev.chagok.app"
        // Pleos requires API 28+ (signing key rotation / lineage), emulator is API 34.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Emulator (arm64 on Apple silicon) and x86_64 hosts; drops unused ABIs from SDK natives.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("keystore.properties not found: release APK is signed with the debug key")
            }
        }
        // `./gradlew installProbe`: debug build plus extra AAOS read permissions and PropertyProbe,
        // which logs every vehicle property the app can read. Never ship it.
        create("probe") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".probe"
            matchingFallbacks += listOf("debug")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.vehicle.data) // ErrorCode (see VehicleErrors.kt)
    implementation(libs.pleos.navihelper)
    // On-device TTS needs no credentials.
    implementation(libs.pleos.tts)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // QR codes for passing a line or a card to a phone (Apache-2.0).
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
