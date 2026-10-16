package ru.suslikovanora.media.core

import java.util.Locale

/** A film time as the players show it: "05:03", "1:05:03" (negative counts as 0). */
fun clockText(ms: Long): String {
    val t = ms.coerceAtLeast(0L) / 1000L
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
}
