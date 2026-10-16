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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties

/**
 * libmpv-2.dll (120 МБ) не входит в MSI. Её ставит установщик, а приложение лишь проверяет, что нужная версия на месте
 * (метка `libmpv.version` = SHA-256 библиотеки из `libmpv.properties`), и докачивает её, только если её нет или она
 * другой версии (например, после самообновления со старой версии). Обновление приложения поэтому не тащит libmpv заново.
 * Качается зафиксированный архив сборки mpv (7z, ~30 МБ), распаковывает его tar.exe из Windows.
 *
 * Новая библиотека, пока старая загружена в процесс, не заменяется: она ложится рядом как `libmpv-2.dll.new` и
 * встаёт на место при следующем запуске, до первой загрузки ([applyPending], вызывается из [Mpv]).
 */
object MpvInstall {
    private val pin = Properties().apply {
        runCatching { MpvInstall::class.java.getResourceAsStream("/libmpv.properties")?.use { load(it) } }
    }
    private val url: String get() = pin.getProperty("archive", "")
    private val archiveSha256: String get() = pin.getProperty("archiveSha256", "").lowercase()
    private val sha256: String get() = pin.getProperty("dllSha256", "").lowercase()

    /** Общая для обычной и отладочной сборки папка: библиотека качается один раз. */
    val dir: File get() = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "SuslikMedia/mpv")
    private val dll get() = File(dir, "libmpv-2.dll")
    private val mark get() = File(dir, "libmpv.version")
    private val pendingDll get() = File(dir, "libmpv-2.dll.new")
    private val pendingMark get() = File(dir, "libmpv.version.new")

    /** 0..1 пока качается, иначе null. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    private var job: Deferred<Boolean>? = null

    /** Нужная версия уже лежит в папке (или ждёт следующего запуска). */
    fun ready(): Boolean = sha256.isEmpty() ||
        (dll.isFile && runCatching { mark.readText().trim() }.getOrNull() == sha256) ||
        (pendingDll.isFile && runCatching { pendingMark.readText().trim() }.getOrNull() == sha256)

    /** Отложенная замена с прошлого запуска. Только до того, как libmpv загружена в процесс. */
    fun applyPending() {
        if (!pendingDll.isFile) return
        runCatching {
            if (dll.exists() && !dll.delete()) return
            if (pendingDll.renameTo(dll)) { mark.delete(); pendingMark.renameTo(mark) }
        }
    }

    /** Запускает проверку (и загрузку, если нужно) один раз за запуск; повторный вызов после ошибки пробует снова. */
    fun start(scope: CoroutineScope): Deferred<Boolean> = synchronized(this) {
        job?.takeIf { it.isActive || (it.isCompleted && !failed) }
            ?: scope.async(Dispatchers.IO) { ensure() }.also { job = it }
    }

    /** Ждёт уже начатую загрузку (false — её нет или она не удалась). */
    suspend fun await(): Boolean = job?.await() ?: ready()

    private suspend fun ensure(): Boolean {
        if (ready() || url.isEmpty()) return true
        failed = false
        return runCatching { download() }.fold(
            onSuccess = { true },
            onFailure = {
                PlayerLog.write("libmpv: download failed: ${it.message}")
                failed = true; false
            }
        ).also { withContext(Dispatchers.Main) { progress = null } }
    }

    private suspend fun download() {
        dir.mkdirs()
        val work = File(System.getProperty("java.io.tmpdir"), "SuslikMedia-mpv").apply { deleteRecursively(); mkdirs() }
        try {
            val archive = File(work, "mpv.7z")
            PlayerLog.write("libmpv: downloading $url")
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.ALWAYS).build()
            val res = client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            if (res.statusCode() != 200) { res.body().close(); error("HTTP ${res.statusCode()}") }
            val total = res.headers().firstValueAsLong("content-length").orElse(-1)
            val md = MessageDigest.getInstance("SHA-256")
            var got = 0L; var lastStep = -1
            res.body().use { input ->
                archive.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); got += n
                        val step = if (total > 0) (got * 100 / total).toInt() else 0
                        if (step != lastStep) { lastStep = step; withContext(Dispatchers.Main) { progress = step / 100f } }
                    }
                }
            }
            if (hex(md.digest()) != archiveSha256) error("archive checksum mismatch")
            // 7z (с фильтром BCJ2) читает libarchive, который в Windows 10/11 — это System32\tar.exe.
            val tar = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\tar.exe")
            val p = ProcessBuilder(tar.path, "-xf", archive.path, "-C", work.path, "libmpv-2.dll").redirectErrorStream(true).start()
            val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
            val unpacked = File(work, "libmpv-2.dll")
            if (p.waitFor() != 0 || !unpacked.isFile) error("tar: $out")
            if (hex(sha256Of(unpacked)) != sha256) error("checksum mismatch")
            val part = File(dir, "libmpv-2.dll.part")
            unpacked.copyTo(part, overwrite = true)
            // Уже загруженную в процесс библиотеку Windows не даст заменить: тогда — при следующем запуске.
            val placed = (!dll.exists() || dll.delete()) && part.renameTo(dll)
            if (placed) mark.writeText(sha256)
            else { pendingDll.delete(); part.renameTo(pendingDll); pendingMark.writeText(sha256) }
            PlayerLog.write("libmpv: installed ${if (placed) "" else "(on next start) "}${dll.absolutePath}")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun sha256Of(f: File): ByteArray = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
