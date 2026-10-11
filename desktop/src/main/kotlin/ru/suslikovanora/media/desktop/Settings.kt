package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Playback settings (stored on the phone, read by the player each time a film opens). */
object PlaybackSettings {
    // Presets and their hints live in core.PlaybackPresets (the same texts in both apps).
    val burstPresets = PlaybackPresets.burst("на диске")
    const val BURST_DEFAULT = PlaybackPresets.BURST_DEFAULT
    val cachePresets = PlaybackPresets.cache
    const val CACHE_DEFAULT = PlaybackPresets.CACHE_DEFAULT

    private val store = Store("settings")
    fun burstMb() = PlaybackPresets.clampBurst(store.get("burst_mb")?.toIntOrNull() ?: BURST_DEFAULT)
    fun setBurstMb(v: Int) = store.put("burst_mb", v.toString())
    fun cacheMs() = store.get("vlc_cache_ms")?.toIntOrNull() ?: CACHE_DEFAULT
    fun setCacheMs(v: Int) = store.put("vlc_cache_ms", v.toString())

    /** Tester code (BETA_CODE on the server): with it, update checks also offer versions from the beta channel. */
    fun betaCode() = store.get("beta_code").orEmpty()
    fun setBetaCode(v: String) = store.put("beta_code", v.trim().ifBlank { null })

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
            Text("Настройки", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            Text("Применяются при следующем открытии фильма.", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Section("Запас фильма на диске",
                    "Фильм скачивается рывками вперёд и хранится во временной папке: обрывы связи с зеркалом не " +
                        "прерывают просмотр, внешняя озвучка не расходится с картинкой после перемотки, перемотка в пределах " +
                        "запаса — мгновенная, а Wi-Fi ноутбука отдыхает между рывками. Чем выше качество файла, " +
                        "тем больше нужен запас. Подсказка: битрейт (Мбит/с) ≈ размер файла в ГБ × 2,2 ÷ длительность в часах.")
                PlaybackSettings.burstPresets.forEach { p ->
                    PresetRow(p, p.value == burst) { burst = p.value; PlaybackSettings.setBurstMb(p.value) }
                }
                Section("Буфер плеера",
                    "Сколько секунд видео плеер набирает перед стартом и после перемотки. Меньше — быстрее отклик, больше — устойчивее к плохой сети.",
                    top = 18.dp)
                PlaybackSettings.cachePresets.forEach { p ->
                    PresetRow(p, p.value == cache) { cache = p.value; PlaybackSettings.setCacheMs(p.value) }
                }
                BetaSection()
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                LampButton("Готово") { close() }
            }
        }
    }
}

/** «Бета-версии»: the tester code; checked on the server right away. */
@Composable
private fun BetaSection() {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(PlaybackSettings.betaCode()) }
    var state by remember { mutableStateOf<String?>(if (PlaybackSettings.betaCode().isNotBlank()) "Код сохранён." else null) }
    fun check() {
        PlaybackSettings.setBetaCode(code)
        if (code.isBlank()) { state = "Бета-версии выключены."; return }
        state = "Проверяем…"
        scope.launch {
            state = runCatching { Api.get("/api/app/beta").optBoolean("tester") }.getOrNull().let {
                when (it) { true -> "Код принят: новые бета-версии будут приходить сюда первыми."; false -> "Сервер не принял этот код."; null -> "Не удалось проверить — код сохранён." }
            }
            if (code.isNotBlank()) Updater.available = Updater.check(force = true)
        }
    }
    Section("Бета-версии", "Только для проверки новых версий до выхода всем. Введите код тестировщика (BETA_CODE с сервера); " +
        "пустое поле — обычные обновления.", top = 18.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Field(code, { code = it }, "Код тестировщика", password = true, onDone = { check() }) }
        Spacer(Modifier.width(10.dp))
        OutlineButton("Сохранить") { check() }
    }
    state?.let { Text(it, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp)) }
}

@Composable
private fun Section(title: String, text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, modifier = Modifier.padding(top = top))
    Text(text, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
}

@Composable
private fun PresetRow(p: PlaybackPresets.Preset, on: Boolean, onClick: () -> Unit) {
    val edge by animateColorAsState(if (on) Sm.lamp else Sm.line, tween(220), label = "edge")
    val bg by animateColorAsState(if (on) androidx.compose.ui.graphics.Color(0x24FFB02E) else Sm.film, tween(220), label = "bg")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.dp, edge, RoundedCornerShape(14.dp))
            .hoverClick(remember { MutableInteractionSource() }, onClick = onClick)
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
