package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.ui.*
import ru.suslikovanora.media.desktop.player.Mpv
import ru.suslikovanora.media.desktop.player.MpvInstall

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Playback settings (stored on the phone, read by the player each time a film opens). */
object PlaybackSettings {
    // Presets and their hints live in core.PlaybackPresets (the same texts in both apps).
    val burstPresets = PlaybackPresets.burst(tr("settings.na_diske"))
    const val BURST_DEFAULT = PlaybackPresets.BURST_DEFAULT
    val cachePresets = PlaybackPresets.cache
    const val CACHE_DEFAULT = PlaybackPresets.CACHE_DEFAULT

    private val store = Store("settings")
    fun burstMb() = PlaybackPresets.clampBurst(store.get("burst_mb")?.toIntOrNull() ?: BURST_DEFAULT)
    fun setBurstMb(v: Int) = store.put("burst_mb", v.toString())
    fun cacheMs() = store.get("cache_ms")?.toIntOrNull()
        ?: store.get("vlc_cache_ms")?.toIntOrNull()
        ?: CACHE_DEFAULT
    fun setCacheMs(v: Int) = store.put("cache_ms", v.toString())

    /** Движок плеера: mpv. */
    fun playerEngine(): String = "mpv"
    fun setPlayerEngine(v: String) = store.put("player_engine", "mpv")

    /** Tester code (BETA_CODE on the server): with it, update checks also offer versions from the beta channel. */
    fun betaCode() = store.get("beta_code").orEmpty()
    fun setBetaCode(v: String) = store.put("beta_code", v.trim().ifBlank { null })
    /** «Канал обновлений»: false = Релиз (the code stays saved but is not sent, so only versions for everyone come). */
    fun betaOn() = store.get("beta_off") != "1"
    fun setBetaOn(on: Boolean) = store.put("beta_off", if (on) null else "1")
    /** The code that goes with requests: only while the beta channel is chosen. */
    fun sentBetaCode() = betaCode().takeIf { it.isNotBlank() && betaOn() }
    /** The beta channel is really in use (chosen and a code saved); otherwise — the release channel. */
    fun betaChannel() = sentBetaCode() != null

    /** The gear in the header opens the sheet (shown by Main over the library). */
    var open by mutableStateOf(false)
}

/** «Настройки» over the library: the same card style and entrance as «Что нового». Applies to the next film opened. */
@Composable
fun SettingsSheet(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var burst by remember { mutableStateOf(PlaybackSettings.burstMb()) }
    var cache by remember { mutableStateOf(PlaybackSettings.cacheMs()) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(360, easing = Sm.ease)) }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { shown.animateTo(0f, tween(220, easing = Sm.ease)); onClose() }
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Sm.scrim)
            .hoverClick(remember { MutableInteractionSource() }) { close() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer { val v = shown.value; translationY = (1f - v) * 60f; scaleX = 0.96f + 0.04f * v; scaleY = scaleX }
                .widthIn(max = 620.dp).padding(24.dp).clip(RoundedCornerShape(22.dp)).background(Sm.booth)
                .border(1.dp, Sm.line2, RoundedCornerShape(22.dp))
                .hoverClick(remember { MutableInteractionSource() }) {}
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp)
        ) {
            Text(tr("settings.nastroyki"), color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            Text(tr("settings.primenyayutsya_pri_sleduyusche"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Section(tr("settings.zapas_filma_na_diske"),
                    tr("settings.film_skachivaetsya_ryvkami_vpe") +
                        tr("settings.preryvayut_prosmotr_vneshnyaya") +
                        tr("settings.zapasa_mgnovennaya_a_wi") +
                        tr("settings.tem_bolshe_nuzhen_zapas"))
                PlaybackSettings.burstPresets.forEach { p ->
                    PresetRow(p, p.value == burst) { burst = p.value; PlaybackSettings.setBurstMb(p.value) }
                }
                Section(tr("settings.bufer_pleera"),
                    tr("settings.skolko_sekund_video_pleer"),
                    top = 18.dp)
                PlaybackSettings.cachePresets.forEach { p ->
                    PresetRow(p, p.value == cache) { cache = p.value; PlaybackSettings.setCacheMs(p.value) }
                }
                EngineSection()
                StorageSection()
                if (Diag.on) DiagSection() else ChannelSection()
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                LampButton(tr("settings.gotovo")) { close() }
            }
        }
    }
}

/**
 * «Канал обновлений»: Релиз / Бета. Switching checks the server at once: a version that is not installed yet lights up
 * the update button in the header; an older one (back from a beta to the release) cannot be installed over a newer
 * one, which is explained instead.
 */
