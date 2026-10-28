package ru.suslikovanora.media.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.suslikovanora.media.core.TimeRange
import ru.suslikovanora.media.tr
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm

/** One line of the audio / subtitles sheet. */
data class TrackChoice(val label: String, val on: Boolean)

/**
 * What the glass panel over the film shows (the same look as the Windows player); written by PlayerActivity,
 * read by [PlayerOverlay]. The panel sits in one ComposeView over the film: where it has no button,
 * touches fall through to the film's gestures ([PlayerGestures]).
 */
class PlayerUiState {
    var shown by mutableStateOf(true)
    var full by mutableStateOf(true)     // sideways / TV; upright the buttons live in the panel below the film
    var tv by mutableStateOf(false)
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var time by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var hasPrev by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var volume by mutableIntStateOf(0)
    var volumeMax by mutableIntStateOf(15)
    var scales by mutableStateOf<List<String>>(emptyList())
    var scaleIndex by mutableIntStateOf(0)
    var picker by mutableStateOf<String?>(null) // "audio", "spu", "more"
    var pickerItems by mutableStateOf<List<TrackChoice>>(emptyList())
    var nextTitle by mutableStateOf<String?>(null) // «Следующая серия: S01E02» / «Следующий фильм: …» with its countdown
    /** Inside the intro (core.MarkersDetector): the «Пропустить заставку» button bottom right. */
    var introShown by mutableStateOf(false)
    var introRange by mutableStateOf<TimeRange?>(null)
    var creditsRange by mutableStateOf<TimeRange?>(null)
    /** A guest of the shared room: the timeline is a «live» line (the host seeks for everyone). */
    var live by mutableStateOf(false)
    var nextIn by mutableIntStateOf(0)
    var showLock by mutableStateOf(true)
    var showMark by mutableStateOf(false)
    var insets by mutableStateOf(IntArray(4)) // left, top, right, bottom (px): camera cutout and system bars

    /** Remote control: where focus goes once the panel is composed ("play", "seek"). */
    var focusWant by mutableStateOf<String?>(null)
    /** Read by the activity's key handling (the remote's ←/→ on the timeline seek by 10 s). */
    var seekFocused = false
    var hasFocus = false

    var onBack: () -> Unit = {}
    var onPlayPause: () -> Unit = {}
    var onSeekBy: (Long) -> Unit = {}
    var onScrub: (Long?) -> Unit = {}
    var onSeekTo: (Long) -> Unit = {}
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onFull: () -> Unit = {}
    var onLock: () -> Unit = {}
    var onMark: () -> Unit = {}
    var onPicker: (String?) -> Unit = {}
    var onPick: (String, Int) -> Unit = { _, _ -> }
    var onScale: (Int) -> Unit = {}
    /** Сдвиг субтитров и звука этого файла, мс (+ — позже); [onSubDelay] / [onAudioDelay] получают новое значение целиком. */
    var subDelay by mutableLongStateOf(0L)
    var audioDelay by mutableLongStateOf(0L)
    var onSubDelay: (Long) -> Unit = {}
    var onAudioDelay: (Long) -> Unit = {}
    var onVolume: (Int) -> Unit = {}
    var onNextNow: () -> Unit = {}
    var onNextCancel: () -> Unit = {}
    var onSkipIntro: () -> Unit = {}
    /** Any touch on the panel: the hide timer starts again. */
    var onPoke: () -> Unit = {}
}

private val sheetEase = CubicBezierEasing(0.16f, 1.0f, 0.30f, 1.0f)
private val LiveRed = Color(0xFFE5484D)

