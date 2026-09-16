plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "nu.bruijn.rcvehicle"
    compileSdk = 35

    defaultConfig {
        applicationId = "nu.bruijn.rcvehicle"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.3.5"
        buildConfigField("long", "EXPIRES_AT_MS", "0L")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // "demo" is for a build handed to an outside party for evaluation - self-
    // expires 6 months after it's built (MainActivity checks BuildConfig.EXPIRES_AT_MS
    // on launch). "standard" is every other build, including this project's own
    // day-to-day dev/test installs, and never expires (EXPIRES_AT_MS stays the
    // defaultConfig's 0L above). Per direct request: this must never accidentally
    // apply to builds Michiel installs for himself.
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
        }
        create("demo") {
            dimension = "distribution"
            val expiresAtMs = System.currentTimeMillis() + 182L * 24 * 60 * 60 * 1000
            buildConfigField("long", "EXPIRES_AT_MS", "${expiresAtMs}L")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Built from libs/yggdrasil-go (git submodule) via scripts/build_yggdrasil_aar.sh -
    // not published anywhere, so a plain files() dependency on the checked-in .aar
    // rather than a Maven coordinate. See YggdrasilVpnService's doc.
    implementation(files("libs/yggdrasil.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation("com.google.zxing:core:3.5.3") // QR generation (vehicle side)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // QR scanning (controller side)
}
