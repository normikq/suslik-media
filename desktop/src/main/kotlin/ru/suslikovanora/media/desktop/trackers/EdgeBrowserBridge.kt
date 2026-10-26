package ru.suslikovanora.media.desktop.trackers

import com.sun.jna.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.suslikovanora.media.desktop.AppDir
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

/** Трекер показал форму входа: cookie устарела или не подошла. */
class TrackerAuthException : IllegalStateException("куки не приняты — обновите bb_session в «Трекеры»")

/** Cloudflare так и не пропустил фоновый браузер. */
class TrackerChallengeException : IllegalStateException("Cloudflare не пропустил проверку — повторите поиск позже")

/**
 * Background Microsoft Edge runner via Chrome DevTools Protocol (CDP).
 *
 * On Windows, runs inside an isolated hidden desktop ("SuslikHiddenDesktop"):
 * - Zero taskbar icons, zero window flashes, zero desktop disturbance.
 * - Full native browser rendering context so Cloudflare Turnstile passes smoothly.
 * - Automatically terminates the browser process and all child renderers immediately
 *   after search or download completes.
 */
object EdgeBrowserBridge {

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private interface WinUser32 : Library {
        companion object {
            val INSTANCE: WinUser32? = if (!isWindows) null else runCatching {
                Native.load("user32", WinUser32::class.java)
            }.getOrNull()
        }
        fun CreateDesktopW(
            lpszDesktop: WString,
            lpszDevice: WString?,
            pDevmode: Pointer?,
            dwFlags: Int,
            dwDesiredAccess: Int,
            lpsa: Pointer?
        ): Pointer?

        fun CloseDesktop(hDesktop: Pointer): Boolean
    }

    private interface WinKernel32 : Library {
        companion object {
            val INSTANCE: WinKernel32? = if (!isWindows) null else runCatching {
                Native.load("kernel32", WinKernel32::class.java)
            }.getOrNull()
        }

        @Structure.FieldOrder(
            "cb", "lpReserved", "lpDesktop", "lpTitle",
            "dwX", "dwY", "dwXSize", "dwYSize",
            "dwXCountChars", "dwYCountChars", "dwFillAttribute",
            "dwFlags", "wShowWindow", "cbReserved2", "lpReserved2",
            "hStdInput", "hStdOutput", "hStdError"
        )
        class STARTUPINFO : Structure() {
            @JvmField var cb: Int = 0
            @JvmField var lpReserved: WString? = null
            @JvmField var lpDesktop: WString? = null
            @JvmField var lpTitle: WString? = null
            @JvmField var dwX: Int = 0
            @JvmField var dwY: Int = 0
            @JvmField var dwXSize: Int = 0
            @JvmField var dwYSize: Int = 0
            @JvmField var dwXCountChars: Int = 0
            @JvmField var dwYCountChars: Int = 0
            @JvmField var dwFillAttribute: Int = 0
            @JvmField var dwFlags: Int = 0
            @JvmField var wShowWindow: Short = 0
            @JvmField var cbReserved2: Short = 0
            @JvmField var lpReserved2: Pointer? = null
            @JvmField var hStdInput: Pointer? = null
            @JvmField var hStdOutput: Pointer? = null
            @JvmField var hStdError: Pointer? = null

            init {
                cb = size()
            }
        }

        @Structure.FieldOrder("hProcess", "hThread", "dwProcessId", "dwThreadId")
        class PROCESS_INFORMATION : Structure() {
            @JvmField var hProcess: Pointer? = null
            @JvmField var hThread: Pointer? = null
            @JvmField var dwProcessId: Int = 0
            @JvmField var dwThreadId: Int = 0
        }

        fun CreateProcessW(
            lpApplicationName: WString?,
            lpCommandLine: WString?,
            lpProcessAttributes: Pointer?,
            lpThreadAttributes: Pointer?,
            bInheritHandles: Boolean,
            dwCreationFlags: Int,
            lpEnvironment: Pointer?,
            lpCurrentDirectory: WString?,
            lpStartupInfo: STARTUPINFO,
            lpProcessInformation: PROCESS_INFORMATION
        ): Boolean

        fun TerminateProcess(hProcess: Pointer, uExitCode: Int): Boolean
        fun CloseHandle(hObject: Pointer): Boolean
    }

