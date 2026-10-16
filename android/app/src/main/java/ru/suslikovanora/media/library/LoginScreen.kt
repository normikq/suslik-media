package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import ru.suslikovanora.media.tr

/** Native version of the website's login: one shared password; the session cookie is shared with the WebView. */
@Composable
fun LoginScreen(store: LibraryStore, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
        Modifier.fillMaxSize().background(Sm.night).verticalScroll(rememberScrollState())
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
                Text(tr("login.voyti"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 19.sp, color = Sm.text)
                Text(tr("login.vvedite_obschiy_parol_dostupa"), color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                SearchField(value = password, onValue = { password = it }, placeholder = tr("login.parol_dostupa"), password = true, onDone = { submit() })
                Spacer(Modifier.height(12.dp))
                LampButton(if (busy) tr("login.vypolnyaetsya_vhod") else tr("login.voyti"), enabled = !busy && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { submit() }
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
