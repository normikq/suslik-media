pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
}
rootProject.name = "suslik-desktop"

// Code shared with the other app (models, changelog, reserve, playback supervisor).
includeBuild("../shared")
