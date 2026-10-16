package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.animateContentSize
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
    /** [replace] = the release replacing a beta with a higher number (the installer cannot do that by itself). */
    data class Info(val version: String, val size: Long, val notes: String, val url: String, val file: String, val beta: Boolean = false,
                    val replace: Boolean = false)

    /** Shown as the breathing button in the header (null = up to date). */
    var available by mutableStateOf<Info?>(null)
    /** 0..1 while downloading the installer, null otherwise. */
    var progress by mutableStateOf<Float?>(null)
    var error by mutableStateOf<String?>(null)

    private val prefs = Store("updates")

    /** Set by the build (-Dsuslik.version); "0" when unknown (then any server version counts as newer). */
    val current: String get() = System.getProperty("suslik.version") ?: System.getProperty("jpackage.app-version") ?: "0"

    fun isNewer(remote: String, local: String): Boolean = Versions.isNewer(remote, local) // shared (core)

    private val tempDir get() = File(System.getProperty("java.io.tmpdir"), "SuslikMedia-update")

    /** The installer that updated us is no longer needed once the app runs again: free the space (100+ MB). */
    fun cleanup() { runCatching { tempDir.listFiles()?.forEach { it.delete() }; tempDir.delete() } }
    /** Bytes of a downloaded update still lying in the temp folder (for «Очистить кэш»). */
    fun tempBytes(): Long = tempDir.listFiles()?.sumOf { it.length() } ?: 0L

    suspend fun check(force: Boolean = false): Info? {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) { cleanup() }
        val info = latest() ?: return null
        prefs.put("checkedAt", now.toString())
        if (current == "0") return null // dev run without a version: never nag
        if (!UpdateChannel.offers(UpdateChannel.verdict(info.version, current, release = !PlaybackSettings.betaChannel()))) return null
        if (!force && prefs.get("skipped") == info.version) return null
        return info
    }

    /** The newest version on the server for this computer's channel, newer or not (null = none / no answer). */
    suspend fun latest(): Info? {
        val j = runCatching { Api.get("/api/app/update?platform=desktop") }.getOrNull() ?: return null
        if (!j.optBoolean("available")) return null
        val version = j.optString("version")
        return Info(version, j.optLong("size"), j.optString("notes"), j.optString("url"), j.optString("file"), j.optString("channel") == "beta",
            replace = !PlaybackSettings.betaChannel() && current != "0" && Versions.compare(version, current) < 0)
    }

    fun skip(version: String) = prefs.put("skipped", version)

    /** Downloads into %TEMP%\SuslikMedia-update; [onProgress] gets 0..1. */
    suspend fun download(info: Info, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = tempDir.apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val ext = info.file.substringAfterLast('.', "msi").lowercase().takeIf { it == "msi" || it == "exe" } ?: "msi"
        val out = File(dir, "Suslik-Media-${info.version}.$ext")
        val res = Api.client.send(Api.request(info.url).timeout(java.time.Duration.ofMinutes(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) throw Api.HttpError(res.statusCode(), tr("update.server_ne_otdal_fayl", "p" to res.statusCode()))
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
    /**
     * Windows Installer looks only at the first three numbers of a version: 2.0.0.1 counts as the same as 2.0.0 and
     * would not replace it. Such an installer (and any lower one) goes the "remove, then install" way.
     */
    fun needsReinstall(remote: String): Boolean {
        fun msi(v: String) = Regex("""\d+""").findAll(v).map { it.value }.take(3).joinToString(".")
        return !Versions.isNewer(msi(remote), msi(current))
    }

    fun installAndExit(file: File, replace: Boolean = false) {
        val msi = file.name.endsWith(".msi", true)
        val cmd = when {
            // Windows Installer refuses to put a lower (or, by its first three numbers, equal) version over the installed
            // one: the installed copy is removed first
            // (settings, sign-in and progress live in %APPDATA% and stay), then the release is installed. Runs after the
            // app has closed.
            replace && msi -> {
                val script = "Start-Sleep -Seconds 2\n" +
                    "Get-ChildItem 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall' | Get-ItemProperty | " +
                    "Where-Object { \$_.DisplayName -eq 'Suslik Media' } | ForEach-Object { " +
                    "Start-Process msiexec -ArgumentList ('/x ' + \$_.PSChildName + ' /passive /norestart') -Wait }\n" +
                    "Start-Process msiexec -ArgumentList ('/i \"' + '${file.absolutePath.replace("'", "''")}' + '\"')\n"
                // Encoded: the script then needs no quoting on the command line (Windows argument quoting is fragile).
                val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
                listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            }
            msi -> listOf("msiexec", "/i", file.absolutePath)
            else -> listOf(file.absolutePath)
        }
        ProcessBuilder(cmd).start()
        Thread.sleep(400)
        AppExit.finish()
    }
}

private fun mb(bytes: Long) = if (bytes > 0) tr("update.f_mb").format(bytes / 1048576.0) else ""

/** Checks once at start (after sign-in); a newer version lights up the update button in the header. */
@Composable
fun UpdateCheck(authenticated: Boolean, @Suppress("UNUSED_PARAMETER") show: Boolean = true) {
    // The debug build never updates itself (it is a separate app; a new one is installed from the PC).
    LaunchedEffect(authenticated) { if (authenticated && !Diag.on) Updater.available = Updater.check() }
}

/**
 * Square amber button that softly "breathes" while an update is waiting. Hover: it widens and says which version.
 * Click: downloads with a progress ring around the arrow, starts the installer, the app closes and comes back updated.
 */
@Composable
fun UpdateButton() {
    val info = Updater.available
    androidx.compose.animation.AnimatedVisibility(
        visible = info != null,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(400)) +
            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(500, easing = Sm.ease), initialScale = 0.6f),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
    ) {
        val i = info ?: return@AnimatedVisibility
        val scope = rememberCoroutineScope()
        val src = remember { MutableInteractionSource() }
        val hovered by src.collectIsHoveredAsState()
        val progress = Updater.progress
        val busy = progress != null
        val failed = Updater.error != null && !busy
        // breathing: slow in-out pulse of the glow and a hair of scale; calm while hovered / downloading
        val breath = androidx.compose.animation.core.rememberInfiniteTransition(label = "breath")
        val b by breath.animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse
            ), label = "b"
        )
        val calm = hovered || busy
        val glow = if (calm) 0.55f else 0.18f + 0.5f * b
        val scale = if (calm) 1f else 1f + 0.035f * b
        val ring by androidx.compose.animation.core.animateFloatAsState(progress ?: 0f, androidx.compose.animation.core.tween(300), label = "ring")
        val accent = if (failed) Sm.bad else Sm.lamp
        Row(
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                .drawBehind {
                    // soft halo outside the button
                    val spread = 7.dp.toPx() * (if (calm) 0.8f else 0.4f + 0.6f * b)
                    drawRoundRect(accent.copy(alpha = glow * 0.45f), topLeft = androidx.compose.ui.geometry.Offset(-spread, -spread),
                        size = androidx.compose.ui.geometry.Size(size.width + 2 * spread, size.height + 2 * spread),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx() + spread))
                }
                .clip(RoundedCornerShape(12.dp))
                .background(if (failed) Color(0x33FF6B6B) else if (hovered && !busy) Color(0xFFFFC04F) else Sm.lamp)
                .hoverClick(src, enabled = !busy) {
                    Updater.error = null; Updater.progress = 0f
                    scope.launch {
                        try { val f = Updater.download(i) { Updater.progress = it }; Updater.progress = 1f; Updater.installAndExit(f, i.replace || Updater.needsReinstall(i.version)) }
                        catch (e: Exception) { Updater.progress = null; Updater.error = Api.describe(e) }
                    }
                }
                .animateContentSize(androidx.compose.animation.core.tween(260, easing = Sm.ease))
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                    val ink = if (failed) Sm.bad else Sm.lampInk
                    val w = size.width; val st = 2.2.dp.toPx()
                    if (busy) {
                        // progress ring
                        drawArc(ink.copy(alpha = 0.25f), 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(st))
                        drawArc(ink, -90f, 360f * ring, false, style = androidx.compose.ui.graphics.drawscope.Stroke(st, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    } else {
                        // arrow into a tray
                        val cap = androidx.compose.ui.graphics.StrokeCap.Round
                        drawLine(ink, androidx.compose.ui.geometry.Offset(w / 2, w * 0.12f), androidx.compose.ui.geometry.Offset(w / 2, w * 0.62f), st, cap)
                        drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.28f, w * 0.42f), androidx.compose.ui.geometry.Offset(w / 2, w * 0.64f), st, cap)
                        drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.72f, w * 0.42f), androidx.compose.ui.geometry.Offset(w / 2, w * 0.64f), st, cap)
                        drawLine(ink, androidx.compose.ui.geometry.Offset(w * 0.14f, w * 0.86f), androidx.compose.ui.geometry.Offset(w * 0.86f, w * 0.86f), st, cap)
                    }
                }
            }
            val label = when {
                busy -> if ((progress ?: 0f) >= 1f) tr("update.zapusk_ustanovschika") else "${((progress ?: 0f) * 100).toInt()}%"
                failed && hovered -> tr("update.oshibka_p_povtorit", "p" to Updater.error!!.take(40))
                hovered -> tr("update.obnovit_do_version", "version" to i.version) + (if (i.beta) tr("update.beta") else "") + (mb(i.size).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                else -> null
            }
            if (label != null) Text(label, color = if (failed) Sm.bad else Sm.lampInk, fontFamily = Onest, fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(end = 14.dp))
        }
    }
}
