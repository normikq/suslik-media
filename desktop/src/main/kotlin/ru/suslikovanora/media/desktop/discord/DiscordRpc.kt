package ru.suslikovanora.media.desktop.discord

import kotlinx.coroutines.*
import org.json.JSONObject
import ru.suslikovanora.media.desktop.PlayerLog
import ru.suslikovanora.media.desktop.Store
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Discord Rich Presence (RPC) over the local Windows named pipe (\\.\pipe\discord-ipc-0).
 * Displays current film/series title, episode, playback status and time counter in user's Discord profile.
 * Completely native, zero third-party C/C++ libraries or DLLs required.
 *
 * The Discord application ID comes from our server (`discord_client_id` in /api/session, see Library.checkSession) and is
 * kept between runs; `-Dsuslik.discordClientId` from the build is the fallback. Without an ID nothing is shown:
 * Discord refuses a handshake with an unknown application.
 */
object DiscordRpc {
    private val settings = Store.of("settings")

    val isEnabled: Boolean
        get() = settings.get("discord_rpc") != "0"

    fun setEnabled(enabled: Boolean) {
        settings.put("discord_rpc", if (enabled) "1" else "0")
        if (!enabled) stop()
    }

    /** Discord application ID (digits only), or null while unknown. */
    var clientId: String?
        get() = (settings.get("discord_client_id") ?: System.getProperty("suslik.discordClientId"))?.takeIf { it.matches(Regex("\\d{15,25}")) }
        set(value) {
            val v = value?.trim()?.takeIf { it.matches(Regex("\\d{15,25}")) }
            if (v == settings.get("discord_client_id")) return
            settings.put("discord_client_id", v)
            stop() // the next update reconnects under the new application
        }

    // All work with the pipe goes on this one thread, in order (so no locks). Reading the pipe has no timeout: if Discord
    // hangs, only this thread waits — before, every update took a new IO thread that stood behind the lock forever.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "discord-rpc").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var pipe: RandomAccessFile? = null
    private var connectedAs: String? = null
    private val scope = CoroutineScope(worker + SupervisorJob())
    @Volatile private var busySince = 0L
    private var lastUpdateJob: Job? = null
    private val pid = ProcessHandle.current().pid()
    @Volatile private var lastProblem: String? = null

    /** Writes a reason to the log once (not on every pause / seek). */
    private fun problem(text: String) {
        if (text != lastProblem) { lastProblem = text; PlayerLog.write("[discord] $text") }
    }

    private fun ensureConnected(id: String): Boolean {
        if (pipe != null && connectedAs == id) return true
        closePipe()

        for (i in 0..9) {
            val pipePath = "\\\\.\\pipe\\discord-ipc-$i"
            try {
                if (!File(pipePath).exists()) continue
                pipe = RandomAccessFile(pipePath, "rw")

                // Opcode 0: Handshake
                writeFrame(0, JSONObject().put("v", 1).put("client_id", id))
                val (op, res) = readFrame() ?: (-1 to null)
                if (op == 1 && res?.optString("evt") == "READY") {
                    connectedAs = id; lastProblem = null
                    PlayerLog.write("[discord] connected to $pipePath")
                    return true
                }
                // Opcode 2 = CLOSE: e.g. «Invalid Client ID»
                problem("handshake refused on $pipePath: op=$op ${res?.optString("message") ?: res}")
                closePipe()
            } catch (e: Exception) {
                closePipe()
            }
        }
        problem("Discord is not running (no discord-ipc pipe answered)")
        return false
    }

    private fun writeFrame(opcode: Int, json: JSONObject) {
        val p = pipe ?: return
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(opcode)
        header.putInt(payload.size)
        p.write(header.array())
        p.write(payload)
    }

