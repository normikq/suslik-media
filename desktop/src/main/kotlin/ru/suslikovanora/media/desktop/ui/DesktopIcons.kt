package ru.suslikovanora.media.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SearchIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF8E96A8), size: Dp = 16.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        val r = w * 0.32f
        val cx = w * 0.42f
        val cy = h * 0.42f

        drawCircle(color, r, Offset(cx, cy), style = Stroke(sw))
        val cos45 = 0.7071f
        val x1 = cx + r * cos45
        val y1 = cy + r * cos45
        val x2 = w * 0.88f
        val y2 = h * 0.88f
        drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun RefreshIcon(modifier: Modifier = Modifier, color: Color = Color.White, size: Dp = 15.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.7.dp.toPx()
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.35f

        // Arc sweeps clockwise from 20° to 270° (top)
        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(sw, cap = StrokeCap.Round)
        )

        // Arrow head at top pointing right (clockwise direction)
        val tipX = cx + 2.5.dp.toPx()
        val tipY = cy - r
        val arrowW = 4.dp.toPx()
        val arrowH = 3.2.dp.toPx()
        val headPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - arrowW, tipY - arrowH)
            lineTo(tipX - arrowW * 0.7f, tipY)
            lineTo(tipX - arrowW, tipY + arrowH)
            close()
        }
        drawPath(headPath, color = color, style = Fill)
    }
}

@Composable
fun GridIcon(modifier: Modifier = Modifier, color: Color = Color.White, size: Dp = 16.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val pad = w * 0.12f
        val gap = w * 0.14f
        val boxW = (w - pad * 2f - gap) / 2f
        val cr = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        drawRoundRect(color, Offset(pad, pad), Size(boxW, boxW), cr)
        drawRoundRect(color, Offset(pad + boxW + gap, pad), Size(boxW, boxW), cr)
        drawRoundRect(color, Offset(pad, pad + boxW + gap), Size(boxW, boxW), cr)
        drawRoundRect(color, Offset(pad + boxW + gap, pad + boxW + gap), Size(boxW, boxW), cr)
    }
}

@Composable
fun ListIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF8E96A8), size: Dp = 16.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        val padX = w * 0.14f
        val x1 = padX
        val x2 = w - padX

        val y1 = h * 0.25f
        val y2 = h * 0.50f
        val y3 = h * 0.75f

        drawLine(color, Offset(x1, y1), Offset(x2, y1), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(x1, y2), Offset(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(x1, y3), Offset(x2, y3), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun FunnelIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF8E96A8), size: Dp = 15.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.6.dp.toPx()

        val path = Path().apply {
            moveTo(w * 0.15f, h * 0.22f)
            lineTo(w * 0.85f, h * 0.22f)
            lineTo(w * 0.58f, h * 0.56f)
            lineTo(w * 0.58f, h * 0.82f)
            lineTo(w * 0.42f, h * 0.74f)
            lineTo(w * 0.42f, h * 0.56f)
            close()
        }
        drawPath(path, color = color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun ThreeDotsIcon(modifier: Modifier = Modifier, color: Color = Color.White, size: Dp = 18.dp) {
    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2f
        val h = this.size.height
        val r = 1.6.dp.toPx()

        drawCircle(color, r, Offset(cx, h * 0.28f))
        drawCircle(color, r, Offset(cx, h * 0.50f))
        drawCircle(color, r, Offset(cx, h * 0.72f))
    }
}

@Composable
fun ChevronLeft(modifier: Modifier = Modifier, color: Color = Color.White, size: Dp = 14.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()

        val path = Path().apply {
            moveTo(w * 0.62f, h * 0.22f)
            lineTo(w * 0.35f, h * 0.50f)
            lineTo(w * 0.62f, h * 0.78f)
        }
        drawPath(path, color = color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun ChevronRight(modifier: Modifier = Modifier, color: Color = Color.White, size: Dp = 14.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()

        val path = Path().apply {
            moveTo(w * 0.38f, h * 0.22f)
            lineTo(w * 0.65f, h * 0.50f)
            lineTo(w * 0.38f, h * 0.78f)
        }
        drawPath(path, color = color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun LogoPlayIcon(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Color(0xFF141720))
            .border(1.dp, Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(13.dp)) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.18f, h * 0.08f)
                lineTo(w * 0.92f, h * 0.50f)
                lineTo(w * 0.18f, h * 0.92f)
                close()
            }
            drawPath(path, color = Color.White, style = Fill)
        }
    }
}

@Composable
fun PlayTriangle(modifier: Modifier = Modifier, color: Color = Color.White, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.08f)
            lineTo(w * 0.92f, h * 0.50f)
            lineTo(w * 0.18f, h * 0.92f)
            close()
        }
        drawPath(path, color = color, style = Fill)
    }
}

@Composable
fun LightningIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF60A5FA), size: Dp = 12.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.16f, h * 0.54f)
            lineTo(w * 0.50f, h * 0.54f)
            lineTo(w * 0.42f, h)
            lineTo(w * 0.84f, h * 0.44f)
            lineTo(w * 0.52f, h * 0.44f)
            close()
        }
        drawPath(path, color = color, style = Fill)
    }
}

@Composable
fun LinkChainIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF8E96A8), size: Dp = 14.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.6.dp.toPx()
        rotate(degrees = -45f, pivot = Offset(w / 2f, h / 2f)) {
            val rw = w * 0.46f
            val rh = h * 0.32f
            val cr = CornerRadius(rh / 2f, rh / 2f)
            drawRoundRect(color, Offset(w * 0.10f, (h - rh) / 2f), Size(rw, rh), cr, style = Stroke(sw))
            drawRoundRect(color, Offset(w * 0.44f, (h - rh) / 2f), Size(rw, rh), cr, style = Stroke(sw))
        }
    }
}

@Composable
fun ChevronDownIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF8E96A8), size: Dp = 12.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.38f)
            lineTo(w * 0.50f, h * 0.65f)
            lineTo(w * 0.78f, h * 0.38f)
        }
        drawPath(path, color = color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CloseCrossIcon(modifier: Modifier = Modifier, color: Color = Color.White, size: Dp = 12.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.8.dp.toPx()
        val pad = w * 0.22f
        drawLine(color, Offset(pad, pad), Offset(w - pad, h - pad), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w - pad, pad), Offset(pad, h - pad), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
fun InfoCircleIcon(modifier: Modifier = Modifier, color: Color = Color(0xFF8E96A8), size: Dp = 15.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.5.dp.toPx()
        drawCircle(color, w * 0.42f, Offset(w / 2f, h / 2f), style = Stroke(sw))
        drawCircle(color, 1.1.dp.toPx(), Offset(w / 2f, h * 0.30f))
        drawLine(color, Offset(w / 2f, h * 0.44f), Offset(w / 2f, h * 0.72f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}



@Composable
fun HeartIcon(modifier: Modifier = Modifier, filled: Boolean, color: Color = Color.White, size: Dp = 16.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.88f)
            cubicTo(w * 0.08f, h * 0.58f, w * 0.06f, h * 0.30f, w * 0.26f, h * 0.18f)
            cubicTo(w * 0.40f, h * 0.10f, w * 0.50f, h * 0.20f, w * 0.5f, h * 0.30f)
            cubicTo(w * 0.50f, h * 0.20f, w * 0.60f, h * 0.10f, w * 0.74f, h * 0.18f)
            cubicTo(w * 0.94f, h * 0.30f, w * 0.92f, h * 0.58f, w * 0.5f, h * 0.88f)
            close()
        }
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(1.6.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
