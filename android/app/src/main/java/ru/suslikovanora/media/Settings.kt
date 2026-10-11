package ru.suslikovanora.media

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
    val burstPresets = PlaybackPresets.burst("на телефоне")
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
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Sm.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer { val v = shown.value; translationY = (1f - v) * 60f; scaleX = 0.96f + 0.04f * v; scaleY = scaleX }
                .widthIn(max = 560.dp).padding(14.dp).clip(RoundedCornerShape(22.dp)).background(Sm.booth)
                .border(1.dp, Sm.line2, RoundedCornerShape(22.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp)
        ) {
            Text("Настройки", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            Text("Применяются при следующем открытии фильма.", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Section("Экономная загрузка",
                    "Фильм скачивается рывками вперёд и хранится на телефоне, а между рывками связь отдыхает — " +
                        "батарея садится медленнее, обрывы связи не прерывают просмотр. Чем выше качество файла, " +
                        "тем больше нужен запас. Подсказка: битрейт (Мбит/с) ≈ размер файла в ГБ × 2,2 ÷ длительность в часах.")
                PlaybackSettings.burstPresets.forEach { p ->
                    PresetRow(p, p.value == burst) { burst = p.value; PlaybackSettings.setBurstMb(ctx, p.value) }
                }
                Section("Буфер плеера",
                    "Сколько секунд видео плеер набирает перед стартом и после перемотки. Меньше — быстрее отклик, больше — устойчивее к плохой сети.",
                    top = 18.dp)
                PlaybackSettings.cachePresets.forEach { p ->
                    PresetRow(p, p.value == cache) { cache = p.value; PlaybackSettings.setCacheMs(ctx, p.value) }
                }
                ChannelSection()
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                LampButton("Готово") { close() }
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
        state = "Проверяем обновления…"
        scope.launch {
            val installed = Updater.currentVersion(ctx)
            val info = Updater.latest(ctx)
            val v = UpdateChannel.verdict(info?.version, installed, release = !PlaybackSettings.betaChannel(ctx))
            state = if (v == UpdateChannel.Verdict.Replace)
                // Android itself never installs a lower version code over a higher one (no app can override that).
                "Релиз ${info?.version} заменит бету $installed, но Android не ставит версию с меньшим номером поверх " +
                    "большей. Сейчас перейти можно только так: удалить приложение и установить релиз с сайта (вход и прогресс " +
                    "на телефоне сбросятся). Или дождаться следующего релиза — он установится сам."
            else UpdateChannel.message(v, info?.version, installed, info?.beta == true)
            if (v == UpdateChannel.Verdict.Offer) offer = info
        }
    }
    fun saveCode() {
        PlaybackSettings.setBetaCode(ctx, code)
        if (code.isBlank()) { state = "Введите код тестировщика."; return }
        state = "Проверяем код…"
        scope.launch {
            when (runCatching { ru.suslikovanora.media.net.Api.get("/api/app/beta").optBoolean("tester") }.getOrNull()) {
                true -> checkUpdates()
                false -> state = "Сервер не принял этот код."
                null -> state = "Не удалось проверить — код сохранён."
            }
        }
    }
    fun pick(b: Boolean) {
        if (b == beta) return
        beta = b
        PlaybackSettings.setBetaOn(ctx, b)
        if (!b) checkUpdates() else if (code.isBlank()) state = "Введите код тестировщика и нажмите «Сохранить»." else saveCode()
    }
    Section("Канал обновлений", "Релиз — проверенные версии для всех. Бета — новые версии раньше всех, " +
        "для проверки (нужен код тестировщика).", top = 18.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChannelOption("Релиз", !beta, Modifier.weight(1f)) { pick(false) }
        ChannelOption("Бета", beta, Modifier.weight(1f)) { pick(true) }
    }
    androidx.compose.animation.AnimatedVisibility(beta) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ru.suslikovanora.media.library.SearchField(code, { code = it }, "Код тестировщика", password = true, onDone = { saveCode() }) }
            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
            ru.suslikovanora.media.library.OutlineButton("Сохранить") { saveCode() }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(state ?: "Установлена версия ${Updater.currentVersion(ctx)}.", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp,
            lineHeight = 17.sp, modifier = Modifier.weight(1f))
        Text("Проверить", color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(8.dp))
                .clickable { checkUpdates() }.padding(horizontal = 8.dp, vertical = 6.dp))
    }
    offer?.let { UpdateDialog(it) { offer = null } }
}

@Composable
private fun ChannelOption(title: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val edge by animateColorAsState(if (on) Sm.lamp else Sm.line, tween(220), label = "edge")
    val bg by animateColorAsState(if (on) Sm.lampSoft else Sm.film, tween(220), label = "bg")
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(14.dp))
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
