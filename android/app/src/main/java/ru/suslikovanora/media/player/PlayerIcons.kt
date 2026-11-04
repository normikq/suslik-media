package ru.suslikovanora.media.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm

@Composable
fun AudioTrackIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 20.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val headCx = w * 0.38f
        val headCy = h * 0.70f
        val headRx = w * 0.22f
        val headRy = h * 0.16f

        rotate(degrees = -22f, pivot = Offset(headCx, headCy)) {
            drawOval(
                color = color,
                topLeft = Offset(headCx - headRx, headCy - headRy),
                size = Size(headRx * 2f, headRy * 2f)
            )
        }

        val stemX = headCx + headRx * 0.72f
        val stemTop = h * 0.18f
        val stemWidth = 1.8.dp.toPx()

        drawLine(
            color = color,
            start = Offset(stemX, headCy),
            end = Offset(stemX, stemTop),
            strokeWidth = stemWidth,
            cap = StrokeCap.Round
        )

        val flagPath = Path().apply {
            moveTo(stemX, stemTop)
            cubicTo(
                stemX + w * 0.26f, stemTop + h * 0.08f,
                stemX + w * 0.32f, stemTop + h * 0.28f,
                stemX + w * 0.12f, stemTop + h * 0.44f
            )
            cubicTo(
                stemX + w * 0.24f, stemTop + h * 0.26f,
                stemX + w * 0.18f, stemTop + h * 0.14f,
                stemX, stemTop + h * 0.10f
            )
            close()
        }
        drawPath(flagPath, color = color, style = Fill)
    }
}

@Composable
fun SubtitlesBadgeIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 22.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val strokeW = 1.4.dp.toPx()
        val padX = w * 0.08f
        val padY = h * 0.18f
        val rectW = w - padX * 2f
        val rectH = h - padY * 2f
        val cornerRadius = 3.0.dp.toPx()

        drawRoundRect(
            color = color,
            topLeft = Offset(padX, padY),
            size = Size(rectW, rectH),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = strokeW)
        )

        val cRadius = rectH * 0.21f
        val centerY = padY + rectH * 0.5f
        val cStroke = 1.5.dp.toPx()

        // 2.0dp guaranteed gap between the two 'C' glyphs
        val gap = 2.0.dp.toPx()
        val d = gap + 1.707f * cRadius
        val cx = padX + rectW * 0.5f + 0.3.dp.toPx()
        val c1CenterX = cx - d / 2f
        val c2CenterX = cx + d / 2f

        drawArc(
            color = color,
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(c1CenterX - cRadius, centerY - cRadius),
            size = Size(cRadius * 2f, cRadius * 2f),
            style = Stroke(width = cStroke, cap = StrokeCap.Round)
        )

        drawArc(
            color = color,
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(c2CenterX - cRadius, centerY - cRadius),
            size = Size(cRadius * 2f, cRadius * 2f),
            style = Stroke(width = cStroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun SettingsGearIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 20.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f

        val rOut = minOf(w, h) * 0.45f
        val rIn = minOf(w, h) * 0.31f
        val rHole = minOf(w, h) * 0.15f

        val teeth = 8
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            var first = true

            for (i in 0 until teeth) {
                val baseDeg = i * (360.0 / teeth)

                val a1 = Math.toRadians(baseDeg - 10.0)
                val a2 = Math.toRadians(baseDeg + 10.0)
                val a3 = Math.toRadians(baseDeg + 14.5)
                val a4 = Math.toRadians(baseDeg + 30.5)

                val p1x = (cx + rOut * kotlin.math.cos(a1)).toFloat()
                val p1y = (cy + rOut * kotlin.math.sin(a1)).toFloat()

                val p2x = (cx + rOut * kotlin.math.cos(a2)).toFloat()
                val p2y = (cy + rOut * kotlin.math.sin(a2)).toFloat()

                val p3x = (cx + rIn * kotlin.math.cos(a3)).toFloat()
                val p3y = (cy + rIn * kotlin.math.sin(a3)).toFloat()

                val p4x = (cx + rIn * kotlin.math.cos(a4)).toFloat()
                val p4y = (cy + rIn * kotlin.math.sin(a4)).toFloat()

                if (first) {
                    moveTo(p1x, p1y)
                    first = false
                } else {
                    lineTo(p1x, p1y)
                }
                lineTo(p2x, p2y)
                lineTo(p3x, p3y)
                lineTo(p4x, p4y)
            }
            close()

            addOval(Rect(cx - rHole, cy - rHole, cx + rHole, cy + rHole))
        }

        drawPath(path = path, color = color, style = Fill)
    }
}

