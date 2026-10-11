package ru.suslikovanora.media.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import java.net.URLEncoder

/** Everything the player needs: stream, title, optional season playlist, sidecar subtitles, progress keys. */
data class PlayRequest(
    val url: String,
    val title: String,
    val playlist: String?,
    val subUrls: List<String>,
    val watchKeys: List<String>,
    val audioUrls: List<String> = emptyList()
)

/**
 * Same choice as the website's play sheet: through the server (mirror; for series a playlist with the rest of
 * the season) or the direct AllDebrid link. "Смотреть" opens the app's VLC player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySheet(
    target: PlayTarget,
    onDismiss: () -> Unit,
    onPlay: (PlayRequest) -> Unit
) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sub by remember(target) { mutableStateOf("Получение ссылок…") }
    var mirror by remember(target) { mutableStateOf<String?>(null) }
    var direct by remember(target) { mutableStateOf<String?>(null) }
    var playlist by remember(target) { mutableStateOf<String?>(null) }
    var preparingPlaylist by remember(target) { mutableStateOf(false) }
    var subUrls by remember(target) { mutableStateOf<List<String>>(emptyList()) }
    var audioUrls by remember(target) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(target) {
        val link = target.file.link
        if (link == null) { sub = "Ссылка на файл ещё не готова — нажмите «Обновить» в библиотеке."; return@LaunchedEffect }
        val name = target.file.name.ifBlank { target.file.path.substringAfterLast('/') }
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        var firstError: String? = null
        coroutineScope {
            val d = async { runCatching { Api.post("/api/file/unlock", JSONObject().put("link", link)).optString("url").ifBlank { null } } }
            val m = async { runCatching { Api.get("/api/file/mirror-url?link=${enc(link)}&name=${enc(name)}").optString("url").ifBlank { null } } }
            val dr = d.await(); val mr = m.await()
            direct = dr.getOrNull(); mirror = mr.getOrNull()
            firstError = (dr.exceptionOrNull() ?: mr.exceptionOrNull())?.let { Api.describe(it) }
        }
        sub = if (mirror != null || direct != null) "Выберите источник" else (firstError ?: "Не удалось получить ссылки")
        // Subtitle files next to the video: the player adds them as extra tracks (signed mirror links, no login needed).
        if (target.subs.isNotEmpty() && (mirror != null || direct != null)) {
            subUrls = coroutineScope {
                target.subs.map { f ->
                    async {
                        runCatching {
                            Api.get("/api/file/mirror-url?link=${enc(f.link!!)}&name=${enc(f.name.ifBlank { f.path.substringAfterLast('/') })}")
                                .optString("url").ifBlank { null }
                        }.getOrNull()
                    }
                }.mapNotNull { it.await() }
            }
            if (subUrls.isNotEmpty()) sub = "Выберите источник · субтитров из раздачи: ${subUrls.size}"
        }
        // External voice-overs (.mka/.ac3 next to the video): added by the player as extra audio tracks.
        if (target.audio.isNotEmpty() && (mirror != null || direct != null)) {
            audioUrls = coroutineScope {
                target.audio.map { f ->
                    async {
                        runCatching {
                            Api.get("/api/file/mirror-url?link=${enc(f.link!!)}&name=${enc(f.name.ifBlank { f.path.substringAfterLast('/') })}")
                                .optString("url").ifBlank { null }
                        }.getOrNull()
                    }
                }.mapNotNull { it.await() }
            }
            if (audioUrls.isNotEmpty()) sub = "$sub · озвучек из раздачи: ${audioUrls.size}"
        }
        // Series via the mirror: this episode plus every following one to the end of the season.
        val following = target.following.filter { it.link != null }
        if (mirror != null && following.size > 1) {
            preparingPlaylist = true
            runCatching {
                val items = JSONArray()
                following.forEach { items.put(JSONObject().put("index", it.index).put("link", it.link)) }
                Api.post("/api/playlist", JSONObject().put("magnetId", target.magnetId).put("title", target.label).put("items", items))
                    .optString("url").ifBlank { null }
            }.onSuccess { playlist = it }
            preparingPlaylist = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Sm.booth,
        contentColor = Sm.text,
        scrimColor = Sm.scrim,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 18.dp).navigationBarsPadding()) {
            Text(target.label, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, color = Sm.text)
            Text(sub, fontFamily = Onest, fontSize = 13.5.sp, color = Sm.muted, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            SourceGroup(
                title = "Через зеркало", hint = if (playlist != null) "Через ваш сервер · дальше все серии сезона" else "Трафик идёт через ваш сервер",
                url = mirror, busy = preparingPlaylist,
                onPlay = {
                    val keys = if (playlist != null) target.following.filter { f -> f.link != null }.map { f -> watchKey(target.magnetId, f) }
                    else listOf(watchKey(target.magnetId, target.file))
                    onPlay(PlayRequest(it, target.label, playlist, subUrls, keys, audioUrls))
                }, onCopy = { copy(context, it) }
            )
            Spacer(Modifier.height(10.dp))
            SourceGroup(
                title = "Напрямую", hint = "Прямая ссылка AllDebrid",
                url = direct, busy = false,
                onPlay = { onPlay(PlayRequest(it, target.label, null, subUrls, listOf(watchKey(target.magnetId, target.file)), audioUrls)) },
                onCopy = { copy(context, it) }
            )
            Spacer(Modifier.height(12.dp))
            OutlineButton("Закрыть", modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

@Composable
private fun SourceGroup(title: String, hint: String, url: String?, busy: Boolean, onPlay: (String) -> Unit, onCopy: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sm.film)
            .border(1.dp, Sm.line, RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        Text(title, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Sm.text)
        Text(hint, fontFamily = Onest, fontSize = 12.5.sp, color = Sm.muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LampButton(
                if (busy) "Готовим плейлист…" else "▶ Смотреть", enabled = url != null && !busy,
                modifier = Modifier.weight(1f)
            ) { url?.let(onPlay) }
            OutlineButton("Копировать", color = if (url != null) Sm.text else Sm.muted) { url?.let(onCopy) }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Ссылка", text))
    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
}