@Composable
private fun ChannelSection() {
    val scope = rememberCoroutineScope()
    var beta by remember { mutableStateOf(PlaybackSettings.betaOn() && PlaybackSettings.betaCode().isNotBlank()) }
    var code by remember { mutableStateOf(PlaybackSettings.betaCode()) }
    var state by remember { mutableStateOf<String?>(null) }
    fun checkUpdates() {
        state = tr("settings.proveryaem_obnovleniya")
        scope.launch {
            val installed = Updater.current
            val info = Updater.latest()
            val v = UpdateChannel.verdict(info?.version, installed, release = !PlaybackSettings.betaChannel())
            state = UpdateChannel.message(v, info?.version, installed, info?.beta == true) + when (v) {
                UpdateChannel.Verdict.Offer -> tr("settings.knopka_obnovit_v_shapke")
                UpdateChannel.Verdict.Replace -> tr("settings.knopka_obnovit_v_shapke_2")
                else -> ""
            }
            Updater.available = if (UpdateChannel.offers(v)) info else null
        }
    }
    fun saveCode() {
        PlaybackSettings.setBetaCode(code)
        if (code.isBlank()) { state = tr("settings.vvedite_kod_testirovschika"); return }
        state = tr("settings.proveryaem_kod")
        scope.launch {
            when (runCatching { Api.get("/api/app/beta").optBoolean("tester") }.getOrNull()) {
                true -> checkUpdates()
                false -> state = tr("settings.server_ne_prinyal_etot")
                null -> state = tr("settings.ne_udalos_proverit_kod")
            }
        }
    }
    fun pick(b: Boolean) {
        if (b == beta) return
        beta = b
        PlaybackSettings.setBetaOn(b)
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
            Box(Modifier.weight(1f)) { Field(code, { code = it }, tr("settings.kod_testirovschika"), password = true, onDone = { saveCode() }) }
            Spacer(Modifier.width(10.dp))
            OutlineButton(tr("settings.sohranit")) { saveCode() }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(state ?: tr("settings.ustanovlena_versiya_current", "current" to Updater.current), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp,
            lineHeight = 17.sp, modifier = Modifier.weight(1f))
        Text(tr("settings.proverit"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(8.dp))
                .hoverClick(remember { MutableInteractionSource() }) { checkUpdates() }.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

@Composable
private fun ChannelOption(title: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val edge by animateColorAsState(if (on) Color.White.copy(alpha = 0.85f) else Sm.line, tween(220), label = "edge")
    val bg by animateColorAsState(if (on) Color(0xFF1E2332) else Sm.film, tween(220), label = "bg")
    val glowAlpha by animateFloatAsState(if (on) 0.35f else 0f, tween(220), label = "glow")
    Box(
        modifier
            .drawBehind {
                if (glowAlpha > 0.001f) {
                    val pad = 3.dp.toPx()
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = glowAlpha * 0.16f),
                                0.5f to Color.White.copy(alpha = glowAlpha * 0.05f),
                                1.0f to Color.Transparent
                            ),
                            center = center,
                            radius = size.maxDimension * 0.65f
                        ),
                        topLeft = Offset(-pad, -pad),
                        size = Size(size.width + pad * 2, size.height + pad * 2),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                    )
                }
            }
            .clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(14.dp))
            .hoverClick(remember { MutableInteractionSource() }, onClick = onClick).padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = if (on) Color.White else Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
    }
}

