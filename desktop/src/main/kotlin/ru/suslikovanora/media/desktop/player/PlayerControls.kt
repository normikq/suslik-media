package ru.suslikovanora.media.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import kotlinx.coroutines.delay
import ru.suslikovanora.media.desktop.ui.Onest
import ru.suslikovanora.media.desktop.ui.Sm
import ru.suslikovanora.media.desktop.ui.hoverClick
import ru.suslikovanora.media.desktop.ui.hoverScale
import ru.suslikovanora.media.desktop.tr

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tooltip: String? = null,
    size: Dp = 42.dp,
    shape: Shape = CircleShape,
    content: @Composable () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered && enabled, pressed && enabled, 1.05f)

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            pressed -> 0.48f
            active -> 0.42f
            hovered -> 0.30f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 160)
    )

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .size(size)
            .drawBehind {
                if (glowAlpha > 0.001f) {
                    val r = this.size.minDimension * 0.58f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = glowAlpha),
                                0.45f to Color.White.copy(alpha = glowAlpha * 0.50f),
                                0.80f to Color.White.copy(alpha = glowAlpha * 0.15f),
                                1.0f to Color.Transparent
                            ),
                            center = center,
                            radius = r
                        ),
                        radius = r,
                        center = center
                    )
                }
            }
            .hoverClick(src, enabled, onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun HeroPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    enabled: Boolean = true,      // false: a room guest while the host holds the pause
    lockedTip: String? = null
) {
    GlassIconButton(
        onClick = onClick,
        size = size,
        enabled = enabled,
        tooltip = if (!enabled && lockedTip != null) lockedTip else if (playing) tr("player.pauza_probel") else tr("player.vosproizvedenie_probel")
    ) {
        if (playing) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.2.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White)
                )
                Box(
                    modifier = Modifier
                        .width(4.2.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White)
                )
            }
        } else {
            Canvas(
                modifier = Modifier
                    .size(20.dp)
                    .offset(x = 1.5.dp)
            ) {
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

@Composable
fun VerticalVolumeSlider(
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(38.dp)
            .height(134.dp),
        shape = RoundedCornerShape(19.dp),
        color = Color(0xF2141824),
        border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "$volume%",
                color = if (volume > 100) Sm.lamp else Sm.text,
                fontFamily = Onest,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(6.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .width(28.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                onDragStateChange(true)
                                val frac = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                                onVolumeChange((frac * 150f).toInt())
                            },
                            onDragEnd = { onDragStateChange(false) },
                            onDragCancel = { onDragStateChange(false) },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val frac = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                                onVolumeChange((frac * 150f).toInt())
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.type == PointerEventType.Press) {
                                    val y = ev.changes.firstOrNull()?.position?.y ?: continue
                                    val frac = (1f - (y / size.height)).coerceIn(0f, 1f)
                                    onVolumeChange((frac * 150f).toInt())
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                val totalH = maxHeight
                val fillRatio = (volume / 150f).coerceIn(0f, 1f)

                // Background track
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x33FFFFFF))
                )
                // Active track
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(totalH * fillRatio)
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White)
                )
                // Thumb circle
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = -(totalH * fillRatio - 5.dp).coerceAtLeast(0.dp))
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
fun VolumeGlassCapsule(
    volume: Int,
    muted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    verticalOnly: Boolean = false,
    size: Dp = 42.dp,
    wheelTrigger: Long = 0L
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    var wheelActive by remember { mutableStateOf(false) }

    LaunchedEffect(wheelTrigger) {
        if (wheelTrigger > 0L) {
            wheelActive = true
            delay(1500)
            wheelActive = false
        }
    }

    if (verticalOnly) {
        val showPopup = hovered || dragging || wheelActive
        Box(
            modifier = Modifier.hoverable(src),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = showPopup,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.padding(bottom = (size + 6.dp))
            ) {
                VerticalVolumeSlider(
                    volume = volume,
                    onVolumeChange = onVolumeChange,
                    onDragStateChange = { dragging = it }
                )
            }

            GlassIconButton(
                onClick = onMuteToggle,
                active = muted,
                size = size
            ) {
                VolumeSpeakerIcon(
                    volume = volume,
                    muted = muted,
                    color = if (muted) Sm.lamp else Color.White,
                    size = if (size < 40.dp) 19.dp else 22.dp
                )
            }
        }
    } else {
        val isExpanded = hovered || dragging || wheelActive
        val width by animateDpAsState(
            targetValue = if (isExpanded) 160.dp else size,
            animationSpec = tween(220, easing = Sm.ease)
        )

        val glowAlpha by animateFloatAsState(
            targetValue = if (!isExpanded && (hovered || wheelActive)) 0.30f else 0f,
            animationSpec = tween(160)
        )

        val bg = if (isExpanded) Color(0x99161B26) else Color.Transparent
        val border = if (isExpanded) Color(0x33FFFFFF) else Color.Transparent

        val animVol by animateFloatAsState(
            targetValue = volume.toFloat(),
            animationSpec = tween(120)
        )

        Box(
            modifier = Modifier
                .height(size)
                .width(width)
                .drawBehind {
                    if (glowAlpha > 0.001f) {
                        val r = size.toPx() * 0.58f
                        val centerOffset = Offset(size.toPx() / 2f, size.toPx() / 2f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.White.copy(alpha = glowAlpha),
                                    0.45f to Color.White.copy(alpha = glowAlpha * 0.50f),
                                    0.80f to Color.White.copy(alpha = glowAlpha * 0.15f),
                                    1.0f to Color.Transparent
                                ),
                                center = centerOffset,
                                radius = r
                            ),
                            radius = r,
                            center = centerOffset
                        )
                    }
                }
                .clip(RoundedCornerShape(50))
                .background(bg)
                .then(if (isExpanded) Modifier.border(1.dp, border, RoundedCornerShape(50)) else Modifier)
                .hoverable(src)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .size(size - 6.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMuteToggle)
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center
                ) {
                    VolumeSpeakerIcon(
                        volume = volume,
                        muted = muted,
                        color = if (muted) Sm.lamp else Color.White,
                        size = if (size < 40.dp) 19.dp else 22.dp
                    )
                }
                if (width > 60.dp) {
                    Spacer(Modifier.width(2.dp))
                    Slider(
                        value = if (dragging) volume.toFloat() else animVol,
                        valueRange = 0f..150f,
                        onValueChange = {
                            dragging = true
                            onVolumeChange(it.toInt())
                        },
                        onValueChangeFinished = {
                            dragging = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0x4DFFFFFF)
                        ),
                        modifier = Modifier.weight(1f).height(24.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$volume%",
                        color = Sm.text.copy(alpha = 0.85f),
                        fontFamily = Onest,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerChip(label: String, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed, 1.04f)
    val bg = when {
        on -> Color.White
        hovered -> Color(0x38FFFFFF)
        else -> Color(0x1F161B26)
    }
    val border = when {
        on -> Color.White
        hovered -> Color(0x66FFFFFF)
        else -> Color(0x33FFFFFF)
    }
    val fg = when {
        on -> Color(0xFF111418)
        else -> Color.White.copy(alpha = 0.9f)
    }
    val weight = if (on) FontWeight.Bold else FontWeight.Medium
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .hoverClick(src, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = fg, fontFamily = Onest, fontWeight = weight, fontSize = 12.5.sp, maxLines = 1)
    }
}

