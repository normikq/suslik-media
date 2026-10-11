package ru.suslikovanora.media.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What changed, in plain words, for the last big releases (newest first). Shown once after an update. */
object Changelog {
    data class Release(val version: String, val title: String, val items: List<String>)

    val releases = listOf(
        Release("1.6.0", "Плеер стал плавнее", listOf(
            "Пауза, перемотка и смена серии больше не подвешивают окно — всё откликается сразу.",
            "Озвучка из отдельного файла больше не убегает от картинки после перемотки и паузы.",
            "Выбранные вами озвучка и субтитры включаются сразу, без паузы в звуке.",
            "Плеер открывается быстрее: он готовится заранее, пока вы выбираете, что посмотреть.",
            "Появилось это окно — теперь после обновления видно, что нового."
        )),
        Release("1.5.2", "Обновления в один клик", listOf(
            "Когда выходит новая версия, в шапке мягко «дышит» кнопка обновления.",
            "Одно нажатие — приложение само скачает обновление, установит его и откроется снова.",
            "Старые установщики удаляются сами и не занимают место."
        )),
        Release("1.5.1", "Избранное и новый плеер", listOf(
            "Звёздочка на постере: избранное всегда сверху библиотеки, у каждого своё.",
            "Список «Продолжить просмотр» можно очистить целиком или убрать отдельный фильм.",
            "Новая вкладка «Ссылка»: добавляйте раздачи по magnet-ссылке или .torrent-файлом.",
            "Трилогии и сборники показывают все фильмы, а не только первый.",
            "В плеере: скорость, главы, пропуск заставки, мини-плеер, «поверх окон», ночной звук и размер субтитров.",
            "Полноэкранный режим, а панель управления плавно прячется, когда не нужна."
        ))
    )
}

private val seen = Store("whatsnew")

/** «Что нового» over the library, once per installed version. */
@Composable
fun WhatsNew(enabled: Boolean) {
    val current = Updater.current
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (enabled && current != "0" && seen.get("version") != current &&
            Changelog.releases.any { !Updater.isNewer(it.version, current) }) {
            delay(700) // the library appears first
            open = true
        }
    }
    if (open) WhatsNewCard(current) { seen.put("version", current); open = false }
}

@Composable
private fun WhatsNewCard(current: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    val releases = remember(current) { Changelog.releases.filter { !Updater.isNewer(it.version, current) }.take(3) }
    // Every block slides in a little after the previous one.
    val items = remember { releases.map { Animatable(0f) } }
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
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Sm.scrim)
            .hoverClick(remember { MutableInteractionSource() }) { close() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer {
                val v = shown.value
                translationY = (1f - v) * 36f; scaleX = 0.96f + 0.04f * v; scaleY = scaleX
            }
                .widthIn(max = 560.dp).padding(24.dp).clip(RoundedCornerShape(22.dp)).background(Sm.booth)
                .border(1.dp, Sm.line2, RoundedCornerShape(22.dp))
                .hoverClick(remember { MutableInteractionSource() }) {}
                .padding(start = 26.dp, end = 26.dp, top = 24.dp, bottom = 20.dp)
        ) {
            Text("Что нового", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 22.sp)
            Text("Suslik Media обновился до версии $current", color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                releases.forEachIndexed { i, r ->
                    val a = items[i]
                    Column(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 22f }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (i == 0) Sm.lamp else Sm.film)
                                .padding(horizontal = 9.dp, vertical = 3.dp)) {
                                Text(r.version, color = if (i == 0) Sm.lampInk else Sm.muted, fontFamily = Onest,
                                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Text(r.title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp,
                                modifier = Modifier.padding(start = 10.dp))
                        }
                        Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                LampButton("Понятно") { close() }
            }
        }
    }
}
