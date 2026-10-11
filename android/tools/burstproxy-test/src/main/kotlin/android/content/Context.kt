package android.content
open class Context(val cacheDir: java.io.File) { open val applicationContext: Context get() = this }
