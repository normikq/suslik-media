pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "SuslikMedia"
include(":app")

// App core without Android APIs (reserve BurstProxy, models, changelog, playback supervisor…), tested on the JVM.
includeBuild("core")
