package ru.suslikovanora.media.desktop.player

import uk.co.caprica.vlcj.player.base.MediaPlayer

/** Reflection keeps this independent of the exact vlcj method signatures. */
fun call(o: Any?, name: String): Any? =
    o?.let { runCatching { it.javaClass.getMethod(name).invoke(it) }.getOrNull() }

/** Chapters of the current title: (start ms, name). Empty if the file has none. */
fun readChapters(mp: MediaPlayer): List<Pair<Long, String>> {
    val list = call(call(mp, "chapters"), "descriptions") as? List<*> ?: return emptyList()
    return list.mapNotNull { d ->
        val off = (call(d, "offset") as? Number)?.toLong() ?: return@mapNotNull null
        off to ((call(d, "name") as? String).orEmpty())
    }.sortedBy { it.first }
}

/** (decoded audio blocks, audio buffers played, pictures shown) — null when this VLC gives no statistics. */
fun soundStats(mp: MediaPlayer): Triple<Long, Long, Long>? = runCatching {
    val info = mp.media().info() ?: return null
    val cls = Class.forName("uk.co.caprica.vlcj.media.MediaStatistics")
    val st = cls.getDeclaredConstructor().newInstance()
    info.javaClass.getMethod("statistics", cls).invoke(info, st)
    fun n(vararg names: String): Long? = names.firstNotNullOfOrNull { (call(st, it) as? Number)?.toLong() }
    Triple(n("decodedAudio", "audioDecoded") ?: return null, n("audioBuffersPlayed", "playedAudioBuffers") ?: return null,
        n("picturesDisplayed", "displayedPictures") ?: 0L)
}.getOrNull()

/** Debug build: VLC's cumulative counters for the [vlc] journal line (empty when this VLC gives none). Player thread. */
fun vlcStats(mp: MediaPlayer): Map<String, Long> = runCatching {
    val info = mp.media().info() ?: return emptyMap()
    val cls = Class.forName("uk.co.caprica.vlcj.media.MediaStatistics")
    val st = cls.getDeclaredConstructor().newInstance()
    info.javaClass.getMethod("statistics", cls).invoke(info, st)
    fun n(vararg names: String): Long = names.firstNotNullOfOrNull { (call(st, it) as? Number)?.toLong() } ?: 0L
    mapOf(
        "read" to n("inputBytesRead", "readBytes"), "shown" to n("picturesDisplayed", "displayedPictures"),
        "lost" to n("picturesLost", "lostPictures"), "video" to n("decodedVideo", "videoDecoded"),
        "audio" to n("decodedAudio", "audioDecoded"), "played" to n("audioBuffersPlayed", "playedAudioBuffers"),
        "aLost" to n("audioBuffersLost", "lostAudioBuffers"), "corrupt" to n("demuxCorrupted"), "discont" to n("demuxDiscontinuity"),
    )
}.getOrDefault(emptyMap())

/** One line for the info panel: picture, codecs, bitrate, dropped frames. Runs on the player thread. */
fun describePlayback(mp: MediaPlayer): String {
    val parts = ArrayList<String>()
    runCatching { mp.video().videoDimension()?.let { parts += "${it.width}×${it.height}" } }
    val info = runCatching { mp.media().info() }.getOrNull()
    (call(info, "videoTracks") as? List<*>)?.firstOrNull()?.let { t -> (call(t, "codecDescription") as? String)?.takeIf { it.isNotBlank() }?.let { parts += it } }
    val audioId = runCatching { mp.audio().track() }.getOrDefault(-1)
    runCatching { mp.audio().trackDescriptions().firstOrNull { it.id() == audioId }?.description() }.getOrNull()?.let { parts += "звук: $it" }
    runCatching {
        val cls = Class.forName("uk.co.caprica.vlcj.media.MediaStatistics")
        val st = cls.getDeclaredConstructor().newInstance()
        info!!.javaClass.getMethod("statistics", cls).invoke(info, st)
        fun n(vararg names: String): Number? = names.firstNotNullOfOrNull { call(st, it) as? Number }
        n("inputBitrate")?.toDouble()?.let { if (it > 0) parts += "поток %.1f Мбит/с".format(it * 8000 / 1000) }
        n("demuxBitrate")?.toDouble()?.let { if (it > 0) parts += "видео %.1f Мбит/с".format(it * 8000 / 1000) }
        val shown = n("picturesDisplayed", "displayedPictures")?.toLong() ?: 0L
        val lost = n("picturesLost", "lostPictures")?.toLong() ?: 0L
        if (shown + lost > 0) parts += "кадров показано $shown, потеряно $lost"
        val aLost = n("audioBuffersLost", "lostAudioBuffers")?.toLong() ?: 0L
        if (aLost > 0) parts += "потеряно звука: $aLost"
    }
    return parts.joinToString("  ·  ").ifBlank { "Нет данных" }
}
