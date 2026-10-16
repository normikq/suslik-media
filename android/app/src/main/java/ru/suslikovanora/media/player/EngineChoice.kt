package ru.suslikovanora.media.player

import ru.suslikovanora.media.core.MirrorLink
import android.content.Context
import android.net.Uri
import ru.suslikovanora.media.PlaybackSettings
import java.util.Locale

/**
 * Какой движок открывает файл. Настройка «Плеер» = VLC — всегда VLC. Иначе Media3, кроме:
 *  - контейнеров, которые Media3 не читает (Blu-ray `.m2ts`, `.ts`, `.iso`) — сразу VLC;
 *  - файлов, для которых движок уже выбран раньше (Media3 не справился — запомнено [remember]): «Продолжить»
 *    сразу открывает их в нужном движке, без повторной попытки и переключения посреди просмотра.
 */
class EngineChoice(context: Context) {
    data class Pick(val kind: EngineKind, val reason: String)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("player_engine", Context.MODE_PRIVATE)

    fun choose(url: String): Pick {
        if (PlaybackSettings.engine(app) == EngineKind.VLC) return Pick(EngineKind.VLC, "setting: VLC")
        val ext = Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in VLC_ONLY) return Pick(EngineKind.VLC, ".$ext: Media3 cannot read this container")
        remembered(url)?.let { return Pick(it.kind, "remembered for this file (${it.reason})") }
        return Pick(EngineKind.MEDIA3, "setting: Media3")
    }

    fun remembered(url: String): Pick? {
        val v = prefs.getString(MirrorLink.fileKey(url), null) ?: return null
        val kind = EngineKind.values().firstOrNull { v.startsWith(it.tag + "|") } ?: return null
        return Pick(kind, v.substringAfter('|').substringAfter('|'))
    }

    /** The engine for this file from now on (with when and why — for the journal). */
    fun remember(url: String, kind: EngineKind, reason: String) {
        val all = prefs.all
        val e = prefs.edit()
        // a few hundred files at most: the oldest go
        if (all.size >= MAX_FILES) all.entries.sortedBy { (it.value as? String)?.split('|')?.getOrNull(1)?.toLongOrNull() ?: 0L }
            .take(all.size - MAX_FILES + 1).forEach { e.remove(it.key) }
        e.putString(MirrorLink.fileKey(url), "${kind.tag}|${System.currentTimeMillis()}|$reason").apply()
    }

    companion object {
        private val VLC_ONLY = setOf("m2ts", "mts", "ts", "iso")
        private const val MAX_FILES = 400
    }
}
