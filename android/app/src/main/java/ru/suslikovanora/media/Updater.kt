package ru.suslikovanora.media

import ru.suslikovanora.media.core.*

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import ru.suslikovanora.media.library.LampButton
import ru.suslikovanora.media.library.OutlineButton
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import java.io.File

/**
 * App updates from the own server: the APK put into the site's updates/ folder. The app asks at every start,
 * shows what is new, downloads (with the session cookie) and hands the file to Android's installer.
 * The new APK must be signed with the same key as the installed one (Android refuses otherwise).
 */
object Updater {
    data class Info(val version: String, val size: Long, val notes: String, val url: String, val beta: Boolean = false)


    fun currentVersion(ctx: Context): String =
        runCatching { @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    fun isNewer(remote: String, local: String): Boolean = Versions.isNewer(remote, local) // shared (core)

    /** The installer of the previous update is no longer needed once the app starts again: free the space. */
    fun cleanup(ctx: Context) {
        runCatching { File(ctx.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
    }

    /** null = nothing new (or not time to ask yet, unless [force]). */
    suspend fun check(ctx: Context, force: Boolean = false): Info? {
        val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) { cleanup(ctx) }
        val info = latest(ctx) ?: return null
        prefs.edit().putLong("checkedAt", now).apply()
        if (!isNewer(info.version, currentVersion(ctx))) return null
        if (!force && prefs.getString("skipped", null) == info.version) return null
        return info
    }

    /** The newest version on the server for this device's channel, newer or not (null = none / no answer). */
    suspend fun latest(@Suppress("UNUSED_PARAMETER") ctx: Context): Info? {
        val j = runCatching { Api.get("/api/app/update?platform=android") }.getOrNull() ?: return null
        if (!j.optBoolean("available")) return null
        return Info(j.optString("version"), j.optLong("size"), j.optString("notes"), j.optString("url"), j.optString("channel") == "beta")
    }

    fun skip(ctx: Context, version: String) =
        ctx.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("skipped", version).apply()

    /** Streams the APK into the app cache; [onProgress] gets 0..1. */
    suspend fun download(ctx: Context, info: Info, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "suslik-media-${info.version}.apk")
        Api.plain.newCall(Request.Builder().url(Api.BASE + info.url).get().build()).execute().use { res ->
            if (!res.isSuccessful) throw Api.HttpError(res.code, "Сервер не отдал файл обновления (${res.code})")
            val body = res.body ?: throw Api.HttpError(res.code, "Пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            var done = 0L; var lastStep = -1
            body.byteStream().use { input ->
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
        }
        out
    }

    /** false = Android first needs "allow installs from this app" (the settings screen was opened). */
    fun install(activity: Activity, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }
}

private fun mb(bytes: Long) = if (bytes > 0) "%.1f МБ".format(bytes / 1048576.0) else ""

/** "Доступна версия …": what is new, download progress, install. */
@Composable
fun UpdateDialog(info: Updater.Info, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    fun installNow(f: File) {
        val act = ctx as? Activity ?: return
        if (!Updater.install(act, f)) hint = "Разрешите установку из этого приложения и нажмите «Установить» ещё раз."
    }
    Dialog(onDismissRequest = { if (progress == null || file != null) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Sm.booth)
                .border(1.dp, Sm.line2, RoundedCornerShape(20.dp)).padding(20.dp)
        ) {
            Text("Доступна версия ${info.version}" + if (info.beta) " (бета)" else "", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 18.sp)
            Text("Установлена ${Updater.currentVersion(ctx)}" + (mb(info.size).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                color = Sm.muted, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            if (info.notes.isNotBlank()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(Sm.film).padding(12.dp)) {
                    Text(info.notes, color = Sm.text, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                }
                Box(Modifier.height(12.dp))
            }
            progress?.let { p ->
                if (file == null) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)).background(Sm.line)) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(6.dp).background(Sm.lamp))
                    }
                    Text("Загрузка… ${(p * 100).toInt()}%", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                }
            }
            (error ?: hint)?.let { Text(it, color = if (error != null) Sm.bad else Sm.lamp, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    file != null -> LampButton("Установить", modifier = Modifier.weight(1f)) { installNow(file!!) }
                    progress != null -> LampButton("Загрузка…", enabled = false, modifier = Modifier.weight(1f)) {}
                    else -> LampButton("Обновить", modifier = Modifier.weight(1f)) {
                        error = null; progress = 0f
                        scope.launch {
                            try { val f = Updater.download(ctx, info) { progress = it }; file = f; installNow(f) }
                            catch (e: Exception) { progress = null; error = Api.describe(e) }
                        }
                    }
                }
                if (progress == null || file != null) OutlineButton("Позже") { onDismiss() }
            }
            if (progress == null && file == null) Text("Пропустить эту версию", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(6.dp))
                    .clickableNoIndication { Updater.skip(ctx, info.version); onDismiss() }.padding(4.dp))
        }
    }
}

@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
