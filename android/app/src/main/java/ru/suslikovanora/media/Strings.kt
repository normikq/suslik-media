package ru.suslikovanora.media

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Строки интерфейса лежат в `assets/strings/<язык>.json` (плоские ключи, подстановки `{имя}`).
 * Чтобы добавить перевод, положите рядом `en.json` с теми же ключами; недостающие ключи берутся из `ru.json`.
 * Язык берётся из системы; если файла нет — русский. Загрузка — в `SuslikApp.onCreate`.
 */
object Strings {
    private const val BASE = "ru"
    @Volatile private var base = JSONObject()
    @Volatile private var current: JSONObject? = null

    private fun load(ctx: Context, lang: String): JSONObject? = runCatching {
        ctx.assets.open("strings/$lang.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrNull()

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        base = load(app, BASE) ?: JSONObject()
        val lang = Locale.getDefault().language.lowercase()
        current = if (lang == BASE) null else load(app, lang)
    }

    fun get(key: String, params: Array<out Pair<String, Any?>>): String {
        var s = current?.optString(key, "")?.takeIf { it.isNotEmpty() } ?: base.optString(key, key)
        for ((name, value) in params) s = s.replace("{$name}", value.toString())
        return s
    }
}

/** Текст интерфейса по ключу: `tr("player.pause")`, `tr("player.next", "title" to name)` ← «Следующая: {title}». */
fun tr(key: String, vararg params: Pair<String, Any?>): String = Strings.get(key, params)