@Composable
fun PlayerOverlay(s: PlayerUiState) {
    val d = LocalDensity.current
    val ins = s.insets
    val pad = with(d) { PaddingValues(start = ins[0].toDp(), top = ins[1].toDp(), end = ins[2].toDp(), bottom = ins[3].toDp()) }
    Box(Modifier.fillMaxSize().onFocusChanged { s.hasFocus = it.hasFocus }) {
        AnimatedVisibility(
            visible = s.shown,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = Sm.ease)) { -it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(220, easing = FastOutLinearInEasing)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) { TopBar(s, pad) }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(
                visible = s.introShown && s.nextTitle == null,
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = Sm.ease)) { it / 2 },
                exit = fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.End)
            ) {
                SkipIntroButton(s, Modifier.padding(end = 16.dp + with(d) { ins[2].toDp() }, bottom = 10.dp + if (s.shown) 0.dp else with(d) { ins[3].toDp() }))
            }
            AnimatedVisibility(
                visible = s.nextTitle != null,
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = Sm.ease)) { it / 2 },
                exit = fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.End)
            ) {
                NextCapsule(s, Modifier.padding(end = 16.dp + with(d) { ins[2].toDp() }, bottom = 10.dp + if (s.shown) 0.dp else with(d) { ins[3].toDp() }))
            }
            AnimatedVisibility(
                visible = s.shown,
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = Sm.ease)) { it / 3 },
                exit = fadeOut(tween(200)) + slideOutVertically(tween(220, easing = FastOutLinearInEasing)) { it / 3 }
            ) { BottomBar(s, pad) }
        }
    }
}

@Composable
private fun TopBar(s: PlayerUiState, pad: PaddingValues) {
    val full = s.full
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC090B10), Color(0x66090B10), Color.Transparent)))
            .padding(start = pad.startPad(), end = pad.endPad(), top = pad.calculateTopPadding())
            .padding(horizontal = if (full) 16.dp else 6.dp, vertical = if (full) 10.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(onClick = s.onBack, size = if (full) 46.dp else 42.dp, description = tr("player.nazad")) {
            BackChevronIcon(color = Sm.text, size = 22.dp)
        }
        Text(
            s.title, color = Sm.text.copy(alpha = 0.92f), fontFamily = Onest, fontWeight = FontWeight.Medium,
            fontSize = if (full) 15.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)
        )
        if (full && s.showLock) GlassIconButton(onClick = s.onLock, size = 46.dp, description = tr("player.blokirovka_ekrana")) {
            LockIcon(color = Sm.text, size = 21.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomBar(s: PlayerUiState, pad: PaddingValues) {
    val full = s.full
    val btn = if (full) 46.dp else 40.dp
    val hero = if (full) 56.dp else 48.dp
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    LaunchedEffect(s.focusWant) {
        val want = s.focusWant ?: return@LaunchedEffect
        s.focusWant = null
        runCatching { if (want == "seek") seekFocus.requestFocus() else playFocus.requestFocus() }
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33090B10), Color(0xB3090B10), Color(0xF0090B10))))
            .padding(start = pad.startPad(), end = pad.endPad(), bottom = pad.calculateBottomPadding())
            .padding(start = if (full) 18.dp else 8.dp, end = if (full) 18.dp else 8.dp, top = if (full) 30.dp else 14.dp, bottom = if (full) 8.dp else 2.dp)
    ) {
        if (full) {
            AnimatedVisibility(
                visible = s.picker != null,
                enter = fadeIn(tween(180, easing = LinearOutSlowInEasing)) + slideInVertically(tween(220, easing = sheetEase)) { 24 },
                exit = fadeOut(tween(140, easing = FastOutLinearInEasing)) + slideOutVertically(tween(160, easing = FastOutLinearInEasing)) { 24 }
            ) { PlayerSheet(s, Modifier.padding(bottom = 12.dp)) }
        }

        // timeline: time · bar · length
        var scrub by remember { mutableStateOf<Long?>(null) }
        val animBuffered by animateFloatAsState(s.buffered.toFloat(), tween(260), label = "buffered")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val active = scrub != null
            Box(
                Modifier.defaultMinSize(minWidth = 52.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (active) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                if (s.live) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(LiveRed))
                    Text(tr("party.vmeste"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                } else Text(fmt(scrub ?: s.time), color = Color.White, fontFamily = Onest,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
            }
            if (s.live) {
                // As in a stream: the line is full, no seeking (the host seeks for the room)
                Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(LiveRed))
            } else SeekBar(
                position = scrub ?: s.time, length = s.length, buffered = animBuffered.toLong(),
                intro = s.introRange, credits = s.creditsRange,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp).focusRequester(seekFocus),
                onFocus = { s.seekFocused = it },
                onScrub = { t -> scrub = t; s.onScrub(t); s.onPoke() },
                onSeek = { t -> scrub = null; s.onSeekTo(t) }
            )
            Text(if (s.live) fmt(s.time) else if (s.length > 0) fmt(s.length) else "--:--", color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium,
                fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.End,
                modifier = Modifier.defaultMinSize(minWidth = 52.dp).padding(horizontal = 6.dp, vertical = 2.dp))
        }

        // three zones: episodes · ↺10 ▶ ↻10 · tracks, settings, volume, full screen
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = if (full) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.hasPrev) GlassIconButton(onClick = s.onPrev, size = btn, description = tr("player.predyduschaya_seriya")) {
                        PrevEpisodeIcon(color = Sm.text, size = 16.dp)
                    }
                    if (s.hasNext) GlassIconButton(onClick = s.onNext, size = btn, description = tr("player.sleduyuschaya_seriya")) {
                        NextEpisodeIcon(color = Sm.text, size = 16.dp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (full) 14.dp else 8.dp)) {
                GlassIconButton(onClick = { s.onSeekBy(-10_000L) }, size = btn, description = tr("overlay.nazad_10")) {
                    Seek10Icon(forward = false, color = Color.White)
                }
                HeroPlayButton(s.playing, s.onPlayPause, hero, Modifier.focusRequester(playFocus))
                GlassIconButton(onClick = { s.onSeekBy(10_000L) }, size = btn, description = tr("overlay.vpered_10")) {
                    Seek10Icon(forward = true, color = Color.White)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
                    if (full) {
                        GlassIconButton(onClick = { toggle(s, "audio") }, active = s.picker == "audio", size = btn, description = tr("playertrack.audiodorozhka")) {
                            AudioTrackIcon(color = Color.White, size = 20.dp)
                        }
                        GlassIconButton(onClick = { toggle(s, "spu") }, active = s.picker == "spu", size = btn, description = tr("playertrack.subtitry")) {
                            SubtitlesBadgeIcon(color = Color.White, size = 22.dp)
                        }
                        GlassIconButton(onClick = { toggle(s, "more") }, active = s.picker == "more", size = btn, description = tr("overlay.nastroyki")) {
                            SettingsGearIcon(color = Color.White, size = 20.dp)
                        }
                        if (!s.tv) VolumeButton(s, btn)
                    }
                    if (!s.tv) GlassIconButton(
                        onClick = s.onFull, size = btn,
                        description = if (full) tr("player.vyyti_iz_polnoekrannogo_rezhim") else tr("player.na_ves_ekran")
                    ) { FullscreenToggleIcon(fullscreen = full, color = Color.White, size = 20.dp) }
                }
            }
        }
    }
}