/**
 * Скелетон дорожки (кружочек-индикатор + полоса названия) с мерцающей анимацией шиммера.
 */
@Composable
fun PlayerChipSkeleton(
    index: Int = 0,
    modifier: Modifier = Modifier
) {
    val skeletonWidths = remember { listOf(48.dp, 62.dp, 44.dp, 56.dp, 52.dp, 64.dp, 50.dp) }
    val barWidth = skeletonWidths[index % skeletonWidths.size]

    val transition = rememberInfiniteTransition()
    val translateAnim by transition.animateFloat(
        initialValue = -120f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1450, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color(0x38788C9E),
            Color(0x8CB8D2EA),
            Color(0x38788C9E)
        ),
        start = Offset(translateAnim - 160f, 0f),
        end = Offset(translateAnim, 0f)
    )

    Box(
        modifier = modifier
            .graphicsLayer { alpha = pulseAlpha }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x1F161B26))
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(shimmerBrush)
            )
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(7.dp)
                    .clip(RoundedCornerShape(3.5.dp))
                    .background(shimmerBrush)
            )
        }
    }
}

/**
 * Дорожка с плавной анимацией появления (scale + fade-in со ступенчатой задержкой).
 */
@Composable
fun PlayerChipAnimated(
    label: String,
    on: Boolean,
    index: Int = 0,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val delayMs = (index * 30L).coerceAtMost(250L)
        if (delayMs > 0) delay(delayMs)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.86f,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
        exit = fadeOut(animationSpec = tween(140))
    ) {
        PlayerChip(label = label, on = on, onClick = onClick)
    }
}

