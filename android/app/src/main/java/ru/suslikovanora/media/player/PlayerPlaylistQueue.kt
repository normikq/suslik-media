package ru.suslikovanora.media.player

import android.net.Uri
import android.os.Handler
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages video playlist queue (.m3u), episode transitions,
 * next episode card UI, and countdown timer.
 */
class PlayerPlaylistQueue(
    private val handler: Handler,
    private val callback: Callback
) {
    data class Entry(val title: String, val url: String)

    interface Callback {
        fun isActivityAlive(): Boolean
        fun getCurrentUrl(): String
        /** [finished]: the current file is left on its end credits and counts as watched to the end. */
        fun onSaveCurrentPosition(finished: Boolean)
        fun onPlayEntry(entry: Entry, index: Int)
        fun onQueueUiUpdated(hasMultiple: Boolean, hasPrevious: Boolean, hasNext: Boolean, displayText: String?, episodes: List<String>, index: Int)
        /** The card came up at the end of the file (or on its end credits, [onCredits]: it goes on playing). */
        fun onNextEpisodeOffered(onCredits: Boolean)
        /** The countdown waits while the viewer holds a pause (on the credits). */
        fun isPaused(): Boolean
        /** The card «Следующая: …» shows [title], starting in [seconds]. */
        fun onNextCountdown(title: String, seconds: Int)
        fun onNextCardDismissed()
        fun showControlsTemporarily()
    }

    companion object {
        const val NEXT_COUNTDOWN_S = 10
    }

    private var queue: List<Entry> = emptyList()
    private var queueIndex: Int = -1

    private var countdown = 0
    private var countdownOnCredits = false
    /** The countdown card is up (on the credits or at the end). */
    var nextOffered = false
        private set
    /** The card counts on the credits while the file plays (not at its end). */
    val offeredOnCredits: Boolean get() = nextOffered && countdownOnCredits

    val size: Int get() = queue.size
    val currentIndex: Int get() = queueIndex
    val hasNext: Boolean get() = queue.size > 1 && queueIndex in 0 until queue.size - 1
    val hasPrevious: Boolean get() = queue.size > 1 && queueIndex > 0
    val entries: List<Entry> get() = queue
    val currentEntry: Entry? get() = queue.getOrNull(queueIndex)

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdownOnCredits && callback.isPaused()) { handler.postDelayed(this, 500L); return }
            if (countdown <= 0) {
                hideNextCard()
                playEntry(queueIndex + 1, finished = true)
                return
            }
            queue.getOrNull(queueIndex + 1)?.let { callback.onNextCountdown(it.title, countdown) }
            countdown--
            handler.postDelayed(this, 1000L)
        }
    }

    fun fetchPlaylist(playlistUrl: String) {
        Thread {
            val loaded: List<Entry> = try {
                val c = URL(playlistUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                try {
                    if (c.responseCode != 200) emptyList()
                    else parseM3u(c.inputStream.bufferedReader(Charsets.UTF_8).readText())
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                emptyList()
            }
            handler.post {
                if (callback.isActivityAlive()) applyQueue(loaded)
            }
        }.start()
    }

    fun applyQueue(entries: List<Entry>) {
        val currentUrl = callback.getCurrentUrl()
        val idx = entries.indexOfFirst { mediaKey(it.url) == mediaKey(currentUrl) }
        if (idx < 0) return // playlist does not contain what is playing: ignore
        queue = entries
        queueIndex = idx
        notifyUiUpdated()
    }

    fun reset() {
        queue = emptyList()
        queueIndex = -1
        hideNextCard()
        notifyUiUpdated()
    }

    fun playEntry(index: Int, finished: Boolean = false) {
        hideNextCard()
        if (index < 0 || index >= queue.size) return
        callback.onSaveCurrentPosition(finished)
        val entry = queue[index]
        queueIndex = index
        notifyUiUpdated()
        callback.onPlayEntry(entry, index)
    }

    /** [finished]: from the card on the end credits — the current one counts as watched. */
    fun playNext(finished: Boolean = false) {
        if (hasNext) playEntry(queueIndex + 1, finished)
    }

    fun playPrevious() {
        if (hasPrevious) playEntry(queueIndex - 1)
    }

    /** The card of the next entry with a countdown of [seconds] (0 — at once); [onCredits] — the file still plays. */
    fun offerNextEpisode(seconds: Int = NEXT_COUNTDOWN_S, onCredits: Boolean = false): Boolean {
        if (queueIndex < 0 || queueIndex >= queue.size - 1) return false
        // Already counting on the credits: the end of the file keeps that countdown
        if (nextOffered && !onCredits) { countdownOnCredits = false; callback.onNextEpisodeOffered(false); return true }
        countdown = seconds.coerceAtLeast(0)
        countdownOnCredits = onCredits
        nextOffered = true
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
        callback.onNextEpisodeOffered(onCredits)
        return true
    }

    fun hideNextCard() {
        handler.removeCallbacks(countdownRunnable)
        nextOffered = false
        callback.onNextCardDismissed()
    }

    fun stop() {
        handler.removeCallbacks(countdownRunnable)
    }

    fun notifyUiUpdated() {
        val has = queue.size > 1 && queueIndex >= 0
        val text = if (has) "${queueIndex + 1}/${queue.size}" else null
        callback.onQueueUiUpdated(
            hasMultiple = has,
            hasPrevious = has && queueIndex > 0,
            hasNext = has && queueIndex < queue.size - 1,
            displayText = text,
            episodes = if (has) queue.map { it.title } else emptyList(),
            index = if (has) queueIndex else -1
        )
    }

    private fun parseM3u(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var title: String? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF") -> title = line.substringAfter(',', "").trim()
                line.startsWith("#") -> {}
                line.startsWith("https://") || line.startsWith("http://") -> {
                    out.add(Entry(title?.takeIf { it.isNotBlank() } ?: line.substringAfterLast('/').substringBefore('?'), line))
                    title = null
                }
            }
        }
        return out
    }

    private fun mediaKey(url: String): String {
        val u = Uri.parse(url)
        return u.host.orEmpty() + u.path.orEmpty()
    }
}
