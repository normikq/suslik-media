package ru.suslikovanora.media

import androidx.compose.ui.focus.focusRequester
import ru.suslikovanora.media.ui.rememberTvStartFocus
import ru.suslikovanora.media.ui.keepFocusInside
import ru.suslikovanora.media.ui.noFocus
import ru.suslikovanora.media.ui.focusRing
import ru.suslikovanora.media.core.*

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.suslikovanora.media.library.LampButton
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded

/** Playback settings (stored on the phone, read by the player each time a film opens). */
object PlaybackSettings {
    // Presets and their hints live in core.PlaybackPresets (the same texts in both apps).
    val burstPresets = PlaybackPresets.burst(tr("settings.na_telefone"))
    const val BURST_DEFAULT = PlaybackPresets.BURST_DEFAULT
    val cachePresets = PlaybackPresets.cache
    const val CACHE_DEFAULT = PlaybackPresets.CACHE_DEFAULT

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun burstMb(ctx: Context) = PlaybackPresets.clampBurst(prefs(ctx).getInt("burst_mb", BURST_DEFAULT))
    fun setBurstMb(ctx: Context, v: Int) = prefs(ctx).edit().putInt("burst_mb", v).apply()
    fun cacheMs(ctx: Context) = prefs(ctx).getInt("vlc_cache_ms", CACHE_DEFAULT)
    fun setCacheMs(ctx: Context, v: Int) = prefs(ctx).edit().putInt("vlc_cache_ms", v).apply()
    /** Tester code (BETA_CODE on the server): sent with requests (Api), unlocks updates from the beta channel. */
    fun betaCode(ctx: Context) = prefs(ctx).getString("beta_code", null).orEmpty()
    fun setBetaCode(ctx: Context, v: String) = prefs(ctx).edit().putString("beta_code", v.trim().ifBlank { null }).apply()
    /** «Канал обновлений»: false = Релиз (the code stays saved but is not sent, so only versions for everyone come). */
    fun betaOn(ctx: Context) = !prefs(ctx).getBoolean("beta_off", false)
    fun setBetaOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("beta_off", !on).apply()
    /** «Тяжёлые файлы на мобильной сети»: ask before a film heavier than this many Mbit/s (0 = never ask). */
    const val HEAVY_DEFAULT = 20
    fun heavyMbit(ctx: Context) = prefs(ctx).getInt("heavy_mbit", HEAVY_DEFAULT)
    fun setHeavyMbit(ctx: Context, v: Int) = prefs(ctx).edit().putInt("heavy_mbit", v).apply()
    /**
     * «Плеер» (for testing the new engine): VLC — every film in VLC, as before; Media3 — films in Media3, and in VLC
     * only what Media3 cannot play (player/EngineChoice). Media3 by default (2.4.3); VLC only for whoever picked it.
     */
    fun engine(ctx: Context): ru.suslikovanora.media.player.EngineKind =
        if (prefs(ctx).getString("player_engine", "media3") == "vlc") ru.suslikovanora.media.player.EngineKind.VLC
        else ru.suslikovanora.media.player.EngineKind.MEDIA3
    fun setEngine(ctx: Context, kind: ru.suslikovanora.media.player.EngineKind) = prefs(ctx).edit().putString("player_engine", kind.tag).apply()
    /** The beta channel is really in use (chosen and a code saved); otherwise — the release channel. */
    fun betaChannel(ctx: Context) = betaOn(ctx) && betaCode(ctx).isNotBlank()
}

