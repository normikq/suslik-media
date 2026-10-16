package ru.suslikovanora.media.desktop

import org.json.JSONObject
import java.util.Locale

/**
 * Строки интерфейса лежат в `resources/strings/<язык>.json` (плоские ключи, подстановки `{имя}`).
 * Чтобы добавить перевод, положите рядом `en.json` с теми же ключами; недостающие ключи берутся из `ru.json`.
 * Язык: `-Dsuslik.lang=en` или язык системы; если файла нет — русский.
 */
object Strings {
    private const val BASE = "ru"

    private fun load(lang: String): JSONObject? =
        Strings::class.java.getResourceAsStream("/strings/$lang.json")?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }

    private val base: JSONObject by lazy { load(BASE) ?: JSONObject() }
    private val current: JSONObject? by lazy {
        val lang = (System.getProperty("suslik.lang") ?: Locale.getDefault().language).lowercase()
        if (lang == BASE) null else runCatching { load(lang) }.getOrNull()
    }

    fun get(key: String, params: Array<out Pair<String, Any?>>): String {
        var s = current?.optString(key, "")?.takeIf { it.isNotEmpty() } ?: base.optString(key, key)
        for ((name, value) in params) s = s.replace("{$name}", value.toString())
        return s
    }
}

/** Текст интерфейса по ключу: `tr("player.pause")`, `tr("player.next", "title" to name)` ← «Следующая: {title}». */
fun tr(key: String, vararg params: Pair<String, Any?>): String = Strings.get(key, params)

/** Число со знаком «+» для положительных (задержка звука и субтитров). */
fun signed(n: Long): String = if (n > 0) "+$n" else n.toString()