/**
 * Seek bar: amber progress, chapter boundaries as gaps, bigger on hover; click to jump, drag to scrub.
 * [onScrub] reports the scrub position while dragging (null when done), [onHover] the time under the cursor.
 */
@Composable
fun SeekBar(
    position: Long,
    length: Long,
    buffered: Long = 0L,
    marks: List<Long>,
    intro: ru.suslikovanora.media.core.TimeRange? = null,
    credits: ru.suslikovanora.media.core.TimeRange? = null,
    modifier: Modifier,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    onHover: (Long?) -> Unit
) {
    var w by remember { mutableStateOf(1f) }
    var hover by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(false) }
    var scrubTo by remember { mutableStateOf(0L) }
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val grow by animateFloatAsState(if (hover || drag) 1f else 0f, tween(180))
    fun at(x: Float) = if (length <= 0) 0L else ((x / w).coerceIn(0f, 1f) * length).toLong()
    Box(
        modifier.height(26.dp)
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(length) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Exit -> { hover = false; hoverX = null; onHover(null) }
                            else -> {
                                hover = true
                                val x = e.changes.firstOrNull()?.position?.x
                                hoverX = x
                                if (x != null) onHover(at(x))
                            }
                        }
                    }
                }
            }
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
                drawRoundRect(Sm.line2, topLeft = Offset(0f, cy - h / 2), size = Size(size.width, h), cornerRadius = r)
                val bf = if (length > 0) (buffered.toFloat() / length).coerceIn(0f, 1f) else 0f
                if (bf > 0f) drawRoundRect(Color(0x80B4B8C4), topLeft = Offset(0f, cy - h / 2), size = Size(size.width * bf, h), cornerRadius = r)
                // Отметки заставки (янтарная) и титров (серый сланец)
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
                // chapter boundaries: small dark gaps across the track
                if (length > 0) for (m in marks) {
                    if (m <= 0 || m >= length) continue
                    val x = size.width * (m.toFloat() / length)
                    drawRect(Sm.booth, topLeft = Offset(x - 1.5f, cy - h / 2 - 1), size = Size(3f, h + 2))
                }
                // Hover preview pip on the track when cursor is away from thumb
                val hX = hoverX
                if (hover && hX != null && !drag && kotlin.math.abs(hX - size.width * f) > 10.dp.toPx()) {
                    val hoverCenter = Offset(hX.coerceIn(0f, size.width), cy)
                    val hGlowR = 11.dp.toPx() * grow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = 0.35f * grow),
                                0.50f to Color.White.copy(alpha = 0.12f * grow),
                                1.0f to Color.Transparent
                            ),
                            center = hoverCenter,
                            radius = hGlowR
                        ),
                        radius = hGlowR,
                        center = hoverCenter
                    )
                    drawCircle(Color.White.copy(alpha = 0.75f * grow), radius = 2.8.dp.toPx(), center = hoverCenter)
                }
                // Active thumb with soft luminous white glow
                val thumbCenter = Offset(size.width * f, cy)
                if (grow > 0.01f) {
                    val glowR = (16f * grow).dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = 0.52f * grow),
                                0.45f to Color.White.copy(alpha = 0.22f * grow),
                                1.0f to Color.Transparent
                            ),
                            center = thumbCenter,
                            radius = glowR
                        ),
                        radius = glowR,
                        center = thumbCenter
                    )
                }
                drawCircle(Color.White, radius = (5f + 3f * grow).dp.toPx(), center = thumbCenter)
            }
    )
}
