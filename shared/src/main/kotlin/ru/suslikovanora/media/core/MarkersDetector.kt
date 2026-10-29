package ru.suslikovanora.media.core

import org.json.JSONObject
import java.net.URLEncoder

/** A chapter of the open file: where it starts and its name. */
data class Chapter(val timeMs: Long, val title: String)

/**
 * Opening and end credits of a video, and which subtitles go with the voice-over. The same rules in both apps:
 * first the file's own chapters («Opening», «Заставка», «Credits», «Титры»…), then what the server found in the open
 * databases (`GET /api/markers`, website/src/services/markers.js — AniSkip for anime).
 */
object MarkersDetector {
    // (?iu): Cyrillic too («Заставка», «ТИТРЫ»); \b stays Latin — it is only around «op» / «ed»
    val INTRO_REGEX = Regex("""(?iu)(intro|opening|заставк|опенинг|вступлен|начальн|main\s*titles?|^op\b|\bop\s?\d*$)""", RegexOption.IGNORE_CASE)
    val CREDITS_REGEX = Regex("""(?iu)(credits|outro|ending|эндинг|титр|конечн|^ed\b|\bed\s?\d*$)""", RegexOption.IGNORE_CASE)

    /** An intro chapter without a next one (or a very long one: a wrong name) is taken as this long. */
    const val INTRO_MAX_MS = 120_000L
    private const val INTRO_SANE_MS = 5 * 60_000L
    /** The countdown is not offered for credits shorter than this (the end of the file comes anyway). */
    const val CREDITS_MIN_MS = 5_000L
    /** Auto-skip waits this long inside the intro, so a jump into it does not throw the picture at once. */
    const val AUTO_SKIP_DELAY_MS = 1_500L
    /** Countdown choices in the settings, seconds; 0 — at once. */
    val COUNTDOWN_CHOICES = listOf(5, 8, 12, 0)
    const val COUNTDOWN_DEFAULT = 8

    /**
     * Intro and credits from the chapter names. The intro must start in the first half (a «Credits» chapter of the
     * opening is not the end), the credits — in the second half; the credits last to the end of the video.
     */
    fun fromChapters(chapters: List<Chapter>, lengthMs: Long): MediaMarkers {
        val list = chapters.sortedBy { it.timeMs }
        val half = if (lengthMs > 0) lengthMs / 2 else Long.MAX_VALUE
        var intro: TimeRange? = null
        for ((i, c) in list.withIndex()) {
            if (c.timeMs >= half || !INTRO_REGEX.containsMatchIn(c.title) || CREDITS_REGEX.containsMatchIn(c.title)) continue
            val next = list.getOrNull(i + 1)?.timeMs
            var end = if (next != null && next > c.timeMs && next - c.timeMs <= INTRO_SANE_MS) next else c.timeMs + INTRO_MAX_MS
            if (lengthMs > 0) end = minOf(end, lengthMs)
            if (end > c.timeMs) { intro = TimeRange(c.timeMs, end); break }
        }
        var credits: TimeRange? = null
        if (lengthMs > 0) {
            val c = list.lastOrNull { it.timeMs >= half && it.timeMs < lengthMs && CREDITS_REGEX.containsMatchIn(it.title) && !INTRO_REGEX.containsMatchIn(it.title) }
            // «Credits» followed by «Preview»: still the credits go on to the end — after them only the next episode
            if (c != null) credits = TimeRange(c.timeMs, lengthMs)
        }
        return MediaMarkers(intro, credits)
    }

    /** The file's own marks first; what it does not have — from the server, cut to the video's length. */
    fun merge(file: MediaMarkers, server: MediaMarkers?, lengthMs: Long): MediaMarkers {
        fun fit(r: TimeRange?, toEnd: Boolean): TimeRange? {
            if (r == null) return null
            if (lengthMs <= 0) return r.takeIf { it.endMs > it.startMs }
            if (r.startMs >= lengthMs) return null
            val end = if (toEnd) lengthMs else minOf(r.endMs, lengthMs)
            return TimeRange(r.startMs.coerceAtLeast(0L), end).takeIf { it.endMs > it.startMs }
        }
        return MediaMarkers(file.intro ?: fit(server?.intro, false), file.credits ?: fit(server?.credits, true))
    }

    /** When the «next episode / film» card comes up: the start of the credits, if they are long enough to matter. */
    fun nextOfferAt(m: MediaMarkers, lengthMs: Long): Long? {
        val c = m.credits ?: return null
        if (lengthMs <= 0 || lengthMs - c.startMs < CREDITS_MIN_MS) return null
        return c.startMs
    }

    /** The intro still worth skipping at [timeMs]: inside it and more than 2 s of it left. */
    fun introAt(m: MediaMarkers, timeMs: Long): TimeRange? = m.intro?.takeIf { timeMs in it && it.endMs - timeMs > 2_000L }

