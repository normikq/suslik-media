package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

import java.io.File

/**
 * «Очистить кэш» in the settings: what the app keeps that can be downloaded again, and clearing it. Watch progress,
 * the sign-in, the settings and the saved library list are data, not cache — they stay.
 *
 *  - reserve: the film reserve (BurstProxy) in %TEMP%\SuslikMedia-stream;
 *  - posters: poster pictures in %APPDATA%\SuslikMedia\posters;
 *  - other: a downloaded update, the previous part of the player journal, the picture for the Windows media flyout.
 */
object Storage {
    data class Sizes(val reserve: Long, val posters: Long, val other: Long)

    private fun size(f: File): Long = if (f.exists()) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    private val posters get() = AppDir.file("posters")
    private val oldJournal get() = AppDir.file("player.old.log")
    private val flyoutPictures get() = AppDir.file("smtc") // poster or frame for the Windows media flyout

    /** Blocking (walks the folders): call off the window's thread. */
    fun sizes(): Sizes = Sizes(size(Reserve.dir), size(posters), Updater.tempBytes() + size(oldJournal) + size(flyoutPictures))

    /** Blocking. The settings are shown only without a film open, so every reserve on disk is a closed one. */
    fun clear() {
        BurstProxy.dropRetired()
        if (!BurstProxy.initialized) Reserve.dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        posters.listFiles()?.forEach { runCatching { it.delete() } }
        Updater.cleanup()
        runCatching { oldJournal.delete() }
        flyoutPictures.listFiles()?.forEach { runCatching { it.delete() } }
        PlayerLog.write("storage: cache cleared")
    }
}
