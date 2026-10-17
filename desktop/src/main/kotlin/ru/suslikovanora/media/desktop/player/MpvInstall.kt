package ru.suslikovanora.media.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import ru.suslikovanora.media.desktop.PlayerLog
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties

/**
 * libmpv-2.dll (120 МБ) не входит в установщик: её качает с нашего сервера установщик и кладёт в папку приложения
 * (`app/resources`). Если её там всё же нет (сервер был недоступен при установке), приложение докачивает её туда же
 * само, с того же адреса (`libmpv.properties`), и говорит, если сервер недоступен.
 */
object MpvInstall {
    private val pin = Properties().apply {
        runCatching { MpvInstall::class.java.getResourceAsStream("/libmpv.properties")?.use { load(it) } }
    }
    private val url: String get() = pin.getProperty("path", "").let { if (it.isEmpty() || ru.suslikovanora.media.desktop.Api.HOST.isEmpty()) "" else ru.suslikovanora.media.desktop.Api.BASE + it }
    private val sha256: String get() = pin.getProperty("dllSha256", "").lowercase()

    /** Папка ресурсов установленного приложения; при запуске из IDE — рабочая папка. */
    private val dir: File get() = File(System.getProperty("compose.application.resources.dir") ?: System.getProperty("user.dir"))
    private val dll get() = File(dir, "libmpv-2.dll")

    /** 0..1 пока качается, иначе null. */
    var progress by mutableStateOf<Float?>(null)
        private set
    /** Текст последней ошибки загрузки (например, «сервер недоступен»), иначе null. */
    var error by mutableStateOf<String?>(null)
        private set

    private var job: Deferred<Boolean>? = null

    /** Библиотека уже где-то есть (см. [Mpv.candidates]). */
    fun ready(): Boolean = Mpv.candidates().isNotEmpty()

    /** Запускает проверку (и загрузку, если нужно) один раз за запуск; повторный вызов после ошибки пробует снова. */
    fun start(scope: CoroutineScope): Deferred<Boolean> = synchronized(this) {
        job?.takeIf { it.isActive || (it.isCompleted && error == null) }
            ?: scope.async(Dispatchers.IO) { ensure() }.also { job = it }
    }

    /** Ждёт уже начатую загрузку (false — её нет или она не удалась). */
    suspend fun await(): Boolean = job?.await() ?: ready()

    private suspend fun ensure(): Boolean {
        if (ready() || url.isEmpty()) return true
        withContext(Dispatchers.Main) { error = null; progress = 0f }
        val failure = runCatching { download() }.exceptionOrNull()
        if (failure != null) PlayerLog.write("libmpv: download from $url failed: $failure")
        withContext(Dispatchers.Main) {
            progress = null
            error = failure?.let { message(it as? IOException ?: IOException(it)) }
        }
        return failure == null
    }

    private fun message(e: IOException): String = when (e) {
        is ServerAnswer -> "сервер ответил ошибкой ${e.code}"
        is BadFile -> "файл с сервера повреждён"
        else -> "сервер недоступен"
    }

    private class ServerAnswer(val code: Int) : IOException("HTTP $code")
    private class BadFile : IOException("checksum mismatch")

    private suspend fun download() {
        dir.mkdirs()
        val part = File(dir, "libmpv-2.dll.part")
        try {
            PlayerLog.write("libmpv: downloading $url")
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
            val res = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            if (res.statusCode() != 200) { res.body().close(); throw ServerAnswer(res.statusCode()) }
            val total = res.headers().firstValueAsLong("content-length").orElse(-1)
            val md = MessageDigest.getInstance("SHA-256")
            var got = 0L; var lastStep = -1
            res.body().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); got += n
                        val step = if (total > 0) (got * 100 / total).toInt() else 0
                        if (step != lastStep) { lastStep = step; withContext(Dispatchers.Main) { progress = step / 100f } }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (sha256.isNotEmpty() && hex != sha256) throw BadFile()
            if (!part.renameTo(dll)) throw IOException("cannot place ${dll.absolutePath}")
            runCatching { File(dir, "libmpv.version").writeText(sha256) }
            PlayerLog.write("libmpv: installed ${dll.absolutePath}")
        } finally {
            part.delete()
        }
    }
}
