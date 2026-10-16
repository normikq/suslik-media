package ru.suslikovanora.media.player

import android.content.Context
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.BurstProxy
import java.io.File
import java.util.TreeSet

/**
 * The same film has a different signed address (…?link=…&exp=…&sig=…) each time it is opened: its identity is the path
 * plus the `link` parameter — the same rule as `BurstProxy.keyOf` (private there; BurstProxy itself stays untouched).
 */
object MediaKeys {
    fun keyOf(url: String): String {
        val q = url.substringAfter('?', "")
        val link = q.split('&').firstOrNull { it.startsWith("link=") } ?: return url
        return url.substringBefore('?') + "?" + link
    }
}

/**
 * Media3's reserve on disk (`cache/media3`): every byte ExoPlayer reads from the mirror is kept, so a seek back,
 * a reconnect and «Продолжить» read from the phone instead of the network.
 *
 * One limit for both engines: «Экономная загрузка» (the BurstProxy setting) minus what BurstProxy's rings take right now.
 * A file is downloaded by one engine only, and opening a file in one engine clears the other engine's reserve
 * ([PlayerActivity] does that), so in practice the engine in use gets the whole limit. 0 = no disk reserve for Media3.
 */
@OptIn(UnstableApi::class)
object Media3Cache {
    private var cache: SimpleCache? = null
    private var dir: File? = null
    private val evictor = SharedLimitEvictor()

    val keyFactory = CacheKeyFactory { spec -> spec.key ?: MediaKeys.keyOf(spec.uri.toString()) }

    fun directory(ctx: Context) = File(ctx.cacheDir, "media3")

    /** The cache, created on first use (one [SimpleCache] per folder per process). */
    @Synchronized
    fun get(ctx: Context): SimpleCache {
        cache?.let { return it }
        val d = directory(ctx)
        dir = d
        return SimpleCache(d, evictor, StandaloneDatabaseProvider(ctx.applicationContext)).also { cache = it }
    }

    /** Media3 keeps a reserve on disk (the limit is above 0). */
    val enabled: Boolean get() = evictor.maxBytes > 0

    fun usedMb(): Double = (cache?.cacheSpace ?: 0L) / 1048576.0

    /** Sets the shared limit before a film is opened: [totalBytes] for both engines, BurstProxy's part subtracted. */
    fun setLimit(ctx: Context, totalBytes: Long) {
        val burst = runCatching { BurstProxy.diskBytes(File(ctx.cacheDir, "stream")) }.getOrDefault(0L)
        evictor.maxBytes = (totalBytes - burst).coerceAtLeast(0L)
        cache?.let { c -> runCatching { evictor.trim(c) } }
    }

    /** Only these files' data stays (the film, its voice-overs and subtitles); the rest is removed now. */
    fun keepOnly(ctx: Context, urls: Collection<String>) {
        val c = if (urls.isEmpty()) cache ?: return else get(ctx)
        val keep = urls.map { MediaKeys.keyOf(it) }.toSet()
        var freed = 0L
        for (key in runCatching { c.keys.toList() }.getOrDefault(emptyList())) if (key !in keep) {
            freed += runCatching { c.getCachedBytes(key, 0, C.LENGTH_UNSET.toLong()) }.getOrDefault(0L)
            runCatching { c.removeResource(key) }
        }
        if (freed > 0) AppLog.write("[media3] reserve: %.1f MB of other files removed".format(freed / 1048576.0))
    }

    /** One file's data (it is going to VLC: its reserve is BurstProxy's now). */
    fun remove(url: String) {
        val c = cache ?: return
        runCatching { c.removeResource(MediaKeys.keyOf(url)) }
    }

    /** Bytes on disk (also before the cache is opened in this process). */
    fun diskBytes(ctx: Context): Long =
        cache?.cacheSpace ?: directory(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** «Очистить кэш» with no film open. Blocking: off the main thread. */
    fun clearAll(ctx: Context) {
        val c = cache
        if (c != null) { for (key in runCatching { c.keys.toList() }.getOrDefault(emptyList())) runCatching { c.removeResource(key) } }
        else directory(ctx).listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }

    fun aheadMbFor(url: String, positionBytes: Long): Double? {
        val c = cache ?: return null
        return runCatching { c.getCachedBytes(MediaKeys.keyOf(url), positionBytes, C.LENGTH_UNSET.toLong()) / 1048576.0 }.getOrNull()
    }

    /** Least recently used spans go first, as in Media3's `LeastRecentlyUsedCacheEvictor`, but the limit can change. */
    private class SharedLimitEvictor : CacheEvictor {
        @Volatile var maxBytes = 96L shl 20
        private val spans = TreeSet<CacheSpan> { a, b ->
            val d = a.lastTouchTimestamp - b.lastTouchTimestamp
            if (d == 0L) a.compareTo(b) else if (d < 0) -1 else 1
        }
        private var size = 0L

        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() {}
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
        }
        override fun onSpanAdded(cache: Cache, span: CacheSpan) { spans.add(span); size += span.length; evict(cache, 0) }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { spans.remove(span); size -= span.length }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
        }
        fun trim(cache: Cache) = synchronized(cache) { evict(cache, 0) }
        private fun evict(cache: Cache, required: Long) {
            while (size + required > maxBytes && spans.isNotEmpty()) cache.removeSpan(spans.first())
        }
    }
}
