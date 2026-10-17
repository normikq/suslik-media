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
        fun onSaveCurrentPosition()
        fun onPlayEntry(entry: Entry, index: Int)
        fun onQueueUiUpdated(hasMultiple: Boolean, hasPrevious: Boolean, hasNext: Boolean, displayText: String?, episodes: List<String>, index: Int)
        fun onNextEpisodeOffered()
        /** The card «Следующая: …» shows [title], starting in [seconds]. */
        fun onNextCountdown(title: String, seconds: Int)
        fun onNextCardDismissed()
        fun showControlsTemporarily()
    }

    companion object {
        private const val NEXT_COUNTDOWN_S = 10
    }

    private var queue: List<Entry> = emptyList()
    private var queueIndex: Int = -1

    private var countdown = 0

    val size: Int get() = queue.size
    val currentIndex: Int get() = queueIndex
    val hasNext: Boolean get() = queue.size > 1 && queueIndex in 0 until queue.size - 1
    val hasPrevious: Boolean get() = queue.size > 1 && queueIndex > 0
    val entries: List<Entry> get() = queue
    val currentEntry: Entry? get() = queue.getOrNull(queueIndex)

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdown <= 0) {
                hideNextCard()
                playEntry(queueIndex + 1)
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

    fun playEntry(index: Int) {
        hideNextCard()
        if (index < 0 || index >= queue.size) return
        callback.onSaveCurrentPosition()
        val entry = queue[index]
        queueIndex = index
        notifyUiUpdated()
        callback.onPlayEntry(entry, index)
    }

    fun playNext() {
        if (hasNext) playEntry(queueIndex + 1)
    }

    fun playPrevious() {
        if (hasPrevious) playEntry(queueIndex - 1)
    }

    fun offerNextEpisode(): Boolean {
        if (queueIndex < 0 || queueIndex >= queue.size - 1) return false
        countdown = NEXT_COUNTDOWN_S
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
        callback.onNextEpisodeOffered()
        return true
    }

    fun hideNextCard() {
        handler.removeCallbacks(countdownRunnable)
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
