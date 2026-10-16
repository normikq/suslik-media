package ru.suslikovanora.media.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import ru.suslikovanora.media.tr

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
        fun onNextCardDismissed()
        fun showControlsTemporarily()
    }

    companion object {
        const val EXTRA_PLAYLIST = "media_playlist"
        private const val NEXT_COUNTDOWN_S = 10
    }

    private var queue: List<Entry> = emptyList()
    private var queueIndex: Int = -1

    private var nextCard: LinearLayout? = null
    private var nextCardTitle: TextView? = null
    private var nextCardTimer: TextView? = null
    private var countdown = 0

    private var dpFunc: (Int) -> Int = { it }

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
            nextCardTimer?.text = tr("playerplaylist.cherez_countdown_s", "countdown" to countdown)
            countdown--
            handler.postDelayed(this, 1000L)
        }
    }

    fun buildNextCard(context: Context, dp: (Int) -> Int, pillButtonBuilder: (String) -> TextView): View {
        dpFunc = dp
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xEB11141B.toInt())
                setStroke(dp(1), 0x55FFB02E.toInt())
            }
            visibility = View.GONE
            isClickable = true
        }
        card.addView(TextView(context).apply {
            text = tr("player.sleduyuschaya_seriya")
            setTextColor(0xFF8E96A8.toInt())
            textSize = 13f
        })
        val titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            setPadding(0, dp(2), 0, dp(2))
        }
        card.addView(titleView)
        nextCardTitle = titleView

        val timerView = TextView(context).apply {
            setTextColor(0xFFFFB02E.toInt())
            textSize = 13f
        }
        card.addView(timerView)
        nextCardTimer = timerView

        val nextRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        nextRow.addView(pillButtonBuilder(tr("playerplaylist.smotret_seychas")).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xFFFFB02E.toInt())
            }
            setTextColor(0xFF1C1200.toInt())
            setOnClickListener {
                hideNextCard()
                playEntry(queueIndex + 1)
            }
        }, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })

        nextRow.addView(pillButtonBuilder(tr("playerplaylist.otmena")).apply {
            setOnClickListener {
                hideNextCard()
                callback.showControlsTemporarily()
            }
        }, LinearLayout.LayoutParams(-2, dp(42)))

        card.addView(nextRow)
        nextCard = card
        return card
    }

    fun updateCardMargins(full: Boolean) {
        val card = nextCard ?: return
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        if (full) {
            lp.rightMargin = dpFunc(24)
            lp.bottomMargin = dpFunc(110)
        } else {
            lp.rightMargin = dpFunc(10)
            lp.bottomMargin = dpFunc(10)
        }
        card.layoutParams = lp
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

    fun advanceToNext(): Boolean {
        if (!hasNext) return false
        playEntry(queueIndex + 1)
        return true
    }

    fun offerNextEpisode(): Boolean {
        if (queueIndex < 0 || queueIndex >= queue.size - 1) return false
        nextCardTitle?.text = queue[queueIndex + 1].title
        countdown = NEXT_COUNTDOWN_S
        nextCard?.let { card ->
            card.alpha = 0f
            card.translationY = dpFunc(16).toFloat()
            card.visibility = View.VISIBLE
            card.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
        callback.onNextEpisodeOffered()
        return true
    }

    fun hideNextCard() {
        handler.removeCallbacks(countdownRunnable)
        callback.onNextCardDismissed()
        nextCard?.let { card ->
            if (card.visibility == View.VISIBLE) {
                card.animate().alpha(0f).setDuration(180).withEndAction {
                    card.visibility = View.GONE
                }.start()
            }
        }
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
