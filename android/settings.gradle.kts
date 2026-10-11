pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "SuslikMedia"
include(":app")

// Code shared with the other app (models, changelog, reserve, playback supervisor).
includeBuild("../shared")
