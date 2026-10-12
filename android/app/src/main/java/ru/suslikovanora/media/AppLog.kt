package ru.suslikovanora.media

import android.content.Context
import ru.suslikovanora.media.core.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Player journal on the phone (files/player.log, ~1 MB, the previous part kept as player.old.log): what the player
 * did — open, seek, pause, buffering, reconnects, recoveries, the reserve's requests. Sent with crash reports.
 */
object AppLog {
    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    // Debug build: a much bigger journal in the app's folder on the shared storage, so a computer can take it with
    // `adb pull /sdcard/Android/data/ru.suslikovanora.media.diag/files/diag`.
    private val limit = if (BuildConfig.DIAG) 20_000_000L else 1_000_000L

    fun init(ctx: Context) {
        if (dir != null) return
        val c = ctx.applicationContext
        dir = if (BuildConfig.DIAG) runCatching { c.getExternalFilesDir("diag") }.getOrNull() ?: c.filesDir else c.filesDir
    }
    val folder: File? get() = dir
    val file: File? get() = dir?.let { File(it, "player.log") }

    fun write(msg: String) {
        val f = file ?: return
        val line = "${fmt.format(Date())}  $msg\n"
        runCatching {
            writer.execute {
                runCatching {
                    if (f.length() > limit) { val old = File(f.parentFile, "player.old.log"); old.delete(); f.renameTo(old) }
                    f.appendText(line)
                }
            }
        }
    }

    /** Both parts (previous + current), newest last, at most [max] characters — for «Отправить журнал». */
    fun full(max: Int = 850_000): String = runCatching {
        val f = file!!
        val cur = tailOf(f, max.toLong())
        val old = if (cur.length < max) tailOf(File(f.parentFile, "player.old.log"), (max - cur.length).toLong()) else ""
        val t = old + cur
        if (t.length > max) "…" + t.takeLast(max) else t.ifEmpty { "(журнал пуст)" }
    }.getOrDefault("(no journal)")

    fun tail(max: Int = 60_000): String = runCatching {
        val t = tailOf(file!!, max.toLong()); if (t.length >= max) "…$t" else t
    }.getOrDefault("(no journal)")

    /** The last [bytes] of a file, read from the end (the debug journal is tens of MB: never read it whole). */
    private fun tailOf(f: File, bytes: Long): String {
        if (!f.exists() || bytes <= 0) return ""
        java.io.RandomAccessFile(f, "r").use { r ->
            val from = maxOf(0L, r.length() - bytes)
            val buf = ByteArray((r.length() - from).toInt())
            r.seek(from); r.readFully(buf)
            val t = String(buf, Charsets.UTF_8)
            return if (from > 0) t.substringAfter('\n') else t // drop the cut first line
        }
    }
}

/**
 * Reports that send themselves to the server (POST /api/app/report):
 *  - an uncaught exception: written to a file at once (the app is going down), sent at the next start;
 *  - the player screen ended without closing normally (a native VLC crash cannot be caught): next start;
 *  - the playback supervisor had to reopen a film: the journal around it (at most once per 10 minutes).
 */
object CrashReports {
    @Volatile private var app: Context? = null
    @Volatile private var lastRecovery = 0L

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        AppLog.init(ctx)
        Diag.init(ctx)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(ctx.applicationContext.filesDir, "crash.txt").writeText(
                    "Thread ${t.name}\n${e.stackTraceToString()}\n\n--- journal ---\n${AppLog.tail()}")
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Main screen started: anything left from last time goes to the server. */
    fun sendPending() {
        val ctx = app ?: return
        val crash = File(ctx.filesDir, "crash.txt")
        if (crash.exists()) { val text = runCatching { crash.readText() }.getOrDefault(""); crash.delete(); send("exception", text) }
        val prefs = ctx.getSharedPreferences("player_state", Context.MODE_PRIVATE)
        if (prefs.getBoolean("running", false) && !PlayerActivity.alive) {
            prefs.edit().putBoolean("running", false).apply()
            send("player-ended", "The player screen ended without closing normally (crash of VLC, or the system closed the app).\n\n--- journal ---\n${AppLog.tail()}")
        }
    }

    /** PlayerActivity: open / closed normally. */
    fun playerRunning(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences("player_state", Context.MODE_PRIVATE).edit().putBoolean("running", on).apply()

    fun recovery(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRecovery < 10 * 60_000L) return
        lastRecovery = now
        Thread({ runCatching { Thread.sleep(20_000) }; send("recovery", "$reason\n\n--- journal ---\n${AppLog.tail()}") }, "report")
            .apply { isDaemon = true; start() }
    }

    /**
     * «Настройки → Отправить журнал»: the whole player journal goes to the server now (workflow «Crash reports»,
     * kind = journal). Returns whether the server took it.
     */
    suspend fun sendJournal(ctx: Context): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val head = "Журнал отправлен вручную · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android " +
                "${android.os.Build.VERSION.RELEASE} · запас ${PlaybackSettings.burstMb(ctx)} МБ\n\n"
            val body = (head + AppLog.full()).toRequestBody("text/plain; charset=utf-8".toMediaType())
            val req = okhttp3.Request.Builder().url(ru.suslikovanora.media.net.Api.BASE + "/api/app/report")
                .header("Content-Type", "text/plain; charset=utf-8").header("X-Platform", "android")
                .header("X-App-Version", Updater.currentVersion(ctx)).header("X-Report-Kind", if (Diag.on) "diag-journal" else "journal")
                .post(body).build()
            ru.suslikovanora.media.net.Api.plain.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun send(kind: String, text: String) {
        val ctx = app ?: return
        Thread({
            runCatching {
                val body = text.take(900_000).toRequestBody("text/plain; charset=utf-8".toMediaType())
                val req = okhttp3.Request.Builder().url(ru.suslikovanora.media.net.Api.BASE + "/api/app/report")
                    .header("Content-Type", "text/plain; charset=utf-8").header("X-Platform", "android")
                    .header("X-App-Version", Updater.currentVersion(ctx)).header("X-Report-Kind", kind)
                    .post(body).build()
                ru.suslikovanora.media.net.Api.plain.newCall(req).execute().close()
            }
        }, "report").apply { isDaemon = true; start() }
    }
}
