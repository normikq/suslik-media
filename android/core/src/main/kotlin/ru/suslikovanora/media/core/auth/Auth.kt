package ru.suslikovanora.media.core.auth

import org.json.JSONObject

/*
 * Вход (общий пароль, Discord, код устройства) и «Мои фильмы»: протокол сервера (`website/src/routes/auth.js`, `/api/shelf`).
 * Без сети: приложения сами шлют запросы и отдают сюда ответы.
 */

/** Пользователь сервера. Вход по общему паролю — [isShared] (id "shared"). */
data class UserProfile(val id: String, val name: String, val role: String, val avatarUrl: String? = null) {
    val isAdmin: Boolean get() = role == "admin"
    val isShared: Boolean get() = id == SHARED_ID

    companion object {
        const val SHARED_ID = "shared"

        /** `user` из `/api/session` или опроса кода устройства. Нет пользователя — null. */
        fun parse(u: JSONObject?): UserProfile? {
            if (u == null) return null
            fun str(key: String) = if (u.isNull(key)) "" else u.optString(key)
            val id = str("id")
            return UserProfile(
                id = id,
                name = str("name").ifBlank { str("username") },
                role = str("role"),
                avatarUrl = str("avatar").takeIf { it.isNotBlank() }
            )
        }
    }
}

/** Ответ `/api/session`. [discordClientId] — приложение Discord нашего сервера (активность «Смотрит»). */
data class SessionInfo(val authenticated: Boolean, val user: UserProfile?, val discordClientId: String?) {
    companion object {
        const val PATH = "/api/session"

        fun parse(res: JSONObject): SessionInfo {
            val auth = res.optBoolean("authenticated")
            return SessionInfo(
                authenticated = auth,
                user = if (auth) UserProfile.parse(res.optJSONObject("user")) else null,
                discordClientId = res.optString("discord_client_id").ifBlank { null }
            )
        }
    }
}

/** Код устройства: [code] — для опроса (секрет устройства), [userCode] — человеку на экран, [url] — где его подтвердить. */
data class DevicePairing(val code: String, val userCode: String, val url: String)

sealed class DevicePoll {
    /** Подтверждено: [token] шлём заголовком `X-Device-Token` с каждым запросом (живёт дольше cookie). */
    data class Authorized(val token: String?, val user: UserProfile?) : DevicePoll()
    data object Expired : DevicePoll()
    data object Pending : DevicePoll()
}

object DeviceAuth {
    const val START = "/api/auth/device"
    const val POLL = "/api/auth/device/poll"
    const val TOKEN_HEADER = "X-Device-Token"

    /** [name] — как устройство видно на сайте («Suslik Media Windows»); [siteUrl] — адрес сайта без `/` в конце. */
    fun startBody(name: String, siteUrl: String): JSONObject =
        JSONObject().put("name", name).put("site_url", siteUrl.trimEnd('/'))

    /** Ответ [START]. [site] — адрес сайта со `/` в конце: запасная ссылка для старого сервера. */
    fun parsePairing(res: JSONObject, site: String): DevicePairing {
        val userCode = res.getString("user_code")
        val url = res.optString("verification_url").takeIf { it.contains("code=") } ?: "${site}link?code=$userCode"
        return DevicePairing(res.getString("code"), userCode, url)
    }

    fun pollBody(p: DevicePairing): JSONObject = JSONObject().put("code", p.code)

    /** Ответ [POLL] с кодом 2xx. */
    fun parsePoll(res: JSONObject): DevicePoll = when (res.optString("status")) {
        "authorized" -> DevicePoll.Authorized(
            res.optString("device_token").ifBlank { null },
            UserProfile.parse(res.optJSONObject("user"))
        )
        "expired" -> DevicePoll.Expired
        else -> DevicePoll.Pending
    }

    /** Ошибка HTTP при опросе: 410 / 404 — код истёк (10 мин), иначе пробуем ещё. */
    fun pollFailed(httpCode: Int): DevicePoll = if (httpCode == 410 || httpCode == 404) DevicePoll.Expired else DevicePoll.Pending

    /**
     * Сессию, токен устройства и бета-код шлём только своему серверу [base] (`https://host[/путь]`):
     * аватары идут с CDN Discord, постеры — с чужих сайтов.
     */
    fun isOwnServer(url: String, base: String): Boolean = ru.suslikovanora.media.core.SiteAddress.isOwn(url, base)
}

/** «Мои фильмы» (`services/shelf.js`): флаг `mine` у раздачи. */
object Shelf {
    const val PATH = "/api/shelf"
    fun body(magnetId: Long, mine: Boolean): JSONObject = JSONObject().put("id", magnetId).put("mine", mine)
}
