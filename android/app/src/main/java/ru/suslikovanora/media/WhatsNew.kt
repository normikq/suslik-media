package ru.suslikovanora.media

import android.content.Context
import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.suslikovanora.media.library.LampButton
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded

/** What changed, in plain words, for the last big releases (newest first). Shown once after an update. */
object Changelog {
    data class Release(val version: String, val title: String, val items: List<String>)

    val releases = listOf(
        Release("1.6.0", "Плеер стал стабильнее", listOf(
            "Озвучка из отдельного файла больше не сбивается после паузы и перемотки.",
            "Выбранные вами озвучка и субтитры включаются сразу после старта серии.",
            "Меньше заминок звука при продолжении просмотра.",
            "Появилось это окно — теперь после обновления видно, что нового."
        )),
        Release("1.5.0", "Полностью новое приложение", listOf(
            "Все вкладки теперь «родные» — приложение открывается быстрее и работает плавнее.",
            "Вкладка «Ссылка»: magnet-ссылка или .torrent-файл прямо с телефона.",
            "Magnet-ссылки из браузера и других приложений открываются сразу в Suslik Media.",
            "Поиск раздач работает внутри приложения, а добавленное видно в истории."
        )),
        Release("1.4.1", "Избранное и обновления", listOf(
            "Звёздочка на постере: избранное всегда сверху библиотеки.",
            "Библиотека больше не мигает при открытии и обновлении.",
            "Приложение само сообщает о новой версии и устанавливает её в пару нажатий.",
            "Новая иконка."
        ))
    )
}

private fun seenPrefs(ctx: Context) = ctx.getSharedPreferences("whatsnew", Context.MODE_PRIVATE)

/** «Что нового» over the library, once per installed version. */
@Composable
fun WhatsNew(enabled: Boolean) {
    val ctx = LocalContext.current
    val current = remember { Updater.currentVersion(ctx) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (enabled && current != "0" && seenPrefs(ctx).getString("version", null) != current &&
            Changelog.releases.any { !Updater.isNewer(it.version, current) }) {
            delay(700) // the library appears first
            open = true
        }
    }
    if (open) WhatsNewCard(current) { seenPrefs(ctx).edit().putString("version", current).apply(); open = false }
}

@Composable
private fun WhatsNewCard(current: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    val releases = remember(current) { Changelog.releases.filter { !Updater.isNewer(it.version, current) }.take(3) }
    val items = remember { releases.map { Animatable(0f) } } // each block slides in a little after the previous one
    LaunchedEffect(Unit) {
        launch { shown.animateTo(1f, tween(380, easing = Sm.ease)) }
        items.forEachIndexed { i, a -> launch { delay(180L + i * 110L); a.animateTo(1f, tween(460, easing = Sm.ease)) } }
    }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { shown.animateTo(0f, tween(240, easing = Sm.ease)); onDone() }
    }
    BackHandler { close() }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Sm.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer {
                val v = shown.value
                translationY = (1f - v) * 60f; scaleX = 0.96f + 0.04f * v; scaleY = scaleX
            }
                .widthIn(max = 520.dp).padding(18.dp).clip(RoundedCornerShape(22.dp)).background(Sm.booth)
                .border(1.dp, Sm.line2, RoundedCornerShape(22.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp)
        ) {
            Text("Что нового", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            Text("Suslik Media обновился до версии $current", color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Column(Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                releases.forEachIndexed { i, r ->
                    val a = items[i]
                    Column(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40f }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (i == 0) Sm.lamp else Sm.film)
                                .padding(horizontal = 9.dp, vertical = 3.dp)) {
                                Text(r.version, color = if (i == 0) Sm.lampInk else Sm.muted, fontFamily = Onest,
                                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Text(r.title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                modifier = Modifier.padding(start = 10.dp))
                        }
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            r.items.forEach { line ->
                                Row {
                                    Box(Modifier.padding(top = 7.dp, end = 10.dp).size(5.dp).clip(CircleShape)
                                        .background(if (i == 0) Sm.lamp else Sm.line2))
                                    Text(line, color = if (i == 0) Sm.text else Sm.muted, fontFamily = Onest, fontSize = 14.sp, lineHeight = 20.sp)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                LampButton("Понятно") { close() }
            }
        }
    }
}