/** «Настройки» over the main screen: the same card style and entrance as «Что нового». */
@Composable
fun SettingsSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var burst by remember { mutableStateOf(PlaybackSettings.burstMb(ctx)) }
    var cache by remember { mutableStateOf(PlaybackSettings.cacheMs(ctx)) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(360, easing = Sm.ease)) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { shown.animateTo(0f, tween(220, easing = Sm.ease)); onClose() }
    }
    BackHandler { close() }
    val start = rememberTvStartFocus()
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Sm.scrim)
            .noFocus().clickable(remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer { val v = shown.value; translationY = (1f - v) * 60f; scaleX = 0.96f + 0.04f * v; scaleY = scaleX }
                .widthIn(max = 560.dp).padding(14.dp).clip(RoundedCornerShape(22.dp)).background(Sm.booth)
                .border(1.dp, Sm.line2, RoundedCornerShape(22.dp))
                .keepFocusInside()
                .noFocus().clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp)
        ) {
            Text(tr("settings.nastroyki"), color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            Text(tr("settings.primenyayutsya_pri_sleduyusche"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Section(tr("settings.ekonomnaya_zagruzka"),
                    tr("settings.film_skachivaetsya_ryvkami_vpe") +
                        tr("settings.batareya_saditsya_medlennee_ob") +
                        tr("settings.tem_bolshe_nuzhen_zapas"))
                PlaybackSettings.burstPresets.forEach { p ->
                    PresetRow(p, p.value == burst) { burst = p.value; PlaybackSettings.setBurstMb(ctx, p.value) }
                }
                Section(tr("settings.bufer_pleera"),
                    tr("settings.skolko_sekund_video_pleer"),
                    top = 18.dp)
                PlaybackSettings.cachePresets.forEach { p ->
                    PresetRow(p, p.value == cache) { cache = p.value; PlaybackSettings.setCacheMs(ctx, p.value) }
                }
                EngineSection()
                TrafficSection()
                StorageSection()
                if (Diag.on) DiagSection() else ChannelSection()
                JournalSection()
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                LampButton(tr("settings.gotovo"), modifier = Modifier.focusRequester(start)) { close() }
            }
        }
    }
}

/**
 * «Канал обновлений»: Релиз / Бета. Switching checks the server at once: a version that is not installed yet is
 * offered right here; an older one (back from a beta to the release) cannot be installed over a newer one, which
 * is explained instead.
 */
@Composable
private fun ChannelSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var beta by remember { mutableStateOf(PlaybackSettings.betaOn(ctx) && PlaybackSettings.betaCode(ctx).isNotBlank()) }
    var code by remember { mutableStateOf(PlaybackSettings.betaCode(ctx)) }
    var state by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<Updater.Info?>(null) }
    fun checkUpdates() {
        state = tr("settings.proveryaem_obnovleniya")
        scope.launch {
            val installed = Updater.currentVersion(ctx)
            val info = Updater.latest(ctx)
            val v = Updater.verdict(ctx, info, release = !PlaybackSettings.betaChannel(ctx))
            state = if (v == UpdateChannel.Verdict.Replace)
                // Android itself never installs a lower version code over a higher one (no app can override that).
                tr("settings.reliz_p_zamenit_betu", "p" to info?.version, "installed" to installed) +
                    tr("settings.bolshey_seychas_pereyti_mozhno") +
                    tr("settings.na_telefone_sbrosyatsya_ili")
            else UpdateChannel.message(v, info?.version, installed, info?.beta == true)
            if (v == UpdateChannel.Verdict.Offer) offer = info
        }
    }
    fun saveCode() {
        PlaybackSettings.setBetaCode(ctx, code)
        if (code.isBlank()) { state = tr("settings.vvedite_kod_testirovschika"); return }
        state = tr("settings.proveryaem_kod")
        scope.launch {
            when (runCatching { ru.suslikovanora.media.net.Api.get("/api/app/beta").optBoolean("tester") }.getOrNull()) {
                true -> checkUpdates()
                false -> state = tr("settings.server_ne_prinyal_etot")
                null -> state = tr("settings.ne_udalos_proverit_kod")
            }
        }
    }
    fun pick(b: Boolean) {
        if (b == beta) return
        beta = b
        PlaybackSettings.setBetaOn(ctx, b)
        if (!b) checkUpdates() else if (code.isBlank()) state = tr("settings.vvedite_kod_testirovschika_i") else saveCode()
    }
    Section(tr("settings.kanal_obnovleniy"), tr("settings.reliz_proverennye_versii_dlya") +
        tr("settings.dlya_proverki_nuzhen_kod"), top = 18.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChannelOption(tr("settings.reliz"), !beta, Modifier.weight(1f)) { pick(false) }
        ChannelOption(tr("settings.beta"), beta, Modifier.weight(1f)) { pick(true) }
    }
    androidx.compose.animation.AnimatedVisibility(beta) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ru.suslikovanora.media.library.SearchField(code, { code = it }, tr("settings.kod_testirovschika"), password = true, onDone = { saveCode() }) }
            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
            ru.suslikovanora.media.library.OutlineButton(tr("settings.sohranit")) { saveCode() }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(state ?: tr("settings.ustanovlena_versiya_p", "p" to Updater.currentVersion(ctx)), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp,
            lineHeight = 17.sp, modifier = Modifier.weight(1f))
        Text(tr("settings.proverit"), color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(8.dp))
                .focusRing(RoundedCornerShape(8.dp)).clickable { checkUpdates() }.padding(horizontal = 8.dp, vertical = 6.dp))
    }
    offer?.let { UpdateDialog(it) { offer = null } }
}

