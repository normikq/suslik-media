plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Адрес сайта не хранится в репозитории: секрет SITE_URL в CI или suslik.siteUrl в ~/.gradle/gradle.properties.
val siteHost: String = (System.getenv("SITE_URL")?.takeIf { it.isNotBlank() } ?: findProperty("suslik.siteUrl") as String? ?: "")
    .trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
gradle.taskGraph.whenReady {
    if (siteHost.isEmpty() && allTasks.any { t -> listOf("package", "assemble", "run", "bundle").any { t.name.startsWith(it) } })
        throw GradleException("Не задан адрес сайта: переменная окружения SITE_URL или suslik.siteUrl в ~/.gradle/gradle.properties")
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
        buildConfig = true // BuildConfig.DIAG: the debug build's detailed journal (always false in the normal app)
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
        // Debug build («Suslik Debug»): the release app plus a detailed journal (system, network, VLC, reserve, HTTP
        // timings, UI frames) for tracing what to optimise. Its own package — installs next to the normal app, own
        // login and settings, never updates itself. Optimised like the release (fair numbers), names kept readable,
        // profileable from a computer. Built by CI as a separate artifact; never published to the site.
        create("diag") {
            initWith(getByName("release"))
            applicationIdSuffix = ".diag"
            matchingFallbacks += listOf("release")
            proguardFiles("proguard-diag.pro")
            buildConfigField("boolean", "DIAG", "true")
            manifestPlaceholders["appLabel"] = "Suslik Debug"
            if (ksPath == null) signingConfig = signingConfigs.getByName("debug")
        }
    }

    defaultConfig {
        applicationId = "ru.suslikovanora.media"
        minSdk = 24
        targetSdk = 35
        versionCode = 61
        versionName = "2.4.4"
        buildConfigField("boolean", "DIAG", "false")
        buildConfigField("String", "SITE_HOST", "\"$siteHost\"")
        manifestPlaceholders["appLabel"] = "Suslik Media — медиатека"

        // Package native VLC libraries only for 64-bit ARM Android devices.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
}

composeCompiler { stabilityConfigurationFile.set(project.layout.projectDirectory.file("compose-stability.conf")) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("ru.suslikovanora.media:shared") // ../shared (includeBuild): models, changelog, reserve, supervisor
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

    // Media3 (ExoPlayer) — the second player engine next to VLC (player/Media3Engine.kt), with the FFmpeg audio decoder
    // (DTS, TrueHD, AC3, E-AC3; ~0.6 MB, arm64). The decoder is not on Maven: android/ffmpeg/build.sh (CI:
    // media3-ffmpeg.yml) builds it into libs/. Its version must match MEDIA3_VERSION in build.sh.
    val media3 = "1.5.1"
    implementation(files("libs/media3-decoder-ffmpeg-$media3.aar"))
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-decoder:$media3") // the local AAR brings no transitive dependencies
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-database:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
