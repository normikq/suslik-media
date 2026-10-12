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
    /** «Тяжёлые файлы на мобильной сети»: ask before a film heavier than this many Mbit/s (0 = never ask). */
    const val HEAVY_DEFAULT = 20
    fun heavyMbit(ctx: Context) = prefs(ctx).getInt("heavy_mbit", HEAVY_DEFAULT)
    fun setHeavyMbit(ctx: Context, v: Int) = prefs(ctx).edit().putInt("heavy_mbit", v).apply()
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
                TrafficSection()
                if (Diag.on) DiagSection() else ChannelSection()
                JournalSection()
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
            val v = Updater.verdict(ctx, info, release = !PlaybackSettings.betaChannel(ctx))
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

/** «Трафик фильмов» and the question before a heavy film on mobile data. */
@Composable
private fun TrafficSection() {
    val ctx = LocalContext.current
    var usage by remember { mutableStateOf(TrafficMeter.month(ctx)) }
    var limit by remember { mutableStateOf(PlaybackSettings.heavyMbit(ctx)) }
    Section("Трафик фильмов", "Сколько фильмов скачано через экономную загрузку в этом месяце. " +
        "Постеры и списки в счёт не входят.", top = 18.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Мобильная сеть: ${TrafficMeter.format(usage.first)}\nWi-Fi: ${TrafficMeter.format(usage.second)}",
            color = Sm.text, fontFamily = Onest, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
        ru.suslikovanora.media.library.OutlineButton("Сбросить") { TrafficMeter.reset(ctx); usage = 0L to 0L }
    }
    Section("Тяжёлые файлы на мобильной сети", "Перед просмотром спросим, стоит ли тратить мобильный интернет, если файл " +
        "тяжелее выбранного. 13 Мбит/с — это около 6 ГБ в час, Remux и 4K — 20–30 ГБ.", top = 14.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Выкл", 12 to "12 Мбит", 20 to "20 Мбит", 40 to "40 Мбит").forEach { (v, label) ->
            ChannelOption(label, limit == v, Modifier.weight(1f)) { limit = v; PlaybackSettings.setHeavyMbit(ctx, v) }
        }
    }
}

/** «Журнал плеера»: send the journal to the server (to analyse stutters: every reserve burst is in it). */
@Composable
private fun JournalSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Section("Журнал плеера", "Если фильм подтормаживал, отправьте журнал разработчику: в нём видно, как грузился фильм " +
        "и сколько ждали сервер. В нём есть имена файлов, но нет ссылок и паролей. Журнал уходит только на ваш сервер.", top = 18.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(state ?: "Отправляется только по кнопке.", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp,
            lineHeight = 17.sp, modifier = Modifier.weight(1f))
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        ru.suslikovanora.media.library.OutlineButton(if (busy) "Отправка…" else "Отправить журнал") {
            if (busy) return@OutlineButton
            busy = true; state = "Отправляем…"
            scope.launch {
                val ok = CrashReports.sendJournal(ctx)
                busy = false
                state = if (ok) "Журнал отправлен. Спасибо!" else "Не получилось отправить — проверьте интернет и попробуйте ещё раз."
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
    Section("Отладочная сборка", "Подробный журнал: Android/data/${ctx.packageName}/files/diag " +
        "(с компьютера: adb pull /sdcard/Android/data/${ctx.packageName}/files/diag). Разрешения: ${Diag.permissions(ctx)}. " +
        "Обновления в этой сборке выключены — новая ставится с компьютера.", top = 18.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ru.suslikovanora.media.library.OutlineButton("Метка", Modifier.weight(1f)) { state = "Метка #${Diag.mark("settings")} записана в журнал" }
        ru.suslikovanora.media.library.OutlineButton("Батарея", Modifier.weight(1f)) {
            Diag.batterySnapshot("button"); state = "Снимок батареи пишется в журнал (нужно разрешение DUMP)"
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ru.suslikovanora.media.library.OutlineButton("Поделиться", Modifier.weight(1f)) {
            val zip = Diag.zipJournal(ctx)
            if (zip == null) state = "Не получилось собрать архив" else runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", zip)
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("application/zip").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Журнал отладки")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                state = "Архив ${zip.length() / 1024} КБ"
            }.onFailure { state = "Ошибка: ${it.message}" }
        }
        ru.suslikovanora.media.library.OutlineButton("Очистить", Modifier.weight(1f)) { Diag.clearJournal(); state = "Старые части удалены" }
    }
    Section("Журнал VLC", "Подробный (-vv) пишет каждое действие декодера в logcat.log — много мегабайт в минуту. " +
        "Включайте на время одного опыта. Применяется при следующем открытии фильма.", top = 12.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChannelOption("Обычный", !verbose, Modifier.weight(1f)) { verbose = false; Diag.setVlcVerbose(ctx, false) }
        ChannelOption("Подробный", verbose, Modifier.weight(1f)) { verbose = true; Diag.setVlcVerbose(ctx, true) }
    }
    state?.let { Text(it, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp)) }
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
