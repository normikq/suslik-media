package ru.suslikovanora.media.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.pointer.PointerButton
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
import ru.suslikovanora.media.desktop.ui.CloseCrossIcon
import ru.suslikovanora.media.desktop.tr

enum class ResizeDirection {
    NONE,
    LEFT, RIGHT, TOP, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

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
            GlassIconButton(onClick = onClose, tooltip = tr("player.v_biblioteku_esc")) {
                Text("‹", color = Sm.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            GlassIconButton(
                onClick = onToggleOnTop,
                active = onTop,
                tooltip = if (onTop) tr("player.otkrepit_okno_t") else tr("player.poverh_vseh_okon_t")
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

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    infoSource: String = "key",
    infoText: String,
    chapters: List<Pair<Long, String>>,
    chapterAtTime: Int,
    picker: String?,
    pickerItems: List<Pair<Int, String>>,
    pickerCurrent: Int,
    pickerLoading: Boolean = false,
    pickerPendingCount: Int = 0,
    /** «💾 Запомнить задержку для всех» под дорожками звука (внешняя озвучка с сервера); null — кнопки нет. */
    saveDelayText: String? = null,
    onSaveDelay: () -> Unit = {},
    /** «🗑 Удалить озвучку» (тот же автор/админ); null — кнопки нет. */
    deleteTrackText: String? = null,
    onDeleteTrack: () -> Unit = {},
    volume: Int,
    userMuted: Boolean,
    fullscreen: Boolean,
    lastVolumeWheelAt: Long,
    nextIn: Int,
    inIntro: Boolean,
    intro: ru.suslikovanora.media.core.TimeRange? = null,
    credits: ru.suslikovanora.media.core.TimeRange? = null,
    seeking: Float?,
    onScrubChange: (Float?) -> Unit,
    onPoke: () -> Unit,
    onTogglePause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayEntry: (Int) -> Unit,
    onSkipIntro: () -> Unit,
    /** «Следующая серия: S01E02» / «Следующий фильм: …» for the countdown card. */
    nextTitle: String = "",
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
    onClose: () -> Unit = {},
    /** A guest of a watch-together room: the room leads, the timeline is a full «live» line (no seeking). */
    live: Boolean = false,
    /** The room is paused by its host (or waits for someone): a guest cannot start it — the play button is grey. */
    playLocked: Boolean = false,
    onWindowDragStart: () -> Unit = {},
    onWindowDragMove: () -> Unit = {},
    onWindowDragEnd: () -> Unit = {},
    onWindowResizeStart: (ResizeDirection) -> Unit = {},
    onWindowResizeMove: (ResizeDirection) -> Unit = {},
    onWindowResizeEnd: () -> Unit = {},
    onSetCursor: (java.awt.Cursor) -> Unit = {},
    onVolumeWheel: (Float) -> Unit,
    // Высота нижней полосы, которая ловит клики мимо кнопок; null — вся панель (мини-плеер).
    // Клики выше полосы уходят к видео (пауза, двойной клик, колесо громкости).
    inputHeight: androidx.compose.ui.unit.Dp? = null
) {
    val controlsAlpha = if (closing) 0f else (1f - barSlide).coerceIn(0f, 1f)
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
        val input = Modifier
                .then(if (!mini) Modifier.pointerHoverIcon(PointerIcon.Default) else Modifier)
                .pointerInput(mini) {
                    awaitPointerEventScope {
                        var isDragging = false
                        var isResizing = false
                        var activeResizeDir = ResizeDirection.NONE
                        var startPressTime = 0L
                        var lastTapTime = 0L
                        var startX = 0f
                        var startY = 0f

                        fun detectDir(x: Float, y: Float, w: Float, h: Float): ResizeDirection {
                            if (w <= 0f || h <= 0f) return ResizeDirection.NONE
                            val edgePx = (8.dp).toPx()
                            val cornerPx = (20.dp).toPx()
                            val inLeftCorner = x in 0f..cornerPx
                            val inRightCorner = x in (w - cornerPx)..w
                            val inTopCorner = y in 0f..cornerPx
                            val inBottomCorner = y in (h - cornerPx)..h

                            return when {
                                inLeftCorner && inTopCorner -> ResizeDirection.TOP_LEFT
                                inRightCorner && inTopCorner -> ResizeDirection.TOP_RIGHT
                                inLeftCorner && inBottomCorner -> ResizeDirection.BOTTOM_LEFT
                                inRightCorner && inBottomCorner -> ResizeDirection.BOTTOM_RIGHT
                                y in 0f..edgePx -> ResizeDirection.TOP
                                y in (h - edgePx)..h -> ResizeDirection.BOTTOM
                                x in 0f..edgePx -> ResizeDirection.LEFT
                                x in (w - edgePx)..w -> ResizeDirection.RIGHT
                                else -> ResizeDirection.NONE
                            }
                        }

                        fun cursorForDir(dir: ResizeDirection): java.awt.Cursor {
                            return when (dir) {
                                ResizeDirection.TOP_LEFT, ResizeDirection.BOTTOM_RIGHT ->
                                    java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.NW_RESIZE_CURSOR)
                                ResizeDirection.TOP_RIGHT, ResizeDirection.BOTTOM_LEFT ->
                                    java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.NE_RESIZE_CURSOR)
                                ResizeDirection.TOP, ResizeDirection.BOTTOM ->
                                    java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)
                                ResizeDirection.LEFT, ResizeDirection.RIGHT ->
                                    java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.W_RESIZE_CURSOR)
                                ResizeDirection.NONE ->
                                    java.awt.Cursor.getDefaultCursor()
                            }
                        }

                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                PointerEventType.Press -> {
                                    onPoke()
                                    if (picker != null) {
                                        onClosePicker()
                                    }
                                    if (mini && e.button == PointerButton.Primary) {
                                        val change = e.changes.firstOrNull()
                                        if (change != null && !change.isConsumed) {
                                            startPressTime = System.currentTimeMillis()
                                            startX = change.position.x
                                            startY = change.position.y
                                            val w = size.width.toFloat()
                                            val h = size.height.toFloat()
                                            val dir = detectDir(startX, startY, w, h)
                                            if (dir != ResizeDirection.NONE) {
                                                isResizing = true
                                                activeResizeDir = dir
                                                isDragging = false
                                                onSetCursor(cursorForDir(dir))
                                                onWindowResizeStart(dir)
                                            } else {
                                                isResizing = false
                                                activeResizeDir = ResizeDirection.NONE
                                                isDragging = false
                                                onWindowDragStart()
                                            }
                                        }
                                    }
                                }
                                PointerEventType.Move -> {
                                    if (mini) {
                                        val change = e.changes.firstOrNull()
                                        if (change != null) {
                                            if (isResizing) {
                                                onSetCursor(cursorForDir(activeResizeDir))
                                                onWindowResizeMove(activeResizeDir)
                                            } else if (startPressTime > 0L) {
                                                val dist = kotlin.math.hypot(change.position.x - startX, change.position.y - startY)
                                                if (dist > 4f) {
                                                    isDragging = true
                                                    onWindowDragMove()
                                                }
                                            } else {
                                                val w = size.width.toFloat()
                                                val h = size.height.toFloat()
                                                val hoverDir = detectDir(change.position.x, change.position.y, w, h)
                                                onSetCursor(cursorForDir(hoverDir))
                                            }
                                        }
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (mini) {
                                        if (isResizing) {
                                            isResizing = false
                                            activeResizeDir = ResizeDirection.NONE
                                            startPressTime = 0L
                                            onWindowResizeEnd()
                                            onSetCursor(java.awt.Cursor.getDefaultCursor())
                                        } else if (startPressTime > 0L) {
                                            val pressDuration = System.currentTimeMillis() - startPressTime
                                            startPressTime = 0L
                                            if (isDragging) {
                                                isDragging = false
                                                onWindowDragEnd()
                                            } else if (pressDuration < 300L) {
                                                val now = System.currentTimeMillis()
                                                if (now - lastTapTime < 300L) {
                                                    lastTapTime = 0L
                                                    onMiniToggle()
                                                } else {
                                                    lastTapTime = now
                                                    onTogglePause()
                                                }
                                            }
                                        }
                                    }
                                }
                                PointerEventType.Exit -> {
                                    if (mini && !isResizing && !isDragging) {
                                        onSetCursor(java.awt.Cursor.getDefaultCursor())
                                    }
                                }
                                PointerEventType.Scroll -> {
                                    val delta = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (delta != 0f) {
                                        onVolumeWheel(delta)
                                    }
                                }
                            }
                        }
                    }
                }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!mini) {
                        Modifier.graphicsLayer {
                            translationY = barSlide * 300.dp.toPx()
                            alpha = controlsAlpha
                        }
                    } else Modifier
                )
                .then(if (inputHeight == null) input else Modifier)
        ) {
            if (inputHeight != null) Box(Modifier.fillMaxWidth().height(inputHeight).align(Alignment.BottomCenter).then(input))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (mini) 100.dp else 200.dp)
                    .align(Alignment.BottomCenter)
                    .then(
                        if (mini) {
                            Modifier.graphicsLayer {
                                translationY = barSlide * 110.dp.toPx()
                                alpha = controlsAlpha
                            }
                        } else Modifier
                    )
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
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .then(
                        if (mini) {
                            Modifier.graphicsLayer {
                                translationY = barSlide * 110.dp.toPx()
                                alpha = controlsAlpha
                            }
                        } else Modifier
                    )
                    .padding(start = if (isVeryNarrow) 12.dp else 24.dp, end = if (isVeryNarrow) 12.dp else 24.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
            val infoPivotX = if (infoSource == "button") 0.82f else 0.50f
            val infoInitialScale = if (infoSource == "button") 0.25f else 0.85f
            val infoOffsetYFactor = if (infoSource == "button") 0.20f else 0.35f

            AnimatedVisibility(
                visible = infoOpen,
                enter = fadeIn(tween(200, easing = LinearOutSlowInEasing)) +
                        scaleIn(
                            initialScale = infoInitialScale,
                            transformOrigin = TransformOrigin(infoPivotX, 1.0f),
                            animationSpec = tween(300, easing = CubicBezierEasing(0.16f, 1.0f, 0.30f, 1.0f))
                        ) +
                        slideInVertically(
                            initialOffsetY = { (it * infoOffsetYFactor).toInt() },
                            animationSpec = tween(300, easing = CubicBezierEasing(0.16f, 1.0f, 0.30f, 1.0f))
                        ),
                exit = fadeOut(tween(160, easing = FastOutLinearInEasing)) +
                       scaleOut(
                           targetScale = infoInitialScale,
                           transformOrigin = TransformOrigin(infoPivotX, 1.0f),
                           animationSpec = tween(180, easing = FastOutLinearInEasing)
                       ) +
                       slideOutVertically(
                           targetOffsetY = { (it * infoOffsetYFactor).toInt() },
                           animationSpec = tween(180, easing = FastOutLinearInEasing)
                       )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xD90E1118))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        infoText.ifBlank { tr("player.sobiraem_dannye") },
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
                        Text(
                            tr("player.propustit_zastavku_s"),
                            color = Color.White,
                            fontFamily = Onest,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = (picker == "more"),
                enter = fadeIn(tween(180, easing = LinearOutSlowInEasing)) +
                        slideInVertically(
                            initialOffsetY = { 24 },
                            animationSpec = tween(220, easing = CubicBezierEasing(0.16f, 1.0f, 0.30f, 1.0f))
                        ),
                exit = fadeOut(tween(140, easing = FastOutLinearInEasing)) +
                        slideOutVertically(
                            targetOffsetY = { 24 },
                            animationSpec = tween(160, easing = FastOutLinearInEasing)
                        )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF20E1118))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr("player.nastroyki_vosproizvedeniya"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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

            var lastTracksKind by remember { mutableStateOf("audio") }
            if (picker == "audio" || picker == "spu") {
                lastTracksKind = picker!!
            }
            val isTracksPicker = (picker == "audio" || picker == "spu")
            val tracksPivotX = if (lastTracksKind == "audio") 0.70f else 0.76f

            AnimatedVisibility(
                visible = isTracksPicker,
                enter = fadeIn(tween(180, easing = LinearOutSlowInEasing)) +
                        slideInVertically(
                            initialOffsetY = { 24 },
                            animationSpec = tween(220, easing = CubicBezierEasing(0.16f, 1.0f, 0.30f, 1.0f))
                        ),
                exit = fadeOut(tween(140, easing = FastOutLinearInEasing)) +
                        slideOutVertically(
                            targetOffsetY = { 24 },
                            animationSpec = tween(160, easing = FastOutLinearInEasing)
                        )
            ) {
                val current = pickerCurrent
                val kind = lastTracksKind
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF20E1118))
                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (kind == "audio") tr("player.audiodorozhki") else tr("player.subtitry"),
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
                            modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(220)),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val hasLoadedTracks = pickerItems.any { it.first != -1 }
                            val isAudio = (kind == "audio")
                            val skeletonCount = when {
                                pickerPendingCount > 0 -> pickerPendingCount
                                pickerLoading && !hasLoadedTracks -> if (isAudio) 6 else 5
                                else -> 0
                            }

                            if (!hasLoadedTracks && !pickerLoading && skeletonCount == 0) {
                                Text(
                                    tr("player.dorozhki_esche_ne_opredeleny"),
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontFamily = Onest,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            pickerItems.forEachIndexed { idx, (id, name) ->
                                val on = id == current
                                PlayerChipAnimated(name, on, idx) {
                                    onSelectTrack(kind, id, name)
                                }
                            }

                            repeat(skeletonCount) { i ->
                                PlayerChipSkeleton(index = pickerItems.size + i)
                            }
                        }
                        if (kind == "audio" && saveDelayText != null) {
                            PlayerChipAnimated(saveDelayText, false, pickerItems.size) { onSaveDelay() }
                        }
                        if (kind == "audio" && deleteTrackText != null) {
                            PlayerChipAnimated(deleteTrackText, false, pickerItems.size + 1) { onDeleteTrack() }
                        }
                    }
                }
            }

            // The next episode / film: on its end credits (core.MarkersDetector) or at the end of the file, bottom right
            if (nextIn >= 0 && index < queue.size - 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC161B26))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
                        .padding(start = 16.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            nextTitle.ifBlank { tr("player.sleduyuschaya_p", "p" to queue.getOrNull(index + 1)?.title.orEmpty()) },
                            color = Sm.text,
                            fontFamily = Onest,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.16f))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tr("player.cherez_nextin_s", "nextIn" to nextIn),
                                color = Color.White,
                                fontFamily = Onest,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            GlassIconButton(
                                onClick = { onPlayEntry(index + 1) },
                                size = 32.dp,
                                tooltip = tr("player.vklyuchit_seychas")
                            ) {
                                NextPlayTriangleIcon(color = Color.White, size = 12.5.dp)
                            }
                            GlassIconButton(
                                onClick = onCancelNextIn,
                                size = 32.dp,
                                tooltip = tr("player.otmena")
                            ) {
                                CloseCrossIcon(color = Color.White, size = 12.dp)
                            }
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
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 52.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isScrubActive) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (live) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                        Text(tr("party.vmeste"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 6.dp))
                    } else Text(
                        fmt(hoverAt ?: seeking?.toLong() ?: time),
                        color = Color.White,
                        fontFamily = Onest,
                        fontWeight = if (isScrubActive) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
                if (live) {
                    // Как у стрима: полоса заполнена до конца, перемотки нет
                    Box(
                        Modifier.weight(1f).padding(horizontal = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFE5484D))
                    )
                } else SeekBar(
                    position = seeking?.toLong() ?: time,
                    length = length,
                    buffered = animBuffered.toLong(),
                    marks = chapters.map { it.first },
                    intro = intro,
                    credits = credits,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    onScrub = { t -> onScrubChange(t?.toFloat()); onPoke() },
                    onSeek = { t ->
                        onScrubChange(null)
                        onSeekTo(t)
                    },
                    onHover = { t -> hoverAt = t }
                )
                Text(
                    if (live) fmt(time) else if (length > 0) fmt(length) else "--:--",
                    color = Sm.muted,
                    fontFamily = Onest,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.defaultMinSize(minWidth = 52.dp).padding(horizontal = 6.dp, vertical = 2.dp),
                    textAlign = TextAlign.End
                )
            }

            if (mini) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroPlayButton(playing = playing, onClick = onTogglePause, enabled = !(playLocked && !playing), lockedTip = tr("party.pauzu_snimaet_sozdatel"))
                    if (index > 0 && !live) {
                        GlassIconButton(onClick = { onPlayEntry(index - 1) }, tooltip = tr("player.predyduschaya")) {
                            NextEpisodeIcon(color = Sm.text, size = 16.dp, modifier = Modifier.graphicsLayer { scaleX = -1f })
                        }
                    }
                    if (index < queue.size - 1 && !live) {
                        GlassIconButton(onClick = { onPlayEntry(index + 1) }, tooltip = tr("player.sleduyuschaya")) {
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
                    GlassIconButton(onClick = onMiniToggle, tooltip = tr("player.obychnyy_rezhim_o")) {
                        Text("⤢", color = Sm.text, fontSize = 16.sp)
                    }
                    GlassIconButton(onClick = onClose, tooltip = tr("player.zakryt_esc")) {
                        CloseCrossIcon(color = Sm.text, size = 11.dp)
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
                            // The previous episode: the mirrored «next» (the queue is the whole series, see PlayDialog).
                            // Until the season's playlist is loaded the queue holds only this file while index is already
                            // the episode's place in the season: no button then (queue[index - 1] crashed, 30.10).
                            val prevEntry = if (live) null else queue.getOrNull(index - 1)
                            if (prevEntry != null) {
                                GlassIconButton(
                                    onClick = { onPlayEntry(index - 1) },
                                    tooltip = tr("player.predyduschaya_p", "p" to prevEntry.title),
                                    size = btnSize
                                ) {
                                    NextEpisodeIcon(color = Sm.text, size = if (isCompact) 14.dp else 16.dp, modifier = Modifier.graphicsLayer { scaleX = -1f })
                                }
                            }
                            if (index < queue.size - 1 && !live) {
                                GlassIconButton(
                                    onClick = { onPlayEntry(index + 1) },
                                    tooltip = tr("player.sleduyuschaya_p", "p" to queue[index + 1].title),
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
                        if (!live) GlassIconButton(
                            onClick = { onSeekBy(-10_000L) },
                            size = if (isCompact) 38.dp else 42.dp,
                            tooltip = tr("player.nazad_na_sek")
                        ) {
                            Seek10Icon(forward = false, color = Color.White)
                        }
                        HeroPlayButton(playing = playing, onClick = onTogglePause, size = heroSize,
                            enabled = !(playLocked && !playing), lockedTip = tr("party.pauzu_snimaet_sozdatel"))
                        if (!live) GlassIconButton(
                            onClick = { onSeekBy(10_000L) },
                            size = if (isCompact) 38.dp else 42.dp,
                            tooltip = tr("player.vpered_na_sek")
                        ) {
                            Seek10Icon(forward = true, color = Color.White)
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
        if (mini && controlsAlpha > 0.05f) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .padding(end = 4.dp, bottom = 4.dp)
                    .graphicsLayer { alpha = controlsAlpha }
            ) {
                val w = size.width
                val h = size.height
                val color = Color.White.copy(alpha = 0.45f)
                val stroke = 1.5f
                drawLine(color, androidx.compose.ui.geometry.Offset(w, h - 9f), androidx.compose.ui.geometry.Offset(w - 9f, h), strokeWidth = stroke)
                drawLine(color, androidx.compose.ui.geometry.Offset(w, h - 5f), androidx.compose.ui.geometry.Offset(w - 5f, h), strokeWidth = stroke)
            }
        }
    }
}
}