    fun getExecutable(type: BrowserType = preferredBrowserType): File? =
        BrowserExecutableFinder.find(type)

    fun isAvailable(type: BrowserType = preferredBrowserType): Boolean =
        getExecutable(type) != null

    val isAnyAvailable: Boolean
        get() = BrowserType.entries.filter { it.isChromium }.any { BrowserExecutableFinder.find(it) != null }

    val activeBrowserName: String
        get() = preferredBrowserType.displayName

    var preferredBrowserType: BrowserType
        get() {
            val saved = LocalTrackersManager.store.get("tracker.browser_type")
            if (!saved.isNullOrBlank()) {
                val found = runCatching { BrowserType.valueOf(saved) }.getOrNull()
                if (found != null && BrowserExecutableFinder.find(found) != null) return found
            }
            return when {
                BrowserExecutableFinder.find(BrowserType.EDGE) != null -> BrowserType.EDGE
                BrowserExecutableFinder.find(BrowserType.CHROME) != null -> BrowserType.CHROME
                BrowserExecutableFinder.find(BrowserType.YANDEX) != null -> BrowserType.YANDEX
                BrowserExecutableFinder.find(BrowserType.BRAVE) != null -> BrowserType.BRAVE
                BrowserExecutableFinder.find(BrowserType.OPERA) != null -> BrowserType.OPERA
                BrowserExecutableFinder.find(BrowserType.VIVALDI) != null -> BrowserType.VIVALDI
                else -> BrowserType.EDGE
            }
        }
        set(value) {
            LocalTrackersManager.store.put("tracker.browser_type", value.name)
        }

    val profileDir: File get() = profileDirFor(preferredBrowserType)

    fun profileDirFor(type: BrowserType): File {
        val dirName = when (type) {
            BrowserType.EDGE -> "edge_tracker_profile"
            BrowserType.CHROME -> "chrome_tracker_profile"
            BrowserType.YANDEX -> "yandex_tracker_profile"
            BrowserType.BRAVE -> "brave_tracker_profile"
            BrowserType.OPERA -> "opera_tracker_profile"
            BrowserType.OPERA_GX -> "opera_gx_tracker_profile"
            BrowserType.VIVALDI -> "vivaldi_tracker_profile"
            else -> "chromium_tracker_profile"
        }
        return AppDir.file(dirName).apply { mkdirs() }
    }

    private var currentPid: Int = 0
    private var currentProcessHandle: Pointer? = null
    private var currentThreadHandle: Pointer? = null
    private var currentDesktopHandle: Pointer? = null
    private var fallbackProcess: Process? = null
    private var cdpPort: Int = 0

