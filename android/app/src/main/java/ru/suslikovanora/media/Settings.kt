package ru.suslikovanora.media

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
    data class Preset(val value: Int, val title: String, val hint: String)

    /** Size of the «экономная загрузка» reserve per film, in MB (0 = off: VLC reads the mirror directly). */
    val burstPresets = listOf(
        Preset(0, "Выкл", "Как раньше: фильм идёт напрямую, без запаса на телефоне. Связь не отдыхает."),
        Preset(32, "32 МБ", "SD и 720p (до ~4 Мбит/с, файлы до ~2 ГБ). Когда на телефоне мало места."),
        Preset(64, "64 МБ", "Обычный 1080p (до ~8 Мбит/с, файлы 2–6 ГБ). Примерно минута запаса."),
        Preset(96, "96 МБ", "1080p хорошего качества (до ~15 Мбит/с, файлы 6–12 ГБ). Подходит почти всем."),
        Preset(160, "160 МБ", "1080p Remux и лёгкий 4K (до ~25 Мбит/с, файлы 12–25 ГБ)."),
        Preset(256, "256 МБ", "4K (до ~40 Мбит/с, файлы 25–40 ГБ)."),
        Preset(512, "512 МБ", "4K Remux / Blu-ray (до ~80 Мбит/с, файлы от 40 ГБ). Нужно ~1,5 ГБ свободного места.")
    )
    const val BURST_DEFAULT = 96

    /** VLC's own buffer, ms: how much it holds before playing (start / after a seek). */
    val cachePresets = listOf(
        Preset(1500, "1,5 с", "Самый быстрый старт и перемотка. Только со стабильным интернетом или экономной загрузкой."),
        Preset(3000, "3 с", "Рекомендуется вместе с экономной загрузкой: быстро стартует, провалы сети гасит запас."),
        Preset(6000, "6 с", "Без экономной загрузки: выдерживает короткие провалы сети."),
        Preset(10000, "10 с", "Слабая или нестабильная мобильная сеть: дольше старт, зато меньше остановок.")
    )
    const val CACHE_DEFAULT = 3000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun burstMb(ctx: Context) = prefs(ctx).getInt("burst_mb", BURST_DEFAULT)
    fun setBurstMb(ctx: Context, v: Int) = prefs(ctx).edit().putInt("burst_mb", v).apply()
    fun cacheMs(ctx: Context) = prefs(ctx).getInt("vlc_cache_ms", CACHE_DEFAULT)
    fun setCacheMs(ctx: Context, v: Int) = prefs(ctx).edit().putInt("vlc_cache_ms", v).apply()
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
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                LampButton("Готово") { close() }
            }
        }
    }
}

@Composable
private fun Section(title: String, text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, modifier = Modifier.padding(top = top))
    Text(text, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
}

@Composable
private fun PresetRow(p: PlaybackSettings.Preset, on: Boolean, onClick: () -> Unit) {
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