/** «Плеер»: VLC or Media3 (VLC stays the fallback for what Media3 cannot play). */
@Composable
private fun EngineSection() {
    val ctx = LocalContext.current
    var engine by remember { mutableStateOf(PlaybackSettings.engine(ctx)) }
    Section(tr("settings.pleer"), tr("settings.pleer_opisanie"), top = 18.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ru.suslikovanora.media.player.EngineKind.VLC to "VLC", ru.suslikovanora.media.player.EngineKind.MEDIA3 to "Media3").forEach { (k, label) ->
            ChannelOption(label, engine == k, Modifier.weight(1f)) { engine = k; PlaybackSettings.setEngine(ctx, k) }
        }
    }
}

/** «Трафик фильмов» and the question before a heavy film on mobile data. */
@Composable
private fun TrafficSection() {
    val ctx = LocalContext.current
    var usage by remember { mutableStateOf(TrafficMeter.month(ctx)) }
    var limit by remember { mutableStateOf(PlaybackSettings.heavyMbit(ctx)) }
    Section(tr("settings.trafik_filmov"), tr("settings.skolko_filmov_skachano_cherez") +
        tr("settings.postery_i_spiski_v"), top = 18.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("settings.mobilnaya_set_p_wi", "mobile" to TrafficMeter.format(usage.first), "wifi" to TrafficMeter.format(usage.second)),
            color = Sm.text, fontFamily = Onest, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
        ru.suslikovanora.media.library.OutlineButton(tr("settings.sbrosit")) { TrafficMeter.reset(ctx); usage = 0L to 0L }
    }
    Section(tr("settings.tyazhelye_fayly_na_mobilnoy"), tr("settings.pered_prosmotrom_sprosim_stoit") +
        tr("settings.tyazhelee_vybrannogo_mbit_s"), top = 14.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to tr("settings.vykl"), 12 to tr("settings.mbit"), 20 to tr("settings.mbit_2"), 40 to tr("settings.mbit_3")).forEach { (v, label) ->
            ChannelOption(label, limit == v, Modifier.weight(1f)) { limit = v; PlaybackSettings.setHeavyMbit(ctx, v) }
        }
    }
}

/** «Память»: how much the reserve, the posters and the rest take; «Очистить кэш». */
@Composable
private fun StorageSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Storage.Sizes?>(null) }
    var busy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { sizes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Storage.sizes(ctx) } }
    Section(tr("settings.pamyat"), tr("settings.kesh_mozhno_ochistit_vse") +
        tr("settings.dannye_filmov_kotoryh_bolshe"), top = 18.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val s = sizes
        Text(if (s == null) tr("settings.schitaem") else tr("settings.zapas_filmov_p_postery", "reserve" to formatBytes(s.reserve), "posters" to formatBytes(s.posters), "other" to formatBytes(s.other)),
            color = Sm.text, fontFamily = Onest, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
        ru.suslikovanora.media.library.OutlineButton(if (busy) tr("settings.ochistka") else tr("settings.ochistit_kesh")) {
            if (busy) return@OutlineButton
            busy = true
            scope.launch {
                val before = sizes
                val after = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Storage.clear(ctx); Storage.sizes(ctx) }
                sizes = after; busy = false
                state = before?.let { b -> tr("settings.osvobozhdeno_p", "p" to formatBytes((b.reserve + b.posters + b.other - after.reserve - after.posters - after.other).coerceAtLeast(0L))) } ?: tr("settings.gotovo")
            }
        }
    }
    if (state.isNotEmpty()) Text(state, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
}