@Composable
fun VolumeSpeakerIcon(
    volume: Int,
    muted: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 21.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val strokeW = 1.8.dp.toPx()
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val backX = w * 0.08f
        val hornStartX = w * 0.30f
        val frontX = w * 0.48f

        val cy = h * 0.5f
        val rectHalfH = h * 0.18f
        val hornHalfH = h * 0.38f

        val speakerPath = Path().apply {
            moveTo(backX, cy - rectHalfH)
            lineTo(hornStartX, cy - rectHalfH)
            lineTo(frontX, cy - hornHalfH)
            lineTo(frontX, cy + hornHalfH)
            lineTo(hornStartX, cy + rectHalfH)
            lineTo(backX, cy + rectHalfH)
            close()
        }
        drawPath(path = speakerPath, color = color, style = Fill)

        if (muted) {
            drawLine(
                color = color,
                start = Offset(w * 0.14f, h * 0.16f),
                end = Offset(w * 0.86f, h * 0.84f),
                strokeWidth = 1.9.dp.toPx(),
                cap = StrokeCap.Round
            )
        } else if (volume > 0) {
            val waveCenter = Offset(w * 0.30f, cy)

            // Wave 1
            val r1 = w * 0.30f
            drawArc(
                color = color,
                startAngle = -42f,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset(waveCenter.x - r1, waveCenter.y - r1),
                size = Size(r1 * 2f, r1 * 2f),
                style = stroke
            )

            // Wave 2
            if (volume > 40) {
                val r2 = w * 0.48f
                drawArc(
                    color = color,
                    startAngle = -42f,
                    sweepAngle = 84f,
                    useCenter = false,
                    topLeft = Offset(waveCenter.x - r2, waveCenter.y - r2),
                    size = Size(r2 * 2f, r2 * 2f),
                    style = stroke
                )
            }
        }
    }
}

@Composable
fun FullscreenToggleIcon(
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 20.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val strokeW = 1.8.dp.toPx()
        val pad = w * 0.18f
        val x1 = pad
        val x2 = w - pad
        val y1 = pad
        val y2 = h - pad
        val arm = w * 0.25f

        if (!fullscreen) {
            // Expand corners
            drawLine(color, Offset(x1, y1 + arm), Offset(x1, y1), strokeW, StrokeCap.Round)
            drawLine(color, Offset(x1, y1), Offset(x1 + arm, y1), strokeW, StrokeCap.Round)

            drawLine(color, Offset(x2 - arm, y1), Offset(x2, y1), strokeW, StrokeCap.Round)
            drawLine(color, Offset(x2, y1), Offset(x2, y1 + arm), strokeW, StrokeCap.Round)

            drawLine(color, Offset(x1, y2 - arm), Offset(x1, y2), strokeW, StrokeCap.Round)
            drawLine(color, Offset(x1, y2), Offset(x1 + arm, y2), strokeW, StrokeCap.Round)

            drawLine(color, Offset(x2 - arm, y2), Offset(x2, y2), strokeW, StrokeCap.Round)
            drawLine(color, Offset(x2, y2), Offset(x2, y2 - arm), strokeW, StrokeCap.Round)
        } else {
            // Collapse corners
            drawLine(color, Offset(x1 + arm, y1), Offset(x1 + arm, y1 + arm), strokeW, StrokeCap.Round)
            drawLine(color, Offset(x1 + arm, y1 + arm), Offset(x1, y1 + arm), strokeW, StrokeCap.Round)

            drawLine(color, Offset(x2 - arm, y1), Offset(x2 - arm, y1 + arm), strokeW, StrokeCap.Round)
            drawLine(color, Offset(x2 - arm, y1 + arm), Offset(x2, y1 + arm), strokeW, StrokeCap.Round)

            drawLine(color, Offset(x1 + arm, y2), Offset(x1 + arm, y2 - arm), strokeW, StrokeCap.Round)
            drawLine(color, Offset(x1 + arm, y2 - arm), Offset(x1, y2 - arm), strokeW, StrokeCap.Round)

            drawLine(color, Offset(x2 - arm, y2), Offset(x2 - arm, y2 - arm), strokeW, StrokeCap.Round)
            drawLine(color, Offset(x2 - arm, y2 - arm), Offset(x2, y2 - arm), strokeW, StrokeCap.Round)
        }
    }
}

