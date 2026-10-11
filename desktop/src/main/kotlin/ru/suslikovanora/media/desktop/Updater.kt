package ru.suslikovanora.media.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.http.HttpResponse

/**
 * Updates from the own server: the .msi (or .exe) put into the site's updates/ folder. Checked at start (at most
 * every 6 h); the installer is downloaded with the session cookie, started, and the app closes so it can be replaced.
 */
object Updater {
    data class Info(val version: String, val size: Long, val notes: String, val url: String, val file: String)

    private val prefs = Store("updates")

    /** Set by the build (-Dsuslik.version); "0" when unknown (then any server version counts as newer). */
    val current: String get() = System.getProperty("suslik.version") ?: System.getProperty("jpackage.app-version") ?: "0"

    private fun parts(v: String) = Regex("""\d+""").findAll(v).map { it.value.toInt() }.toList().let { it + List(3) { 0 } }.take(3)
    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private val tempDir get() = File(System.getProperty("java.io.tmpdir"), "SuslikMedia-update")

    /** The installer that updated us is no longer needed once the app runs again: free the space (100+ MB). */
    fun cleanup() { runCatching { tempDir.listFiles()?.forEach { it.delete() }; tempDir.delete() } }

    suspend fun check(force: Boolean = false): Info? {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) { cleanup() }
        val j = runCatching { Api.get("/api/app/update?platform=desktop") }.getOrNull() ?: return null
        prefs.put("checkedAt", now.toString())
        if (!j.optBoolean("available")) return null
        val version = j.optString("version")
        if (current == "0" || !isNewer(version, current)) return null // dev run without a version: never nag
        if (!force && prefs.get("skipped") == version) return null
        return Info(version, j.optLong("size"), j.optString("notes"), j.optString("url"), j.optString("file"))
    }

    fun skip(version: String) = prefs.put("skipped", version)

    /** Downloads into %TEMP%\SuslikMedia-update; [onProgress] gets 0..1. */
    suspend fun download(info: Info, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = tempDir.apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val ext = info.file.substringAfterLast('.', "msi").lowercase().takeIf { it == "msi" || it == "exe" } ?: "msi"
        val out = File(dir, "Suslik-Media-${info.version}.$ext")
        val res = Api.client.send(Api.request(info.url).timeout(java.time.Duration.ofMinutes(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) throw Api.HttpError(res.statusCode(), "Сервер не отдал файл обновления (${res.statusCode()})")
        val total = res.headers().firstValueAsLong("content-length").orElse(info.size)
        var done = 0L; var lastStep = -1
        res.body().use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    output.write(buf, 0, n); done += n
                    val step = if (total > 0) (done * 100 / total).toInt() else 0
                    if (step != lastStep) { lastStep = step; withContext(Dispatchers.Main) { onProgress(step / 100f) } }
                }
            }
        }
        out
    }

    /** Starts the installer and closes the app (Windows cannot replace files of a running program). */
    fun installAndExit(file: File) {
        val cmd = if (file.name.endsWith(".msi", true)) listOf("msiexec", "/i", file.absolutePath) else listOf(file.absolutePath)
        ProcessBuilder(cmd).start()
        Thread.sleep(400)
        kotlin.system.exitProcess(0)
    }
}

private fun mb(bytes: Long) = if (bytes > 0) "%.1f МБ".format(bytes / 1048576.0) else ""

/** Checks once at start; shows the update card over the library when there is something new. */
@Composable
fun UpdateCheck(authenticated: Boolean, show: Boolean) {
    var info by remember { mutableStateOf<Updater.Info?>(null) }
    LaunchedEffect(authenticated) { if (authenticated) info = Updater.check() }
    val i = info ?: return
    if (show) UpdateDialog(i) { info = null }
}

@Composable
private fun UpdateDialog(info: Updater.Info, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(260, easing = Sm.ease))
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Sm.scrim)
            .hoverClick(remember { MutableInteractionSource() }, enabled = progress == null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer { translationY = (1f - a) * 24f }.widthIn(max = 520.dp).clip(RoundedCornerShape(20.dp)).background(Sm.booth)
                .border(1.dp, Sm.line2, RoundedCornerShape(20.dp))
                .hoverClick(remember { MutableInteractionSource() }) {}.padding(22.dp)
        ) {
            Text("Доступна версия ${info.version}", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 19.sp)
            Text("Установлена ${Updater.current}" + (mb(info.size).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            if (info.notes.isNotBlank()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)).background(Sm.film).padding(14.dp)) {
                    Text(info.notes, color = Sm.text, fontFamily = Onest, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
                Box(Modifier.height(14.dp))
            }
            progress?.let { p ->
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(Sm.line)) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(6.dp).background(Sm.lamp))
                }
                Text(if (p >= 1f) "Запускаем установщик…" else "Загрузка… ${(p * 100).toInt()}%", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            }
            error?.let { Text(it, color = Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(bottom = 12.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LampButton(if (progress == null) "Обновить и перезапустить" else "Загрузка…", enabled = progress == null) {
                    error = null; progress = 0f
                    scope.launch {
                        try { val f = Updater.download(info) { progress = it }; progress = 1f; Updater.installAndExit(f) }
                        catch (e: Exception) { progress = null; error = Api.describe(e) }
                    }
                }
                if (progress == null) {
                    OutlineButton("Позже") { onDismiss() }
                    OutlineButton("Пропустить версию") { Updater.skip(info.version); onDismiss() }
                }
            }
            Text("Приложение закроется, установщик обновит его и сохранит все настройки и прогресс просмотра.",
                color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
