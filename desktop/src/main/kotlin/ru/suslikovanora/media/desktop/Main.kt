package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.player.*
import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.awt.Desktop
import java.net.URI

/** Smallest normal window (the mini-player lifts this limit while it is on). */
const val MIN_W = 1005
const val MIN_H = 685

fun main() {
    // Compose's DirectX renderer crashes on some new GPUs/drivers (seen on RTX 50xx); OpenGL is required for mpv OpenGL FBO.
    System.setProperty("skiko.renderApi", "OPENGL")
    // VLC hands over every text (track names, chapters, titles) as UTF-8. Without this JNA decoded it with the
    // Windows code page — Russian voice-over / subtitle names came out as «РЎСѓР±С‚РёС‚СЂС‹».
    System.setProperty("jna.encoding", "UTF-8")
    // Already open: that window comes to the front, this start ends here (no second window, no second VLC).
    if (!SingleInstance.acquire()) return
    CrashReports.start() // last run crashed? → report; from now on uncaught errors report themselves
    Diag.start() // debug build only: the detailed journal
    app()
    // The window is gone: make sure the process really ends (no "ghost" left in Task Manager).
    AppExit.finish()
}

private fun app() = application(exitProcessOnExit = false) { // the exit itself: AppExit.finish() in main()
    val lib = remember { Library() }
    val win = rememberWindowState(width = 1280.dp, height = 860.dp)
    var playing by remember { mutableStateOf<PlayRequest?>(null) }
    var noVlc by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Library) }
    var preparing by remember { mutableStateOf(false) }
    val keys = remember { mutableStateOf<((KeyEvent) -> Boolean)?>(null) }
    val onTop = remember { mutableStateOf(false) }       // always on top (pin / mini-player)
    var playerGen by remember { mutableStateOf(0) }      // bumps to re-create the player (settings restart)
    var playerStart by remember { mutableStateOf(0) }    // playlist episode the re-created player starts at
    val appScope = rememberCoroutineScope()
    // Kept here so the library comes back scrolled to where it was after watching something.
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    // The very first VLC start scans all its plugins (several seconds): do it now, in the background.
    val vlcWarm = remember { CompletableDeferred<Boolean>() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val mpv = ru.suslikovanora.media.desktop.player.EngineChoice.useMpv
            val ok = Vlc.available || mpv
            if (ok && !mpv) runCatching { VlcFactory.warm() } // the real engine, reused by every player
            vlcWarm.complete(ok)
        }
    }
    val presence = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    val focus = remember { arrayOfNulls<androidx.compose.ui.focus.FocusManager>(1) } // plain holder, not state
    LaunchedEffect(playing) {
        if (playing != null) { leaving = false; presence.animateTo(1f, tween(440, easing = Sm.ease)) }
    }
    fun closePlayer() {
        if (leaving) return
        appScope.launch {
            leaving = true
            // (The player already told the library about the new progress, before this animation started.)
            presence.animateTo(0f, tween(620, easing = Sm.ease))
            playing = null; leaving = false; onTop.value = false
        }
    }
    fun requestPlay(req: PlayRequest) {
        appScope.launch {
            if (!vlcWarm.isCompleted) preparing = true
            val ok = vlcWarm.await()
            preparing = false
            if (ok) { playerStart = 0; playing = req } else noVlc = true
        }
    }

    LaunchedEffect(Unit) {
        lib.restoreSnapshot()
        val ok = lib.checkSession()
        if (ok == null && lib.items.isNotEmpty()) lib.refresh() // offline check failed: still try with the saved copy
    }
    // Signed in (at start or after the login screen): load the library in the app-wide scope.
    LaunchedEffect(lib.authenticated) { if (lib.authenticated == true) lib.refresh() }

    Window(
        onCloseRequest = ::exitApplication,
        title = (if (Diag.on) "Suslik Debug" else "Suslik Media").let { app -> playing?.let { "${it.title} — $app" } ?: tr("app.app_mediateka", "app" to app) },
        icon = painterResource("icons/icon-256.png"),
        state = win,
        alwaysOnTop = onTop.value,
        onPreviewKeyEvent = { event ->
            if (playing != null) {
                keys.value?.invoke(event) ?: false
            } else if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
                WindowMode.toggleFullscreen()
                true
            } else {
                false
            }
        }
    ) {

        // Dark title bar in the app's colours, and a dark window behind Compose (no white flash while animating/resizing).
        LaunchedEffect(Unit) {
            WindowMode.currentWindow = window
            window.background = java.awt.Color(0x09, 0x0B, 0x10)
            window.contentPane.background = java.awt.Color(0x09, 0x0B, 0x10)
            TitleBar.style(window)
            // Window / taskbar / Alt+Tab icon: ready-made sizes, so Windows picks a crisp one instead of shrinking 1024 px.
            runCatching {
                window.iconImages = listOf(16, 24, 32, 48, 64, 128, 256).mapNotNull { sz ->
                    Thread.currentThread().contextClassLoader.getResourceAsStream("icons/icon-$sz.png")?.use { javax.imageio.ImageIO.read(it) }
                }
            }
            window.minimumSize = java.awt.Dimension(MIN_W, MIN_H) // smaller than this the header and tabs no longer fit
            // Started again while open: show this window instead (restored, in front, focused).
            SingleInstance.register {
                win.isMinimized = false
                window.toFront()
                // Windows only flashes the taskbar button for toFront() from a background app: a short "on top" lifts it.
                if (!window.isAlwaysOnTop) { window.isAlwaysOnTop = true; window.isAlwaysOnTop = onTop.value }
                window.requestFocus()
            }
        }
        val lens by androidx.compose.animation.core.animateFloatAsState(tab.ordinal.toFloat(), tween(420, easing = Sm.ease))
        focus[0] = androidx.compose.ui.platform.LocalFocusManager.current
        SuslikTheme {
          Box(Modifier.fillMaxSize().background(Sm.night)) {
            // Library and player share the window. The library stays composed underneath (instant, hitch-free return);
            // [presence] drives both layers: 0 = library, 1 = player.
            Box(Modifier.fillMaxSize().graphicsLayer {
                val p = presence.value
                val s = 0.93f + 0.07f * (1f - p)          // recedes while the player is up, comes forward on return
                scaleX = s; scaleY = s; alpha = (1f - p * 1.15f).coerceIn(0f, 1f)
            }) {
                androidx.compose.animation.Crossfade(lib.authenticated == false, animationSpec = tween(420, easing = Sm.ease), modifier = Modifier.fillMaxSize().background(Sm.night)) { needLogin ->
                  if (needLogin) {
                    LoginView(lib)
                  } else {
                    val tabs: @Composable () -> Unit = {
                        NavTabs(tab, lens) { tab = it; if (it == Tab.Library) appScope.launch { lib.refresh() } }
                    }
                  androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Sm.night)) {
                    // Fixed header: stays put while the pages below change.
                    Box(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 4.dp)) {
                        Brand({ appScope.launch { lib.logout() } }, tabs)
                    }
                    // Pages glide sideways in the direction of the tab, old one fading out first (no flash, no jump).
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (fadeIn(tween(320, delayMillis = 70, easing = Sm.ease)) +
                                slideInHorizontally(tween(460, easing = Sm.ease)) { dir * it / 14 }) togetherWith
                                (fadeOut(tween(180)) + slideOutHorizontally(tween(460, easing = Sm.ease)) { -dir * it / 14 })
                        },
                        modifier = Modifier.fillMaxSize().background(Sm.night)
                    ) { t ->
                        when (t) {
                            Tab.Library -> LibraryView(lib, tabs, grid) { r -> requestPlay(r) }
                            Tab.Search -> SearchView(lib) {}
                            Tab.Add -> AddView(lib) {}
                        }
                    }
                  }
                  }
                }
            }
            playing?.let { req ->
                val filmId = remember(req) { req.watchKeys.firstOrNull()?.substringBefore('|')?.toLongOrNull() }
                val lastHome = remember(req) { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) } // plain holder, not state
                Box(Modifier.fillMaxSize()
                    .graphicsLayer {
                        val p = presence.value
                        // Read live (the library is still settling); a frame where it is briefly unknown keeps the last one.
                        val home = if (leaving) (TileBounds.target(filmId) ?: lastHome[0]) else null
                        lastHome[0] = home
                        if (home != null) {
                            // Exit: the frame flies back into its own poster in the library.
                            flyInto(home, 1f - p)
                        } else if (leaving) {
                            // Exit when the poster is not on screen: the picture shrinks into a rounded card and fades.
                            val s = 0.86f + 0.14f * p
                            scaleX = s; scaleY = s
                            alpha = (p * 1.6f).coerceIn(0f, 1f)
                            shape = RoundedCornerShape((28f * (1f - p)).coerceAtLeast(0f).dp); clip = true
                        } else {
                            val s = 1.035f - 0.035f * p
                            scaleX = s; scaleY = s; alpha = p
                        }
                    }
                    // Swallows pointer events so the hidden library underneath never reacts.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                ) {
                    key(req, playerGen) {
                        PlayerView(req, lib, win, keys, onTop, startIndex = playerStart,
                            parentWindow = window,
                            onRestart = { i -> playerStart = i; playerGen++ }) { closePlayer() }
                    }
                }
            }
            // New version on the server: card over the library (never over a playing film).
            UpdateCheck(authenticated = lib.authenticated == true, show = playing == null)
            // Once after an update: what changed, in plain words.
            WhatsNew(enabled = lib.authenticated == true && playing == null)
            if (PlaybackSettings.open && playing == null) SettingsSheet { PlaybackSettings.open = false }
          }
            if (preparing) AlertDialog(
                onDismissRequest = {},
                title = { Text(tr("app.zapuskaem_vlc")) },
                text = { Text(tr("app.pervyy_zapusk_pleera_zanimaet")) },
                confirmButton = {}
            )
            if (noVlc) AlertDialog(
                onDismissRequest = { noVlc = false },
                title = { Text(tr("app.nuzhen_vlc")) },
                text = { Text(tr("app.dlya_vosproizvedeniya_ustanovi")) },
                confirmButton = { TextButton({ noVlc = false; runCatching { Desktop.getDesktop().browse(URI("https://www.videolan.org/vlc/")) } }) { Text(tr("app.skachat_vlc")) } },
                dismissButton = { TextButton({ noVlc = false }) { Text(tr("library.zakryt")) } }
            )
        }
    }
}