@Composable
fun NextEpisodeIcon(
    modifier: Modifier = Modifier,
    color: Color = Sm.text,
    size: Dp = 16.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val cy = h * 0.5f
        val halfH = h * 0.28f

        val p1 = Path().apply {
            moveTo(w * 0.16f, cy - halfH)
            lineTo(w * 0.46f, cy)
            lineTo(w * 0.16f, cy + halfH)
            close()
        }
        drawPath(p1, color = color, style = Fill)

        val p2 = Path().apply {
            moveTo(w * 0.46f, cy - halfH)
            lineTo(w * 0.76f, cy)
            lineTo(w * 0.46f, cy + halfH)
            close()
        }
        drawPath(p2, color = color, style = Fill)

        val barX = w * 0.82f
        drawLine(
            color = color,
            start = Offset(barX, cy - halfH),
            end = Offset(barX, cy + halfH),
            strokeWidth = 1.7.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun Seek10Icon(
    forward: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.size(28.dp)
        ) {
            val strokeWidth = 1.9.dp.toPx()
            val stroke = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = 9.4.dp.toPx() // +10% enlarged diameter

            val headLen = 4.4.dp.toPx()
            val headHalfH = 3.3.dp.toPx()

            if (!forward) {
                // СЛЕВА НАЗАД (↺ Rewind 10):
                // Стрелка вверху направлена НАЛЕВО (против часовой стрелки)
                val baseX = cx + 0.8.dp.toPx()
                val tipX = cx - 4.4.dp.toPx()
                val tipY = cy - r

                val arrowPath = Path().apply {
                    moveTo(tipX, tipY) // вершина стрелки смотрит НАЛЕВО
                    lineTo(baseX, tipY - headHalfH)
                    lineTo(baseX, tipY + headHalfH)
                    close()
                }
                drawPath(path = arrowPath, color = color, style = Fill)

                // Дуга от основания стрелки идет по часовой стрелке вокруг круга
                drawArc(
                    color = color,
                    startAngle = -86f,
                    sweepAngle = 265f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = stroke
                )
            } else {
                // СПРАВА ВПЕРЕД (↻ Forward 10):
                // Стрелка вверху направлена НАПРАВО (по часовой стрелке)
                val baseX = cx - 0.8.dp.toPx()
                val tipX = cx + 4.4.dp.toPx()
                val tipY = cy - r

                val arrowPath = Path().apply {
                    moveTo(tipX, tipY) // вершина стрелки смотрит НАПРАВО
                    lineTo(baseX, tipY - headHalfH)
                    lineTo(baseX, tipY + headHalfH)
                    close()
                }
                drawPath(path = arrowPath, color = color, style = Fill)

                // Дуга от основания стрелки идет против часовой стрелки вокруг круга
                drawArc(
                    color = color,
                    startAngle = -94f,
                    sweepAngle = -265f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = stroke
                )
            }
        }
        Text(
            "10",
            color = color,
            fontFamily = Onest,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun NextPlayTriangleIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 12.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.12f)
            lineTo(w * 0.90f, h * 0.50f)
            lineTo(w * 0.18f, h * 0.88f)
            close()
        }
        drawPath(path, color = color, style = Fill)
    }
}

@Composable
fun CloseCrossIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 12.dp,
    strokeWidth: Dp = 1.6.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = strokeWidth.toPx()
        val pad = w * 0.14f
        drawLine(color, Offset(pad, pad), Offset(w - pad, h - pad), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w - pad, pad), Offset(pad, h - pad), strokeWidth = sw, cap = StrokeCap.Round)
    }
}


/** «‹» drawn as a chevron: a text glyph sat off-centre and differed per phone font. */
@Composable
fun BackChevronIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 20.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.62f, h * 0.20f)
            lineTo(w * 0.32f, h * 0.50f)
            lineTo(w * 0.62f, h * 0.80f)
        }
        drawPath(path, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The previous episode: [NextEpisodeIcon] mirrored. */
@Composable
fun PrevEpisodeIcon(
    modifier: Modifier = Modifier,
    color: Color = Sm.text,
    size: Dp = 16.dp
) {
    NextEpisodeIcon(modifier.graphicsLayer { scaleX = -1f }, color, size)
}

/** Padlock: the screen lock against accidental touches. */
@Composable
fun LockIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    size: Dp = 20.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        drawArc(
            color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.32f, h * 0.14f), size = Size(w * 0.36f, h * 0.44f),
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
        drawLine(color, Offset(w * 0.32f, h * 0.36f), Offset(w * 0.32f, h * 0.46f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.68f, h * 0.36f), Offset(w * 0.68f, h * 0.46f), sw, StrokeCap.Round)
        drawRoundRect(
            color, topLeft = Offset(w * 0.22f, h * 0.46f), size = Size(w * 0.56f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.08f), style = Stroke(width = sw)
        )
        drawCircle(color, radius = w * 0.045f, center = Offset(w * 0.5f, h * 0.64f))
    }
}