    /**
     * `/api/markers?…` for the server (TheIntroDB, AniSkip); [episode] null — a film (of a collection). Null — nothing to
     * ask with (no title).
     */
    fun serverPath(title: String?, alt: String?, season: Int?, episode: Int?, durationMs: Long): String? {
        val raw = listOfNotNull(title, alt).map { it.trim() }.filter { it.length >= 2 }
        val titles = mutableListOf<String>()
        for (t in raw) {
            if (!titles.contains(t)) titles.add(t)
            for (p in t.split(Regex("""\s*[/|]\s*|\s+[-–—]\s+"""))) {
                val pt = p.trim()
                if (pt.length >= 2 && !titles.contains(pt)) titles.add(pt)
            }
        }
        if (titles.isEmpty()) return null
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        return buildString {
            append("/api/markers?title=").append(enc(titles[0]))
            titles.getOrNull(1)?.let { append("&alt=").append(enc(it)) }
            if (episode != null && episode > 0) {
                append("&season=").append(season?.takeIf { it > 0 } ?: 1)
                append("&episode=").append(episode)
            }
            if (durationMs > 0) append("&duration=").append(durationMs / 1000)
        }
    }

    /** A film of a collection for the server: «Harry Potter and the Chamber of Secrets (2002)» from its file name. */
    fun filmTitle(fileName: String, parsed: String? = null): String? {
        val name = parsed?.takeIf { it.isNotBlank() }?.let { cleanCollectionFilmTitle(it) } ?: cleanCollectionFilmTitle(fileName)
        if (name.length < 2 || name.all { it.isDigit() }) return null
        return extractYear(fileName)?.let { y -> if (name.contains(y.toString())) name else "$name ($y)" } ?: name
    }

    /** The server's answer: `{ intro: {startMs, endMs} | null, credits: … }`. */
    fun parseServer(json: JSONObject?): MediaMarkers? {
        if (json == null) return null
        fun range(key: String): TimeRange? {
            val o = json.optJSONObject(key) ?: return null
            val s = o.optLong("startMs", -1L); val e = o.optLong("endMs", -1L)
            return if (s >= 0 && e > s) TimeRange(s, e) else null
        }
        return MediaMarkers(range("intro"), range("credits"))
    }

    // ---- Smart subtitles ----------------------------------------------------------------------------------------

    /** A sound or subtitle track as the player sees it: [lang] as in the file («rus», «en»), [forced] — the file's flag. */
    data class Track(val id: Int, val title: String?, val lang: String?, val forced: Boolean = false)

    sealed class SubChoice {
        /** Leave the subtitles as they are (the language of the sound is not known, or nothing fits). */
        object Keep : SubChoice()
        object Off : SubChoice()
        data class Pick(val id: Int) : SubChoice()
    }

    private val RU_LANG = Regex("""(?i)^(ru|rus|russian|русский)$""")
    private val OTHER_LANG = Regex("""(?i)^[a-z]{2,3}$|^(english|japanese|korean|chinese|german|french|spanish|italian)$""")
    private val RU_WORDS = Regex("""(?iu)(рус|russian|дубляж|дублир|закадр|многоголос|двухголос|одноголос|авторск|\b(mvo|dvo|avo)\b)""")
    private val FOREIGN_WORDS = Regex("""(?iu)(оригинал|original|english|англ|japanese|япон|korean|корей|\b(eng|jpn|jap|kor)\b)""")
    private val FORCED_WORDS = Regex("""(?iu)(forced|форсир|надпис|\bsigns\b|только надписи)""")
    private val FULL_WORDS = Regex("""(?iu)(полн|full)""")
    private val CYRILLIC = Regex("""\p{IsCyrillic}""")

    /** true — Russian speech, false — another language, null — cannot tell. */
    fun isRussianAudio(t: Track): Boolean? {
        val lang = t.lang?.trim().orEmpty()
        if (lang.isNotEmpty() && RU_LANG.matches(lang)) return true
        val title = t.title.orEmpty()
        if (FOREIGN_WORDS.containsMatchIn(title)) return false
        if (RU_WORDS.containsMatchIn(title)) return true
        if (lang.isNotEmpty() && !lang.equals("und", true) && OTHER_LANG.matches(lang)) return false
        // A studio named in Cyrillic («Кубик в Кубе», «Пифагор») is a Russian voice-over
        if (CYRILLIC.containsMatchIn(title)) return true
        return null
    }

    fun isForced(t: Track): Boolean = t.forced || FORCED_WORDS.containsMatchIn(t.title.orEmpty())

    /** Russian subtitles: the language tag, or a Russian word / Cyrillic in the name when there is no tag. */
    fun isRussianSub(t: Track): Boolean {
        val lang = t.lang?.trim().orEmpty()
        if (lang.isNotEmpty() && !lang.equals("und", true)) return RU_LANG.matches(lang)
        val title = t.title.orEmpty()
        return RU_WORDS.containsMatchIn(title) || CYRILLIC.containsMatchIn(title)
    }

    /**
     * Subtitles for the sound [audio]: Russian speech — the forced (signs) Russian track, or none at all (full
     * subtitles would repeat the speech); another language — the full Russian track («Полные» first).
     */
    fun pickSubtitle(audio: Track?, subs: List<Track>): SubChoice {
        val ru = audio?.let { isRussianAudio(it) } ?: return SubChoice.Keep
        val russian = subs.filter { it.id >= 0 && isRussianSub(it) }
        return if (ru) {
            russian.firstOrNull { isForced(it) }?.let { SubChoice.Pick(it.id) } ?: SubChoice.Off
        } else {
            val full = russian.filter { !isForced(it) }
            (full.firstOrNull { FULL_WORDS.containsMatchIn(it.title.orEmpty()) } ?: full.firstOrNull())
                ?.let { SubChoice.Pick(it.id) } ?: SubChoice.Keep
        }
    }
}