/** Debug build only, in place of «Канал обновлений»: where the journal is, a mark, the journal as one zip. */
@Composable
private fun DiagSection() {
    var state by remember { mutableStateOf("") }
    Section(tr("settings.otladochnaya_sborka"), tr("settings.podrobnyy_zhurnal_absolutepath", "absolutePath" to PlayerLog.file.absolutePath) +
        tr("settings.problemy_eta_sborka_ne"), top = 18.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineButton(tr("settings.metka"), Modifier.weight(1f)) { state = tr("settings.metka_p_zapisana_v", "p" to Diag.mark("settings")) }
        OutlineButton(tr("settings.papka_zhurnala"), Modifier.weight(1f)) {
            state = runCatching { java.awt.Desktop.getDesktop().open(AppDir.dir); "" }.getOrElse { tr("settings.ne_otkrylas_message", "message" to it.message) }
        }
        OutlineButton(tr("settings.arhiv"), Modifier.weight(1f)) {
            state = Diag.zipJournal()?.let { tr("settings.na_rabochem_stole_name", "name" to it.name) } ?: tr("settings.ne_poluchilos_sobrat_arhiv")
        }
    }
    if (state.isNotEmpty()) Text(state, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
}

/** Видеодвижок mpv. */
@Composable
private fun EngineSection() {
    val loading = MpvInstall.progress
    val mpvAvail = loading == null && Mpv.available
    val libFile = Mpv.libraryFile
    val scope = rememberCoroutineScope()

    Section(
        tr("settings.dvizhok_pleera"),
        tr("settings.dvizhok_opisanie"),
        top = 18.dp
    )
    EngineOption(
        title = tr("settings.dvizhok_mpv"),
        subtitle = if (mpvAvail) tr("settings.dvizhok_mpv_hint") else tr("settings.dvizhok_mpv_nedostupen"),
        selected = true,
        enabled = true,
        modifier = Modifier.fillMaxWidth()
    ) {}
    val statusText = when {
        loading != null -> tr("settings.dvizhok_mpv_zagruzka", "p" to (loading * 100).toInt())
        mpvAvail && libFile != null -> tr("settings.dvizhok_mpv_put", "path" to libFile.absolutePath)
        MpvInstall.error != null -> tr("settings.dvizhok_mpv_oshibka", "err" to MpvInstall.error!!)
        else -> tr("settings.dvizhok_mpv_otsutstvuet")
    }
    Text(
        statusText,
        color = if (mpvAvail || loading != null) Sm.muted else Sm.bad,
        fontFamily = Onest,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
    // Не скачалась (сервер недоступен и т. п.): повторить отсюда.
    if (!mpvAvail && loading == null) {
        OutlineButton(tr("settings.dvizhok_mpv_skachat"), Modifier.padding(top = 8.dp)) {
            MpvInstall.start(scope)
        }
    }
}

@Composable
private fun EngineOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val edge by animateColorAsState(if (selected) Color.White.copy(alpha = 0.85f) else Sm.line, tween(220), label = "edge")
    val bg by animateColorAsState(if (selected) Color(0xFF1E2332) else if (enabled) Sm.film else Sm.film.copy(alpha = 0.5f), tween(220), label = "bg")
    val glowAlpha by animateFloatAsState(if (selected) 0.35f else 0f, tween(220), label = "glow")
    Box(
        modifier
            .drawBehind {
                if (glowAlpha > 0.001f) {
                    val pad = 3.dp.toPx()
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = glowAlpha * 0.16f),
                                0.5f to Color.White.copy(alpha = glowAlpha * 0.05f),
                                1.0f to Color.Transparent
                            ),
                            center = center,
                            radius = size.maxDimension * 0.65f
                        ),
                        topLeft = Offset(-pad, -pad),
                        size = Size(size.width + pad * 2, size.height + pad * 2),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                    )
                }
            }
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, edge, RoundedCornerShape(14.dp))
            .let { if (enabled) it.hoverClick(remember { MutableInteractionSource() }, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp)
                    .drawBehind {
                        if (selected) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                                    center = center,
                                    radius = 12.dp.toPx()
                                ),
                                radius = 12.dp.toPx()
                            )
                        }
                    }
                    .clip(CircleShape).border(2.dp, if (selected) Color.White else Sm.line2, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    title,
                    color = if (selected) Color.White else if (enabled) Sm.text else Sm.muted,
                    fontFamily = Onest,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    subtitle,
                    color = if (enabled) Sm.muted else Sm.bad,
                    fontFamily = Onest,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** «Память»: how much the reserve, the posters and the rest take; «Очистить кэш». */
@Composable
private fun StorageSection() {
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<Storage.Sizes?>(null) }
    var busy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { sizes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Storage.sizes() } }
    Section(tr("settings.pamyat"), tr("settings.kesh_mozhno_ochistit_vse") +
        tr("settings.dannye_filmov_kotoryh_bolshe"), top = 18.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val s = sizes
        Text(if (s == null) tr("settings.schitaem") else tr("settings.zapas_filmov_p_postery", "reserve" to formatBytes(s.reserve), "posters" to formatBytes(s.posters), "other" to formatBytes(s.other)),
            color = Sm.text, fontFamily = Onest, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
        OutlineButton(if (busy) tr("settings.ochistka") else tr("settings.ochistit_kesh")) {
            if (busy) return@OutlineButton
            busy = true
            scope.launch {
                val before = sizes
                val after = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Storage.clear(); Storage.sizes() }
                sizes = after; busy = false
                state = before?.let { b -> tr("settings.osvobozhdeno_p", "p" to formatBytes((b.reserve + b.posters + b.other - after.reserve - after.posters - after.other).coerceAtLeast(0L))) } ?: tr("settings.gotovo")
            }
        }
    }
    if (state.isNotEmpty()) Text(state, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Section(title: String, text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, modifier = Modifier.padding(top = top))
    Text(text, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
}

@Composable
private fun PresetRow(p: PlaybackPresets.Preset, on: Boolean, onClick: () -> Unit) {
    val edge by animateColorAsState(if (on) Color.White.copy(alpha = 0.85f) else Sm.line, tween(220), label = "edge")
    val bg by animateColorAsState(if (on) Color(0xFF1E2332) else Sm.film, tween(220), label = "bg")
    val glowAlpha by animateFloatAsState(if (on) 0.35f else 0f, tween(220), label = "glow")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .drawBehind {
                if (glowAlpha > 0.001f) {
                    val pad = 3.dp.toPx()
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = glowAlpha * 0.16f),
                                0.5f to Color.White.copy(alpha = glowAlpha * 0.05f),
                                1.0f to Color.Transparent
                            ),
                            center = center,
                            radius = size.maxDimension * 0.65f
                        ),
                        topLeft = Offset(-pad, -pad),
                        size = Size(size.width + pad * 2, size.height + pad * 2),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                    )
                }
            }
            .clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.dp, edge, RoundedCornerShape(14.dp))
            .hoverClick(remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(18.dp)
                .drawBehind {
                    if (on) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                                center = center,
                                radius = 12.dp.toPx()
                            ),
                            radius = 12.dp.toPx()
                        )
                    }
                }
                .clip(CircleShape).border(2.dp, if (on) Color.White else Sm.line2, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(p.title, color = if (on) Color.White else Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(p.hint, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}
