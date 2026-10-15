package ru.suslikovanora.media.desktop.player

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
import ru.suslikovanora.media.desktop.ui.Onest
import ru.suslikovanora.media.desktop.ui.Sm

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

        val strokeW = 1.6.dp.toPx()
        val padX = w * 0.06f
        val padY = h * 0.16f
        val rectW = w - padX * 2f
        val rectH = h - padY * 2f
        val cornerRadius = 3.2.dp.toPx()

        drawRoundRect(
            color = color,
            topLeft = Offset(padX, padY),
            size = Size(rectW, rectH),
            cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = strokeW)
        )

        val cRadius = rectH * 0.23f
        val centerY = padY + rectH * 0.5f
        val cStroke = 1.7.dp.toPx()

        val c1CenterX = padX + rectW * 0.36f
        val c2CenterX = padX + rectW * 0.64f

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
    size: Dp = 20.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val strokeW = 1.7.dp.toPx()
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val backX = w * 0.16f
        val hornStartX = w * 0.34f
        val frontX = w * 0.50f

        val cy = h * 0.5f
        val rectHalfH = h * 0.15f
        val hornHalfH = h * 0.32f

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
                start = Offset(w * 0.20f, h * 0.22f),
                end = Offset(w * 0.82f, h * 0.78f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round
            )
        } else if (volume > 0) {
            val waveCenter = Offset(w * 0.36f, cy)

            // Wave 1
            val r1 = w * 0.24f
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
                val r2 = w * 0.38f
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
    color: Color = Sm.text
) {
    Box(
        modifier = modifier.size(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    if (forward) scaleX = -1f
                }
        ) {
            val strokeWidth = 1.6.dp.toPx()
            val stroke = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            val cx = size.width / 2f
            val cy = size.height / 2f + 0.5.dp.toPx()
            val r = 8.5.dp.toPx()

            drawArc(
                color = color,
                startAngle = -77f,
                sweepAngle = 334f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = stroke
            )

            val tipX = cx - 5.5.dp.toPx()
            val tipY = cy - r
            val wingW = 3.6.dp.toPx()
            val wingH = 3.2.dp.toPx()

            val arrowPath = Path().apply {
                moveTo(tipX + wingW, tipY - wingH)
                lineTo(tipX, tipY)
                lineTo(tipX + wingW, tipY + wingH)
            }
            drawPath(path = arrowPath, color = color, style = stroke)
        }
        Text(
            "10",
            color = color,
            fontFamily = Onest,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}
