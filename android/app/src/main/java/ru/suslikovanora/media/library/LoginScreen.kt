package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import ru.suslikovanora.media.tr

/**
 * Sign-in: Discord through the browser (a code on this screen, confirmed on the site — the TV shows the link and the code
 * for the phone) or the shared password. The session cookie is shared with the WebView.
 */
@Composable
fun LoginScreen(store: LibraryStore, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tv = ru.suslikovanora.media.Tv.isTv(context)
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openBrowser(url: String) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }
    // While the code is on the screen: ask the server every 2 s whether the browser has confirmed it
    LaunchedEffect(store.pairing) {
        if (store.pairing == null) return@LaunchedEffect
        while (store.pairing != null) {
            kotlinx.coroutines.delay(2000)
            when (store.pollDeviceAuth()) {
                true -> { onLoggedIn(); break }
                false -> { error = tr("login.kod_istek"); break }
                null -> {}
            }
        }
    }
    fun startDiscord() {
        if (busy) return
        busy = true; error = null // the previous error (an expired code) has nothing to do with the new one
        scope.launch {
            val err = store.startDeviceAuth(if (tv) "Suslik Media TV" else "Suslik Media Android")
            busy = false
            if (err != null) error = err else if (!tv) store.pairing?.let { openBrowser(it.url) }
        }
    }
    // One page-load reveal, like the site's "lamp warming up".
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(700, easing = Sm.ease)) }

    fun submit() {
        if (busy || password.isBlank()) return
        busy = true; error = null
        scope.launch {
            val err = store.login(password)
            busy = false
            if (err == null) { password = ""; onLoggedIn() } else error = err
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        BrandHeader(onLogout = null)
        Column(Modifier.graphicsLayer { alpha = reveal.value; translationY = (1f - reveal.value) * 24f }) {
            Spacer(Modifier.height(48.dp))
            Text(tr("login.vse_vashi_ssylki_v"), color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text(
                tr("login.naydite_razblokiruyte_smotrite"),
                color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold,
                fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                tr("login.privatnyy_portal_dlya_ssylok"), color = Sm.muted, fontFamily = Onest, fontSize = 16.sp,
                lineHeight = 24.sp, modifier = Modifier.padding(top = 14.dp, bottom = 26.dp)
            )
            Panel {
                val pairing = store.pairing
                if (pairing != null) {
                    Text(tr("login.vhod_cherez_discord"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 19.sp, color = Sm.text)
                    Text(
                        if (tv) tr("login.tv_otkroyte_ssylku", "url" to pairing.url) else tr("login.podtverdite_v_brauzere"),
                        color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )
                    if (tv) {
                        Box(Modifier.fillMaxWidth().padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
                            QrCode(pairing.url, Modifier.size(180.dp))
                        }
                        Text(tr("login.tv_qr_podskazka"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFF1E2330))
                            .border(1.dp, Sm.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(pairing.userCode, color = androidx.compose.ui.graphics.Color.White, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = 4.sp)
                    }
                    Text(tr("login.tolko_svoy_kod"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!tv) LampButton(tr("login.otkryt_v_brauzere"), modifier = Modifier.weight(1f)) { openBrowser(pairing.url) }
                        OutlineButton(tr("login.otmena"), modifier = if (tv) Modifier.fillMaxWidth() else Modifier) { store.cancelDeviceAuth() }
                    }
                } else {
                    Text(tr("login.voyti"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 19.sp, color = Sm.text)
                    Spacer(Modifier.height(12.dp))
                    LampButton(tr("login.voyti_cherez_discord"), enabled = !busy, modifier = Modifier.fillMaxWidth()) { startDiscord() }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(1.dp).background(androidx.compose.ui.graphics.Color(0x1AFFFFFF)))
                        Text(tr("login.ili_po_parolyu"), color = Sm.muted, fontFamily = Onest, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(androidx.compose.ui.graphics.Color(0x1AFFFFFF)))
                    }
                    Spacer(Modifier.height(14.dp))
                    SearchField(value = password, onValue = { password = it }, placeholder = tr("login.parol_dostupa"), password = true, onDone = { submit() })
                    Spacer(Modifier.height(12.dp))
                    OutlineButton(if (busy) tr("login.vypolnyaetsya_vhod") else tr("login.voyti"), modifier = Modifier.fillMaxWidth()) { submit() }
                }
                error?.let { Text(it, color = Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
            Text(
                tr("login.ispolzuyte_tolko_kontent_i"),
                color = Color5f677a, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

private val Color5f677a = androidx.compose.ui.graphics.Color(0xFF5F677A)

/** QR code of [text]: dark modules on white with a quiet zone (a phone camera needs it), drawn once per text. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            com.google.zxing.qrcode.QRCodeWriter().encode(
                text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 0, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
            )
        }.getOrNull()
    } ?: return
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.White).padding(10.dp)) {
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) {
            drawRect(
                androidx.compose.ui.graphics.Color.Black,
                androidx.compose.ui.geometry.Offset(x * cell, y * cell),
                androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f)
            )
        }
    }
}
