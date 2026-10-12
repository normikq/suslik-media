package ru.suslikovanora.media

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.Window
import androidx.core.content.ContextCompat
import ru.suslikovanora.media.core.BurstProxy
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug build only («Suslik Debug», BuildConfig.DIAG): a detailed journal of what the app and the phone do, written
 * into the same timeline as the player journal (AppLog), to find what to optimise.
 *
 *  [sys]   every 5 s while a film is open / the app is on screen, every 60 s otherwise: battery (%, °C, current),
 *          network (type, generation, signal, metered, link speed), the app's traffic and CPU time since the last line,
 *          the busiest threads, memory, screen / power saving / doze / overheating / Data Saver;
 *  [ui]    frames drawn in that time: slow (> 17 ms) and frozen (> 700 ms) ones, the worst frame;
 *  [net]   network switches as Android reports them (available / lost / capabilities / blocked);
 *  [event] screen on/off, charger, power saving, doze, activities shown and hidden, memory pressure;
 *  [http]  every request: DNS, TCP, TLS, reused connection or not, time to the first byte, size (no query strings);
 *  [vlc] / [proxy]  from the player (PlayerActivity): playback statistics and the reserve's windows every 5 s;
 *  [battery] `dumpsys batterystats` of the app when a film is closed (needs DUMP, granted via adb).
 *
 * Next to the journal: logcat.log (the app's own Android log, incl. VLC and StrictMode), system.log (warnings of the
 * whole system — only with READ_LOGS granted via adb), batterystats-*.txt.
 *
 * In the normal app [on] is a compile-time false: R8 removes all of this.
 */
object Diag {
    val on: Boolean = BuildConfig.DIAG

    @Volatile private var app: Context? = null
    /** PlayerActivity is open: sample every 5 s even with the screen off (that is when the downloader works alone). */
    @Volatile var playerOpen = false
    private val exec by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diag").apply { isDaemon = true } } }
    private val marks = AtomicInteger()

