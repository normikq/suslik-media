package ru.suslikovanora.media.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.suslikovanora.media.core.clockText
import ru.suslikovanora.media.party.Party
import ru.suslikovanora.media.tr
import ru.suslikovanora.media.ui.Avatar
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded

private val Amber = Color(0xFFF2B84B)

/** «Вместе» (as the PC's «Совместный просмотр»): create a room, join by code, open the room's film. */
@Composable
fun TogetherScreen(store: LibraryStore, list: LazyListState, onLibrary: () -> Unit, onOpen: (PlayTarget) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> String?) {
        if (busy) return
        busy = true; error = null
        scope.launch { error = block(); busy = false }
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") { HeaderSlot() }
        item(key = "title") {
            Column {
                Text(tr("party.sovmestnyy_prosmotr"), fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Color.White)
                Text(tr("party.odin_film_odna_pauza"), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (!Party.inRoom) {
            Party.notice?.let { n -> item(key = "notice") { Text(n, color = Amber, fontFamily = Onest, fontSize = 14.sp) } }
            item(key = "create") {
                Panel {
                    Text(tr("party.novaya_komnata"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text)
                    Text(tr("party.sozdayte_komnatu"), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    LampButton(if (busy) tr("party.sozdaem") else tr("party.sozdat_komnatu"), enabled = !busy, modifier = Modifier.fillMaxWidth()) { run { Party.create() } }
                }
            }
            item(key = "join") {
                Panel {
                    Text(tr("party.voyti_po_kodu"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text)
                    Text(tr("party.kod_prishlet"), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    SearchField(value = code, onValue = { code = it }, placeholder = tr("party.kod_komnaty"), onDone = { if (code.isNotBlank()) run { Party.join(code) } })
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(if (busy) tr("party.vhodim") else tr("party.voyti"), modifier = Modifier.fillMaxWidth()) { if (code.isNotBlank()) run { Party.join(code) } }
                }
            }
        } else {
            item(key = "room") { RoomPanel() }
            item(key = "film") { FilmPanel(store, onLibrary, onOpen) }
            item(key = "members") { MembersPanel() }
            item(key = "hint") {
                Text(tr("party.podskazka_sinhron"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
        error?.let { e -> item(key = "error") { Text(e, color = Sm.bad, fontFamily = Onest, fontSize = 14.sp) } }
    }
}

@Composable
private fun RoomPanel() {
    val id = Party.roomId ?: return
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Panel {
        Text(tr("party.kod_komnaty"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
        Text(id, color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 2.dp))
        Text(
            (if (Party.connected) tr("party.na_svyazi") else tr("party.podklyuchaemsya")) +
                (if (Party.hostName.isNotBlank()) tr("party.sozdal", "name" to Party.hostName) else "") +
                (if (Party.hostOnly) tr("party.upravlyaet_sozdatel") else ""),
            color = if (Party.connected) Sm.muted else Amber, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlineButton(if (copied) tr("party.skopirovano") else tr("party.skopirovat"), modifier = Modifier.weight(1f)) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Suslik Media", id)); copied = true
            }
            OutlineButton(tr("party.podelitsya"), modifier = Modifier.weight(1f)) {
                runCatching {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, tr("party.priglashenie", "code" to id))
                    }, null))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlineButton(tr("party.vyyti_iz_komnaty"), modifier = Modifier.fillMaxWidth(), color = Sm.bad) { Party.leave() }
    }
}

@Composable
private fun FilmPanel(store: LibraryStore, onLibrary: () -> Unit, onOpen: (PlayTarget) -> Unit) {
    // The room's clock moves while it plays: the shown time is refreshed every second
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    Panel {
        Text(tr("party.film"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text)
        val f = Party.file
        if (f == null) {
            Text(if (Party.isHost) tr("party.ne_vybran_host") else tr("party.ne_vybran_gost", "who" to Party.hostName.ifBlank { tr("party.sozdatel_komnaty_mal") }),
                color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            if (Party.isHost) LampButton(tr("party.k_mediateke")) { onLibrary() }
            return@Panel
        }
        val s = Party.state
        Text(f.title.ifBlank { f.key.substringAfterLast('/') }, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val loadingNames = Party.members.filter { it.buffering }.joinToString { it.name }
        val stateText = when {
            !s.playing -> tr("party.na_pauze")
            s.waiting -> tr("party.zhdyom") + (if (loadingNames.isNotBlank()) tr("party.u_kogo", "names" to loadingNames) else "")
            else -> tr("party.idyot")
        }
        Text(stateText + " · " + clockText(tick.let { Party.targetMs() }), color = if (s.playing && s.waiting) Amber else Sm.muted,
            fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        val target = remember(store.items, f.key) { targetForKey(store.items, f.key) }
        if (target == null) Text(tr("party.net_v_mediateke"), color = Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
        // «Другой фильм» — also when this film is missing: otherwise the creator would have to make a new room
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // A guest picks nothing: the host's file opens by itself (MainActivity), the button only opens it again
            if (target != null) LampButton(tr("party.smotret_vmeste")) { if (Party.isHost) onOpen(target) else Party.followed = null }
            if (Party.isHost) OutlineButton(tr("party.drugoy_film")) { onLibrary() }
        }
    }
}

@Composable
private fun MembersPanel() {
    Panel {
        Text(tr("party.v_komnate", "n" to Party.members.size), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text)
        Spacer(Modifier.height(8.dp))
        for (m in Party.members) {
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.avatar, m.name, 28.dp, 12.sp)
                Text(m.name + (if (m.key == Party.myKey) tr("party.vy") else "") + (if (Party.isCreator(m)) tr("party.sozdatel") else "") +
                    (if (m.buffering) tr("party.zagruzhaetsya") else ""),
                    color = Sm.text, fontFamily = Onest, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}
