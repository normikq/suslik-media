package ru.suslikovanora.media

import ru.suslikovanora.media.core.*

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import ru.suslikovanora.media.library.BrandHeader
import ru.suslikovanora.media.library.LocalHeaderHeight
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.suslikovanora.media.library.AddModel
import ru.suslikovanora.media.library.AddScreen
import ru.suslikovanora.media.library.LibraryScreen
import ru.suslikovanora.media.library.LibraryStore
import ru.suslikovanora.media.library.LoginScreen
import ru.suslikovanora.media.library.PlaySheet
import ru.suslikovanora.media.library.PlayTarget
import ru.suslikovanora.media.library.SearchModel
import ru.suslikovanora.media.library.SearchScreen
import ru.suslikovanora.media.library.TogetherScreen
import ru.suslikovanora.media.party.Party
import ru.suslikovanora.media.ui.NavBar
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.ambientBackdrop
import ru.suslikovanora.media.ui.SuslikTheme
import ru.suslikovanora.media.ui.Tab

/**
 * Host screen, fully native (Compose): «Ссылка» (magnet / file link / .torrent), «Библиотека», «Мои фильмы»,
 * «Вместе» (shared viewing, party/Party.kt), «Поиск».
 * No WebView any more: nothing of the website runs in the background, the session cookie is shared through
 * CookieManager (same as before, so an existing login keeps working).
 */
