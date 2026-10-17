package ru.suslikovanora.media.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Окно удаления (desktop/setup, лежит в MSI как app/resources/SuslikUninstall.exe): Windows «Установленные приложения»
 * открывает его вместо голого msiexec, и в нём можно удалить ещё и настройки, кэш и запас фильмов.
 * Запись в Windows делает сам SuslikUninstall.exe (`/register`); приложение просит об этом раз на версию, потому что
 * обновление через MSI (самообновление) установщик не запускает.
 */
object Uninstaller {
    private val exe: File?
        get() = System.getProperty("compose.application.resources.dir")?.let { File(it, "SuslikUninstall.exe") }?.takeIf { it.isFile }

    val available: Boolean get() = !Diag.on && exe != null

    private val prefs by lazy { Store("setup") }

    /** Блокирующий (до 20 с): вызывать не из потока окна. */
    fun register() {
        if (Diag.on) return
        val e = exe ?: return
        val key = "${Updater.current}|${e.absolutePath}"
        if (prefs.get("registered") == key) return
        runCatching {
            val p = ProcessBuilder(e.absolutePath, "/register", Updater.current).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) prefs.put("registered", key)
            else PlayerLog.write("setup: register failed (${runCatching { p.exitValue() }.getOrNull()})")
        }.onFailure { PlayerLog.write("setup: register failed: ${it.message}") }
    }
}
