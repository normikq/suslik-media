package ru.suslikovanora.media.desktop.party

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.suslikovanora.media.desktop.Library
import ru.suslikovanora.media.desktop.PlayDialog
import ru.suslikovanora.media.desktop.PlayTarget
import ru.suslikovanora.media.desktop.clock
import ru.suslikovanora.media.desktop.copy
import ru.suslikovanora.media.desktop.player.PlayRequest
import ru.suslikovanora.media.desktop.targetFor
import ru.suslikovanora.media.desktop.tr
import ru.suslikovanora.media.desktop.ui.*

/** Страница «Совместный просмотр» (пункт в боковой панели): создать комнату, войти по коду, открыть фильм комнаты. */
@Composable
fun TogetherView(lib: Library, onLibrary: () -> Unit, onPlay: (PlayRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<PlayTarget?>(null) }

    fun run(block: suspend () -> String?) {
        if (busy) return
        busy = true; error = null
        scope.launch { error = block(); busy = false }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(28.dp)) {
        item {
            Text(tr("party.sovmestnyy_prosmotr"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Sm.text)
            Text(tr("party.odin_film_odna_pauza"), color = Sm.muted, fontFamily = Onest, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        }
        if (!Party.inRoom) item {
            Party.notice?.let { Text(it, color = Color(0xFFF2B84B), fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Panel(Modifier.weight(1f)) {
                    Text(tr("party.novaya_komnata"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text)
                    Text(tr("party.sozdayte_komnatu"),
                        color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    LampButton(if (busy) tr("party.sozdaem") else tr("party.sozdat_komnatu"), enabled = !busy, modifier = Modifier.fillMaxWidth()) { run { Party.create() } }
                }
                Panel(Modifier.weight(1f)) {
                    Text(tr("party.voyti_po_kodu"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text)
                    Text(tr("party.kod_prishlet"), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    Field(code, { code = it }, tr("party.kod_komnaty"), onDone = { if (code.isNotBlank()) run { Party.join(code) } })
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(if (busy) tr("party.vhodim") else tr("party.voyti"), modifier = Modifier.fillMaxWidth()) { if (code.isNotBlank()) run { Party.join(code) } }
                }
            }
        } else item {
            RoomPanel(lib, onLibrary) { t -> dialog = t }
        }
        error?.let { e -> item { Text(e, color = Sm.bad, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) } }
    }
    dialog?.let { t -> PlayDialog(t, lib, onDismiss = { dialog = null }, onPlay = { dialog = null; onPlay(it) }) }
}

@Composable
private fun RoomPanel(lib: Library, onLibrary: () -> Unit, onOpen: (PlayTarget) -> Unit) {
    val id = Party.roomId ?: return
    // The room's clock moves while it plays: refresh the shown time every second
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("party.kod_komnaty"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                Text(id, color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
            OutlineButton(if (copied) tr("party.skopirovano") else tr("party.skopirovat")) { copy(id); copied = true }
            Spacer(Modifier.width(10.dp))
            OutlineButton(tr("party.vyyti_iz_komnaty")) { Party.leave() }
        }
        Text(
            (if (Party.connected) tr("party.na_svyazi") else tr("party.podklyuchaemsya")) +
                (if (Party.hostName.isNotBlank()) tr("party.sozdal", "name" to Party.hostName) else "") +
                (if (Party.hostOnly) tr("party.upravlyaet_sozdatel") else ""),
            color = if (Party.connected) Sm.muted else Color(0xFFF2B84B), fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)
        )
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.weight(2f)) {
            Text(tr("party.film"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text)
            val f = Party.file
            if (f == null) {
                Text(if (Party.isHost) tr("party.ne_vybran_host")
                    else tr("party.ne_vybran_gost", "who" to Party.hostName.ifBlank { tr("party.sozdatel_komnaty_mal") }),
                    color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                if (Party.isHost) LampButton(tr("party.k_mediateke")) { onLibrary() }
            } else {
                val s = Party.state
                Text(f.title.ifBlank { f.key.substringAfterLast('/') }, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                val loadingNames = Party.members.filter { it.buffering }.joinToString { it.name }
                val stateText = when {
                    !s.playing -> tr("party.na_pauze")
                    s.waiting -> tr("party.zhdyom") + (if (loadingNames.isNotBlank()) tr("party.u_kogo", "names" to loadingNames) else "")
                    else -> tr("party.idyot")
                }
                Text(stateText + " · " + clock(tick.let { Party.targetMs() }), color = if (s.playing && s.waiting) Color(0xFFF2B84B) else Sm.muted,
                    fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                val magnetId = f.key.substringBefore('|').toLongOrNull()
                val path = f.key.substringAfter('|')
                val target = lib.items.firstOrNull { it.m.id == magnetId }?.let { targetFor(it, path) }
                if (target == null) {
                    Text(tr("party.net_v_mediateke"), color = Sm.bad, fontFamily = Onest, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp))
                }
                // «Другой фильм» — и когда этого фильма нет: иначе создателю пришлось бы пересоздавать комнату
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (target != null) LampButton(tr("party.smotret_vmeste")) { onOpen(target) }
                    if (Party.isHost) OutlineButton(tr("party.drugoy_film")) { onLibrary() }
                }
            }
        }
        Panel(Modifier.weight(1f)) {
            Text(tr("party.v_komnate", "n" to Party.members.size), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text)
            Spacer(Modifier.height(8.dp))
            for (m in Party.members) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ru.suslikovanora.media.desktop.Avatar(m.avatar, m.name, 26.dp, 12.sp)
                    Text(m.name + (if (m.key == Party.myKey) tr("party.vy") else "") + (if (Party.isCreator(m)) tr("party.sozdatel") else "") +
                        (if (m.buffering) tr("party.zagruzhaetsya") else ""),
                        color = Sm.text, fontFamily = Onest, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(tr("party.podskazka_sinhron"),
        color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)))
}
