package ru.suslikovanora.media.desktop

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Player journal: %APPDATA%\SuslikMedia\player.log. VLC's own warnings and errors (decoder, video output, network),
 * what the player did (open, seek, pause, recovery) and the disk reserve's requests. Written in the background, kept
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
                    if (file.length() > 2_000_000) { val old = AppDir.file("player.old.log"); old.delete(); file.renameTo(old) }
                    file.appendText(line)
                }
            }
        }
    }

    /** VLC's native log (warnings and errors only) into the journal; once per engine. */
    fun attach(factory: uk.co.caprica.vlcj.factory.MediaPlayerFactory) {
        runCatching {
            val log = factory.application().newLog()
            log.setLevel(uk.co.caprica.vlcj.log.LogLevel.WARNING)
            log.addLogListener { level, module, _, _, _, _, _, message -> write("vlc $level [$module] $message") }
        }.onFailure { write("vlc log unavailable: ${it.message}") }
    }
}
