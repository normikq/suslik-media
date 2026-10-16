package ru.suslikovanora.media

import ru.suslikovanora.media.core.*

import android.content.Context
import ru.suslikovanora.media.library.Posters
import ru.suslikovanora.media.net.Api
import java.io.File

/**
 * «Очистить кэш» in the settings: what the app keeps that can be downloaded again, and clearing it. Watch progress,
 * the sign-in, the settings and the saved library list are data, not cache — they stay.
 *
 *  - reserve: the film reserve — VLC's (BurstProxy, cache/stream) and Media3's (cache/media3);
 *  - posters: Coil's picture cache (cache/image_cache);
 *  - other: everything else in the cache folder — answers of the server (HTTP cache), a downloaded update,
 *    shared journal archives.
 */
object Storage {
    data class Sizes(val reserve: Long, val posters: Long, val other: Long)

    private fun size(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun reserveDir(ctx: Context) = File(ctx.cacheDir, "stream")
    private fun postersDir(ctx: Context) = File(ctx.cacheDir, "image_cache")

    /** Blocking (walks the folders): call off the main thread. */
    fun sizes(ctx: Context): Sizes {
        val reserve = size(reserveDir(ctx)) + size(ru.suslikovanora.media.player.Media3Cache.directory(ctx))
        val posters = size(postersDir(ctx))
        return Sizes(reserve, posters, (size(ctx.cacheDir) - reserve - posters).coerceAtLeast(0L))
    }

    /** Blocking: call off the main thread. A film that is open in the player keeps its reserve. */
    fun clear(ctx: Context) {
        BurstProxy.dropRetired()
        // No player in this process (or none open): the folder holds only leftovers — all of it can go.
        if (!BurstProxy.initialized || !PlayerActivity.alive) reserveDir(ctx).listFiles()?.forEach { it.deleteRecursively() }
        if (!PlayerActivity.alive) runCatching { ru.suslikovanora.media.player.Media3Cache.clearAll(ctx) }
        runCatching { Posters.loader(ctx).let { it.diskCache?.clear(); it.memoryCache?.clear() } }
        runCatching { Api.client.cache?.evictAll() }
        ctx.cacheDir.listFiles()?.forEach { f ->
            if (f.name != "stream" && f.name != "media3" && f.name != "image_cache" && f.name != "http") runCatching { f.deleteRecursively() }
        }
        AppLog.write("storage: cache cleared")
    }
}
