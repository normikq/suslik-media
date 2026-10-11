plugins { kotlin("jvm") version "2.0.21"; application }
repositories { mavenCentral() }
dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
kotlin { jvmToolchain(21) }
application { mainClass.set("TestKt") }