    fun init(ctx: Context) {
        if (!on || app != null) return
        val c = ctx.applicationContext
        app = c
        AppLog.write("[diag] ===== process start · ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · pid ${Process.myPid()} · " +
            "permissions: ${permissions(c)}")
        exitReasons(c)
        strictMode()
        lifecycle(c)
        (ctx as? Activity)?.let { AppLog.write("[event] ${it.javaClass.simpleName} created"); watchFrames(it.window) }
        receivers(c)
        network(c)
        logcat(c)
        exec.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { AppLog.write("[diag] sample failed: $it") } },
            2, 5, TimeUnit.SECONDS)
    }

    fun vlcVerbose(ctx: Context): Boolean = on && prefs(ctx).getBoolean("vlc_verbose", false)
    fun setVlcVerbose(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("vlc_verbose", v).apply()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)

    /** «📍» in the player / «Метка» in the settings: a visible marker in the journal («here it stuttered»). */
    fun mark(where: String): Int {
        val n = marks.incrementAndGet()
        AppLog.write("[mark] ========== MARK #$n ($where) ==========")
        return n
    }

    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun permissions(ctx: Context): String = listOf(
        "READ_LOGS" to Manifest.permission.READ_LOGS, "DUMP" to Manifest.permission.DUMP,
        "READ_PHONE_STATE" to Manifest.permission.READ_PHONE_STATE,
    ).joinToString(", ") { (n, p) -> "$n ${if (granted(ctx, p)) "yes" else "no"}" }

    // ---- periodic sample ------------------------------------------------------------------------------------------

    private var lastAt = 0L
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastCpu = -1L
    private var lastProxy = -1L
    private val threadTicks = HashMap<Int, Long>()

    private fun tick() {
        val c = app ?: return
        val now = SystemClock.elapsedRealtime()
        val busy = playerOpen || Foreground.visible.value
        if (!busy && now - lastAt < 60_000) return
        val span = if (lastAt == 0L) 0L else now - lastAt
        lastAt = now
        val sb = StringBuilder("[sys] ")
        sb.append(battery(c)).append(" · ").append(net(c))

        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
        val proxy = BurstProxy.downloaded.get()
        if (lastRx >= 0 && rx >= 0) sb.append(" · app rx +%.2f MB tx +%.3f MB (reserve +%.2f MB)".format(
            (rx - lastRx) / 1048576.0, (tx - lastTx) / 1048576.0, (proxy - lastProxy) / 1048576.0))
        lastRx = rx; lastTx = tx; lastProxy = proxy

        val cpu = Process.getElapsedCpuTime()
        if (lastCpu >= 0 && span > 0) sb.append(" · cpu %d ms (%.1f%% of one core)".format(cpu - lastCpu, (cpu - lastCpu) * 100.0 / span))
        lastCpu = cpu
        topThreads()?.let { sb.append(" · threads ").append(it) }

        val rt = Runtime.getRuntime()
        sb.append(" · heap %d/%d MB native %d MB".format((rt.totalMemory() - rt.freeMemory()) shr 20, rt.maxMemory() shr 20,
            android.os.Debug.getNativeHeapAllocatedSize() shr 20))
        sb.append(" · ").append(power(c))
        AppLog.write(sb.toString())
        frames()?.let { AppLog.write("[ui] $it") }
    }

    private fun battery(c: Context): String = runCatching {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val bm = c.getSystemService(BatteryManager::class.java)
        // µA on most phones, mA on some (Samsung): small numbers are taken as mA already. Negative = discharging.
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val ma = if (raw == Int.MIN_VALUE) null else if (kotlin.math.abs(raw) > 20_000) raw / 1000 else raw
        "battery ${level}%% %.1f°C%s%s".format(temp, ma?.let { " ${it} mA" } ?: "", if (plugged) " charging" else "")
    }.getOrDefault("battery ?")

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission") // the debug build declares and checks what it uses
    private fun net(c: Context): String = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return@runCatching "net none"
        val caps = cm.getNetworkCapabilities(n) ?: return@runCatching "net ?"
        val sb = StringBuilder("net ")
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                sb.append("wifi")
                runCatching { c.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo?.rssi }.getOrNull()
                    ?.takeIf { it < 0 }?.let { sb.append(" $it dBm") }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                sb.append("mobile")
                val tm = c.getSystemService(TelephonyManager::class.java)
                runCatching { if (granted(c, Manifest.permission.READ_PHONE_STATE)) sb.append(" ").append(generation(tm.dataNetworkType)) }
                if (Build.VERSION.SDK_INT >= 28) runCatching { tm.signalStrength?.let { s ->
                    sb.append(" signal ${s.level}/4")
                    if (Build.VERSION.SDK_INT >= 29) s.cellSignalStrengths.firstOrNull()?.dbm?.takeIf { it < 0 && it > -200 }?.let { sb.append(" $it dBm") }
                } }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> sb.append("vpn")
            else -> sb.append("other")
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) sb.append("+vpn")
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) sb.append(" metered")
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) sb.append(" NOT-VALIDATED")
        sb.append(" link ↓%.1f ↑%.1f Mbit".format(caps.linkDownstreamBandwidthKbps / 1000.0, caps.linkUpstreamBandwidthKbps / 1000.0))
        sb.toString()
    }.getOrDefault("net ?")

    private fun generation(t: Int): String = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE, 19 /* LTE_CA */ -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "gen?"
        else -> "type$t"
    }

    private fun power(c: Context): String = runCatching {
        val pm = c.getSystemService(PowerManager::class.java)
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val sb = StringBuilder(if (pm.isInteractive) "screen on" else "screen off")
        if (pm.isPowerSaveMode) sb.append(" · POWER-SAVE")
        if (pm.isDeviceIdleMode) sb.append(" · DOZE")
        if (Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus > 0) sb.append(" · thermal ${pm.currentThermalStatus}")
        if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) sb.append(" · DATA-SAVER")
        sb.append(if (Foreground.visible.value) " · app shown" else " · app hidden")
        if (playerOpen) sb.append(" · player open")
        sb.toString()
    }.getOrDefault("power ?")

    /** The threads that used the most CPU since the last sample, from /proc/self/task (name: ms). */
    private fun topThreads(): String? = runCatching {
        val now = HashMap<Int, Long>()
        val use = HashMap<String, Long>()
        File("/proc/self/task").listFiles()?.forEach { d ->
            val tid = d.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(d, "stat").readText() }.getOrNull() ?: return@forEach
            val name = stat.substringAfter('(').substringBeforeLast(')')
            val f = stat.substringAfterLast(')').trim().split(' ')
            val ticks = (f.getOrNull(11)?.toLongOrNull() ?: 0) + (f.getOrNull(12)?.toLongOrNull() ?: 0)
            now[tid] = ticks
            val d0 = threadTicks[tid] ?: return@forEach
            if (ticks > d0) use[name] = (use[name] ?: 0) + (ticks - d0) * 10 // 100 ticks per second
        }
        val first = threadTicks.isEmpty()
        threadTicks.clear(); threadTicks.putAll(now)
        if (first) null
        else "${now.size}" + use.entries.sortedByDescending { it.value }.take(5).joinToString("") { " ${it.key}:${it.value}ms" }
    }.getOrNull()

    // ---- UI frames ------------------------------------------------------------------------------------------------

    private val frameCount = AtomicInteger()
    private val slowFrames = AtomicInteger()
    private val frozenFrames = AtomicInteger()
    private val worstFrame = AtomicLong()
    private val frameThread by lazy { HandlerThread("diag-frames").apply { start() } }

    /** Counts the window's frames (API 24+): slow / frozen ones show where the UI stutters. */
    fun watchFrames(window: Window) {
        if (!on) return
        runCatching {
            window.addOnFrameMetricsAvailableListener({ _, m, _ ->
                val ms = m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000
                frameCount.incrementAndGet()
                if (ms > 17) slowFrames.incrementAndGet()
                if (ms > 700) frozenFrames.incrementAndGet()
                worstFrame.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
            }, Handler(frameThread.looper))
        }
    }

    private fun frames(): String? {
        val n = frameCount.getAndSet(0)
        if (n == 0) return null
        return "frames $n, slow ${slowFrames.getAndSet(0)} (>17 ms), frozen ${frozenFrames.getAndSet(0)} (>700 ms), worst ${worstFrame.getAndSet(0)} ms"
    }

    // ---- events ---------------------------------------------------------------------------------------------------

    /** Why the app's previous processes ended (Android 11+): killed in the background, crash, low memory… */
    private fun exitReasons(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = c.getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(null, 0, 3).forEach { e ->
                AppLog.write("[diag] earlier exit: %s · reason %d (%s) · importance %d · pss %d MB · %s".format(
                    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(e.timestamp)),
                    e.reason, e.description ?: "", e.importance, e.pss shr 10, e.processName))
            }
        }
    }

    /** Main-thread disk / network use and leaked resources go to logcat.log (penaltyLog): the causes of UI stutters. */
    private fun strictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
            .apply { if (Build.VERSION.SDK_INT >= 23) detectResourceMismatches() }.penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks()
            .detectLeakedRegistrationObjects().penaltyLog().build())
    }

    private fun lifecycle(c: Context) {
        (c as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun log(a: Activity, what: String) = AppLog.write("[event] ${a.javaClass.simpleName} $what")
            override fun onActivityCreated(a: Activity, b: Bundle?) { log(a, if (b == null) "created" else "re-created"); watchFrames(a.window) }
            override fun onActivityStarted(a: Activity) = log(a, "shown")
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) = log(a, "hidden")
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) = log(a, "destroyed${if (a.isChangingConfigurations) " (config change)" else ""}")
        })
        c.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { AppLog.write("[event] memory trim level $level") }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() { AppLog.write("[event] LOW MEMORY") }
        })
    }

    private fun receivers(c: Context) {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED); addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(c, object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
                    val a = i.action?.substringAfterLast('.') ?: return
                    AppLog.write("[event] $a · ${power(ctx)}")
                    if (i.action == Intent.ACTION_SCREEN_OFF || i.action == Intent.ACTION_SCREEN_ON) lastAt = 0L // sample right away
                }
            }, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun network(c: Context) {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var lastCaps = ""
                override fun onAvailable(n: Network) = AppLog.write("[net] default network available: $n")
                override fun onLost(n: Network) = AppLog.write("[net] default network LOST: $n")
                override fun onUnavailable() = AppLog.write("[net] no network")
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    val t = buildString {
                        append(when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                            else -> "other"
                        })
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) append("+vpn")
                        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) append(" metered")
                        append(if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " validated" else " NOT validated")
                        if (Build.VERSION.SDK_INT >= 28 && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) append(" SUSPENDED")
                    }
                    if (t != lastCaps) { lastCaps = t; AppLog.write("[net] $n: $t") }
                }
                override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) =
                    AppLog.write("[net] $n: interface ${lp.interfaceName}, ${lp.dnsServers.size} DNS servers${if (Build.VERSION.SDK_INT >= 28 && lp.isPrivateDnsActive) ", private DNS" else ""}")
                override fun onBlockedStatusChanged(n: Network, blocked: Boolean) = AppLog.write("[net] $n: ${if (blocked) "BLOCKED for the app" else "unblocked"}")
                override fun onLosing(n: Network, ms: Int) = AppLog.write("[net] $n: losing in $ms ms")
            })
        }
    }

    // ---- logcat & battery stats -----------------------------------------------------------------------------------

    /** The app's own Android log (VLC, StrictMode, the system about this app) → logcat.log; with READ_LOGS also system.log. */
    private fun logcat(c: Context) {
        val dir = AppLog.folder ?: return
        pipe(listOf("logcat", "-v", "threadtime", "-T", "1", "--pid=${Process.myPid()}"), File(dir, "logcat.log"), 16_000_000L)
        if (granted(c, Manifest.permission.READ_LOGS))
            pipe(listOf("logcat", "-v", "threadtime", "-T", "1", "*:W"), File(dir, "system.log"), 16_000_000L)
    }

    private fun pipe(cmd: List<String>, out: File, limit: Long) {
        Thread({
            runCatching {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { r ->
                    var w = java.io.FileWriter(out, true)
                    var size = out.length()
                    while (true) {
                        val line = r.readLine() ?: break
                        w.write(line); w.write("\n"); size += line.length + 1
                        if (!r.ready()) w.flush()
                        if (size > limit) {
                            w.close()
                            val old = File(out.parentFile, out.nameWithoutExtension + ".old.log"); old.delete(); out.renameTo(old)
                            w = java.io.FileWriter(out, true); size = 0
                        }
                    }
                    w.close()
                }
            }.onFailure { AppLog.write("[diag] ${cmd.first()} stopped: $it") }
        }, "diag-${out.nameWithoutExtension}").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /**
     * `dumpsys batterystats` for this app (needs DUMP, granted via adb): saved whole into batterystats-<time>.txt, the
     * key lines (mobile radio active, Wi-Fi, wakelocks, CPU) also into the journal. Reset the counters from the computer
     * before a test with `adb shell dumpsys batterystats --reset`.
     */
    fun batterySnapshot(reason: String) {
        if (!on) return
        val c = app ?: return
        if (!granted(c, Manifest.permission.DUMP)) { AppLog.write("[battery] no DUMP permission (adb shell pm grant ${c.packageName} android.permission.DUMP)"); return }
        exec.execute {
            runCatching {
                val p = ProcessBuilder("dumpsys", "batterystats", c.packageName).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor(20, TimeUnit.SECONDS)
                val name = "batterystats-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + ".txt"
                AppLog.folder?.let { File(it, name).writeText(text) }
                val key = text.lines().map { it.trim() }.filter { l ->
                    listOf("Mobile radio active", "Mobile network:", "Wi-Fi network:", "Wifi network:", "Wake lock", "Cpu times",
                        "Total cpu time", "Foreground activities", "Foreground service", "Estimated power use", "Computed drain",
                        "Uid u0a").any { l.startsWith(it, ignoreCase = true) } || l.contains("mobile radio", ignoreCase = true)
                }.distinct().take(40)
                AppLog.write("[battery] snapshot ($reason) → $name, ${text.length / 1024} KB" + key.joinToString("") { "\n    $it" })
            }.onFailure { AppLog.write("[battery] snapshot failed: $it") }
        }
    }

    /** All journal files zipped into the cache, ready for «Поделиться». */
    fun zipJournal(ctx: Context): File? = runCatching {
        val dir = AppLog.folder ?: return null
        val out = File(ctx.cacheDir, "diag-share").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val zip = File(out, "suslik-debug-" + java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { z ->
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        zip
    }.getOrNull()

    fun clearJournal() {
        AppLog.folder?.listFiles()?.forEach { f -> if (f.name.startsWith("batterystats-") || f.name.endsWith(".old.log")) f.delete() }
        AppLog.write("[diag] ===== journal cleared (current files keep growing; old parts and snapshots removed)")
    }

    // ---- HTTP timings ---------------------------------------------------------------------------------------------

    /** OkHttp listener: one [http] line per request with where the time went. Paths only — no query (signatures, links). */
    val http: okhttp3.EventListener.Factory = okhttp3.EventListener.Factory { call -> HttpTimer(call) }

    private class HttpTimer(call: okhttp3.Call) : okhttp3.EventListener() {
        private val t0 = SystemClock.elapsedRealtime()
        private val what = call.request().let { r ->
            val seg = r.url.encodedPath.substringAfterLast('/').let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.take(40)
            "${r.method} ${r.url.host.substringBefore('.')}${r.url.encodedPath.substringBeforeLast('/').take(30)}/$seg" +
                (r.header("Range")?.let { " [${it.removePrefix("bytes=").substringBefore('-').toLongOrNull()?.let { b -> "${b shr 20}MB-" } ?: it}]" } ?: "")
        }
        private var dns = -1L; private var tcp = -1L; private var tls = -1L
        private var tDns = 0L; private var tConn = 0L; private var tTls = 0L
        private var reused = true
        private var proto = ""
        private var code = 0
        private var headersAt = 0L
        private var bytes = 0L
        private fun now() = SystemClock.elapsedRealtime()

        override fun dnsStart(call: okhttp3.Call, domainName: String) { tDns = now() }
        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<InetAddress>) { dns = now() - tDns }
        override fun connectStart(call: okhttp3.Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { reused = false; tConn = now() }
        override fun secureConnectStart(call: okhttp3.Call) { tTls = now(); tcp = tTls - tConn }
        override fun connectEnd(call: okhttp3.Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: okhttp3.Protocol?) { if (tcp < 0) tcp = now() - tConn }
        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) { tls = now() - tTls }
        override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) { proto = connection.protocol().toString() }
        override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
            headersAt = now(); code = response.code
            AppLog.write("[http] $what → $code ${if (response.cacheResponse != null && response.networkResponse == null) "(cache) " else ""}" +
                "· ${setup()} · first byte ${headersAt - t0} ms")
        }
        override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) { bytes = byteCount }
        override fun callEnd(call: okhttp3.Call) {
            // Long downloads (the reserve's bursts) already have their own journal line; here only the short ones.
            if (bytes < (2L shl 20)) AppLog.write("[http] $what done: %.1f KB in %d ms".format(bytes / 1024.0, now() - t0))
        }
        override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) =
            AppLog.write("[http] $what FAILED after ${now() - t0} ms (${ioe.javaClass.simpleName}: ${ioe.message}) · ${setup()}")
        private fun setup() = if (reused) "reused $proto connection" else
            "new $proto connection: dns ${if (dns >= 0) "$dns ms" else "cached"}, tcp $tcp ms, tls $tls ms"
    }
}
