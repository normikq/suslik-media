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
}

// One place for the version: the installer, and the app itself (update check) via -Dsuslik.version.
val appVersion = "1.9.5"

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
            "-Dsuslik.version=$appVersion",
            // AppCDS (Java 19+): on the first exit the JVM saves the classes it loaded into an archive next to the app;
            // from then on they are mapped from it instead of being read and verified again — the window opens noticeably
            // sooner and takes less memory. A new version makes the archive outdated: the JVM rebuilds it by itself.
            "-XX:+AutoCreateSharedArchive", "-XX:SharedArchiveFile=\$APPDIR/suslik.jsa"
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            // java.net.http: API client, jdk.crypto.ec: HTTPS, jdk.unsupported: JNA (VLC)
            modules("java.net.http", "jdk.crypto.ec", "jdk.unsupported", "java.naming", "jdk.accessibility")
            packageName = "Suslik Media"
            packageVersion = appVersion
            description = "Suslik Media"
            windows {
                iconFile.set(project.file("icon.ico")) // .exe, installer, Start menu / desktop shortcut
                // Fixed id: a newer .msi replaces the installed version instead of installing a second copy.
                upgradeUuid = "6f1d2b9e-4c7a-4f3e-9a51-2d8c5e7b3a10"
                menu = true
                shortcut = true
                perUserInstall = true
            }
        }
    }
}