    private val mutex = Mutex()
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    // Браузер живёт между запросами: холодный старт Edge — это 2–6 с на каждый поиск, а поиск с годом и добавление
    // раздачи запускали его ещё раз. Выключение через Browser.close: при убийстве процесса Chromium не успевает
    // дописать куки на диск, и cf_clearance терялся — Cloudflare проверял нас заново при каждом поиске.
    private const val IDLE_SHUTDOWN_MS = 90_000L
    private val idleTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "edge-bridge-idle").apply { isDaemon = true }
    }
    private var idleTask: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            stop()
        })
    }

    /** Браузер больше не нужен: закроется сам, если за [IDLE_SHUTDOWN_MS] не будет нового поиска. */
    @Synchronized
    private fun scheduleIdleStop() {
        idleTask?.cancel(false)
        idleTask = runCatching {
            idleTimer.schedule({ stop() }, IDLE_SHUTDOWN_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /** Запущенный браузер отвечает на CDP: можно переиспользовать. */
    private fun isAlive(): Boolean {
        val port = cdpPort
        if (port == 0 || currentPid <= 0) return false
        if (ProcessHandle.of(currentPid.toLong()).map { !it.isAlive }.orElse(false)) return false
        return runCatching {
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/version"))
                .timeout(Duration.ofSeconds(2)).GET().build()
            client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        }.getOrDefault(false)
    }

    /**
     * Просим Edge закрыться самостоятельно, чтобы он дописал куки (cf_clearance) в профиль.
     * Не ответил за 3 с — дальше его снимает [stop].
     */
    private fun closeGracefully(port: Int) {
        runCatching {
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/version"))
                .timeout(Duration.ofSeconds(2)).GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return
            val wsUrl = JSONObject(res.body()).optString("webSocketDebuggerUrl").ifBlank { return }
            val ws = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
                .buildAsync(URI.create(wsUrl), object : WebSocket.Listener {}).get(3, java.util.concurrent.TimeUnit.SECONDS)
            ws.sendText(JSONObject().put("id", 1).put("method", "Browser.close").toString(), true)
            val pid = currentPid
            if (pid > 0) {
                ProcessHandle.of(pid.toLong()).ifPresent { h ->
                    h.onExit().get(3, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        idleTask?.cancel(false)
        idleTask = null
        try {
            val pid = currentPid
            if (cdpPort != 0) closeGracefully(cdpPort)
            if (pid > 0) {
                runCatching {
                    ProcessHandle.of(pid.toLong()).ifPresent { h ->
                        h.descendants().forEach { it.destroyForcibly() }
                        h.destroyForcibly()
                    }
                }
                if (isWindows) {
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf("taskkill", "/F", "/T", "/PID", pid.toString())).waitFor()
                    }
                }
            }
            currentProcessHandle?.let {
                WinKernel32.INSTANCE?.TerminateProcess(it, 0)
                WinKernel32.INSTANCE?.CloseHandle(it)
            }
            currentThreadHandle?.let {
                WinKernel32.INSTANCE?.CloseHandle(it)
            }
            currentDesktopHandle?.let {
                WinUser32.INSTANCE?.CloseDesktop(it)
            }
            fallbackProcess?.destroyForcibly()
        } catch (_: Exception) {
        } finally {
            currentPid = 0
            currentProcessHandle = null
            currentThreadHandle = null
            currentDesktopHandle = null
            fallbackProcess = null
            cdpPort = 0
        }
    }

    private suspend fun ensureRunning(): Int = withContext(Dispatchers.IO) {
        val type = preferredBrowserType
        val exeFile = getExecutable(type)
            ?: getExecutable(BrowserType.EDGE)
            ?: getExecutable(BrowserType.CHROME)
            ?: getExecutable(BrowserType.YANDEX)
            ?: throw IllegalStateException("Подходящий браузер (Edge, Chrome, Яндекс) не найден на компьютере")
        val exe = exeFile.absolutePath
        val profDir = profileDirFor(type)

        synchronized(this@EdgeBrowserBridge) { idleTask?.cancel(false); idleTask = null }
        if (isAlive()) return@withContext cdpPort // уже запущен: куки и проверка Cloudflare при нас
        stop()

        val port = ServerSocket(0).use { it.localPort }
        var launched = false

        // Скрытый рабочий стол: только если его удалось создать, иначе окно браузера выскочит поверх всего.
        val hDesk = if (isWindows && WinKernel32.INSTANCE != null && WinUser32.INSTANCE != null) {
            WinUser32.INSTANCE?.CreateDesktopW(WString("SuslikHiddenDesktop"), null, null, 0, 0x10000000 /* GENERIC_ALL */, null)
        } else null

        if (hDesk != null) {
            val deskName = "SuslikHiddenDesktop"
            currentDesktopHandle = hDesk

            val si = WinKernel32.STARTUPINFO().apply {
                lpDesktop = WString(deskName)
            }
            val pi = WinKernel32.PROCESS_INFORMATION()

            val cmdLine = "\"$exe\" --remote-debugging-port=$port " +
                    "--disable-blink-features=AutomationControlled " +
                    "--user-data-dir=\"${profDir.absolutePath}\" " +
                    "--no-first-run --no-default-browser-check about:blank"

            val ok = WinKernel32.INSTANCE?.CreateProcessW(
                null, WString(cmdLine), null, null, false, 0, null, null, si, pi
            ) ?: false

            if (ok && pi.dwProcessId > 0) {
                currentPid = pi.dwProcessId
                currentProcessHandle = pi.hProcess
                currentThreadHandle = pi.hThread
                cdpPort = port
                launched = true
            }
        }

        if (!launched) {
            val pb = ProcessBuilder(
                exe,
                "--remote-debugging-port=$port",
                "--user-data-dir=${profDir.absolutePath}",
                "--headless=new",
                "--disable-blink-features=AutomationControlled",
                "--no-first-run",
                "--no-default-browser-check",
                "about:blank"
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            fallbackProcess = proc
            currentPid = proc.pid().toInt()
            cdpPort = port
        }

        // Wait up to 6 seconds for CDP ready
        var up = false
        for (i in 1..30) {
            delay(200)
            try {
                val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/version"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build()
                if (client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    up = true
                    break
                }
            } catch (_: Exception) {}
        }

        if (!up) {
            stop()
            throw IllegalStateException("Не удалось запустить фоновый процесс браузера")
        }

        port
    }

    /**
     * Executes tracker search via Edge in hidden desktop.
     * Браузер остаётся запущенным ещё 90 с: следующий поиск или скачивание пройдут без холодного старта.
     */
    suspend fun searchHtml(baseUrl: String, query: String, cookies: Map<String, String>): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val port = ensureRunning()
                val session = openTabSession(port) ?: return@withContext null

                try {
                    session.sendCmd("Network.enable")
                    session.sendCmd("Page.enable")

                    // Inject tracker cookies
                    val domain = URI.create(baseUrl).host?.let { if (it.startsWith("www.")) it.substring(4) else it } ?: "rutracker.org"
                    val rootDomain = if (domain.count { it == '.' } >= 2) domain.substringAfter('.') else domain

                    for ((k, v) in cookies) {
                        session.sendCmd("Network.setCookie", JSONObject().apply {
                            put("name", k)
                            put("value", v)
                            put("domain", ".$rootDomain")
                            put("path", "/")
                        })
                    }

                    val encoded = URLEncoder.encode(query, "windows-1251")
                    val targetUrl = "${baseUrl.removeSuffix("/")}/forum/tracker.php?nm=$encoded"
                    session.sendCmd("Page.navigate", JSONObject().put("url", targetUrl))

                    // Poll for #tor-tbl or "Ничего не найдено" up to 15 seconds.
                    // Форма входа (куки не приняты) и проверка Cloudflare — отдельные ответы: прямой HTTP-запрос
                    // после них тоже не пройдёт, поэтому сразу говорим пользователю, в чём дело.
                    var resultHtml: String? = null
                    var lastState = ""
                    for (attempt in 1..30) {
                        delay(500)
                        val eval = session.sendCmd(
                            "Runtime.evaluate",
                            JSONObject().put(
                                "expression",
                                "(() => { const el = document.getElementById('tor-tbl'); if (el) return el.outerHTML; " +
                                    "if (document.querySelector('input[name=\"login_username\"]')) return '__LOGIN__'; " +
                                    "if (document.body && document.body.innerText.includes('Не найдено')) return '__EMPTY__'; " +
                                    "if (/just a moment|один момент/i.test(document.title) || document.querySelector('#challenge-form, .cf-turnstile, iframe[src*=\"challenges.cloudflare.com\"]')) return '__CF__'; " +
                                    "return ''; })()"
                            ).put("returnByValue", true)
                        )
                        val resVal = eval.optJSONObject("result")?.optJSONObject("result")?.optString("value") ?: ""
                        if (resVal == "__LOGIN__") throw TrackerAuthException()
                        if (resVal == "__EMPTY__") {
                            resultHtml = ""
                            break
                        }
                        if (resVal.contains("tor-tbl")) {
                            resultHtml = resVal
                            break
                        }
                        if (resVal.isNotEmpty()) lastState = resVal
                    }
                    if (resultHtml == null && lastState == "__CF__") throw TrackerChallengeException()

                    resultHtml
                } finally {
                    session.close()
                }
            } finally {
                scheduleIdleStop()
            }
        }
    }

    /**
     * Downloads .torrent file bytes inside the browser session via fetch().
     * Браузер остаётся запущенным ещё 90 с (см. [searchHtml]).
     */
    suspend fun downloadTorrentBytes(baseUrl: String, topicId: String, cookies: Map<String, String>): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val port = ensureRunning()
                val session = openTabSession(port) ?: return@withContext null

                try {
                    session.sendCmd("Network.enable")
                    session.sendCmd("Page.enable")

                    val domain = URI.create(baseUrl).host?.let { if (it.startsWith("www.")) it.substring(4) else it } ?: "rutracker.org"
                    val rootDomain = if (domain.count { it == '.' } >= 2) domain.substringAfter('.') else domain

                    for ((k, v) in cookies) {
                        session.sendCmd("Network.setCookie", JSONObject().apply {
                            put("name", k)
                            put("value", v)
                            put("domain", ".$rootDomain")
                            put("path", "/")
                        })
                    }

                    val targetUrl = "${baseUrl.removeSuffix("/")}/forum/viewtopic.php?t=$topicId"
                    session.sendCmd("Page.navigate", JSONObject().put("url", targetUrl))

                    // Wait for page ready (up to 8s)
                    for (attempt in 1..16) {
                        delay(400)
                        val eval = session.sendCmd(
                            "Runtime.evaluate",
                            JSONObject().put("expression", "document.readyState === 'complete'").put("returnByValue", true)
                        )
                        if (eval.optJSONObject("result")?.optJSONObject("result")?.optBoolean("value") == true) break
                    }

                    val fetchJs = """
                        new Promise((resolve) => {
                            fetch('/forum/dl.php?t=$topicId', { credentials: 'include' })
                                .then(r => r.blob())
                                .then(b => {
                                    const reader = new FileReader();
                                    reader.onload = () => resolve(reader.result.split(',')[1]);
                                    reader.onerror = () => resolve('');
                                    reader.readAsDataURL(b);
                                })
                                .catch(() => resolve(''));
                        })
                    """.trimIndent()

                    val evalRes = session.sendCmd(
                        "Runtime.evaluate",
                        JSONObject().put("expression", fetchJs).put("awaitPromise", true).put("returnByValue", true)
                    )
                    val b64 = evalRes.optJSONObject("result")?.optJSONObject("result")?.optString("value") ?: ""
                    if (b64.isNotBlank()) {
                        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
                        if (bytes != null && bytes.isNotEmpty() && bytes[0] == 0x64.toByte()) {
                            bytes
                        } else null
                    } else null
                } finally {
                    session.close()
                }
            } finally {
                scheduleIdleStop()
            }
        }
    }

    /**
     * Extracts RuTracker cookies from [cookiesArray] (returned by Storage.getCookies).
     */
    fun formatTrackerCookies(cookiesArray: JSONArray, baseUrl: String): String? {
        val domain = URI.create(baseUrl).host?.let { if (it.startsWith("www.")) it.substring(4) else it } ?: "rutracker.org"
        val domainPattern = if (domain.contains("rutracker")) "rutracker" else domain

        val map = linkedMapOf<String, String>()
        for (i in 0 until cookiesArray.length()) {
            val c = cookiesArray.optJSONObject(i) ?: continue
            val cDomain = c.optString("domain", "")
            val name = c.optString("name", "")
            val value = c.optString("value", "")

            if (cDomain.contains(domainPattern, ignoreCase = true) && name.isNotBlank() && value.isNotBlank()) {
                map[name] = value
            }
        }

        if (!map.containsKey("bb_session")) return null
        if (!map.containsKey("bb_ssl")) map["bb_ssl"] = "1"

        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Checks if a browser tracker profile already has a valid RuTracker session cookie.
     */
    suspend fun getExistingCookies(baseUrl: String = "https://rutracker.org"): String? =
        getExistingCookies(preferredBrowserType, baseUrl)

    suspend fun getExistingCookies(
        type: BrowserType,
        baseUrl: String = "https://rutracker.org"
    ): String? = withContext(Dispatchers.IO) {
        val exe = getExecutable(type)?.absolutePath ?: return@withContext null
        val profDir = profileDirFor(type)
        val cookieDb = File(profDir, "Default/Network/Cookies")
        if (!cookieDb.exists() || cookieDb.length() == 0L) return@withContext null

        mutex.withLock {
            if (isAlive() && cdpPort != 0 && preferredBrowserType == type) {
                val session = openBrowserSession(cdpPort)
                if (session != null) {
                    try {
                        val resp = session.sendCmd("Storage.getCookies")
                        val arr = resp.optJSONObject("result")?.optJSONArray("cookies")
                        if (arr != null) {
                            val res = formatTrackerCookies(arr, baseUrl)
                            if (res != null) return@withContext res
                        }
                    } finally {
                        session.close()
                    }
                }
            }

            var tempProc: Process? = null
            try {
                val port = ServerSocket(0).use { it.localPort }
                val pb = ProcessBuilder(
                    exe,
                    "--remote-debugging-port=$port",
                    "--user-data-dir=${profDir.absolutePath}",
                    "--headless=new",
                    "--disable-blink-features=AutomationControlled",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "about:blank"
                )
                pb.redirectErrorStream(true)
                tempProc = pb.start()

                for (i in 1..25) {
                    delay(150)
                    val session = openBrowserSession(port)
                    if (session != null) {
                        try {
                            val resp = session.sendCmd("Storage.getCookies")
                            val arr = resp.optJSONObject("result")?.optJSONArray("cookies")
                            if (arr != null) {
                                val res = formatTrackerCookies(arr, baseUrl)
                                if (res != null) return@withContext res
                            }
                        } finally {
                            session.close()
                        }
                        break
                    }
                }
                null
            } catch (_: Exception) {
                null
            } finally {
                tempProc?.let { runCatching { it.destroyForcibly() } }
            }
        }
    }

    /**
     * Opens an interactive visible browser window at the RuTracker login page,
     * monitors for login completion via CDP, automatically extracts session cookies
     * once authenticated, closes the window, and returns the cookie header.
     */
    suspend fun loginAndCaptureCookies(
        baseUrl: String,
        onStatus: (String) -> Unit = {}
    ): Result<String> = loginAndCaptureCookies(preferredBrowserType, baseUrl, onStatus)

    suspend fun loginAndCaptureCookies(
        type: BrowserType,
        baseUrl: String = "https://rutracker.org",
        onStatus: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val exeFile = getExecutable(type)
            ?: return@withContext Result.failure(IllegalStateException("Браузер ${type.displayName} не найден на этом компьютере"))
        val exe = exeFile.absolutePath
        val profDir = profileDirFor(type)

        // First check if already logged in in this profile
        val existing = getExistingCookies(type, baseUrl)
        if (existing != null && existing.contains("bb_session")) {
            preferredBrowserType = type
            return@withContext Result.success(existing)
        }

        mutex.withLock {
            stop() // Stop background instance to unlock profile

            var proc: Process? = null
            try {
                val port = ServerSocket(0).use { it.localPort }
                val loginUrl = "${baseUrl.removeSuffix("/")}/forum/login.php"

                onStatus("Открываем окно ${type.displayName}...")
                val pb = ProcessBuilder(
                    exe,
                    "--remote-debugging-port=$port",
                    "--user-data-dir=${profDir.absolutePath}",
                    "--disable-blink-features=AutomationControlled",
                    "--no-first-run",
                    "--no-default-browser-check",
                    loginUrl
                )
                pb.redirectErrorStream(true)
                val p = pb.start()
                proc = p

                // Connect to browser-level CDP
                var session: CdpSession? = null
                for (i in 1..30) {
                    delay(200)
                    session = openBrowserSession(port)
                    if (session != null) break
                }

                if (session == null) {
                    return@withContext Result.failure(IllegalStateException("Не удалось подключиться к окну браузера ${type.displayName}"))
                }

                try {
                    onStatus("Выполните вход на RuTracker в открывшемся окне ${type.displayName} — куки сохранятся автоматически")

                    // Poll every 500ms for up to 3 minutes
                    for (i in 1..360) {
                        delay(500)
                        if (!p.isAlive) {
                            return@withContext Result.failure(IllegalStateException("Окно авторизации ${type.displayName} было закрыто до завершения входа"))
                        }

                        val resp = session.sendCmd("Storage.getCookies")
                        val arr = resp.optJSONObject("result")?.optJSONArray("cookies")
                        if (arr != null) {
                            val header = formatTrackerCookies(arr, baseUrl)
                            if (header != null && header.contains("bb_session")) {
                                onStatus("✓ Вход выполнен! Сохраняем куки...")
                                preferredBrowserType = type
                                runCatching {
                                    session.sendCmd("Browser.close")
                                }
                                delay(600)
                                return@withContext Result.success(header)
                            }
                        }
                    }

                    Result.failure(IllegalStateException("Время ожидания входа истекло (3 минуты)"))
                } finally {
                    session.close()
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                proc?.let {
                    runCatching {
                        if (it.isAlive) it.destroyForcibly()
                    }
                }
            }
        }
    }

    private suspend fun openBrowserSession(port: Int): CdpSession? {
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/version"))
            .timeout(Duration.ofSeconds(2)).GET().build()
        val res = runCatching { client.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (res.statusCode() != 200) return null
        val wsUrl = JSONObject(res.body()).optString("webSocketDebuggerUrl").ifBlank { return null }

        val msgId = AtomicInteger(1)
        val responses = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        val messageBuilder = StringBuilder()

        val wsCompletable = CompletableDeferred<WebSocket>()
        val wsListener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
                wsCompletable.complete(webSocket)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                messageBuilder.append(data)
                if (last) {
                    val full = messageBuilder.toString()
                    messageBuilder.setLength(0)
                    try {
                        val json = JSONObject(full)
                        val id = json.optInt("id", -1)
                        if (id != -1 && responses.containsKey(id)) {
                            responses[id]?.complete(json)
                        }
                    } catch (_: Exception) {}
                }
                webSocket.request(1)
                return null
            }
        }

        val ws = runCatching {
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create(wsUrl), wsListener).get()
        }.getOrNull() ?: return null

        return CdpSession(ws, msgId, responses)
    }

    private class CdpSession(
        val ws: WebSocket,
        val msgId: AtomicInteger,
        val responses: java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>
    ) {
        suspend fun sendCmd(method: String, params: JSONObject = JSONObject()): JSONObject {
            val id = msgId.getAndIncrement()
            val def = CompletableDeferred<JSONObject>()
            responses[id] = def
            try {
                val cmd = JSONObject().put("id", id).put("method", method).put("params", params)
                ws.sendText(cmd.toString(), true)
                return withTimeoutOrNull(15000) { def.await() } ?: JSONObject()
            } finally {
                responses.remove(id)
            }
        }

        fun close() {
            runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done") }
        }
    }

    private suspend fun openTabSession(port: Int): CdpTabSession? {
        val putReq = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/new?about:blank"))
            .method("PUT", HttpRequest.BodyPublishers.noBody())
            .build()
        val newTabRes = runCatching { client.send(putReq, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (newTabRes.statusCode() != 200) return null

        val tabJson = JSONObject(newTabRes.body())
        val tabId = tabJson.getString("id")
        val wsUrl = tabJson.getString("webSocketDebuggerUrl")

        val msgId = AtomicInteger(1)
        val responses = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        val messageBuilder = StringBuilder()

        val wsCompletable = CompletableDeferred<WebSocket>()
        val wsListener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
                wsCompletable.complete(webSocket)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                messageBuilder.append(data)
                if (last) {
                    val full = messageBuilder.toString()
                    messageBuilder.setLength(0)
                    try {
                        val json = JSONObject(full)
                        val id = json.optInt("id", -1)
                        if (id != -1 && responses.containsKey(id)) {
                            responses[id]?.complete(json)
                        }
                    } catch (_: Exception) {}
                }
                webSocket.request(1)
                return null
            }
        }

        val ws = runCatching {
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create(wsUrl), wsListener).get()
        }.getOrNull() ?: return null

        return CdpTabSession(client, port, tabId, ws, msgId, responses)
    }

    private class CdpTabSession(
        val client: HttpClient,
        val port: Int,
        val tabId: String,
        val ws: WebSocket,
        val msgId: AtomicInteger,
        val responses: java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>
    ) {
        suspend fun sendCmd(method: String, params: JSONObject = JSONObject()): JSONObject {
            val id = msgId.getAndIncrement()
            val def = CompletableDeferred<JSONObject>()
            responses[id] = def
            try {
                val cmd = JSONObject().put("id", id).put("method", method).put("params", params)
                ws.sendText(cmd.toString(), true)
                return withTimeoutOrNull(15000) { def.await() } ?: JSONObject()
            } finally {
                responses.remove(id) // иначе ответы копились до конца сессии
            }
        }

        fun close() {
            runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done") }
            runCatching {
                val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/close/$tabId")).GET().build()
                client.send(req, HttpResponse.BodyHandlers.discarding())
            }
        }
    }
}

typealias ChromiumBrowserBridge = EdgeBrowserBridge