    private fun readFrame(): Pair<Int, JSONObject?>? {
        val p = pipe ?: return null
        val header = ByteArray(8)
        p.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val opcode = buf.int
        val len = buf.int
        // A broken length leaves its payload in the pipe: every next read would take those bytes for a header. Reconnect
        if (len !in 0..1_000_000) { problem("bad frame length $len: reconnecting"); closePipe(); return null }
        val payload = ByteArray(len)
        p.readFully(payload)
        return opcode to runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
    }

    private fun closePipe() {
        connectedAs = null
        try {
            pipe?.close()
        } catch (_: Exception) {}
        pipe = null
    }

    /** Sends a command and reads Discord's answer (unread answers would pile up in the pipe; an error says why). */
    private fun send(activity: Any): Boolean {
        val frame = JSONObject()
        frame.put("cmd", "SET_ACTIVITY")
        frame.put("args", JSONObject().put("pid", pid).put("activity", activity))
        frame.put("nonce", UUID.randomUUID().toString())
        writeFrame(1, frame)
        val (op, res) = readFrame() ?: return false
        if (op == 2) { problem("closed by Discord: ${res?.optString("message")}"); closePipe(); return false }
        if (res?.optString("evt") == "ERROR") { problem("SET_ACTIVITY error: ${res.optJSONObject("data")?.optString("message")}"); return false }
        lastProblem = null
        return true
    }

    fun update(
        title: String,
        subtitle: String = "",
        playing: Boolean = true,
        timeMs: Long = 0L,
        lengthMs: Long = 0L,
        posterUrl: String? = null,
        activityName: String = "Suslik Media"
    ) {
        if (!isEnabled) {
            stop()
            return
        }
        val id = clientId ?: run { problem("no Discord application id yet (the server sends it after sign-in)"); return }

        val since = busySince
        if (since > 0L && System.currentTimeMillis() - since > 10_000L) problem("Discord does not answer (the pipe hangs): activity is not updated")
        lastUpdateJob?.cancel() // a waiting update is replaced by this one
        lastUpdateJob = scope.launch {
            busySince = System.currentTimeMillis()
            try {
                if (!ensureConnected(id)) return@launch

                val activity = JSONObject()
                activity.put("type", 3) // «Смотрит»
                activity.put("name", activityName)
                activity.put("details", title.take(128).padEnd(2))
                if (subtitle.isNotBlank()) {
                    activity.put("state", subtitle.take(128).padEnd(2))
                } else if (!playing) {
                    activity.put("state", "Пауза")
                }

                val nowMs = System.currentTimeMillis()
                if (playing && lengthMs > 0L) {
                    // Start and end: Discord draws the progress bar of the film
                    activity.put("timestamps", JSONObject()
                        .put("start", (nowMs - timeMs) / 1000L)
                        .put("end", (nowMs + (lengthMs - timeMs).coerceAtLeast(0L)) / 1000L))
                }

                // Pictures with these names are uploaded in the Developer Portal (Rich Presence → Art Assets);
                // without them Discord just shows no picture.
                val assets = JSONObject()
                if (!posterUrl.isNullOrBlank()) {
                    assets.put("large_image", posterUrl)
                    assets.put("large_text", if (subtitle.isNotBlank() && subtitle != "Suslik Media") "$subtitle: $title".take(128) else title.take(128))
                } else {
                    assets.put("large_image", "brand-mark")
                    assets.put("large_text", "Suslik Media")
                }
                assets.put("small_image", if (playing) "play" else "pause")
                assets.put("small_text", if (playing) "Воспроизведение" else "Пауза")
                activity.put("assets", assets)

                // An older Discord takes only «Играет»: then without the «Смотрит» type
                if (!send(activity) && pipe != null) { activity.remove("type"); send(activity) }
            } catch (e: Exception) {
                problem("update failed: ${e.message}")
                closePipe()
            } finally {
                busySince = 0L
            }
        }
    }

    fun stop() {
        lastUpdateJob?.cancel()
        scope.launch {
            try {
                if (pipe != null) send(JSONObject.NULL)
            } catch (_: Exception) {}
            closePipe()
        }
    }
}