/** «Журнал плеера»: send the journal to the server (to analyse stutters: every reserve burst is in it). */
@Composable
private fun JournalSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Section(tr("settings.zhurnal_pleera"), tr("settings.esli_film_podtormazhival_otpra") +
        tr("settings.i_skolko_zhdali_server"), top = 18.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(state ?: tr("settings.otpravlyaetsya_tolko_po_knopke"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp,
            lineHeight = 17.sp, modifier = Modifier.weight(1f))
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        ru.suslikovanora.media.library.OutlineButton(if (busy) tr("settings.otpravka") else tr("settings.otpravit_zhurnal")) {
            if (busy) return@OutlineButton
            busy = true; state = tr("settings.otpravlyaem")
            scope.launch {
                val ok = CrashReports.sendJournal(ctx)
                busy = false
                state = if (ok) tr("settings.zhurnal_otpravlen_spasibo") else tr("settings.ne_poluchilos_otpravit_provert")
            }
        }
    }
}

/** Debug build only: where the detailed journal is, which adb permissions are on, and its buttons. */
@Composable
private fun DiagSection() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<String?>(null) }
    var verbose by remember { mutableStateOf(Diag.vlcVerbose(ctx)) }
    Section(tr("settings.otladochnaya_sborka"), tr("settings.podrobnyy_zhurnal_android_data", "packageName" to ctx.packageName) +
        tr("settings.s_kompyutera_adb_pull", "packageName" to ctx.packageName, "p" to Diag.permissions(ctx)) +
        tr("settings.obnovleniya_v_etoy_sborke"), top = 18.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ru.suslikovanora.media.library.OutlineButton(tr("settings.metka"), Modifier.weight(1f)) { state = tr("settings.metka_p_zapisana_v", "p" to Diag.mark("settings")) }
        ru.suslikovanora.media.library.OutlineButton(tr("settings.batareya"), Modifier.weight(1f)) {
            Diag.batterySnapshot("button"); state = tr("settings.snimok_batarei_pishetsya_v")
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ru.suslikovanora.media.library.OutlineButton(tr("settings.podelitsya"), Modifier.weight(1f)) {
            val zip = Diag.zipJournal(ctx)
            if (zip == null) state = tr("settings.ne_poluchilos_sobrat_arhiv") else runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", zip)
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("application/zip").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("settings.zhurnal_otladki"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                state = tr("settings.arhiv_p_kb", "p" to zip.length() / 1024)
            }.onFailure { state = tr("settings.oshibka_message", "message" to it.message) }
        }
        ru.suslikovanora.media.library.OutlineButton(tr("settings.ochistit"), Modifier.weight(1f)) { Diag.clearJournal(); state = tr("settings.starye_chasti_udaleny") }
    }
    Section(tr("settings.zhurnal_vlc"), tr("settings.podrobnyy_vv_pishet_kazhdoe") +
        tr("settings.vklyuchayte_na_vremya_odnogo"), top = 12.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChannelOption(tr("settings.obychnyy"), !verbose, Modifier.weight(1f)) { verbose = false; Diag.setVlcVerbose(ctx, false) }
        ChannelOption(tr("settings.podrobnyy"), verbose, Modifier.weight(1f)) { verbose = true; Diag.setVlcVerbose(ctx, true) }
    }
    state?.let { Text(it, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun ChannelOption(title: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val edge by animateColorAsState(if (on) Sm.lamp else Sm.line, tween(220), label = "edge")
    val bg by animateColorAsState(if (on) Sm.lampSoft else Sm.film, tween(220), label = "bg")
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(14.dp))
            .focusRing(RoundedCornerShape(14.dp), color = Sm.text)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = if (on) Sm.lamp else Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
    }
}

@Composable
private fun Section(title: String, text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, modifier = Modifier.padding(top = top))
    Text(text, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
}

@Composable
private fun PresetRow(p: PlaybackPresets.Preset, on: Boolean, onClick: () -> Unit) {
    val edge by animateColorAsState(if (on) Sm.lamp else Sm.line, tween(220), label = "edge")
    val bg by animateColorAsState(if (on) Sm.lampSoft else Sm.film, tween(220), label = "bg")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.dp, edge, RoundedCornerShape(14.dp))
            .focusRing(RoundedCornerShape(14.dp), color = Sm.text)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (on) Sm.lamp else Sm.line2, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Sm.lamp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(p.title, color = if (on) Sm.lamp else Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(p.hint, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}
