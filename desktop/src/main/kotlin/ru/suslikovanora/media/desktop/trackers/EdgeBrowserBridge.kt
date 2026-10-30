package ru.suslikovanora.media.desktop.trackers

import com.sun.jna.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.suslikovanora.media.desktop.tr
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
        fun CreateJobObjectW(lpJobAttributes: Pointer?, lpName: WString?): Pointer?
        fun SetInformationJobObject(
            hJob: Pointer,
            JobObjectInformationClass: Int,
            lpJobObjectInformation: Pointer,
            cbJobObjectInformationLength: Int
        ): Boolean
        fun AssignProcessToJobObject(hJob: Pointer, hProcess: Pointer): Boolean
        fun TerminateJobObject(hJob: Pointer, uExitCode: Int): Boolean
        fun OpenProcess(dwDesiredAccess: Int, bInheritHandle: Boolean, dwProcessId: Int): Pointer?
        fun ResumeThread(hThread: Pointer): Int
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
    private var currentJobHandle: Pointer? = null
    private var fallbackProcess: Process? = null
    private var cdpPort: Int = 0

    private val mutex = Mutex()
    private val activeRequests = AtomicInteger(0)
    private val bridgeScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    // Браузер завершается через 15 с неактивности, освобождая память и ресурсы.
    private const val IDLE_SHUTDOWN_MS = 15_000L
    private val idleTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "edge-bridge-idle").apply { isDaemon = true }
    }
    private var idleTask: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        cleanOrphanTrackerProcesses()
        Runtime.getRuntime().addShutdownHook(Thread {
            stop(force = true)
            cleanOrphanTrackerProcesses()
        })
    }

    private fun createJobForProcess(hProcess: Pointer?): Pointer? {
        if (!isWindows || hProcess == null) return null
        val k32 = WinKernel32.INSTANCE ?: return null
        return runCatching {
            val hJob = k32.CreateJobObjectW(null, null) ?: return@runCatching null
            val is64 = com.sun.jna.Native.POINTER_SIZE == 8
            val structSize = if (is64) 144 else 112
            val mem = Memory(structSize.toLong()).apply {
                clear()
                setInt(16, 0x2000 /* JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE */)
            }
            val ok = k32.SetInformationJobObject(hJob, 9 /* JobObjectExtendedLimitInformation */, mem, structSize)
            if (!ok) {
                val err = com.sun.jna.Native.getLastError()
                System.err.println("[EdgeBrowserBridge] SetInformationJobObject failed: error $err")
            }
            val assigned = k32.AssignProcessToJobObject(hJob, hProcess)
            if (!assigned) {
                val err = com.sun.jna.Native.getLastError()
                System.err.println("[EdgeBrowserBridge] AssignProcessToJobObject failed: error $err")
                k32.CloseHandle(hJob) // без привязки job бесполезен: полагаемся на taskkill
                return@runCatching null
            }
            hJob
        }.getOrNull()
    }

    fun createJobForPid(pid: Int): Pointer? {
        if (!isWindows || pid <= 0) return null
        val k32 = WinKernel32.INSTANCE ?: return null
        return runCatching {
            val hProc = k32.OpenProcess(0x1F0FFF /* PROCESS_ALL_ACCESS */, false, pid) ?: return@runCatching null
            try {
                val hJob = k32.CreateJobObjectW(null, null) ?: return@runCatching null
                val is64 = com.sun.jna.Native.POINTER_SIZE == 8
                val structSize = if (is64) 144 else 112
                val mem = Memory(structSize.toLong()).apply {
                    clear()
                    setInt(16, 0x2000 /* JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE */)
                }
                val ok = k32.SetInformationJobObject(hJob, 9 /* JobObjectExtendedLimitInformation */, mem, structSize)
                if (!ok) {
                    val err = com.sun.jna.Native.getLastError()
                    System.err.println("[EdgeBrowserBridge] SetInformationJobObject failed for pid $pid: error $err")
                }
                if (!k32.AssignProcessToJobObject(hJob, hProc)) {
                    System.err.println("[EdgeBrowserBridge] AssignProcessToJobObject failed for pid $pid: error ${com.sun.jna.Native.getLastError()}")
                    k32.CloseHandle(hJob)
                    return@runCatching null
                }
                hJob
            } finally {
                k32.CloseHandle(hProc)
            }
        }.getOrNull()
    }

    /** Уничтожает процесс и все его дочерние процессы (GPU, utility, renderers). */
    fun killProcessTree(proc: Process?, rootPid: Long = proc?.let { runCatching { it.pid() }.getOrNull() } ?: 0L) {
        if (proc == null && rootPid <= 0L) return
        val pid = if (rootPid > 0L) rootPid else proc?.let { runCatching { it.pid() }.getOrNull() } ?: 0L

        val descendants = if (pid > 0L) {
            runCatching {
                ProcessHandle.of(pid).map { it.descendants().toList() }.orElse(emptyList())
            }.getOrDefault(emptyList())
        } else emptyList()

        if (isWindows && pid > 0L) {
            runCatching {
                val pb = ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString())
                pb.redirectErrorStream(true)
                val p = pb.start()
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        descendants.forEach { runCatching { it.destroyForcibly() } }
        proc?.let { runCatching { it.destroyForcibly() } }
        if (pid > 0L) {
            runCatching { ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    /** Принудительно завершает любые висящие фоновые процессы наших профилей браузеров. */
    fun cleanOrphanTrackerProcesses() {
        if (!isWindows) return
        val profileNames = listOf(
            "edge_tracker_profile",
            "chrome_tracker_profile",
            "yandex_tracker_profile",
            "brave_tracker_profile",
            "opera_tracker_profile",
            "opera_gx_tracker_profile",
            "vivaldi_tracker_profile"
        )
        runCatching {
            ProcessHandle.allProcesses().forEach { h ->
                val cmd = runCatching { h.info().commandLine().orElse("") }.getOrDefault("")
                if (profileNames.any { cmd.contains(it, ignoreCase = true) }) {
                    val pid = h.pid()
                    runCatching {
                        val pb = ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString())
                        val p = pb.start()
                        p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    runCatching { h.destroyForcibly() }
                }
            }
        }
    }

    /** Браузер больше не нужен: закроется сам, если за [IDLE_SHUTDOWN_MS] не будет нового поиска. */
    private fun scheduleIdleStop() {
        synchronized(this) {
            idleTask?.cancel(false)
            idleTask = runCatching {
                idleTimer.schedule({
                    if (activeRequests.get() == 0) {
                        bridgeScope.launch {
                            mutex.withLock {
                                if (activeRequests.get() == 0) {
                                    stopInternal()
                                }
                            }
                        }
                    }
                }, IDLE_SHUTDOWN_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrNull()
        }
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
     */
    private fun closeGracefully(port: Int) {
        runCatching {
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/version"))
                .timeout(Duration.ofMillis(300)).GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return
            val wsUrl = JSONObject(res.body()).optString("webSocketDebuggerUrl").ifBlank { return }
            val ws = client.newWebSocketBuilder().connectTimeout(Duration.ofMillis(300))
                .buildAsync(URI.create(wsUrl), object : WebSocket.Listener {}).get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            ws.sendText(JSONObject().put("id", 1).put("method", "Browser.close").toString(), true)
            val pid = currentPid
            if (pid > 0) {
                ProcessHandle.of(pid.toLong()).ifPresent { h ->
                    h.onExit().get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    /** Асинхронная остановка в фоновом потоке IO — не блокирует интерфейс пользователя. */
    fun stopAsync() {
        bridgeScope.launch {
            if (activeRequests.get() == 0) {
                mutex.withLock {
                    if (activeRequests.get() == 0) {
                        stopInternal()
                    }
                }
            }
        }
    }

    fun stop(force: Boolean = false) {
        if (!force && activeRequests.get() > 0) {
            scheduleIdleStop()
            return
        }
        synchronized(this) {
            stopInternal()
        }
    }

    private fun stopInternal() {
        idleTask?.cancel(false)
        idleTask = null
        try {
            val pid = currentPid
            val job = currentJobHandle
            currentJobHandle = null

            // 1. Просим аккуратно закрыться через CDP (до 500 мс)
            if (cdpPort != 0) closeGracefully(cdpPort)

            // 2. Закрываем Job Object (Windows гарантированно завершит все дочерние процессы)
            job?.let {
                WinKernel32.INSTANCE?.TerminateJobObject(it, 0)
                WinKernel32.INSTANCE?.CloseHandle(it)
            }

            // 3. Добиваем дерево процессов taskkill
            killProcessTree(fallbackProcess, pid.toLong())

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
                null, WString(cmdLine), null, null, false, 0x00000004 /* CREATE_SUSPENDED */, null, null, si, pi
            ) ?: false

            if (ok && pi.dwProcessId > 0) {
                currentPid = pi.dwProcessId
                currentProcessHandle = pi.hProcess
                currentThreadHandle = pi.hThread
                currentJobHandle = createJobForProcess(pi.hProcess)
                // Resume thread only after process is safely assigned to the Job Object
                pi.hThread?.let { WinKernel32.INSTANCE?.ResumeThread(it) }
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
            currentJobHandle = createJobForPid(currentPid)
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

    private fun extractCookieDomain(baseUrl: String): String {
        val host = URI.create(baseUrl).host?.let { if (it.startsWith("www.")) it.substring(4) else it } ?: "rutracker.org"
        val parts = host.split('.')
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts.last()}" else host
    }

    /** Идентификаторы видео-разделов RuTracker (из выпадающего списка форума), кэш на время работы приложения. */
    @Volatile
    private var videoForumIds: List<String>? = null

    private suspend fun loadVideoForumIds(session: CdpTabSession, baseUrl: String): List<String> {
        session.sendCmd("Page.navigate", JSONObject().put("url", "${baseUrl.removeSuffix("/")}/forum/tracker.php"))
        val js = """
            (() => {
                const sel = document.querySelector('select#fs-main, select[name="f[]"], select[name="f"]');
                if (!sel) return '';
                const keep = /кино|видео|мульт|аниме|сериал/i, drop = /обуч|музык|игр|программ|книг|софт/i;
                const ids = [];
                for (const g of sel.querySelectorAll('optgroup')) {
                    const label = g.getAttribute('label') || '';
                    if (drop.test(label)) continue;
                    // Видео бывает и в чужих группах: «Разное → Видео» (f=807) — смотрим и название раздела
                    const whole = keep.test(label);
                    for (const o of g.querySelectorAll('option')) {
                        if (!/^\d+$/.test(o.value)) continue;
                        const name = o.textContent || '';
                        if (whole || (keep.test(name) && !drop.test(name))) ids.push(o.value);
                    }
                }
                return JSON.stringify(ids);
            })()
        """.trimIndent()
        repeat(15) {
            delay(400)
            val v = runCatching {
                session.sendCmd("Runtime.evaluate", JSONObject().put("expression", js).put("returnByValue", true))
            }.getOrNull()?.optJSONObject("result")?.optJSONObject("result")?.optString("value").orEmpty()
            if (v.startsWith("[")) {
                val arr = JSONArray(v)
                val ids = (0 until arr.length()).map { arr.getString(it) }.distinct()
                if (ids.isNotEmpty()) videoForumIds = ids
                return ids
            }
        }
        return emptyList() // список не получили — ищем без фильтра по разделам
    }

    /** Longer GET addresses go as a POST form: servers answer 414 to request lines over ~8 KB (nginx by default). */
    private const val MAX_GET_URL = 6000

    /** tracker.php's search as its own form sends it (POST, `f[]` per forum, `nm` in the page's charset). */
    private suspend fun postTrackerSearch(session: CdpTabSession, baseUrl: String, forums: List<String>, query: String) {
        session.sendCmd("Page.navigate", JSONObject().put("url", "${baseUrl.removeSuffix("/")}/forum/tracker.php"))
        val js = """
            (() => {
                if (document.readyState !== 'complete' || !/tracker\.php${'$'}/.test(location.pathname)) return 'wait';
                const form = document.createElement('form');
                form.method = 'post'; form.action = 'tracker.php'; form.acceptCharset = document.characterSet;
                const add = (name, value) => {
                    const i = document.createElement('input');
                    i.type = 'hidden'; i.name = name; i.value = value; form.appendChild(i);
                };
                for (const f of ${JSONArray(forums)}) add('f[]', f);
                add('nm', ${JSONObject.quote(query)});
                document.body.appendChild(form);
                window.__smPosted = true;
                form.submit();
                return 'sent';
            })()
        """.trimIndent()
        repeat(15) {
            delay(400)
            val v = runCatching {
                session.sendCmd("Runtime.evaluate", JSONObject().put("expression", js).put("returnByValue", true))
            }.getOrNull()?.optJSONObject("result")?.optJSONObject("result")?.optString("value").orEmpty()
            if (v == "sent") return
        }
    }

    /**
     * Executes tracker search via browser in hidden desktop and parses results into compact JSON array directly in DOM.
     * Extracts unescaped text content, exact byte sizes, seeds, and avoids sending large raw HTML blobs over CDP.
     */
    suspend fun searchHtml(baseUrl: String, query: String, cookies: Map<String, String>): String? = withContext(Dispatchers.IO) {
        activeRequests.incrementAndGet()
        try {
            mutex.withLock {
                withTimeoutOrNull(25_000L) {
                    val port = ensureRunning()
                    val session = openTabSession(port) ?: return@withTimeoutOrNull null

                    try {
                        val rootDomain = extractCookieDomain(baseUrl)
                        for ((k, v) in cookies) {
                            session.sendCmd("Network.setCookie", JSONObject().apply {
                                put("name", k)
                                put("value", v)
                                put("domain", ".$rootDomain")
                                put("path", "/")
                            })
                        }

                        val encoded = URLEncoder.encode(query, "windows-1251")
                        // Отсев на стороне трекера: ищем только в разделах кино/сериалов/мультфильмов/аниме
                        val forums = videoForumIds ?: loadVideoForumIds(session, baseUrl)
                        val fParam = if (forums.isNotEmpty()) "f=${forums.joinToString(",")}&" else ""
                        val targetUrl = "${baseUrl.removeSuffix("/")}/forum/tracker.php?${fParam}nm=$encoded"
                        if (targetUrl.length <= MAX_GET_URL) session.sendCmd("Page.navigate", JSONObject().put("url", targetUrl))
                        // Many forums: a URL of many KB may get HTTP 414 — the same search as tracker.php's own form (POST, f[])
                        else postTrackerSearch(session, baseUrl, forums, query)

                        var resultJson: String? = null
                        var lastState = ""
                        for (attempt in 1..28) {
                            delay(400)
                            val eval = runCatching {
                                session.sendCmd(
                                    "Runtime.evaluate",
                                    JSONObject().put(
                                        "expression",
                                        """
                                        (() => {
                                            if (window.__smPosted) return ''; // the form is being sent: still the old page
                                            const table = document.getElementById('tor-tbl');
                                            if (!table) {
                                                if (document.querySelector('input[name="login_username"]')) return '__LOGIN__';
                                                if (document.body && (document.body.innerText.includes('Не найдено') || document.body.innerText.includes('Результатов поиска: 0'))) return '[]';
                                                if (/just a moment|один момент/i.test(document.title) || document.querySelector('#challenge-form, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]')) return '__CF__';
                                                return '';
                                            }
                                            const rows = table.querySelectorAll('tr.hl-tr, tr.tCenter, tr[id^="trs-tr-"]');
                                            if (!rows || rows.length === 0) return '[]';
                                            const items = [];
                                            for (const r of rows) {
                                                const link = r.querySelector('a.tLink, a[href*="viewtopic.php?t="]');
                                                if (!link) continue;
                                                const href = link.getAttribute('href') || '';
                                                const tMatch = href.match(/viewtopic\.php\?t=(\d+)/);
                                                const topicId = tMatch ? tMatch[1] : '';
                                                if (!topicId) continue;
                                                const title = link.textContent.trim();
                                                if (!title) continue;
                                                const forumEl = r.querySelector('a.f, a[href*="tracker.php?f="]');
                                                const forum = forumEl ? forumEl.textContent.trim() : '';
                                                const sizeEl = r.querySelector('td.tor-size');
                                                const sizeBytes = sizeEl ? (parseInt(sizeEl.getAttribute('data-ts_text') || '0', 10) || 0) : 0;
                                                const seedEl = r.querySelector('td.seedmed');
                                                const seeds = seedEl ? (parseInt(seedEl.textContent.replace(/\D/g, ''), 10) || 0) : 0;
                                                const leechEl = r.querySelector('td.leechmed');
                                                const leeches = leechEl ? (parseInt(leechEl.textContent.replace(/\D/g, ''), 10) || 0) : 0;
                                                const magnetEl = r.querySelector('a[href^="magnet:"]');
                                                const magnet = magnetEl ? magnetEl.getAttribute('href') : '';
                                                items.push({ topicId, title, forum, sizeBytes, seeds, leeches, magnet });
                                            }
                                            return JSON.stringify(items);
                                        })()
                                        """.trimIndent()
                                    ).put("returnByValue", true)
                                )
                            }.getOrNull()

                            val resVal = eval?.optJSONObject("result")?.optJSONObject("result")?.optString("value") ?: ""
                            if (resVal == "__LOGIN__") throw TrackerAuthException()
                            if (resVal == "[]" || (resVal.startsWith("[") && resVal.endsWith("]"))) {
                                resultJson = resVal
                                break
                            }
                            if (resVal.isNotEmpty()) lastState = resVal
                        }
                        if (resultJson == null && lastState == "__CF__") throw TrackerChallengeException()

                        resultJson
                    } finally {
                        session.close()
                    }
                }
            }
        } finally {
            activeRequests.decrementAndGet()
            scheduleIdleStop()
        }
    }

    /**
     * Downloads .torrent file bytes inside the browser session via fetch().
     * Браузер остаётся запущенным ещё 15 с (см. [scheduleIdleStop]).
     */
    suspend fun downloadTorrentBytes(baseUrl: String, topicId: String, cookies: Map<String, String>): ByteArray? = withContext(Dispatchers.IO) {
        require(topicId.matches(Regex("""^\d+$"""))) { "Неверный topicId" }
        activeRequests.incrementAndGet()
        try {
            mutex.withLock {
                withTimeoutOrNull(25_000L) {
                    val port = ensureRunning()
                    val session = openTabSession(port) ?: return@withTimeoutOrNull null

                    try {
                        val rootDomain = extractCookieDomain(baseUrl)
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
                            val eval = runCatching {
                                session.sendCmd(
                                    "Runtime.evaluate",
                                    JSONObject().put("expression", "document.readyState === 'complete'").put("returnByValue", true)
                                )
                            }.getOrNull()
                            if (eval?.optJSONObject("result")?.optJSONObject("result")?.optBoolean("value") == true) break
                        }

                        val fetchJs = """
                            new Promise((resolve) => {
                                fetch('/forum/dl.php?t=$topicId', { credentials: 'include' })
                                    .then(r => r.blob())
                                    .then(blob => {
                                        const reader = new FileReader();
                                        reader.onload = () => resolve(reader.result.split(',')[1]);
                                        reader.readAsDataURL(blob);
                                    })
                                    .catch(() => resolve(''));
                            })
                        """.trimIndent()

                        val eval = session.sendCmd(
                            "Runtime.evaluate",
                            JSONObject().put("expression", fetchJs).put("awaitPromise", true).put("returnByValue", true),
                            timeoutMs = 12000L
                        )
                        val b64 = eval.optJSONObject("result")?.optJSONObject("result")?.optString("value") ?: ""
                        if (b64.isNotBlank()) {
                            Base64.getDecoder().decode(b64)
                        } else null
                    } finally {
                        session.close()
                    }
                }
            }
        } finally {
            activeRequests.decrementAndGet()
            scheduleIdleStop()
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
                // Background instance is already running on this profile and has no matching cookies.
                // Launching a second Chromium process on the same user-data-dir would fail to open CDP port.
                return@withContext null
            }

            var tempProc: Process? = null
            var jobHandle: Pointer? = null
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
                val p = pb.start()
                tempProc = p
                jobHandle = createJobForPid(p.pid().toInt())

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
                jobHandle?.let {
                    WinKernel32.INSTANCE?.TerminateJobObject(it, 0)
                    WinKernel32.INSTANCE?.CloseHandle(it)
                }
                killProcessTree(tempProc)
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
            ?: return@withContext Result.failure(IllegalStateException(tr("trackers.browser_not_found", "browser" to type.displayName)))
        val exe = exeFile.absolutePath
        val profDir = profileDirFor(type)

        // First check if already logged in in this profile
        val existing = getExistingCookies(type, baseUrl)
        if (existing != null && existing.contains("bb_session")) {
            preferredBrowserType = type
            return@withContext Result.success(existing)
        }

        mutex.withLock {
            stop(force = true) // Stop background instance to unlock profile

            var proc: Process? = null
            var jobHandle: Pointer? = null
            try {
                val port = ServerSocket(0).use { it.localPort }
                val loginUrl = "${baseUrl.removeSuffix("/")}/forum/login.php"

                onStatus(tr("trackers.browser_opening", "browser" to type.displayName))
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
                jobHandle = createJobForPid(p.pid().toInt())

                // Connect to browser-level CDP
                var session: CdpSession? = null
                for (i in 1..30) {
                    delay(200)
                    session = openBrowserSession(port)
                    if (session != null) break
                }

                if (session == null) {
                    return@withContext Result.failure(IllegalStateException(tr("trackers.browser_connect_failed", "browser" to type.displayName)))
                }

                try {
                    onStatus(tr("trackers.browser_login_prompt", "browser" to type.displayName))

                    // Poll every 500ms for up to 3 minutes
                    for (i in 1..360) {
                        delay(500)
                        if (!p.isAlive) {
                            return@withContext Result.failure(IllegalStateException(tr("trackers.browser_window_closed")))
                        }

                        val resp = runCatching { session.sendCmd("Storage.getCookies") }.getOrNull()
                        val arr = resp?.optJSONObject("result")?.optJSONArray("cookies")
                        if (arr != null) {
                            val header = formatTrackerCookies(arr, baseUrl)
                            if (header != null && header.contains("bb_session")) {
                                onStatus(tr("trackers.browser_login_success"))
                                preferredBrowserType = type
                                runCatching {
                                    session.sendCmd("Browser.close")
                                }
                                runCatching {
                                    p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                                }
                                return@withContext Result.success(header)
                            }
                        }
                    }

                    Result.failure(IllegalStateException(tr("trackers.browser_timeout")))
                } finally {
                    session.close()
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                jobHandle?.let {
                    WinKernel32.INSTANCE?.TerminateJobObject(it, 0)
                    WinKernel32.INSTANCE?.CloseHandle(it)
                }
                killProcessTree(proc)
            }
        }
    }

    /**
     * Проверяет авторизацию на RuTracker через движок браузера.
     * Автоматически проходит Cloudflare Turnstile и считывает имя пользователя.
     */
    suspend fun checkConnection(baseUrl: String, cookies: Map<String, String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        activeRequests.incrementAndGet()
        try {
            mutex.withLock {
                try {
                    val port = ensureRunning()
                    val session = openTabSession(port) ?: return@withContext false to "Не удалось подключиться к браузеру"

                    try {
                        val rootDomain = extractCookieDomain(baseUrl)
                        for ((k, v) in cookies) {
                            session.sendCmd("Network.setCookie", JSONObject().apply {
                                put("name", k)
                                put("value", v)
                                put("url", "${baseUrl.removeSuffix("/")}/")
                                put("domain", ".$rootDomain")
                                put("path", "/")
                            })
                        }

                        val targetUrl = "${baseUrl.removeSuffix("/")}/forum/index.php"
                        session.sendCmd("Page.navigate", JSONObject().put("url", targetUrl))

                        // Wait up to 14s for either logged-in user, login form, or Cloudflare
                        for (attempt in 1..28) {
                            delay(500)
                            val eval = runCatching {
                                session.sendCmd(
                                    "Runtime.evaluate",
                                    JSONObject().put(
                                        "expression",
                                        "(() => { " +
                                            "const u = document.querySelector('#logged-in-username, .logged-in-username, a[href*=\"mode=viewprofile\"] b'); " +
                                            "if (u && u.innerText.trim()) return 'USER:' + u.innerText.trim(); " +
                                            "if (document.querySelector('input[name=\"login_username\"]')) return '__LOGIN__'; " +
                                            "if (/just a moment|один момент/i.test(document.title) || document.querySelector('#challenge-form, .cf-turnstile, iframe[src*=\"challenges.cloudflare.com\"]')) return '__CF__'; " +
                                            "return ''; " +
                                        "})()"
                                    ).put("returnByValue", true)
                                )
                            }.getOrNull()
                            val res = eval?.optJSONObject("result")?.optJSONObject("result")?.optString("value") ?: ""
                            if (res.startsWith("USER:")) {
                                val user = res.substring(5).trim()
                                val updatedCookies = runCatching {
                                    val cookiesResp = session.sendCmd("Storage.getCookies")
                                    val arr = cookiesResp.optJSONObject("result")?.optJSONArray("cookies")
                                    if (arr != null) formatTrackerCookies(arr, baseUrl) else null
                                }.getOrNull()
                                if (!updatedCookies.isNullOrBlank()) {
                                    RuTrackerTracker.cookie = updatedCookies
                                }
                                return@withContext true to tr("trackers.authorized_as", "user" to user, "note" to RuTrackerTracker.browserNote())
                            }
                            if (res == "__LOGIN__") {
                                return@withContext false to "Сессия не принята (обновите bb_session в $activeBrowserName)"
                            }
                        }
                        false to "Не удалось проверить авторизацию (таймаут ответа)"
                    } finally {
                        session.close()
                    }
                } catch (e: Exception) {
                    false to (e.message ?: "Ошибка проверки подключения")
                }
            }
        } finally {
            activeRequests.decrementAndGet()
            scheduleIdleStop()
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
        suspend fun sendCmd(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 7000L): JSONObject {
            val id = msgId.getAndIncrement()
            val def = CompletableDeferred<JSONObject>()
            responses[id] = def
            try {
                val cmd = JSONObject().put("id", id).put("method", method).put("params", params)
                ws.sendText(cmd.toString(), true)
                return withTimeoutOrNull(timeoutMs) { def.await() }
                    ?: throw java.util.concurrent.TimeoutException("CDP command '$method' timed out after ${timeoutMs}ms")
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
        suspend fun sendCmd(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = 7000L): JSONObject {
            val id = msgId.getAndIncrement()
            val def = CompletableDeferred<JSONObject>()
            responses[id] = def
            try {
                val cmd = JSONObject().put("id", id).put("method", method).put("params", params)
                ws.sendText(cmd.toString(), true)
                return withTimeoutOrNull(timeoutMs) { def.await() }
                    ?: throw java.util.concurrent.TimeoutException("CDP command '$method' timed out after ${timeoutMs}ms")
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
