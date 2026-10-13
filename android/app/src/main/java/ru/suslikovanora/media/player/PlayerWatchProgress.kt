package ru.suslikovanora.media.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock

/**
 * Manages video playback position persistence across app restarts and library progress reporting.
 *
 * Persists positions in "positions" SharedPreferences (ms) and "watch" SharedPreferences
 * ("pos|length|timestamp") used by native library tiles.
 */
class PlayerWatchProgress(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun getCurrentUrl(): String
        fun getCurrentQueueIndex(): Int
        fun getQueueSize(): Int
    }

    companion object {
        const val EXTRA_FROM_START = "media_from_start"
        const val EXTRA_WATCH_KEYS = "media_watch_keys"
    }

    private val prefs by lazy { context.getSharedPreferences("positions", Context.MODE_PRIVATE) }
    private val watchPrefs by lazy { context.getSharedPreferences("watch", Context.MODE_PRIVATE) }

    private var watchKeys: List<String> = emptyList()
    private var lastPersistAt = 0L

    fun setWatchKeys(keys: List<String>) {
        watchKeys = keys
    }

    fun readIntent(intent: Intent?) {
        watchKeys = intent?.getStringArrayListExtra(EXTRA_WATCH_KEYS).orEmpty()
    }

    /** "magnetId|path" of what is playing (the playlist starts with current episode, like watchKeys). */
    fun currentWatchKey(): String? {
        val qSize = callback.getQueueSize()
        val qIndex = callback.getCurrentQueueIndex()
        return if (qSize > 0 && qIndex >= 0) watchKeys.getOrNull(qIndex) else watchKeys.firstOrNull()
    }

    /** Position persistence key (keyed by host+path, query/token ignored). */
    fun positionKey(url: String = callback.getCurrentUrl()): String? {
        currentWatchKey()?.let { return "w:$it" }
        val u = Uri.parse(url.ifBlank { return null })
        val key = u.host.orEmpty() + u.path.orEmpty()
        return if (key.isBlank()) null else "pos:$key"
    }

    fun savedPosition(url: String = callback.getCurrentUrl()): Long =
        positionKey(url)?.let { prefs.getLong(it, 0L) } ?: 0L

    /** Where a film opened from library starts: remembered place, or 0 when «С начала» was chosen. */
    fun startPosition(intent: Intent, url: String = callback.getCurrentUrl()): Long =
        if (intent.getBooleanExtra(EXTRA_FROM_START, false)) 0L else savedPosition(url)

    fun persistPosition(time: Long, length: Long, isEnded: Boolean, url: String = callback.getCurrentUrl()) {
        val key = positionKey(url) ?: return
        val nearEnd = length > 0L && time > length - 15_000L
        if (isEnded || nearEnd || time < 5_000L) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putLong(key, time).apply()
        }
        // Progress for library tiles ("продолжить с …", watched bar). Watched to the end = pos == len.
        currentWatchKey()?.let { wk ->
            if (time < 5_000L && !nearEnd) return@let
            val pos = if (nearEnd || (isEnded && length > 0L)) length else time
            watchPrefs.edit().putString(wk, "$pos|${length.coerceAtLeast(0L)}|${System.currentTimeMillis()}").apply()
        }
    }

    fun maybePersist(time: Long, length: Long, isEnded: Boolean, url: String = callback.getCurrentUrl()) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPersistAt < 10_000L) return
        lastPersistAt = now
        persistPosition(time, length, isEnded, url)
    }

    fun clearPosition(url: String = callback.getCurrentUrl()) {
        positionKey(url)?.let { prefs.edit().remove(it).apply() }
    }
}
