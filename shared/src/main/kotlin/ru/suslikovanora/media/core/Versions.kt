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

/**
 * «Канал обновлений», the same rule in both apps. Beta numbers are not tied to release numbers: on the release
 * channel the current release always wins — any other installed version (a beta, even with a higher number) is
 * replaced by it. On the beta channel only a newer version is offered.
 */
object UpdateChannel {
    enum class Verdict { Offer, Replace, Installed, Older, Nothing }

    fun verdict(remote: String?, installed: String, release: Boolean): Verdict = when {
        remote.isNullOrBlank() -> Verdict.Nothing
        Versions.isNewer(remote, installed) -> Verdict.Offer
        Versions.compare(remote, installed) == 0 -> Verdict.Installed
        release -> Verdict.Replace
        else -> Verdict.Older
    }

    /** Should the update be offered at all (start-up check, the header button)? */
    fun offers(v: Verdict) = v == Verdict.Offer || v == Verdict.Replace

    fun message(v: Verdict, remote: String?, installed: String, beta: Boolean): String = when (v) {
        Verdict.Offer -> "Доступна версия $remote" + (if (beta) " (бета)" else "") + "."
        Verdict.Replace -> "Релиз $remote заменит установленную бету $installed."
        Verdict.Installed -> "Установлена актуальная " + (if (beta) "бета-версия" else "версия") + " $installed."
        Verdict.Older -> "Установлена $installed — она новее последней беты ($remote)."
        Verdict.Nothing -> "Не удалось проверить обновления — попробуйте позже."
    }
}