private fun toggle(s: PlayerUiState, kind: String) = s.onPicker(if (s.picker == kind) null else kind)

@Composable
private fun PaddingValues.startPad(): Dp = calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)

@Composable
private fun PaddingValues.endPad(): Dp = calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)

/** Audio tracks, subtitles or «⚙»: a glass sheet over the bar (upright — at the top of the panel below the film). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerSheet(s: PlayerUiState, modifier: Modifier = Modifier) {
    var kind by remember { mutableStateOf("audio") }
    s.picker?.let { kind = it }
    val first = remember { FocusRequester() }
    LaunchedEffect(s.picker) { if (s.picker != null && s.tv) { delay(60); runCatching { first.requestFocus() } } }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xF20E1118))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures { s.onPoke() } } // taps on the sheet stay on it
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (kind) { "audio" -> tr("playertrack.audiodorozhka"); "spu" -> tr("playertrack.subtitry"); else -> tr("overlay.nastroyki_vosproizvedeniya") },
                    color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                )
                GlassIconButton(onClick = { s.onPicker(null) }, size = 36.dp, description = tr("player.zakryt")) {
                    CloseCrossIcon(color = Color.White.copy(alpha = 0.7f), size = 12.dp)
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState()).padding(end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (kind == "more") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SheetLabel(tr("overlay.kadr"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.scales.forEachIndexed { i, name ->
                                PlayerChip(name, i == s.scaleIndex, if (i == 0) Modifier.focusRequester(first) else Modifier) { s.onScale(i) }
                            }
                        }
                    }
                    DelayRow(tr("overlay.sdvig_subtitrov"), s.subDelay) { s.onSubDelay(it); s.onPoke() }
                    DelayRow(tr("overlay.sdvig_zvuka"), s.audioDelay) { s.onAudioDelay(it); s.onPoke() }
                    Text(tr("overlay.sdvig_opisanie"), color = Color.White.copy(alpha = 0.45f), fontFamily = Onest, fontSize = 11.5.sp)
                    if (s.showLock || s.showMark) Row(verticalAlignment = Alignment.CenterVertically) {
                        SheetLabel(tr("overlay.ekran"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (s.showLock) PlayerChip(tr("player.blokirovka_ekrana"), false) { s.onLock() }
                            if (s.showMark) PlayerChip(tr("player.metka_v_zhurnale"), false) { s.onMark() }
                        }
                    }
                } else {
                    val items = s.pickerItems
                    if (items.isEmpty()) Text(tr("overlay.dorozhki_esche_ne_opredeleny"), color = Color.White.copy(alpha = 0.5f), fontFamily = Onest, fontSize = 13.sp)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items.forEachIndexed { i, c ->
                            val focus = if (items.none { it.on }) i == 0 else c.on
                            PlayerChip(c.label, c.on, if (focus) Modifier.focusRequester(first) else Modifier) { s.onPick(kind, i) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(t: String, width: Dp = 76.dp) =
    Text(t, color = Color.White.copy(alpha = 0.6f), fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.width(width))

/** «−0,5  −0,1  [значение]  +0,1  +0,5»: the middle chip shows the shift and, pressed, takes it back to 0 (the Kodi-style subtitle / audio offset). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DelayRow(label: String, ms: Long, onChange: (Long) -> Unit) {
    fun step(d: Long) = onChange(PlaybackTweaks.clamp(ms + d))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SheetLabel(label, 112.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerChip("−0,5", false) { step(-500) }
            PlayerChip("−0,1", false) { step(-100) }
            PlayerChip(tr("overlay.sdvig_znachenie", "v" to PlaybackTweaks.label(ms)), ms != 0L) { onChange(0L) }
            PlayerChip("+0,1", false) { step(100) }
            PlayerChip("+0,5", false) { step(500) }
        }
    }
}

/** «Пропустить заставку» over the intro (the Windows player's pill); the remote's OK presses it. */
@Composable
private fun SkipIntroButton(s: PlayerUiState, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xE6161B26))
            .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(50))
            .clickable(role = Role.Button) { s.onSkipIntro() }
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(tr("player.propustit_zastavku"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** «Следующая: …  через N с  ▶  ✕» — the Windows player's capsule. */
@Composable
private fun NextCapsule(s: PlayerUiState, modifier: Modifier) {
    Row(
        modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(50)).background(Color(0xE6161B26))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(s.nextTitle ?: "", color = Sm.text, fontFamily = Onest, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.5.dp)
        ) {
            Text(tr("playerplaylist.cherez_countdown_s", "countdown" to s.nextIn), color = Color.White, fontFamily = Onest,
                fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, maxLines = 1)
        }
        GlassIconButton(onClick = s.onNextNow, size = 38.dp, description = tr("playerplaylist.smotret_seychas")) {
            NextPlayTriangleIcon(color = Color.White, size = 13.dp)
        }
        GlassIconButton(onClick = s.onNextCancel, size = 38.dp, description = tr("playerplaylist.otmena")) {
            CloseCrossIcon(color = Color.White, size = 12.dp)
        }
    }
}

