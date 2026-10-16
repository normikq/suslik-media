package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import ru.suslikovanora.media.tr

/** Everything the player needs: stream, title, optional season playlist, sidecar subtitles, progress keys. */
data class PlayRequest(
    val url: String,
    val title: String,
    val playlist: String?,
    val subUrls: List<String>,
    val watchKeys: List<String>,
    val audioUrls: List<String> = emptyList(),
    /** «С начала» chosen in the sheet: ignore the remembered place this time. */
    val fromStart: Boolean = false
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
    var sub by remember(target) { mutableStateOf(tr("play.poluchenie_ssylok")) }
    var mirror by remember(target) { mutableStateOf<String?>(null) }
    var direct by remember(target) { mutableStateOf<String?>(null) }
    var playlist by remember(target) { mutableStateOf<String?>(null) }
    var preparingPlaylist by remember(target) { mutableStateOf(false) }
    var preparingExtras by remember(target) { mutableStateOf(false) }
    var subUrls by remember(target) { mutableStateOf<List<String>>(emptyList()) }
    var audioUrls by remember(target) { mutableStateOf<List<String>>(emptyList()) }
    // Where the player stopped last time (the same place the player itself resumes from); null = nothing to resume.
    val resumeAt = remember(target) {
        context.getSharedPreferences("positions", Context.MODE_PRIVATE).getLong("w:" + watchKey(target.magnetId, target.file), 0L)
            .takeIf { it >= 5_000L }
    }
    var fromStart by remember(target) { mutableStateOf(false) }

    LaunchedEffect(target) {
        val link = target.file.link
        if (link == null) { sub = tr("play.ssylka_na_fayl_esche"); return@LaunchedEffect }
        val name = target.file.name.ifBlank { target.file.path.substringAfterLast('/') }
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        // The mirror does not wait for the direct link. Both used to be awaited together, and the direct one goes to
        // AllDebrid through the server: on a bad mobile network (12.10) it failed twice and took ~18 s while the mirror
        // link was there after 5 s — «Получение ссылок…» for all that time. The mirror is the main way (AllDebrid is
        // blocked in Russia), so it is offered as soon as it comes; the direct link fills in when it is ready.
        coroutineScope {
            val d = async { runCatching { Api.post("/api/file/unlock", JSONObject().put("link", link)).optString("url").ifBlank { null } } }
            val mr = runCatching { Api.get("/api/file/mirror-url?link=${enc(link)}&name=${enc(name)}").optString("url").ifBlank { null } }
            mirror = mr.getOrNull()
            if (mirror != null) {
                sub = tr("play.vyberite_istochnik")
                // Subtitle files and voice-overs next to the video: the player adds them as extra tracks (signed mirror
                // links, no login needed). «Смотреть» waits for them so the film does not start without them.
                preparingExtras = target.subs.isNotEmpty() || target.audio.isNotEmpty()
                if (target.subs.isNotEmpty()) {
                    subUrls = mirrorUrls(target.subs, enc)
                    if (subUrls.isNotEmpty()) sub = tr("play.vyberite_istochnik_subtitrov_i", "size" to subUrls.size)
                }
                if (target.audio.isNotEmpty()) {
                    audioUrls = mirrorUrls(target.audio, enc)
                    if (audioUrls.isNotEmpty()) sub = tr("play.sub_ozvuchek_iz_razdachi", "sub" to sub, "size" to audioUrls.size)
                }
                preparingExtras = false
                // Series via the mirror: this episode plus every following one to the end of the season.
                val following = target.following.filter { it.link != null }
                if (following.size > 1) {
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
            val dr = d.await()
            direct = dr.getOrNull()
            if (mirror == null) sub = if (direct != null) tr("play.vyberite_istochnik")
                else ((mr.exceptionOrNull() ?: dr.exceptionOrNull())?.let { Api.describe(it) } ?: tr("play.ne_udalos_poluchit_ssylki"))
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
            resumeAt?.let { at ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StartOption(tr("play.s_p", "p" to clock(at)), tr("play.prodolzhit"), !fromStart, Modifier.weight(1f)) { fromStart = false }
                    StartOption(tr("play.s_nachala"), tr("play.zanovo"), fromStart, Modifier.weight(1f)) { fromStart = true }
                }
            }
            SourceGroup(
                title = tr("play.cherez_zerkalo"), hint = if (playlist != null) tr("play.cherez_vash_server_dalshe") else tr("play.trafik_idet_cherez_vash"),
                url = mirror, busy = preparingPlaylist || preparingExtras, busyText = if (preparingPlaylist) tr("play.gotovim_pleylist") else tr("play.gotovim_dorozhki"),
                onPlay = {
                    val keys = if (playlist != null) target.following.filter { f -> f.link != null }.map { f -> watchKey(target.magnetId, f) }
                    else listOf(watchKey(target.magnetId, target.file))
                    onPlay(PlayRequest(it, target.label, playlist, subUrls, keys, audioUrls, fromStart))
                }, onCopy = { copy(context, it) }
            )
            Spacer(Modifier.height(10.dp))
            SourceGroup(
                title = tr("play.napryamuyu"), hint = tr("play.pryamaya_ssylka_alldebrid"),
                url = direct, busy = false,
                onPlay = { onPlay(PlayRequest(it, target.label, null, subUrls, listOf(watchKey(target.magnetId, target.file)), audioUrls, fromStart)) },
                onCopy = { copy(context, it) }
            )
            Spacer(Modifier.height(12.dp))
            OutlineButton(tr("player.zakryt"), modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

@Composable
private fun SourceGroup(title: String, hint: String, url: String?, busy: Boolean, busyText: String = tr("play.gotovim_pleylist"), onPlay: (String) -> Unit, onCopy: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sm.film)
            .border(1.dp, Sm.line, RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        Text(title, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Sm.text)
        Text(hint, fontFamily = Onest, fontSize = 12.5.sp, color = Sm.muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LampButton(
                if (busy) busyText else tr("add.smotret"), enabled = url != null && !busy,
                modifier = Modifier.weight(1f)
            ) { url?.let(onPlay) }
            OutlineButton(tr("add.kopirovat"), color = if (url != null) Sm.text else Sm.muted) { url?.let(onCopy) }
        }
    }
}

/** «Продолжить с …» / «С начала»: two halves of one choice, the chosen one lit. */
@Composable
private fun StartOption(title: String, hint: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Sm.lampSoft else Sm.film)
            .border(1.dp, if (on) Sm.lamp else Sm.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(title, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) Sm.lamp else Sm.text, maxLines = 1)
        Text(hint, fontFamily = Onest, fontSize = 12.sp, color = Sm.muted)
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(tr("add.ssylka"), text))
    Toast.makeText(context, tr("add.ssylka_skopirovana"), Toast.LENGTH_SHORT).show()
}

/** Signed mirror links for the files next to the video (subtitles, voice-overs); the ones that fail are left out. */
private suspend fun mirrorUrls(files: List<MFile>, enc: (String) -> String): List<String> = coroutineScope {
    files.map { f ->
        async {
            runCatching {
                Api.get("/api/file/mirror-url?link=${enc(f.link!!)}&name=${enc(f.name.ifBlank { f.path.substringAfterLast('/') })}")
                    .optString("url").ifBlank { null }
            }.getOrNull()
        }
    }.mapNotNull { it.await() }
}
