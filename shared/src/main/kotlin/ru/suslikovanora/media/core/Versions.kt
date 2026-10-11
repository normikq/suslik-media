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
 * «Канал обновлений» in the settings: what to tell after switching (or checking) the channel, the same in both apps.
 * A version older than the installed one cannot be put over it (Android refuses a lower version, the Windows
 * installer refuses a downgrade), so that case only explains when the release channel will catch up.
 */
object UpdateChannel {
    enum class Verdict { Offer, Installed, Older, Nothing }

    fun verdict(remote: String?, installed: String): Verdict = when {
        remote.isNullOrBlank() -> Verdict.Nothing
        Versions.isNewer(remote, installed) -> Verdict.Offer
        Versions.compare(remote, installed) == 0 -> Verdict.Installed
        else -> Verdict.Older
    }

    fun message(v: Verdict, remote: String?, installed: String, beta: Boolean): String = when (v) {
        Verdict.Offer -> "Доступна версия $remote" + (if (beta) " (бета)" else "") + "."
        Verdict.Installed -> "Установлена актуальная " + (if (beta) "бета-версия" else "версия") + " $installed."
        Verdict.Older -> if (beta) "Установлена $installed — она новее последней беты ($remote)."
            else "Сейчас установлена бета $installed, а последний релиз — $remote (он старше). Поставить старую версию " +
                "поверх новой система не даёт: обновление по релизному каналу придёт само, как только выйдет версия новее $installed."
        Verdict.Nothing -> "Не удалось проверить обновления — попробуйте позже."
    }
}