/** Speaker: a tap opens the system volume slider beside it (closes by itself after a few seconds). */
@Composable
private fun VolumeButton(s: PlayerUiState, size: Dp) {
    var open by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }
    LaunchedEffect(open, touched) { if (open) { delay(3500); open = false } }
    val width by animateFloatAsState(if (open) 1f else 0f, tween(220, easing = Sm.ease), label = "vol")
    val max = s.volumeMax.coerceAtLeast(1)
    Row(
        Modifier.height(size).clip(RoundedCornerShape(50))
            .background(if (open) Color(0x99161B26) else Color.Transparent)
            .then(if (open) Modifier.border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50)) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(onClick = { open = !open; s.onPoke() }, active = open, size = size, description = tr("overlay.gromkost")) {
            VolumeSpeakerIcon(volume = s.volume * 100 / max, muted = s.volume == 0, color = Color.White, size = 22.dp)
        }
        if (width > 0.05f) {
            Slider(
                value = s.volume.toFloat(), valueRange = 0f..max.toFloat(),
                onValueChange = { s.onVolume(it.toInt()); touched++; s.onPoke() },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x4DFFFFFF)),
                modifier = Modifier.width(120.dp * width).height(24.dp).padding(end = 12.dp)
            )
        }
    }
}

/**
 * A round glass button: no fill at rest, a soft white glow when pressed, active or (remote control) focused.
 * Same as the Windows player's, with touch and D-pad instead of the mouse.
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    size: Dp = 44.dp,
    description: String? = null,
    content: @Composable () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val focused by src.collectIsFocusedAsState()
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val ring = focused && keys
    val scale by animateFloatAsState(if (pressed && enabled) 0.92f else if (ring) 1.06f else 1f, tween(160, easing = Sm.ease), label = "btn")
    val glow by animateFloatAsState(
        when { !enabled -> 0f; pressed -> 0.48f; ring || active -> 0.42f; else -> 0f }, tween(160), label = "glow"
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .size(size)
            .drawBehind {
                if (glow > 0.001f) {
                    val r = this.size.minDimension * 0.58f
                    drawCircle(
                        Brush.radialGradient(
                            0.0f to Color.White.copy(alpha = glow),
                            0.45f to Color.White.copy(alpha = glow * 0.50f),
                            0.80f to Color.White.copy(alpha = glow * 0.15f),
                            1.0f to Color.Transparent,
                            center = center, radius = r
                        ),
                        radius = r, center = center
                    )
                }
            }
            .then(if (ring) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.75f), CircleShape) else Modifier)
            .semantics { role = Role.Button; if (description != null) contentDescription = description }
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun HeroPlayButton(playing: Boolean, onClick: () -> Unit, size: Dp = 56.dp, modifier: Modifier = Modifier) {
    val k = size.value / 48f
    GlassIconButton(onClick = onClick, size = size, modifier = modifier, description = tr("player.pauza_vosproizvedenie")) {
        if (playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.5.dp * k), verticalAlignment = Alignment.CenterVertically) {
                repeat(2) { Box(Modifier.width(4.2.dp * k).height(18.dp * k).clip(RoundedCornerShape(1.5.dp)).background(Color.White)) }
            }
        } else {
            androidx.compose.foundation.Canvas(Modifier.size(20.dp * k).graphicsLayer { translationX = 1.5.dp.toPx() }) {
                val w = this.size.width
                val h = this.size.height
                val path = Path().apply {
                    moveTo(w * 0.18f, h * 0.12f)
                    lineTo(w * 0.88f, h * 0.50f)
                    lineTo(w * 0.18f, h * 0.88f)
                    close()
                }
                drawPath(path, color = Color.White, style = Fill)
            }
        }
    }
}

/** A choice in a sheet: white when on (the Windows player's chip). */
@Composable
fun PlayerChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val focused by src.collectIsFocusedAsState()
    val ring = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(if (pressed) 0.95f else if (ring) 1.05f else 1f, tween(160, easing = Sm.ease), label = "chip")
    val bg = when { on -> Color.White; pressed || ring -> Color(0x38FFFFFF); else -> Color(0x1F161B26) }
    val edge = when { on -> Color.White; ring -> Color(0xCCFFFFFF); pressed -> Color(0x66FFFFFF); else -> Color(0x33FFFFFF) }
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(9.dp)).background(bg).border(if (ring) 1.5.dp else 1.dp, edge, RoundedCornerShape(9.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Color(0xFF111418) else Color.White.copy(alpha = 0.9f), fontFamily = Onest,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The timeline: white progress over the loaded part, thicker while dragged or focused; tap to jump, drag to scrub.
 * [onScrub] reports the place while dragging (null when cancelled).
 */
@Composable
private fun SeekBar(
    position: Long,
    length: Long,
    buffered: Long,
    intro: TimeRange? = null,
    credits: TimeRange? = null,
    modifier: Modifier,
    onFocus: (Boolean) -> Unit,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit
) {
    var w by remember { mutableStateOf(1f) }
    var drag by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var scrubTo by remember { mutableLongStateOf(0L) }
    val ring = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val grow by animateFloatAsState(if (drag || ring) 1f else 0f, tween(180), label = "grow")
    fun at(x: Float) = if (length <= 0) 0L else ((x / w).coerceIn(0f, 1f) * length).toLong()
    Box(
        modifier.height(36.dp)
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .focusable(enabled = length > 0)
            .pointerInput(length) { detectTapGestures { o -> if (length > 0) onSeek(at(o.x)) } }
            .pointerInput(length) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (length > 0) { drag = true; scrubTo = at(o.x); onScrub(scrubTo) } },
                    onDragEnd = { if (drag) { drag = false; onSeek(scrubTo) } },
                    onDragCancel = { drag = false; onScrub(null) }
                ) { change, _ -> if (length > 0) { change.consume(); scrubTo = at(change.position.x); onScrub(scrubTo) } }
            }
            .drawBehind {
                val h = (4f + 3f * grow).dp.toPx()
                val cy = size.height / 2
                val r = CornerRadius(h / 2)
                drawRoundRect(Color(0x33FFFFFF), topLeft = Offset(0f, cy - h / 2), size = Size(size.width, h), cornerRadius = r)
                val bf = if (length > 0) (buffered.toFloat() / length).coerceIn(0f, 1f) else 0f
                if (bf > 0f) drawRoundRect(Color(0x80B4B8C4), topLeft = Offset(0f, cy - h / 2), size = Size(size.width * bf, h), cornerRadius = r)
                if (length > 0) {
                    if (intro != null && intro.endMs > intro.startMs) {
                        val x1 = size.width * (intro.startMs.toFloat() / length).coerceIn(0f, 1f)
                        val x2 = size.width * (intro.endMs.toFloat() / length).coerceIn(0f, 1f)
                        if (x2 > x1) drawRoundRect(Color(0x80F5A623), topLeft = Offset(x1, cy - h / 2), size = Size(x2 - x1, h), cornerRadius = r)
                    }
                    if (credits != null && credits.endMs > credits.startMs) {
                        val x1 = size.width * (credits.startMs.toFloat() / length).coerceIn(0f, 1f)
                        val x2 = size.width * (credits.endMs.toFloat() / length).coerceIn(0f, 1f)
                        if (x2 > x1) drawRoundRect(Color(0x808E96A8), topLeft = Offset(x1, cy - h / 2), size = Size(x2 - x1, h), cornerRadius = r)
                    }
                }
                val f = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
                if (f > 0f) drawRoundRect(Color.White, topLeft = Offset(0f, cy - h / 2), size = Size(size.width * f, h), cornerRadius = r)
                val thumb = Offset(size.width * f, cy)
                if (grow > 0.01f) {
                    val glowR = (16f * grow).dp.toPx()
                    drawCircle(
                        Brush.radialGradient(
                            0.0f to Color.White.copy(alpha = 0.52f * grow),
                            0.45f to Color.White.copy(alpha = 0.22f * grow),
                            1.0f to Color.Transparent,
                            center = thumb, radius = glowR
                        ),
                        radius = glowR, center = thumb
                    )
                }
                if (length > 0) drawCircle(Color.White, radius = (5f + 3f * grow).dp.toPx(), center = thumb)
            }
    )
}

private fun fmt(ms: Long): String = ru.suslikovanora.media.core.clockText(ms)
