package ru.suslikovanora.media.desktop.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.suslikovanora.media.desktop.ui.Onest
import ru.suslikovanora.media.desktop.ui.Sm

@Composable
fun PlayerTopBar(
    title: String,
    queueSize: Int,
    index: Int,
    onTop: Boolean,
    onToggleOnTop: () -> Unit,
    closing: Boolean,
    barSlide: Float,
    onClose: () -> Unit,
    onPoke: () -> Unit
) {
    val controlsAlpha = if (closing) 0f else (1f - barSlide).coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xCC090B10),
                        Color(0x66090B10),
                        Color.Transparent
                    )
                )
            )
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .graphicsLayer {
                translationY = -barSlide * 90.dp.toPx()
                alpha = controlsAlpha
            }
            .pointerHoverIcon(PointerIcon.Default)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Press) {
                            onPoke()
                        }
                    }
                }
            },
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(onClick = onClose, tooltip = "В библиотеку (Esc)") {
                Text("‹", color = Sm.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            GlassIconButton(
                onClick = onToggleOnTop,
                active = onTop,
                tooltip = if (onTop) "Открепить окно (T)" else "Поверх всех окон (T)"
            ) {
                Text("⧉", color = Sm.text, fontSize = 18.sp)
            }
        }
        Text(
            title + if (queueSize > 1) "  ·  ${index + 1}/$queueSize" else "",
            color = Sm.text.copy(alpha = 0.9f),
            fontFamily = Onest,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp).weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerBottomBar(
    title: String,
    queue: List<Entry>,
    index: Int,
    playing: Boolean,
    time: Long,
    length: Long,
    bufferedMs: Long,
    status: String?,
    closing: Boolean,
    barSlide: Float,
    rate: Float,
    rates: List<Float>,
    subScale: Int,
    subDelay: Long,
    nightMode: Boolean,
    onTop: Boolean,
    mini: Boolean,
    infoOpen: Boolean,
    infoText: String,
    chapters: List<Pair<Long, String>>,
    chapterAtTime: Int,
    picker: String?,
    pickerItems: List<Pair<Int, String>>,
    pickerCurrent: Int,
    volume: Int,
    userMuted: Boolean,
    fullscreen: Boolean,
    lastVolumeWheelAt: Long,
    nextIn: Int,
    inIntro: Boolean,
    onPoke: () -> Unit,
    onTogglePause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayEntry: (Int) -> Unit,
    onSkipIntro: () -> Unit,
    onClosePicker: () -> Unit,
    onOpenPicker: (String) -> Unit,
    onSelectTrack: (String, Int, String) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onMoreRate: (Float) -> Unit,
    onMoreSubScale: (Int) -> Unit,
    onMoreSubDelay: (Long) -> Unit,
    onMoreNight: () -> Unit,
    onMoreOnTop: () -> Unit,
    onMoreMini: () -> Unit,
    onMoreInfo: () -> Unit,
    onMoreChapter: (Int) -> Unit,
    onCancelNextIn: () -> Unit,
    onMiniToggle: () -> Unit,
    onVolumeWheel: (Float) -> Unit
) {
    val controlsAlpha = if (closing) 0f else (1f - barSlide).coerceIn(0f, 1f)
    var seeking by remember { mutableStateOf<Float?>(null) }
    var hoverAt by remember { mutableStateOf<Long?>(null) }

    val animBuffered by animateFloatAsState(
        targetValue = bufferedMs.toFloat(),
        animationSpec = tween(260)
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val isCompact = totalWidth < 880.dp
        val isVeryNarrow = totalWidth < 680.dp
        val buttonSpacing = when {
            isVeryNarrow -> 4.dp
            isCompact -> 6.dp
            else -> 10.dp
        }
        val btnSize = when {
            isVeryNarrow -> 34.dp
            isCompact -> 38.dp
            else -> 42.dp
        }
        val heroSize = when {
            isVeryNarrow -> 40.dp
            isCompact -> 44.dp
            else -> 48.dp
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0x33090B10),
                            Color(0xB3090B10),
                            Color(0xF0090B10)
                        )
                    )
                )
                .padding(start = if (isVeryNarrow) 12.dp else 24.dp, end = if (isVeryNarrow) 12.dp else 24.dp, bottom = 14.dp)
                .graphicsLayer {
                    translationY = barSlide * 300.dp.toPx()
                    alpha = controlsAlpha
                }
                .pointerHoverIcon(PointerIcon.Default)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press) {
                                onPoke()
                            } else if (e.type == PointerEventType.Scroll) {
                                val delta = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) {
                                    onVolumeWheel(delta)
                                }
                            }
                        }
                    }
                },
            verticalArrangement = Arrangement.Bottom
        ) {
            if (infoOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xD90E1118))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        infoText.ifBlank { "Собираем данные…" },
                        color = Color.White.copy(alpha = 0.85f),
                        fontFamily = Onest,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            if (inIntro) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC161B26))
                            .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(50))
                            .clickable { onSkipIntro() }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Пропустить заставку (S)", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("⏭", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            if (picker == "more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF20E1118))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Настройки воспроизведения", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            GlassIconButton(onClick = onClosePicker, size = 26.dp) {
                                Text("✕", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            }
                        }
                        MorePanel(
                            rates = rates, rate = rate, onRate = onMoreRate,
                            subScale = subScale, onSubScale = onMoreSubScale,
                            subDelay = subDelay, onSubDelay = onMoreSubDelay,
                            night = nightMode, onNight = onMoreNight,
                            onTop = onTop, onOnTop = onMoreOnTop, mini = mini, onMini = onMoreMini,
                            info = infoOpen, onInfo = onMoreInfo,
                            chapters = chapters, current = chapterAtTime, onChapter = onMoreChapter
                        )
                    }
                }
            }

            if (picker == "audio" || picker == "spu") picker?.let { kind ->
                val current = pickerCurrent
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF20E1118))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (kind == "audio") "Аудиодорожки" else "Субтитры",
                                color = Color.White,
                                fontFamily = Onest,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            GlassIconButton(onClick = onClosePicker, size = 26.dp) {
                                Text("✕", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            }
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (pickerItems.none { it.first != -1 })
                                Text("Дорожки ещё не определены", color = Color.White.copy(alpha = 0.5f), fontFamily = Onest, fontSize = 13.sp)
                            pickerItems.forEach { (id, name) ->
                                val on = id == current
                                PlayerChip(name, on) {
                                    onSelectTrack(kind, id, name)
                                }
                            }
                        }
                    }
                }
            }

            if (nextIn >= 0 && index < queue.size - 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC161B26))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Следующая: ${queue[index + 1].title}", color = Sm.text, fontFamily = Onest, fontSize = 13.sp, maxLines = 1)
                        Text("через $nextIn с", color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        GlassIconButton(onClick = { onPlayEntry(index + 1) }, size = 30.dp, tooltip = "Включить сейчас") {
                            Text("▶", color = Sm.text, fontSize = 11.sp)
                        }
                        GlassIconButton(onClick = onCancelNextIn, size = 30.dp, tooltip = "Отмена") {
                            Text("✕", color = Sm.text, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Scrubber row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                val isScrubActive = (hoverAt != null || seeking != null)
                val timeGlowAlpha by animateFloatAsState(
                    targetValue = if (isScrubActive) 0.38f else 0f,
                    animationSpec = tween(160)
                )
                Box(
                    modifier = Modifier
                        .width(54.dp)
                        .drawBehind {
                            if (timeGlowAlpha > 0.001f) {
                                val r = size.maxDimension * 0.70f
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colorStops = arrayOf(
                                            0.0f to Color.White.copy(alpha = timeGlowAlpha),
                                            0.45f to Color.White.copy(alpha = timeGlowAlpha * 0.45f),
                                            1.0f to Color.Transparent
                                        ),
                                        center = center,
                                        radius = r
                                    ),
                                    radius = r,
                                    center = center
                                )
                            }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        fmt(hoverAt ?: seeking?.toLong() ?: time),
                        color = Color.White,
                        fontFamily = Onest,
                        fontWeight = if (isScrubActive) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
                SeekBar(
                    position = seeking?.toLong() ?: time,
                    length = length,
                    buffered = animBuffered.toLong(),
                    marks = chapters.map { it.first },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    onScrub = { t -> seeking = t?.toFloat(); onPoke() },
                    onSeek = { t ->
                        seeking = null
                        onSeekTo(t)
                    },
                    onHover = { t -> hoverAt = t }
                )
                Text(
                    if (length > 0) fmt(length) else "--:--",
                    color = Sm.muted,
                    fontFamily = Onest,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.width(54.dp),
                    textAlign = TextAlign.End
                )
            }

            if (mini) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroPlayButton(playing = playing, onClick = onTogglePause)
                    if (index < queue.size - 1) {
                        GlassIconButton(onClick = { onPlayEntry(index + 1) }, tooltip = "Следующая") {
                            NextEpisodeIcon(color = Sm.text, size = 16.dp)
                        }
                    }
                    Text(
                        title,
                        color = Sm.text.copy(alpha = 0.9f),
                        fontFamily = Onest,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                    GlassIconButton(onClick = onMiniToggle, tooltip = "Обычный режим (O)") {
                        Text("⤢", color = Sm.text, fontSize = 16.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left group (Next episode, status)
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
                        ) {
                            if (index < queue.size - 1) {
                                GlassIconButton(
                                    onClick = { onPlayEntry(index + 1) },
                                    tooltip = "Следующая: ${queue[index + 1].title}",
                                    size = btnSize
                                ) {
                                    NextEpisodeIcon(color = Sm.text, size = if (isCompact) 14.dp else 16.dp)
                                }
                            }
                            status?.let {
                                Text(it, color = Color.White, fontFamily = Onest, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    // Center Controls (Rewind 10, Hero Play/Pause, Forward 10)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)
                    ) {
                        GlassIconButton(
                            onClick = { onSeekBy(-10_000L) },
                            size = if (isCompact) 36.dp else 40.dp
                        ) {
                            Seek10Icon(forward = false, color = Sm.text)
                        }
                        HeroPlayButton(playing = playing, onClick = onTogglePause, size = heroSize)
                        GlassIconButton(
                            onClick = { onSeekBy(10_000L) },
                            size = if (isCompact) 36.dp else 40.dp
                        ) {
                            Seek10Icon(forward = true, color = Sm.text)
                        }
                    }

                    // Right group (Separator, Audio, Subtitles, Settings, Volume capsule, Fullscreen)
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(buttonSpacing, Alignment.End)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .width(1.dp)
                                    .height(18.dp)
                                    .background(Color(0x33FFFFFF))
                            )
                            GlassIconButton(
                                onClick = { if (picker == "audio") onClosePicker() else onOpenPicker("audio") },
                                active = (picker == "audio"),
                                size = btnSize
                            ) {
                                AudioTrackIcon(
                                    color = Color.White,
                                    size = if (isCompact) 18.dp else 20.dp
                                )
                            }
                            GlassIconButton(
                                onClick = { if (picker == "spu") onClosePicker() else onOpenPicker("spu") },
                                active = (picker == "spu"),
                                size = btnSize
                            ) {
                                SubtitlesBadgeIcon(
                                    color = Color.White,
                                    size = if (isCompact) 20.dp else 22.dp
                                )
                            }
                            GlassIconButton(
                                onClick = { if (picker == "more") onClosePicker() else onOpenPicker("more") },
                                active = (picker == "more"),
                                size = btnSize
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SettingsGearIcon(
                                        color = Color.White,
                                        size = if (isCompact) 18.dp else 20.dp
                                    )
                                    if (rate != 1f && !isVeryNarrow) {
                                        Text(" ${fmtRate(rate)}", color = Color.White, fontFamily = Onest, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            VolumeGlassCapsule(
                                volume = volume,
                                muted = userMuted,
                                onVolumeChange = onVolumeChange,
                                onMuteToggle = onMuteToggle,
                                verticalOnly = isCompact,
                                size = btnSize,
                                wheelTrigger = lastVolumeWheelAt
                            )
                            GlassIconButton(
                                onClick = onFullscreenToggle,
                                size = btnSize
                            ) {
                                FullscreenToggleIcon(
                                    fullscreen = fullscreen,
                                    color = Color.White,
                                    size = if (isCompact) 18.dp else 20.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
