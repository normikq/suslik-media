package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A poster that "flies" into the sidebar tab it now belongs to: into «Мои фильмы» when a film is added there, back into
 * «Библиотека» when it is taken out while «Мои фильмы» are open. The sidebar items report where they are
 * ([navBounds]) and glow for a moment when the poster lands ([glowTab]).
 */
object FlyHub {
    class Flight(val poster: ImageBitmap?, val from: Rect, val to: Tab)

    val flights = mutableStateListOf<Flight>()
    val navBounds = HashMap<Tab, LayoutCoordinates>()
    var glowTab by mutableStateOf<Tab?>(null)
    var glowStamp by mutableStateOf(0)

    fun launch(poster: ImageBitmap?, from: Rect, to: Tab) { flights += Flight(poster, from, to) }

    private fun navRect(tab: Tab): Rect? {
        val c = navBounds[tab]?.takeIf { it.isAttached } ?: return null
        val a = c.localToWindow(Offset.Zero)
        return Rect(a, Offset(a.x + c.size.width, a.y + c.size.height))
    }

    internal fun target(tab: Tab): Rect? = navRect(tab)
    internal fun arrive(f: Flight) { flights.remove(f); glowTab = f.to; glowStamp++ }
}

@Composable
fun FavoriteFlight() {
    Box(Modifier.fillMaxSize()) { FlyHub.flights.toList().forEach { f -> key(f) { OneFlight(f) } } }
}

@Composable
private fun OneFlight(f: FlyHub.Flight) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(680, easing = Sm.fly))
        FlyHub.arrive(f)
    }
    val d = LocalDensity.current
    // Lands on the tab's icon (left side of the item); if the item is not on screen the poster just shrinks in place.
    val to = remember { FlyHub.target(f.to)?.let { Offset(it.left + with(d) { 24.dp.toPx() }, it.center.y) } ?: f.from.center }
    val end = with(d) { 26.dp.toPx() } / maxOf(f.from.width, f.from.height) // the poster shrinks to icon size, same proportions
    val arc = with(d) { 70.dp.toPx() }
    // Laid out once at the poster's own place; the flight is only a transform (scale + shift) in the graphics layer,
    // so no frame needs a new layout: it stays smooth on weak machines.
    Box(
        Modifier.offset { IntOffset(f.from.left.roundToInt(), f.from.top.roundToInt()) }
            .size(with(d) { f.from.width.toDp() }, with(d) { f.from.height.toDp() })
            .graphicsLayer {
                val t = p.value
                val s = 1f + (end - 1f) * t
                scaleX = s; scaleY = s
                translationX = (to.x - f.from.center.x) * t
                translationY = (to.y - f.from.center.y) * t - sin(PI.toFloat() * t) * arc // a small arc upwards
                alpha = 1f - ((t - 0.78f) / 0.22f).coerceIn(0f, 1f)
                shape = RoundedCornerShape(14.dp); clip = true
            }
            .background(Brush.verticalGradient(listOf(Color(0xFF222838), Color(0xFF10131B))))
    ) {
        f.poster?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
