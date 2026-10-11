package ru.suslikovanora.media

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import ru.suslikovanora.media.ui.NavBar
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.SuslikTheme
import ru.suslikovanora.media.ui.Tab

/**
 * Host screen, fully native (Compose): «Ссылка» (magnet / file link / .torrent), «Библиотека», «Поиск».
 * No WebView any more: nothing of the website runs in the background, the session cookie is shared through
 * CookieManager (same as before, so an existing login keeps working).
 */
class MainActivity : ComponentActivity() {
    private lateinit var store: LibraryStore
    private lateinit var add: AddModel
    private lateinit var search: SearchModel
    private var tab by mutableStateOf(Tab.Library)
    private var playTarget by mutableStateOf<PlayTarget?>(null)
    private val uiScope = MainScope() // outlives tab switches: searches and download tracking keep going
    private val siteHost = "short.suslikovanora.ru"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = android.graphics.Color.rgb(8, 9, 11)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(bg), navigationBarStyle = SystemBarStyle.dark(bg))
        CookieManager.getInstance().setAcceptCookie(true)
        store = LibraryStore(this)
        add = AddModel(this, uiScope, store)
        search = SearchModel(uiScope, store)
        savedInstanceState?.getString(KEY_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    playTarget != null -> playTarget = null
                    tab != Tab.Library -> selectTab(Tab.Library)
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
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim() ?: return
        val link = Regex("""magnet:\?\S+""", RegexOption.IGNORE_CASE).find(text)?.value
            ?: Regex("""https://\S+""").find(text)?.value ?: return
        add.mode = 0; add.input = link; add.status = "Нажмите «Добавить», чтобы отправить в AllDebrid"; add.statusOk = true
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
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        // Hoisted so every tab keeps its scroll position (and the library its filter) across tab switches.
        val gridState = rememberLazyGridState()
        val addList = rememberLazyListState()
        val searchList = rememberLazyListState()
        var query by rememberSaveable { mutableStateOf("") }
        val logout: () -> Unit = { scope.launch { store.logout() } }
        Box(Modifier.fillMaxSize().background(Sm.night).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                // Tabs glide sideways in the direction of the chosen tab (no flash: every page has the same dark background).
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn(tween(260, delayMillis = 50)) + slideInHorizontally(tween(380, easing = Sm.ease)) { dir * it / 12 }) togetherWith
                            (fadeOut(tween(150)) + slideOutHorizontally(tween(380, easing = Sm.ease)) { -dir * it / 12 })
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Sm.night),
                    label = "tabs"
                ) { t ->
                    when (t) {
                        Tab.Links -> AddScreen(add, addList, onPlay = { url, title -> handlePlay(url, title) }, onLogout = logout)
                        Tab.Library -> LibraryScreen(
                            store = store, onPlay = { playTarget = it },
                            query = query, onQuery = { query = it }, gridState = gridState, onLogout = logout
                        )
                        Tab.Search -> SearchScreen(search, searchList, onLogout = logout)
                    }
                }
                if (!imeVisible && store.authenticated != false) NavBar(current = tab, onSelect = ::selectTab)
            }
            if (store.authenticated == false) {
                LoginScreen(store) {
                    scope.launch { store.refresh() }
                    add.resume()
                }
            }
            update?.let { u -> UpdateDialog(u) { update = null } }
            playTarget?.let { t ->
                PlaySheet(
                    target = t,
                    onDismiss = { playTarget = null },
                    onPlay = { r -> playTarget = null; handlePlay(r.url, r.title, r.playlist, r.subUrls, r.watchKeys, r.audioUrls) }
                )
            }
        }
    }

    private fun selectTab(t: Tab) {
        if (t == tab) return
        tab = t
        // Coming back to the library after a while: refresh in the background (the grid stays on screen).
        if (t == Tab.Library && store.authenticated == true && System.currentTimeMillis() - store.lastRefreshAt > 60_000L) {
            uiScope.launch { store.refresh() }
        }
    }

    private fun handlePlay(
        url: String, title: String?, playlist: String? = null,
        subUrls: List<String> = emptyList(), watchKeys: List<String> = emptyList(), audioUrls: List<String> = emptyList()
    ) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme !in listOf("http", "https")) {
            Toast.makeText(this, "Неподдерживаемая ссылка", Toast.LENGTH_SHORT).show()
            return
        }
        // A playlist may only come from our own site: it is fetched without any credentials.
        val playlistUri = playlist?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme == "https" && it.host == siteHost }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) // a second tap reuses the running player instead of stacking another
            putExtra(PlayerActivity.EXTRA_URL, uri.toString())
            putExtra(PlayerActivity.EXTRA_TITLE, title ?: "Видео")
            playlistUri?.let { putExtra(PlayerActivity.EXTRA_PLAYLIST, it.toString()) }
            if (subUrls.isNotEmpty()) putStringArrayListExtra(PlayerActivity.EXTRA_SUB_URLS, ArrayList(subUrls))
            if (watchKeys.isNotEmpty()) putStringArrayListExtra(PlayerActivity.EXTRA_WATCH_KEYS, ArrayList(watchKeys))
            if (audioUrls.isNotEmpty()) putStringArrayListExtra(PlayerActivity.EXTRA_AUDIO_URLS, ArrayList(audioUrls))
        })
    }

    override fun onResume() {
        super.onResume()
        store.bumpWatch() // the player may have saved new progress
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
    }
}
