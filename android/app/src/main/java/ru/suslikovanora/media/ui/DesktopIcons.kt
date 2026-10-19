package ru.suslikovanora.media.ui

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

        drawArc(
            color = color,
            startAngle = 20f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(sw, cap = StrokeCap.Round)
        )

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
fun HeartIcon(modifier: Modifier = Modifier, filled: Boolean, color: Color = Color.White, size: Dp = 16.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.88f)
            cubicTo(w * 0.08f, h * 0.58f, w * 0.06f, h * 0.30f, w * 0.26f, h * 0.18f)
            cubicTo(w * 0.40f, h * 0.10f, w * 0.50f, h * 0.20f, w * 0.5f, h * 0.30f)
            cubicTo(w * 0.50f, h * 0.20f, w * 0.60f, h * 0.10f, w * 0.74f, h * 0.18f)
            cubicTo(w * 0.94f, h * 0.30f, w * 0.92f, h * 0.58f, w * 0.5f, h * 0.88f)
            close()
        }
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
    }
}
