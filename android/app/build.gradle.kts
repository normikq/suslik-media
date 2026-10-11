plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.suslikovanora.media"
    compileSdk = 35

    // Use the same JVM target for Java and Kotlin. The installed JDK may be newer;
    // bytecode target remains Java 17 for Android/AGP compatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Native library screen (Jetpack Compose); the other tabs are still the website in a WebView.
    buildFeatures {
        compose = true
    }

    // Release signing from the environment (GitHub Actions secrets). Without it, only debug builds are signed.
    // The same key must sign every release, or Android refuses to install an update over the old version.
    val ksPath = System.getenv("SIGNING_KEYSTORE")
    if (ksPath != null) signingConfigs {
        create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    defaultConfig {
        applicationId = "ru.suslikovanora.media"
        minSdk = 24
        targetSdk = 35
        versionCode = 21
        versionName = "1.8.0"

        // Package native VLC libraries only for 64-bit ARM Android devices.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    // Installs the ready-made start-up profiles of Compose & co. at install time: smooth first launches and scrolls,
    // less CPU (and battery) than running that code interpreted until the JIT warms up.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Origin-restricted JS bridge (addWebMessageListener) and document-start scripts.
    // VLC engine: handles more containers/protocols than the platform player.
    implementation("org.videolan.android:libvlc-all:3.6.0")

    // Native library UI
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // HTTP (shares the WebView's login cookie) and poster loading with memory + disk cache
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
