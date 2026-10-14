// Code shared by the Android and the Windows app (plain Kotlin/JVM — Android consumes it like any JVM library).
// Included into both builds with includeBuild("../shared"); used as implementation("ru.suslikovanora.media:shared").
plugins { kotlin("jvm") version "2.0.21" }

group = "ru.suslikovanora.media"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // org.json: built into Android; the desktop app brings its own copy.
    compileOnly("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1024m"
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
