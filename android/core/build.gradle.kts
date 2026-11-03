// Android app core: reserve BurstProxy, playback supervisor, library model, auth, sync, party, search (plain Kotlin/JVM,
// no Android APIs — unit tests run on the JVM, see .github/workflows/android-core.yml).
// Included into the Android build with includeBuild("core"); used as implementation("ru.suslikovanora.media:core").
plugins { kotlin("jvm") version "2.0.21" }

group = "ru.suslikovanora.media"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // org.json: built into Android (compileOnly), tests bring their own copy.
    compileOnly("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
