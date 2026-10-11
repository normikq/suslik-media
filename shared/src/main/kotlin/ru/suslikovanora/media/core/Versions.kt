package ru.suslikovanora.media.core

/** "1.9.5" style versions (any extra text ignored). */
object Versions {
    private fun parts(v: String) = Regex("""\d+""").findAll(v).map { it.value.toInt() }.toList().let { it + List(3) { 0 } }.take(3)

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    fun compare(a: String, b: String): Int = if (isNewer(a, b)) 1 else if (isNewer(b, a)) -1 else 0
}
