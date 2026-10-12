package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Player journal: %APPDATA%\SuslikMedia\player.log. What the player did (open, seek, pause, buffering, recovery) and
 * the disk reserve's requests. Written in the background, kept
 * small (the previous part is renamed to player.old.log at 2 MB). When something goes wrong, this file says why.
 */
object PlayerLog {
    val file: File by lazy { AppDir.file("player.log") }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "player-log").apply { isDaemon = true } }
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    fun write(msg: String) {
        val line = "${LocalDateTime.now().format(fmt)}  $msg\n"
        runCatching {
            writer.execute {
                runCatching {
                    if (file.length() > (if (Diag.on) 20_000_000 else 2_000_000)) { val old = AppDir.file("player.old.log"); old.delete(); file.renameTo(old) }
                    file.appendText(line)
                }
            }
        }
    }

    // VLC's own log is NOT attached: its native callback (vlcj NativeLog) crashed the whole app on Windows at the
    // first message VLC wrote — that is why 1.9.4 closed as soon as a film opened.
}
