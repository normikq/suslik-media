package ru.suslikovanora.media.player

import android.content.Context
import java.io.File

/**
 * Media3 no longer keeps its own reserve: it reads through BurstProxy like VLC (one reserve, one limit, adapted to the
 * network). Only the folder of the first 2.2.1 builds (`cache/media3`) is left to remove.
 */
object Media3Cache {
    fun directory(ctx: Context) = File(ctx.cacheDir, "media3")

    fun diskBytes(ctx: Context): Long = directory(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Blocking: off the main thread. */
    fun clearAll(ctx: Context) {
        directory(ctx).takeIf { it.exists() }?.let { runCatching { it.deleteRecursively() } }
    }
}
