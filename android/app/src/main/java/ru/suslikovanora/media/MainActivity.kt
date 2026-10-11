package ru.suslikovanora.media

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.suslikovanora.media.library.LibraryScreen
import ru.suslikovanora.media.library.LibraryStore
import ru.suslikovanora.media.library.LoginScreen
import ru.suslikovanora.media.library.PlaySheet
import ru.suslikovanora.media.library.PlayTarget
import ru.suslikovanora.media.ui.NavBar
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.SuslikTheme
import ru.suslikovanora.media.ui.Tab

/**
 * Host screen: the library is native (Compose); "Ссылка" and "Поиск" are still the website in a WebView that is
 * kept alive in the background (and paused while the library is shown, so it costs no CPU/battery).
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var store: LibraryStore
    private var tab by mutableStateOf(Tab.Library)
    private var playTarget by mutableStateOf<PlayTarget?>(null)
    private var activityResumed = false
    private var webPaused = false
    private var pageLoaded = false
    private val uiScope = MainScope()
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val siteHost = "short.suslikovanora.ru"
    private val startUrl = "https://short.suslikovanora.ru/media/"

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        val data = result.data
        val uris = if (result.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            else data.data?.let { arrayOf(it) }
        } else null
        callback?.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = android.graphics.Color.rgb(8, 9, 11)
        // targetSdk 35 forces edge-to-edge: draw behind the bars and pad the WebView by the insets.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(bg), navigationBarStyle = SystemBarStyle.dark(bg))
        webView = WebView(this)
        store = LibraryStore(this)
        savedInstanceState?.getString(KEY_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab = it } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    playTarget != null -> playTarget = null
                    tab != Tab.Library && webView.canGoBack() -> webView.goBack()
                    tab != Tab.Library -> selectTab(Tab.Library)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        webView.setBackgroundColor(android.graphics.Color.rgb(8, 9, 11))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.mediaPlaybackRequiresUserGesture = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        setupNativeBridge()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return true
                if (scheme == "https" && uri.host == siteHost) return false
                if (scheme == "intent") {
                    // Android player links are emitted by the website as intent://...#Intent;scheme=https;...
                    // WebView cannot launch these itself; route the embedded media URL to our native VLC player.
                    val raw = uri.toString()
                    val base = raw.substringBefore("#Intent;")
                    val embeddedScheme = Regex("""(?:^|;)scheme=([a-zA-Z][a-zA-Z0-9+.-]*)(?:;|$)""")
                        .find(raw.substringAfter("#Intent;", ""))?.groupValues?.getOrNull(1) ?: "https"
                    val mediaUrl = Uri.parse("$embeddedScheme://${base.removePrefix("intent://")}")
                    if (mediaUrl.scheme in listOf("http", "https")) handlePlay(mediaUrl.toString(), "Видео")
                    else Toast.makeText(this@MainActivity, "Неподдерживаемая ссылка", Toast.LENGTH_SHORT).show()
                    return true
                }
                if (scheme == "http" || scheme == "https") {
                    // Keep external sites out of the app's authenticated WebView.
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { }
                    return true
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Only needed on old WebViews without document-start scripts.
                if (!bridgeIsOriginRestricted) injectPlayerHooks()
                pageLoaded = true
                tab.webTab?.let { showWebTab(it) }
                syncWebState()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try { filePicker.launch(intent); true } catch (_: Exception) {
                    this@MainActivity.fileChooserCallback = null
                    false
                }
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val downloadUri = Uri.parse(url)
                val safeName = URLUtil.guessFileName(url, contentDisposition, mimeType).take(120)
                val request = DownloadManager.Request(downloadUri)
                    .setTitle(safeName)
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent ?: "")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
                // The site is behind a login cookie; DownloadManager does not share the WebView's cookies.
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
                    ?.let { request.addRequestHeader("Cookie", it) }
                val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(this, "Загрузка началась", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Не удалось начать загрузку", Toast.LENGTH_LONG).show()
            }
        }
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) webView.loadUrl(startUrl)
        setContent { SuslikTheme { AppRoot() } }
    }

    // ---- native shell ------------------------------------------------------------------------

    @Composable
    private fun AppRoot() {
        val scope = rememberCoroutineScope()
        var update by remember { mutableStateOf<Updater.Info?>(null) }
        LaunchedEffect(Unit) {
            store.restoreSnapshot()
            val ok = store.checkSession()
            // New version on the own server? Asked at every start, alongside the library load (offline: quietly nothing).
            if (ok == true) launch { update = Updater.check(this@MainActivity) }
            if (ok == true || (ok == null && store.magnets.isNotEmpty())) store.refresh()
        }
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        // Hoisted so the grid keeps its scroll position and filter while a website tab is open.
        val gridState = rememberLazyGridState()
        var query by rememberSaveable { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(Sm.night).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { (webView.parent as? ViewGroup)?.removeView(webView); webView },
                        update = { it.visibility = if (tab != Tab.Library) View.VISIBLE else View.INVISIBLE },
                        modifier = Modifier.fillMaxSize()
                    )
                    // Fully qualified: the plain (non-Column/Row) overload; the outer Column's scope is not usable here.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = tab == Tab.Library,
                        enter = fadeIn(tween(260)) + slideInVertically(tween(320, easing = Sm.ease)) { it / 40 },
                        exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 60 }
                    ) {
                        LibraryScreen(
                            store = store,
                            onPlay = { playTarget = it },
                            query = query, onQuery = { query = it }, gridState = gridState,
                            onLogout = {
                                scope.launch { store.logout(); webView.reload() }
                            }
                        )
                    }
                }
                if (!imeVisible && store.authenticated != false) NavBar(current = tab, onSelect = ::selectTab)
            }
            if (store.authenticated == false) {
                LoginScreen(store) {
                    webView.reload() // the site picks up the new session cookie
                    scope.launch { store.refresh() }
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
        t.webTab?.let { showWebTab(it) }
        syncWebState()
        // Coming back to the library after a while: refresh in the background (the grid stays on screen).
        if (t == Tab.Library && store.authenticated == true && System.currentTimeMillis() - store.lastRefreshAt > 60_000L) {
            uiScope.launch { store.refresh() }
        }
    }

    /** Switch the website to one of its own tabs; its bottom bar is hidden in the app (see APP_MARKER_JS). */
    private fun showWebTab(name: String) {
        if (!pageLoaded) return
        webView.evaluateJavascript("try{window.setTab&&setTab('$name')}catch(e){}", null)
    }

    /** The WebView runs only while one of its tabs is on screen and the app is in the foreground. */
    private fun syncWebState() {
        if (!::webView.isInitialized) return
        val shouldRun = activityResumed && (tab != Tab.Library || !pageLoaded)
        if (shouldRun && webPaused) { webView.onResume(); webView.resumeTimers(); webPaused = false }
        else if (!shouldRun && !webPaused) { webView.onPause(); webView.pauseTimers(); webPaused = true }
    }

    private var bridgeIsOriginRestricted = false

    /**
     * Preferred: origin-restricted web-message listener + document-start script (the bridge object is
     * only exposed to the site's own origin). Fallback: classic addJavascriptInterface + onPageFinished.
     */
    private fun setupNativeBridge() {
        val origin = "https://$siteHost"
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            try {
                WebViewCompat.addWebMessageListener(webView, "SuslikNative", setOf(origin)) { _, message, _, _, _ ->
                    handlePlayMessage(message.data ?: return@addWebMessageListener)
                }
                WebViewCompat.addDocumentStartJavaScript(webView, APP_MARKER_JS, setOf(origin))
                bridgeIsOriginRestricted = true
                return
            } catch (_: Exception) { /* fall through to the legacy bridge */ }
        }
        webView.addJavascriptInterface(NativePlayerBridge(), "SuslikNative")
    }

    private fun injectPlayerHooks() {
        webView.evaluateJavascript(APP_MARKER_JS, null)
    }

    /**
     * The one call the site makes (see nativePlay() in the site's script):
     * {"url": "...", "title": "...", "playlist": "https://<site>/api/playlist/....m3u" | null}
     * The older "url\ntitle" text form is still understood.
     */
    private fun handlePlayMessage(data: String) {
        if (data.startsWith("{")) {
            val json = runCatching { org.json.JSONObject(data) }.getOrNull() ?: return
            val url = json.optString("url")
            if (url.isBlank()) return
            handlePlay(url, json.optString("title").ifBlank { null }, json.optString("playlist").ifBlank { null })
        } else {
            val sep = data.indexOf('\n')
            if (sep > 0) handlePlay(data.substring(0, sep), data.substring(sep + 1))
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

    inner class NativePlayerBridge {
        @JavascriptInterface
        fun play(url: String, title: String?) {
            runOnUiThread {
                // Legacy path is not origin-restricted by the platform: check the current page here.
                if (Uri.parse(webView.url ?: "").host != siteHost) return@runOnUiThread
                handlePlay(url, title)
            }
        }

        @JavascriptInterface
        fun playJson(json: String) {
            runOnUiThread {
                if (Uri.parse(webView.url ?: "").host != siteHost) return@runOnUiThread
                handlePlayMessage(json)
            }
        }
    }

    // Stop JS timers/animations of the site while the app is in the background or the player is on top.
    override fun onPause() {
        activityResumed = false
        syncWebState()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        store.bumpWatch() // the player may have saved new progress
        activityResumed = true
        syncWebState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        outState.putString(KEY_TAB, tab.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        uiScope.cancel()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("SuslikNative")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        // Announces the app and its contract version to the site (version 2 = JSON nativePlay with playlist,
        // 3 = native library and bottom bar: the site hides its own bar via the "suslik-native" class).
        private const val APP_MARKER_JS =
            "window.__suslikApp = 3; try { document.documentElement.classList.add('suslik-native'); } catch (e) {}"
        private const val KEY_TAB = "tab"
    }
}
