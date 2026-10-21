package ru.suslikovanora.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ru.suslikovanora.media.library.Posters

/** A round Discord avatar (as on the PC); under it, while it loads or without one, the first letter of the name. */
@Composable
fun Avatar(url: String?, name: String, size: Dp, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Sm.accent, Color(0xFF6366F1))))
            .border(1.dp, Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = fontSize)
        if (!url.isNullOrBlank()) AsyncImage(
            model = url, imageLoader = Posters.loader(context), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
    }
}
