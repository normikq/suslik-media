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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.withFrameNanos
import java.awt.Desktop
import java.net.URI

/** Smallest normal window (the mini-player lifts this limit while it is on). */
const val MIN_W = 1005
const val MIN_H = 685

fun main() {
    // Compose's DirectX renderer crashes on some new GPUs/drivers (seen on RTX 50xx); OpenGL is required for mpv OpenGL FBO.
    System.setProperty("skiko.renderApi", "OPENGL")
    // Java2D keeps away from Direct3D too (dxcore.dll once fail-fasted the idle app); must be set before AWT starts.
    System.setProperty("sun.java2d.d3d", "false")
    System.setProperty("sun.java2d.noddraw", "true")
    // Without this JNA decodes UTF-8 text with the Windows code page - Russian names come out garbled.
    System.setProperty("jna.encoding", "UTF-8")
    // Already open: that window comes to the front, this start ends here (no second window).
    if (!SingleInstance.acquire()) return
    Storage.clearOnUpdate() // new version: local caches go, watch history stays
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
    var noMpv by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Library) }
    var preparing by remember { mutableStateOf(false) }
    val keys = remember { mutableStateOf<((KeyEvent) -> Boolean)?>(null) }
    val onTop = remember { mutableStateOf(false) }       // always on top (pin / mini-player)
    var playerGen by remember { mutableStateOf(0) }      // bumps to re-create the player (settings restart)
    var playerStart by remember { mutableStateOf(0) }    // playlist episode the re-created player starts at
    val appScope = rememberCoroutineScope()
    // Kept here so the library comes back scrolled to where it was after watching something.
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val mineGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    // libmpv ставит установщик; если её нет (сервер был недоступен), докачивается в фоне.
    val mpvWarm = remember { CompletableDeferred<Boolean>() }
    LaunchedEffect(Unit) {
        val mpvSetup = ru.suslikovanora.media.desktop.player.MpvInstall.start(appScope)
        appScope.launch(Dispatchers.IO) { Uninstaller.register() }
        // Прогресс и дорожки на аккаунт Discord; пришедшее с телефона или ТВ — сразу в плитки «Продолжить»
        CloudSync.start(appScope) { javax.swing.SwingUtilities.invokeLater { lib.bumpWatch() } }
        withContext(Dispatchers.IO) {
            mpvSetup.await()
            mpvWarm.complete(ru.suslikovanora.media.desktop.player.Mpv.available)
        }
    }
    val presence = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    val focus = remember { arrayOfNulls<androidx.compose.ui.focus.FocusManager>(1) } // plain holder, not state
    // The window's own scope: its frames are the window's frames (closing waits for one without the video).
    val windowScope = remember { arrayOfNulls<kotlinx.coroutines.CoroutineScope>(1) }
    val closing = remember { booleanArrayOf(false) }
    LaunchedEffect(playing) {
        if (playing != null) { leaving = false; presence.animateTo(1f, tween(560, easing = Sm.fly)) }
    }
    fun closePlayer() {
        if (leaving) return
        appScope.launch {
            leaving = true
            // (The player already told the library about the new progress, before this animation started.)
            presence.animateTo(0f, tween(600, easing = Sm.fly))
            // Suslik Debug: Compose сейчас уберёт слой с видео — здесь зависала Skia (цикл glGetError)
            if (Diag.on) { PlayerLog.write("[ui] player close: removing the player"); MpvVideoOut.probeGl("player close: before removal") }
            playing = null; leaving = false; onTop.value = false
            CloudSync.kick() // прогресс этого просмотра — на аккаунт сразу
            if (Diag.on) javax.swing.SwingUtilities.invokeLater { MpvVideoOut.probeGl("player close: after removal") }
        }
    }
    fun requestPlay(req: PlayRequest) {
        // In a room, the host's film becomes the room's film (a guest's other film just plays for them alone)
        val party = ru.suslikovanora.media.desktop.party.Party
        req.watchKeys.getOrNull(req.startIndex)?.let { key -> if (party.isHost) party.putFilm(ru.suslikovanora.media.core.party.PartyFileRef(key, req.title)) }
        appScope.launch {
            if (!mpvWarm.isCompleted) preparing = true
            val ok = mpvWarm.await()
            preparing = false
            if (ok) { playerStart = req.startIndex; playing = req } else noMpv = true
        }
    }

    LaunchedEffect(Unit) {
        lib.restoreSnapshot()
        val ok = lib.checkSession()
        if (ok == null && lib.items.isNotEmpty()) lib.refresh() // offline check failed: still try with the saved copy
    }
    // Once after start, off the critical path: limits of the poster folder.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        withContext(Dispatchers.IO) { runCatching { lib.prunePosterFiles() } }
    }
    // Which films TMDB calls animation (for the «Мультфильмы» category): in the background, whenever the list changes.
    LaunchedEffect(lib.items.size, lib.authenticated) { if (lib.authenticated == true && lib.items.isNotEmpty()) lib.classifyAnimation() }
    // Dolby Vision / HDR / DTS marks on the posters: the server finds them with ffprobe (in the background, saved until the film is deleted)
    LaunchedEffect(lib.items.size, lib.authenticated) { if (lib.authenticated == true && lib.items.isNotEmpty()) lib.refreshTags() }
    // The «Продолжить просмотр» banners (wide pictures, texts) are ready before the library page opens
    LaunchedEffect(lib.items.size, lib.authenticated) { if (lib.authenticated == true && lib.items.isNotEmpty()) warmContinue(lib.items, lib) }
    // A guest of a room stays on the room's page (the library is closed for them, see Sidebar)
    val partyGuest = ru.suslikovanora.media.desktop.party.Party.let { it.inRoom && !it.isHost }
    LaunchedEffect(partyGuest) { if (partyGuest && (tab == Tab.Library || tab == Tab.Mine)) tab = Tab.Together }
    // Signed in (at start or after the login screen): load the library in the app-wide scope.
    LaunchedEffect(lib.authenticated) { if (lib.authenticated == true) lib.refresh() }

    Window(
        // mpv's video output is freed while the window's OpenGL context still exists: on close Compose removes the
        // Skia layer (and its GL context) before the player's onDispose runs, and mpv aborts the process over it
        onCloseRequest = {
            AppExit.armWatchdog()
            if (Diag.on) {
                PlayerLog.write("[ui] window close request (${java.awt.EventQueue.getCurrentEvent()?.javaClass?.simpleName}), minimized=${win.isMinimized}, player=${playing != null}")
                MpvVideoOut.probeGl("window close: start")
            }
            val scope = windowScope[0]
            if (scope == null || !MpvVideoOut.anyLive) {
                MpvVideoOut.disposeAll()
                exitApplication()
            } else if (!closing[0]) {
                closing[0] = true
                // Сначала кадр без видео, пока GL-контекст окна жив (см. MpvVideoOut.anyLive), потом закрытие.
                // Свёрнутое окно не рисует: тогда не ждём дольше 0,7 с (дальше страхует сторож AppExit).
                scope.launch {
                    MpvVideoOut.disposeAll()
                    if (Diag.on) MpvVideoOut.probeGl("window close: video freed")
                    val drawn = withTimeoutOrNull(700) { withFrameNanos { }; withFrameNanos { } } != null
                    if (Diag.on) PlayerLog.write("[ui] window close: frame without video drawn=$drawn")
                    exitApplication()
                }
            }
        },
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
        windowScope[0] = rememberCoroutineScope()

        // Dark title bar in the app's colours, and a dark window behind Compose (no white flash while animating/resizing).
        LaunchedEffect(Unit) {
            WindowMode.currentWindow = window
            window.background = java.awt.Color(0x06, 0x09, 0x11)
            window.contentPane.background = java.awt.Color(0x06, 0x09, 0x11)
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
        DisposableEffect(window) { // [resize] journal (Suslik Debug): the window's size is the reference the surfaces are compared with
            val cp = window.contentPane
            val l = object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    if (cp.width > 0 && cp.height > 0) {
                        ResizeLog.surface("window", cp.width, cp.height)
                        ResizeLog.watchSkia(cp)
                    }
                }
            }
            window.addComponentListener(l)
            onDispose { window.removeComponentListener(l) }
        }
        focus[0] = androidx.compose.ui.platform.LocalFocusManager.current
        SuslikTheme {
          Box(Modifier.fillMaxSize().background(Sm.night).onSizeChanged { ResizeLog.surface("compose", it.width, it.height) }) {
            // Library and player share the window. The library stays composed underneath (instant, hitch-free return);
            // [presence] drives both layers: 0 = library, 1 = player.
            Box(Modifier.fillMaxSize().graphicsLayer {
                val p = presence.value
                if (Motion.simple) {
                    alpha = if (p >= 1f) 0f else 1f       // no scaling / fading of the whole library: it just stays until the player covers it
                } else {
                    val s = 0.93f + 0.07f * (1f - p)      // recedes while the player is up, comes forward on return
                    scaleX = s; scaleY = s; alpha = (1f - p * 1.15f).coerceIn(0f, 1f)
                }
            }) {
                // Its own layer: the scale and fade above change on every frame of the flight, and without it each change
                // drew the whole library again (backdrop, side panel, texts); now the recorded picture is just reused.
                androidx.compose.animation.Crossfade(lib.authenticated == false, animationSpec = tween(420, easing = Sm.ease), modifier = Modifier.fillMaxSize().graphicsLayer().background(Sm.night)) { needLogin ->
                  if (needLogin) {
                    LoginView(lib)
                  } else {
                  androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().ambientBackdrop()) {
                    // Fixed side panel: stays put while the pages change.
                    Sidebar(
                        tab, lib, lib.profile,
                        onSelect = { tab = it; if (it == Tab.Library || it == Tab.Mine) appScope.launch { lib.refresh() } },
                        onSettings = { PlaybackSettings.open = true },
                        onLogout = { appScope.launch { lib.logout() } }
                    )
                    // Pages glide sideways in the direction of the tab, old one fading out first (no flash, no jump).
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                          if (Motion.simple) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None else {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (fadeIn(tween(320, delayMillis = 70, easing = Sm.ease)) +
                                slideInHorizontally(tween(460, easing = Sm.ease)) { dir * it / 14 }) togetherWith
                                (fadeOut(tween(180)) + slideOutHorizontally(tween(460, easing = Sm.ease)) { -dir * it / 14 })
                          }
                        },
                        modifier = Modifier.weight(1f).fillMaxSize()
                    ) { t ->
                        when (t) {
                            Tab.Library -> LibraryView(lib, grid) { r -> requestPlay(r) }
                            Tab.Mine -> LibraryView(lib, mineGrid, mineOnly = true) { r -> requestPlay(r) }
                            Tab.Search -> SearchView(lib) {}
                            Tab.Add -> AddView(lib, onPlay = { requestPlay(it) }) {}
                            Tab.Together -> ru.suslikovanora.media.desktop.party.TogetherView(lib, onLibrary = { tab = Tab.Library }) { r -> requestPlay(r) }
                        }
                    }
                  }
                  }
                }
            }
            playing?.let { req ->
                val filmId = remember(req) { req.watchKeys.firstOrNull()?.substringBefore('|')?.toLongOrNull() }
                val lastHome = remember(req) { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) } // plain holder, not state
                // Where the poster was at the moment of the click (the library has not started to recede yet): the player flies out of it.
                val openFrom = remember(req) { TileBounds.target(filmId) }
                Box(Modifier.fillMaxSize()
                    .graphicsLayer {
                        val p = presence.value
                        // Read live (the library is still settling); a frame where it is briefly unknown keeps the last one.
                        val home = if (leaving) (TileBounds.target(filmId) ?: lastHome[0]) else null
                        lastHome[0] = home
                        if (home != null) {
                            // Exit: the frame flies back into its own poster in the library.
                            flyInto(home, 1f - p)
                        } else if (!leaving && openFrom != null) {
                            // Entrance: the frame grows out of the poster to the full window (the exit played backwards).
                            flyInto(openFrom, 1f - p)
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
                    // (own layer, as the library's: the flight moves the picture without drawing the player again)
                    Box(Modifier.fillMaxSize().graphicsLayer()) {
                        key(req, playerGen) {
                            PlayerView(req, lib, win, keys, onTop, startIndex = playerStart,
                                parentWindow = window,
                                onRestart = { i -> playerStart = i; playerGen++ }) { closePlayer() }
                        }
                    }
                }
            }
            // A poster flying into the sidebar tab it was just moved to (favourites).
            FavoriteFlight()
            // New version on the server: card over the library (never over a playing film).
            UpdateCheck(authenticated = lib.authenticated == true, show = playing == null)
            // Once after an update: what changed, in plain words.
            WhatsNew(enabled = lib.authenticated == true && playing == null)
            if (PlaybackSettings.open && playing == null) SettingsSheet { PlaybackSettings.open = false }
          }
            if (preparing) AlertDialog(
                onDismissRequest = {},
                title = { Text(tr("app.podgotovka_pleera")) },
                text = { Text(tr("app.pervyy_zapusk_pleera_zanimaet")) },
                confirmButton = {}
            )
            val mpvError = ru.suslikovanora.media.desktop.player.MpvInstall.error
            if (noMpv) AlertDialog(
                onDismissRequest = { noMpv = false },
                title = { Text(tr("app.mpv_ne_skachan")) },
                text = { Text(if (mpvError != null) tr("app.mpv_ne_skachan_tekst", "err" to mpvError) else tr("settings.dvizhok_mpv_otsutstvuet")) },
                confirmButton = { TextButton({ noMpv = false; ru.suslikovanora.media.desktop.player.MpvInstall.start(appScope) }) { Text(tr("app.povtorit")) } },
                dismissButton = { TextButton({ noMpv = false }) { Text(tr("library.zakryt")) } }
            )
        }
    }
}
