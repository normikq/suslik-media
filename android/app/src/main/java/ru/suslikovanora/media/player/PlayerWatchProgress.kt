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
        fun getActiveVariantKey(): String? = null
        fun getCanonicalId(): String? = null
    }

    companion object {
    }

    private val prefs by lazy { context.getSharedPreferences("positions", Context.MODE_PRIVATE) }
    private val watchPrefs by lazy { context.getSharedPreferences("watch", Context.MODE_PRIVATE) }

    private var watchKeys: List<String> = emptyList()
    private var lastPersistAt = 0L

    /** The playlist's watch keys, in its order. */
    val keys: List<String> get() = watchKeys

    fun setWatchKeys(keys: List<String>) {
        watchKeys = keys
    }

    fun readIntent(intent: Intent?) {
        watchKeys = intent?.getStringArrayListExtra(PlayerExtras.WATCH_KEYS).orEmpty()
        startKey = intent?.getStringExtra(PlayerExtras.WATCH_KEY)
    }

    /** The episode the player opened with: what plays until the season's playlist is loaded. */
    private var startKey: String? = null

    /** "magnetId|path" of what is playing (watchKeys go in the playlist's order: the whole season). */
    fun currentWatchKey(): String? {
        val qSize = callback.getQueueSize()
        val qIndex = callback.getCurrentQueueIndex()
        return if (qSize > 0 && qIndex >= 0) watchKeys.getOrNull(qIndex) else startKey ?: watchKeys.firstOrNull()
    }

    /** Position persistence key (keyed by host+path, query/token ignored). */
    fun positionKey(url: String = callback.getCurrentUrl()): String? {
        currentWatchKey()?.let { return "w:$it" }
        val u = Uri.parse(url.ifBlank { return null })
        val key = u.host.orEmpty() + u.path.orEmpty()
        return if (key.isBlank()) null else "pos:$key"
    }

    fun savedPosition(url: String = callback.getCurrentUrl()): Long {
        positionKey(url)?.let { prefs.getLong(it, 0L) }?.takeIf { it > 0L }?.let { return it }
        callback.getActiveVariantKey()?.takeIf { it.isNotBlank() }?.let { prefs.getLong("w:$it", 0L) }?.takeIf { it > 0L }?.let { return it }
        val fallback = ru.suslikovanora.media.library.CloudSync.watchFor(context, currentWatchKey(), callback.getCanonicalId())
        if (fallback != null && fallback.pos >= 5_000L && !(fallback.len > 0L && fallback.pos > fallback.len - 15_000L)) {
            return fallback.pos
        }
        return 0L
    }

    /** Where a film opened from library starts: remembered place, or 0 when «С начала» was chosen. */
    fun startPosition(intent: Intent, url: String = callback.getCurrentUrl()): Long =
        intent.getLongExtra(PlayerExtras.START_MS, -1L).takeIf { it >= 0L }
            ?: if (intent.getBooleanExtra(PlayerExtras.FROM_START, false)) 0L else savedPosition(url)

    fun persistPosition(time: Long, length: Long, isEnded: Boolean, url: String = callback.getCurrentUrl()) {
        val key = positionKey(url) ?: return
        val activeVarKey = callback.getActiveVariantKey()?.takeIf { it.isNotBlank() && '|' in it }
        val nearEnd = length > 0L && time > length - 15_000L
        if (isEnded || nearEnd || time < 5_000L) {
            prefs.edit().apply {
                remove(key)
                if (activeVarKey != null) remove("w:$activeVarKey")
            }.apply()
        } else {
            prefs.edit().apply {
                putLong(key, time)
                if (activeVarKey != null) putLong("w:$activeVarKey", time)
            }.apply()
        }
        // Progress for library tiles ("продолжить с …", watched bar). Watched to the end = pos == len.
        currentWatchKey()?.let { wk ->
            if (time < 5_000L && !nearEnd) return@let
            val pos = if (nearEnd || (isEnded && length > 0L)) length else time
            val len = length.coerceAtLeast(0L)
            val at = System.currentTimeMillis()
            val cid = callback.getCanonicalId()?.takeIf { it.isNotBlank() }
            val suffix = if (cid != null) "|$cid" else ""
            watchPrefs.edit().apply {
                putString(wk, "$pos|$len|$at$suffix")
                if (activeVarKey != null && activeVarKey != wk) {
                    putString(activeVarKey, "$pos|$len|$at$suffix")
                }
            }.apply()
            if (activeVarKey != null && activeVarKey != wk) {
                val activeMag = activeVarKey.substringBefore('|')
                val activePath = activeVarKey.substringAfter('|')
                if (activeMag.isNotBlank() && activePath.isNotBlank()) {
                    context.getSharedPreferences("last_episode", Context.MODE_PRIVATE).edit().putString(activeMag, activePath).apply()
                }
            }
            ru.suslikovanora.media.library.CloudSync.noteWatch(wk, pos, len, at, cid)
            if (activeVarKey != null && activeVarKey != wk) {
                ru.suslikovanora.media.library.CloudSync.noteWatch(activeVarKey, pos, len, at, cid)
            }
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