class MainActivity : ComponentActivity() {
    private lateinit var store: LibraryStore
    private lateinit var add: AddModel
    private lateinit var search: SearchModel
    private var tab by mutableStateOf(Tab.Library)
    private var playTarget by mutableStateOf<PlayTarget?>(null)
    /** A room's guest: the host's file, opened without the play sheet's choices (another episode would leave the room behind). */
    private var followTarget by mutableStateOf<PlayTarget?>(null)
    private val uiScope = MainScope() // outlives tab switches: searches and download tracking keep going
    // Remote control (Android TV): Back first brings focus to the tabs below, only then leaves the app.
    private val navFocus = FocusRequester()
    private var navFocused = false
    private var settingsOpen by mutableStateOf(false)
    /** suslik://room/<код> (Discord Activity), ждёт входа в аккаунт: потом — в комнату и на её страницу. */
    private var pendingRoomLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ru.suslikovanora.media.net.Api.init(this)
        CrashReports.init(this)
        CrashReports.sendPending() // a crash or a player that died last time → report
        val bg = android.graphics.Color.rgb(6, 9, 17)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(bg), navigationBarStyle = SystemBarStyle.dark(bg))
        CookieManager.getInstance().setAcceptCookie(true)
        store = LibraryStore(this)
        add = AddModel(this, uiScope, store)
        search = SearchModel(uiScope, store)
        // Progress from the PC or the TV (CloudSync): the tiles re-read it
        uiScope.launch { ru.suslikovanora.media.library.CloudSync.changes.collect { if (it > 0) store.bumpWatch() } }
        savedInstanceState?.getString(KEY_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    playTarget != null -> playTarget = null
                    Tv.isTv(this@MainActivity) && !navFocused && !settingsOpen && store.authenticated == true &&
                        runCatching { navFocus.requestFocus() }.isSuccess -> {}
                    tab != homeTab() -> selectTab(homeTab())
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        handleIncoming(intent)
        setContent { SuslikTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** magnet: links opened from a browser / shared from another app land in «Ссылка», ready to add. */
    private fun handleIncoming(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme.equals("suslik", ignoreCase = true)) {
            if (data?.host.equals("room", ignoreCase = true)) pendingRoomLink = data.toString()
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim() ?: return
        val link = Regex("""magnet:\?\S+""", RegexOption.IGNORE_CASE).find(text)?.value
            ?: Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(text)?.value ?: return
        add.mode = 0; add.input = link; add.status = tr("main.nazhmite_dobavit_chtoby_otprav"); add.statusOk = true
        tab = Tab.Links
    }

    @Composable
    private fun AppRoot() {
        val scope = rememberCoroutineScope()
        var update by remember { mutableStateOf<Updater.Info?>(null) }
        LaunchedEffect(Unit) {
            store.restoreSnapshot()
            val ok = store.checkSession()
            // New version on the own server? Asked at every start, alongside the library load (offline: quietly nothing).
            if (ok == true) launch { update = Updater.check(this@MainActivity) }
            if (ok == true) add.resume()
            if (ok == true || (ok == null && store.magnets.isNotEmpty())) store.refresh()
        }
        // Dolby Vision / HDR / DTS marks (the server looks into the files) and the TV banners' pictures: once the library is there
        // (also when it arrives later: first start without a snapshot, sign-in after the start)
        val libraryReady = store.items.isNotEmpty() && store.authenticated == true
        LaunchedEffect(store.items.size, store.authenticated) {
            if (store.authenticated != true || store.items.isEmpty()) return@LaunchedEffect
            launch { store.refreshTags() }
            launch { store.classifyAnimation() }
            if (Tv.isTv(this@MainActivity)) launch { ru.suslikovanora.media.library.warmHero(store, this@MainActivity) }
        }
        // Read inside derivedStateOf: the root is recomposed when the keyboard appears or goes, not at every frame of its animation
        val imeDensity = LocalDensity.current
        val imeInsets = WindowInsets.ime
        val imeVisible by remember { derivedStateOf { imeInsets.getBottom(imeDensity) > 0 } }
        // Hoisted so every tab keeps its scroll position (and the library its filter) across tab switches.
        val gridState = rememberLazyGridState()
        val mineGrid = rememberLazyGridState()
        val togetherList = rememberLazyListState()
        val addList = rememberLazyListState()
        val searchList = rememberLazyListState()
        var query by rememberSaveable { mutableStateOf("") }
        val logout: () -> Unit = { scope.launch { store.logout() } }
        val density = LocalDensity.current
        var headerHeight by remember { mutableStateOf(46.dp) }
        LaunchedEffect(store.authenticated) {
            // TV: the remote starts on the tabs (from there «up» goes into the open tab)
            if (store.authenticated == true && Tv.isTv(this@MainActivity)) runCatching { navFocus.requestFocus() }
        }
        // The open page (shared by the phone's layout and the TV's)
        val pages: @Composable () -> Unit = {
                    // Tabs glide sideways in the direction of the chosen tab (no flash: every page has the same dark background).
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (fadeIn(tween(260, delayMillis = 50)) + slideInHorizontally(tween(380, easing = Sm.ease)) { dir * it / 12 }) togetherWith
                                (fadeOut(tween(150)) + slideOutHorizontally(tween(380, easing = Sm.ease)) { -dir * it / 12 })
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "tabs"
                    ) { t ->
                        when (t) {
                            Tab.Links -> AddScreen(add, addList, onLogout = logout)
                            Tab.Library -> LibraryScreen(
                                store = store, onPlay = { playTarget = it },
                                query = query, onQuery = { query = it }, gridState = gridState, onLogout = logout
                            )
                            Tab.Mine -> LibraryScreen(
                                store = store, onPlay = { playTarget = it },
                                query = query, onQuery = { query = it }, gridState = mineGrid, onLogout = logout, mineOnly = true
                            )
                            Tab.Together -> TogetherScreen(store, togetherList, onLibrary = { selectTab(Tab.Library) }, onOpen = { playTarget = it })
                            Tab.Search -> SearchScreen(search, searchList, onLogout = logout)
                        }
                    }
        }
        // «Открыть в приложении» из Discord Activity: в комнату (когда вход в аккаунт уже есть) и на её страницу
        LaunchedEffect(pendingRoomLink, store.authenticated) {
            val link = pendingRoomLink ?: return@LaunchedEffect
            if (store.authenticated != true) return@LaunchedEffect
            pendingRoomLink = null
            Party.openLink(link)?.let { Party.notice = it }
            selectTab(Tab.Together)
        }
        // A guest of a room stays on the room's page: the library (and «Мои фильмы») are closed until they leave it
        val guest = Party.isGuest
        val tvUi = Tv.isTv(this@MainActivity)
        androidx.compose.runtime.DisposableEffect(Unit) {
            // приходит из потока событий комнаты (не главный поток): обновление — в корутине экрана
            // Сервер шлёт refresh_library всем в комнате: ведущий фильм выбрал из своей медиатеки — ему не нужно
            Party.onRefreshLibrary = { if (!Party.isHost) scope.launch { store.refresh() } }
            onDispose { Party.onRefreshLibrary = null }
        }
        LaunchedEffect(guest) { if (guest && tab in GUEST_LOCKED) tab = Tab.Together }
        // A guest watches the host's file: it opens by itself at the room's place, once per file (closed by the guest,
        // it waits for «Смотреть вместе»). The guest's progress is kept as usual (PlayerWatchProgress, CloudSync).
        var lastTriedKey by remember { mutableStateOf<String?>(null) }
        val roomKey = Party.file?.key
        LaunchedEffect(guest, roomKey, Party.followed, Party.playingKeys, store.items) {
            // No film (the host closed it): the same film put again opens again
            if (!guest || roomKey == null) {
                followTarget = null
                if (roomKey == null) { Party.followed = null; lastTriedKey = null }
                return@LaunchedEffect
            }
            if (roomKey == Party.followed) return@LaunchedEffect
            // The host went on within the open playlist: the player follows by itself (PlayerPartySync)
            if (roomKey in Party.playingKeys) { Party.followed = roomKey; return@LaunchedEffect }
            val rf = Party.file
            val t = ru.suslikovanora.media.library.targetForKey(store.items, roomKey)
                ?: rf?.let { fileRef ->
                    store.items.firstNotNullOfOrNull { item ->
                        val canMatch = (fileRef.canonicalId != null && item.m.aiTitle?.canonicalId == fileRef.canonicalId) ||
                                fileRef.variants.any { v -> v.key.substringBefore('|') == item.m.id.toString() }
                        if (canMatch) {
                            val f = item.m.files.firstOrNull { file -> fileRef.matches(ru.suslikovanora.media.core.watchKey(item.m.id, file)) }
                                ?: item.m.files.firstOrNull { it.type == "file" }
                            f?.let { ru.suslikovanora.media.library.targetForKey(store.items, ru.suslikovanora.media.core.watchKey(item.m.id, it)) }
                        } else null
                    }
                }
            if (t == null) {
                if (lastTriedKey != roomKey) {
                    lastTriedKey = roomKey
                    store.refresh()
                }
                return@LaunchedEffect
            }
            lastTriedKey = null
            Party.followed = roomKey
            playTarget = null
            followTarget = t
        }
        CompositionLocalProvider(LocalHeaderHeight provides (if (tvUi) 0.dp else headerHeight)) {
        // Without the keyboard's inset: with it the whole screen was measured again at every frame of the keyboard animation
        Box(Modifier.fillMaxSize().ambientBackdrop().windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))) {
            if (store.authenticated == false) {
                LoginScreen(store) {
                    scope.launch { store.refresh() }
                    add.resume()
                }
            } else if (tvUi) {
                // Android TV: the left panel instead of the bottom bar and the brand header (it holds the brand, settings and profile)
                Row(Modifier.fillMaxSize()) {
                    ru.suslikovanora.media.ui.TvSidebar(
                        tab = tab, store = store, onSelect = ::selectTab, onSettings = { settingsOpen = true }, onLogout = logout,
                        focus = navFocus, onFocused = { navFocused = it },
                        locked = if (guest) GUEST_LOCKED else emptySet(), inRoom = Party.inRoom
                    )
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) { pages() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        pages()
                        // The brand header is drawn once, over the tabs: it does not slide with them. It follows the open tab's
                        // scrolling exactly like a first list item would (each tab keeps an empty slot of the same height).
                        val headerY = remember { Animatable(0f) }
                        val headerHidePx = with(LocalDensity.current) { (headerHeight + 14.dp).toPx() }
                        fun offsetOf(t: Tab): Float {
                            val (i, o) = when (t) {
                                Tab.Links -> addList.firstVisibleItemIndex to addList.firstVisibleItemScrollOffset
                                Tab.Library -> gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
                                Tab.Mine -> mineGrid.firstVisibleItemIndex to mineGrid.firstVisibleItemScrollOffset
                                Tab.Together -> togetherList.firstVisibleItemIndex to togetherList.firstVisibleItemScrollOffset
                                Tab.Search -> searchList.firstVisibleItemIndex to searchList.firstVisibleItemScrollOffset
                            }
                            return if (i == 0) o.toFloat().coerceAtMost(headerHidePx) else headerHidePx
                        }
                        LaunchedEffect(tab) {
                            // Another tab scrolled to another place: glide there together with the tab change, then follow.
                            val target = offsetOf(tab)
                            if (kotlin.math.abs(headerY.value - target) > 1f) headerY.animateTo(target, tween(380, easing = Sm.ease))
                            snapshotFlow { offsetOf(tab) }.collect { headerY.snapTo(it) }
                        }
                        Box(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp)
                                .graphicsLayer { translationY = -headerY.value; alpha = 1f - (headerY.value / headerHidePx).coerceIn(0f, 1f) * 0.15f }
                                .onSizeChanged { sz -> val h = with(density) { sz.height.toDp() }; if (h != headerHeight && h > 0.dp) headerHeight = h }
                        ) { BrandHeader(onLogout = logout, onSettings = { settingsOpen = true }, profile = store.profile) }
                    }
                    if (!imeVisible) NavBar(
                        current = tab, onSelect = ::selectTab, focus = navFocus, onFocused = { navFocused = it },
                        locked = if (guest) GUEST_LOCKED else emptySet(), dot = if (Party.inRoom) setOf(Tab.Together) else emptySet()
                    )
                }
            }
            // Once after an update: what changed, in plain words (not on top of the update offer).
            WhatsNew(enabled = store.authenticated == true && update == null)
            update?.let { u -> UpdateDialog(u) { update = null } }
            if (settingsOpen) SettingsSheet { settingsOpen = false }
            followTarget?.let { t ->
                val key = roomKey
                PlaySheet(
                    initial = t,
                    store = store,
                    onDismiss = { followTarget = null },
                    // the room's place at the moment the links are ready, not when the sheet appeared
                    autoStartMs = { if (Party.file?.matches(key) == true) Party.targetMs() else 0L },
                    onPlay = { r -> followTarget = null; handlePlay(r.url, r.title, r.playlist, r.subUrls, r.watchKeys, r.audioUrls, r.fromStart, r.episodeSides, r.customAudio, r.watchKey, r.collection, r.markers, r.startMs, r.roomTitles, r.variants, r.canonicalId) }
                )
            }
            playTarget?.let { t ->
                PlaySheet(
                    initial = t,
                    store = store,
                    onDismiss = { playTarget = null },
                    onPlay = { r -> playTarget = null; handlePlay(r.url, r.title, r.playlist, r.subUrls, r.watchKeys, r.audioUrls, r.fromStart, r.episodeSides, r.customAudio, r.watchKey, r.collection, r.markers, roomTitles = r.roomTitles, variants = r.variants, canonicalId = r.canonicalId) }
                )
            }
        }
        }
    }

    /** Where Back leads: the library, or for a guest of a room (the library is closed for them) the room's page. */
    private fun homeTab() = if (Party.isGuest) Tab.Together else Tab.Library

    private fun selectTab(t: Tab) {
        if (t == tab) return
        if (Party.isGuest && t in GUEST_LOCKED) return
        tab = t
        // Coming back to the library after a while: refresh in the background (the grid stays on screen).
        if ((t == Tab.Library || t == Tab.Mine) && store.authenticated == true && System.currentTimeMillis() - store.lastRefreshAt > 60_000L) {
            uiScope.launch { store.refresh() }
        }
    }

    private fun handlePlay(
        url: String, title: String?, playlist: String? = null,
        subUrls: List<String> = emptyList(), watchKeys: List<String> = emptyList(), audioUrls: List<String> = emptyList(),
        fromStart: Boolean = false, episodeSides: String? = null, customAudio: String? = null, watchKey: String? = null,
        collection: Boolean = false, markers: String? = null, startMs: Long? = null, roomTitles: List<String> = emptyList(),
        variants: List<ru.suslikovanora.media.core.party.PartyVariant> = emptyList(),
        canonicalId: String? = null
    ) {
        val current = watchKey ?: watchKeys.firstOrNull()
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme !in listOf("http", "https")) {
            Toast.makeText(this, tr("main.nepodderzhivaemaya_ssylka"), Toast.LENGTH_SHORT).show()
            return
        }
        // In a room, the host's film becomes the room's film (a guest's other film just plays for them alone)
        current?.let { key -> if (Party.isHost) Party.putFilm(ru.suslikovanora.media.core.party.PartyFileRef(key, roomTitles.getOrNull(watchKeys.indexOf(key)) ?: title ?: tr("main.video"), canonicalId = canonicalId, variants = variants)) }
        // A playlist may only come from our own site: it is fetched without any credentials.
        val playlistUri = playlist?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "https" && ru.suslikovanora.media.core.SiteAddress.isOwn(it.toString(), ru.suslikovanora.media.net.Api.BASE) }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) // a second tap reuses the running player instead of stacking another
            putExtra(PlayerActivity.EXTRA_URL, uri.toString())
            putExtra(PlayerActivity.EXTRA_TITLE, title ?: tr("main.video"))
            canonicalId?.let { putExtra(ru.suslikovanora.media.player.PlayerExtras.CANONICAL_ID, it) }
            playlistUri?.let { putExtra(PlayerActivity.EXTRA_PLAYLIST, it.toString()) }
            if (subUrls.isNotEmpty()) putStringArrayListExtra(PlayerActivity.EXTRA_SUB_URLS, ArrayList(subUrls))
            if (watchKeys.isNotEmpty()) putStringArrayListExtra(PlayerActivity.EXTRA_WATCH_KEYS, ArrayList(watchKeys))
            putExtra(ru.suslikovanora.media.player.PlayerExtras.WATCH_KEY, current)
            if (audioUrls.isNotEmpty()) putStringArrayListExtra(PlayerActivity.EXTRA_AUDIO_URLS, ArrayList(audioUrls))
            putExtra(PlayerActivity.EXTRA_FROM_START, fromStart)
            episodeSides?.let { putExtra(PlayerActivity.EXTRA_EPISODE_SIDES, it) }
            putExtra(ru.suslikovanora.media.player.PlayerExtras.CUSTOM_AUDIO, customAudio)
            putExtra(ru.suslikovanora.media.player.PlayerExtras.COLLECTION, collection)
            putExtra(ru.suslikovanora.media.player.PlayerExtras.MARKERS, markers)
            putStringArrayListExtra(ru.suslikovanora.media.player.PlayerExtras.ROOM_TITLES, ArrayList(roomTitles))
            putExtra(ru.suslikovanora.media.player.PlayerExtras.START_MS, startMs ?: -1L)
            if (variants.isNotEmpty()) {
                val arr = org.json.JSONArray()
                for (v in variants) {
                    arr.put(org.json.JSONObject().apply {
                        put("key", v.key)
                        put("label", v.label)
                        put("quality", v.quality)
                        put("audio", v.audio)
                        put("bitrate", v.bitrate)
                        put("offset_ms", v.offsetMs)
                        put("score", v.score)
                        if (v.hardwareFlags.isNotEmpty()) {
                            val hf = org.json.JSONArray()
                            for (f in v.hardwareFlags) hf.put(f)
                            put("hardware_flags", hf)
                        }
                    })
                }
                putExtra(ru.suslikovanora.media.player.PlayerExtras.VARIANTS, arr.toString())
            }
        })
    }

    override fun onStart() { super.onStart(); Foreground.visible.value = true }
    override fun onStop() {
        Foreground.visible.value = false
        runCatching { CookieManager.getInstance().flush() } // login saved for sure before the app may be killed
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        store.bumpWatch() // the player may have saved new progress
        ru.suslikovanora.media.library.CloudSync.kick() // and it goes to the account; others' progress comes back
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_TAB, tab.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val KEY_TAB = "tab"
        private val GUEST_LOCKED = setOf(Tab.Library, Tab.Mine)
    }
}
