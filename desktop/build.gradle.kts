import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// No fixed toolchain: builds with whatever JDK runs Gradle; the installer bundles THAT runtime, so build with
// JDK 21+ (AppCDS below needs it; the JDK 21 bundled with the IDE is fine). Bytecode stays Java 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    // VLC engine (uses the VLC installed on the PC: 64-bit VLC 3.x)
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("net.java.dev.jna:jna:5.14.0") // title bar colours (dwmapi)
    implementation("ru.suslikovanora.media:shared") // ../shared (includeBuild)
}

// One place for the version: the installer, and the app itself (update check) via -Dsuslik.version.
val appVersion = "2.0.7"
// Windows Installer takes at most three numbers (2.0.0.1 → 2.0.0); the app itself and the file name keep all four.
val msiVersion = appVersion.split(".").take(3).joinToString(".")

// Debug build «Suslik Debug»: `./gradlew packageMsi -Pdiag`. A separate app next to the normal one (own installer id,
// Start menu entry, data folder %APPDATA%\SuslikMediaDebug), a detailed journal, no self-update. See DEBUG-BUILD.md.
val diag = (findProperty("diag") as String?)?.let { it != "false" } ?: false

composeCompiler { stabilityConfigurationFile.set(project.layout.projectDirectory.file("compose-stability.conf")) }

compose.desktop {
    application {
        mainClass = "ru.suslikovanora.media.desktop.MainKt"
        // Small desktop app: compact GC, heap that shrinks back after spikes.
        jvmArgs += listOf(
            "-Dskiko.renderApi=OPENGL", // DirectX renderer crashes on some new GPUs
            "-Xms32m", "-Xmx384m", "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
            "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=48m",
            "-Dfile.encoding=UTF-8",
            // Keep idle connections to the site for 3 min (default 30 s): the next reserve burst ~1.5 min later reuses
            // the connection instead of a new TLS handshake (nginx keepalive_timeout 180s, see NGINX-MIRROR.md).
            "-Djdk.httpclient.keepalive.timeout=180",
            "-Dsuslik.version=$appVersion",
            "-Dsuslik.diag=$diag",
            // AppCDS (Java 19+): on the first exit the JVM saves the classes it loaded into an archive next to the app;
            // from then on they are mapped from it instead of being read and verified again — the window opens noticeably
            // sooner and takes less memory. A new version makes the archive outdated: the JVM rebuilds it by itself.
            "-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=\$APPDIR/suslik.jsa",
            // A native crash (VLC) leaves its report next to the app; CrashReports sends it at the next start.
            "-XX:ErrorFile=\$APPDIR/hs_err_pid%p.log"
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            // java.net.http: API client, jdk.crypto.ec: HTTPS, jdk.unsupported: JNA (VLC)
            modules("java.net.http", "jdk.crypto.ec", "jdk.unsupported", "java.naming", "jdk.accessibility")
            // debug build: CPU / threads / memory for the [sys] journal lines
            if (diag) modules("java.management", "jdk.management")
            packageName = if (diag) "Suslik Debug" else "Suslik Media"
            packageVersion = msiVersion
            description = if (diag) "Suslik Debug" else "Suslik Media"
            windows {
                iconFile.set(project.file("icon.ico")) // .exe, installer, Start menu / desktop shortcut
                // Fixed id: a newer .msi replaces the installed version instead of installing a second copy.
                upgradeUuid = if (diag) "b3e0c5a1-7d24-4b8e-9f63-1a5c8d2e4f70" else "6f1d2b9e-4c7a-4f3e-9a51-2d8c5e7b3a10"
                menu = true
                shortcut = true
                perUserInstall = true
            }
        }
    }
}
