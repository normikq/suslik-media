package ru.suslikovanora.media.desktop.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/** Минимальная привязка к libmpv (client.h). Строки — UTF-8. */
interface MpvApi : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(h: Pointer): Int
    fun mpv_terminate_destroy(h: Pointer)
    fun mpv_set_option_string(h: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(h: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(h: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer?)
    /** args заканчивается null. */
    fun mpv_command(h: Pointer, args: Array<String?>): Int
    fun mpv_observe_property(h: Pointer, userdata: Long, name: String, format: Int): Int
    /** Возвращает mpv_event*, всегда не null. */
    fun mpv_wait_event(h: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(h: Pointer)
    fun mpv_error_string(error: Int): String
}

object Mpv {
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    const val EV_SHUTDOWN = 1
    const val EV_END_FILE = 7
    const val EV_FILE_LOADED = 8
    const val EV_PROPERTY_CHANGE = 22
    const val EV_PLAYBACK_RESTART = 21

    const val END_EOF = 0
    const val END_ERROR = 4

    /**
     * libmpv-2.dll ищем: рядом с приложением (`app/`, `mpv/`), в `%APPDATA%\SuslikMedia\mpv` (туда её кладут
     * установщик и [MpvInstall]), в PATH.
     */
    fun candidates(): List<File> {
        MpvInstall.applyPending()
        val dirs = ArrayList<File>()
        System.getProperty("compose.application.resources.dir")?.let { dirs += File(it) }
        System.getProperty("user.dir")?.let { dirs += File(it); dirs += File(it, "mpv") }
        System.getenv("APPDATA")?.let { dirs += File(it, "SuslikMedia/mpv") }
        System.getenv("SUSLIK_MPV_DIR")?.let { dirs += File(it) }
        return dirs.filter { File(it, "libmpv-2.dll").isFile }
    }

    @Volatile var libraryFile: File? = null
        private set

    @Volatile private var loaded: MpvApi? = null

    /** Пока библиотеки нет, каждый вызов ищет её снова: [MpvInstall] может докачать её посреди работы. */
    val api: MpvApi?
        get() = loaded ?: synchronized(this) {
            loaded ?: run {
                val dir = candidates().firstOrNull() ?: return@run null
                runCatching {
                    System.setProperty("jna.library.path", dir.absolutePath + File.pathSeparator + (System.getProperty("jna.library.path") ?: ""))
                    Native.load("libmpv-2", MpvApi::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
                }.getOrNull()?.also { loaded = it; libraryFile = File(dir, "libmpv-2.dll") }
            }
        }

    val available: Boolean get() = api != null
}
