package ru.suslikovanora.media

import androidx.compose.ui.focus.focusRequester
import ru.suslikovanora.media.ui.rememberTvStartFocus
import ru.suslikovanora.media.ui.keepFocusInside
import ru.suslikovanora.media.ui.noFocus
import ru.suslikovanora.media.core.*

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
import androidx.compose.ui.graphics.Color
import ru.suslikovanora.media.library.LampButton
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import ru.suslikovanora.media.ui.glassSheet

/** The notes this build carries (assets/changelog.json); parsing and choosing are shared (core.Changelog). */
private fun bundledNotes(ctx: Context, current: String): List<Changelog.Release> = runCatching {
    val text = ctx.assets.open("changelog.json").use { it.readBytes().toString(Charsets.UTF_8) }
    Changelog.upTo(Changelog.parse(text), current)
}.getOrDefault(emptyList())

private fun seenPrefs(ctx: Context) = ctx.getSharedPreferences("whatsnew", Context.MODE_PRIVATE)

/** «Что нового» over the library, once per installed version. */
@Composable
fun WhatsNew(enabled: Boolean) {
    val ctx = LocalContext.current
    val current = remember { Updater.currentVersion(ctx) }
    var releases by remember { mutableStateOf<List<Changelog.Release>?>(null) }
    fun markSeen() = seenPrefs(ctx).edit().putString("version", current).apply()
    LaunchedEffect(enabled) {
        if (enabled && current != "0" && seenPrefs(ctx).getString("version", null) != current) {
            val list = bundledNotes(ctx, current)
            // Nothing new to tell (a small fix without its own notes: the newest notes were already shown).
            if (!Changelog.shouldShow(list, seenPrefs(ctx).getString("version", null))) { markSeen(); return@LaunchedEffect }
            delay(700) // the library appears first
            releases = list
        }
    }
    releases?.let { list -> WhatsNewCard(current, list) { markSeen(); releases = null } }
}

@Composable
private fun WhatsNewCard(current: String, releases: List<Changelog.Release>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
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
    val start = rememberTvStartFocus()
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Sm.scrim)
            .noFocus().clickable(remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer {
                val v = shown.value
                translationY = (1f - v) * 60f; scaleX = 0.96f + 0.04f * v; scaleY = scaleX
            }
                .widthIn(max = 520.dp).padding(18.dp).glassSheet(RoundedCornerShape(22.dp))
                .keepFocusInside()
                .noFocus().clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp)
        ) {
            Text(tr("whatsnew.chto_novogo"), color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 21.sp)
            Text(tr("whatsnew.suslik_media_obnovilsya_do", "current" to current), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Column(Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                releases.forEachIndexed { i, r ->
                    val a = items[i]
                    Column(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40f }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (i == 0) Sm.accent else Sm.glassFill)
                                .padding(horizontal = 9.dp, vertical = 3.dp)) {
                                Text(r.version, color = if (i == 0) Color.White else Sm.muted, fontFamily = Onest,
                                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Text(r.title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                modifier = Modifier.padding(start = 10.dp))
                        }
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            r.items.forEach { line ->
                                Row {
                                    Box(Modifier.padding(top = 7.dp, end = 10.dp).size(5.dp).clip(CircleShape)
                                        .background(if (i == 0) Sm.accent else Sm.glassEdge))
                                    Text(line, color = if (i == 0) Sm.text else Sm.muted, fontFamily = Onest, fontSize = 14.sp, lineHeight = 20.sp)
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                LampButton(tr("whatsnew.ponyatno"), modifier = Modifier.focusRequester(start)) { close() }
            }
        }
    }
}
