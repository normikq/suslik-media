package ru.suslikovanora.media.core

import org.json.JSONObject
import ru.suslikovanora.media.core.auth.DeviceAuth
import ru.suslikovanora.media.core.auth.DevicePoll
import ru.suslikovanora.media.core.auth.SessionInfo
import ru.suslikovanora.media.core.auth.Shelf
import ru.suslikovanora.media.core.auth.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTest {
    @Test
    fun parsesSession() {
        val s = SessionInfo.parse(JSONObject("""
            {"authenticated":true,"discord_client_id":"123",
             "user":{"id":"9","name":null,"username":"anya","avatar":"https://cdn/a.png","role":"admin"}}
        """.trimIndent()))
        assertTrue(s.authenticated)
        assertEquals("123", s.discordClientId)
        val u = s.user!!
        assertEquals("anya", u.name)  // name: null → username
        assertTrue(u.isAdmin)
        assertFalse(u.isShared)

        val shared = SessionInfo.parse(JSONObject("""{"authenticated":true,"discord_client_id":"","user":{"id":"shared","role":"admin"}}"""))
        assertTrue(shared.user!!.isShared)
        assertNull(shared.discordClientId)

        val out = SessionInfo.parse(JSONObject("""{"authenticated":false,"user":{"id":"9"}}"""))
        assertNull(out.user)
        assertNull(UserProfile.parse(null))
    }

    @Test
    fun deviceFlow() {
        val body = DeviceAuth.startBody("Suslik Media Android", "https://site/media/")
        assertEquals("https://site/media", body.getString("site_url"))

        val p = DeviceAuth.parsePairing(JSONObject("""{"code":"secret","user_code":"ABC123","verification_url":"https://s/link?code=ABC123"}"""), "https://x/")
        assertEquals("https://s/link?code=ABC123", p.url)
        // Старый сервер без ссылки: строим свою
        val old = DeviceAuth.parsePairing(JSONObject("""{"code":"secret","user_code":"ABC123"}"""), "https://x/media/")
        assertEquals("https://x/media/link?code=ABC123", old.url)
        assertEquals("secret", DeviceAuth.pollBody(old).getString("code"))

        val ok = DeviceAuth.parsePoll(JSONObject("""{"status":"authorized","device_token":"t","user":{"id":"9","name":"A","role":"user"}}"""))
        assertIs<DevicePoll.Authorized>(ok)
        assertEquals("t", ok.token)
        assertEquals("A", ok.user!!.name)
        assertEquals(DevicePoll.Pending, DeviceAuth.parsePoll(JSONObject("""{"status":"pending"}""")))
        assertEquals(DevicePoll.Expired, DeviceAuth.parsePoll(JSONObject("""{"status":"expired"}""")))
        assertEquals(DevicePoll.Expired, DeviceAuth.pollFailed(410))
        assertEquals(DevicePoll.Expired, DeviceAuth.pollFailed(404))
        assertEquals(DevicePoll.Pending, DeviceAuth.pollFailed(502))
    }

    @Test
    fun credentialsOnlyToOwnServer() {
        val base = "https://site.example"
        assertTrue(DeviceAuth.isOwnServer(base, base))
        assertTrue(DeviceAuth.isOwnServer("$base/api/session", base))
        assertFalse(DeviceAuth.isOwnServer("https://site.example.evil.com/x", base))
        assertFalse(DeviceAuth.isOwnServer("https://cdn.discordapp.com/avatars/1.png", base))
    }

    @Test
    fun shelfBody() {
        val b = Shelf.body(42L, true)
        assertEquals(42L, b.getLong("id"))
        assertTrue(b.getBoolean("mine"))
    }
}
