package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.player.Vlc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import java.io.File
import java.lang.management.ManagementFactory
import java.net.NetworkInterface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Debug build only («Suslik Debug», built with `-Pdiag`): a detailed journal in the same timeline as the player journal
 * (PlayerLog), like the Android debug build. The app installs next to the normal one (own Start menu entry, own data
 * folder %APPDATA%\SuslikMediaDebug, own reserve folder) and never updates itself.
 *
 *  [sys]   every 5 s while a film is open, every 60 s otherwise: power (mains / battery %, laptops only), the app's CPU
 *          since the last line and the busiest threads, the whole PC's CPU load, memory, the reserve's traffic;
 *  [net]   network adapters coming and going (Wi-Fi ↔ Ethernet, VPN up / down);
 *  [http]  every request to the server: time to the answer, code, size;
 *  [vlc] / [proxy]  from the player: playback statistics and the reserve's windows every 5 s;
 *  [mark]  F8 in the player or «Метка» in the settings: the moment something went wrong.
 */
object Diag {
    val on: Boolean = System.getProperty("suslik.diag") == "true"
    /** The player is open: [sys] every 5 s instead of every 60 s. */
    @Volatile var playerOpen = false

    private val exec by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diag").apply { isDaemon = true } } }
    @Volatile private var started = false
    private var marks = 0
    @Volatile private var tickFailed = false

    fun start() {
        if (!on || started) return
        started = true
        PlayerLog.write("[diag] ===== start · Suslik Debug ${System.getProperty("suslik.version")} · " +
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} · Java ${System.getProperty("java.version")} · " +
            "${Runtime.getRuntime().availableProcessors()} CPU")
        exec.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { e -> if (!tickFailed) { tickFailed = true; PlayerLog.write("[diag] journal tick failed: $e") } } }, 2, 1, TimeUnit.SECONDS)
    }

    fun mark(where: String): Int {
        val n = synchronized(this) { ++marks }
        PlayerLog.write("[mark] ========== MARK #$n ($where) ==========")
        return n
    }

    // ---- [sys] -------------------------------------------------------------------------------------------------
    private var lastSysAt = 0L
    private var lastCpuNs = -1L
    private var lastMb = -1.0
    private val threadCpu = HashMap<Long, Long>()

    private fun tick() {
        val now = System.currentTimeMillis()
        network()
        if (now - lastSysAt < if (playerOpen) 5_000L else 60_000L) return
        val span = if (lastSysAt == 0L) 0L else now - lastSysAt
        lastSysAt = now
        val sb = StringBuilder("[sys] ")
        sb.append(power())
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        os?.let {
            val cpu = it.processCpuTime
            if (lastCpuNs >= 0 && span > 0) sb.append(" · cpu %d ms (%.1f%% of one core)".format((cpu - lastCpuNs) / 1_000_000, (cpu - lastCpuNs) / 1e4 / span))
            lastCpuNs = cpu
            val sys = it.cpuLoad
            if (sys >= 0) sb.append(" · PC cpu %.0f%%".format(sys * 100))
        }
        topThreads(span)?.let { sb.append(" · threads ").append(it) }
        val rt = Runtime.getRuntime()
        sb.append(" · heap %d/%d MB".format((rt.totalMemory() - rt.freeMemory()) shr 20, rt.maxMemory() shr 20))
        os?.let { sb.append(" · PC free memory %.1f GB".format(it.freeMemorySize / 1073741824.0)) }
        val mb = BurstProxy.downloadedMb()
        if (lastMb >= 0) sb.append(" · film from network +%.2f MB".format(mb - lastMb))
        lastMb = mb
        sb.append(if (playerOpen) " · player open" else "")
        PlayerLog.write(sb.toString())
    }

    /** The five threads that used the most CPU since the last line (VLC's own threads are native and not seen here). */
    private fun topThreads(span: Long): String? = runCatching {
        val tm = ManagementFactory.getThreadMXBean()
        if (!tm.isThreadCpuTimeSupported) return null
        val info = tm.getThreadInfo(tm.allThreadIds).filterNotNull()
        val used = ArrayList<Pair<String, Long>>()
        val seen = HashSet<Long>()
        for (t in info) {
            val ns = tm.getThreadCpuTime(t.threadId).takeIf { it >= 0 } ?: continue
            seen += t.threadId
            val before = threadCpu.put(t.threadId, ns)
            if (before != null && span > 0) used += t.threadName.take(16) to (ns - before) / 1_000_000
        }
        threadCpu.keys.retainAll(seen)
        if (used.isEmpty()) null
        else "${info.size} " + used.sortedByDescending { it.second }.take(5).filter { it.second > 0 }.joinToString(" ") { "${it.first}:${it.second}ms" }
    }.getOrNull()

    // ---- power (laptops): GetSystemPowerStatus -----------------------------------------------------------------
    @Suppress("unused", "PropertyName")
    @Structure.FieldOrder("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
    class PowerStatus : Structure() {
        @JvmField var ACLineStatus: Byte = 0
        @JvmField var BatteryFlag: Byte = 0
        @JvmField var BatteryLifePercent: Byte = 0
        @JvmField var SystemStatusFlag: Byte = 0
        @JvmField var BatteryLifeTime: Int = 0
        @JvmField var BatteryFullLifeTime: Int = 0
    }
    private interface Kernel32 : Library { fun GetSystemPowerStatus(status: PowerStatus): Boolean }
    private val kernel32: Kernel32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    private fun power(): String = runCatching {
        val k = kernel32 ?: return "power ?"
        val s = PowerStatus()
        if (!k.GetSystemPowerStatus(s)) return "power ?"
        val ac = when (s.ACLineStatus.toInt() and 0xFF) { 1 -> "mains"; 0 -> "BATTERY"; else -> "power ?" }
        val flag = s.BatteryFlag.toInt() and 0xFF
        val pct = s.BatteryLifePercent.toInt() and 0xFF
        val saver = if ((s.SystemStatusFlag.toInt() and 1) == 1) " · battery saver" else ""
        when {
            flag == 128 -> "$ac (no battery)"
            pct == 255 -> ac
            else -> "$ac $pct%" + (if ((flag and 8) != 0) " charging" else "") + saver
        }
    }.getOrDefault("power ?")

    // ---- [net] -------------------------------------------------------------------------------------------------
    private var lastNets: Set<String>? = null
    private fun network() {
        val now = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.inetAddresses.toList().isNotEmpty() }
                .map { it.displayName.take(48) + (if (it.isVirtual || it.isPointToPoint) " (tunnel)" else "") }
                .toSortedSet()
        }.getOrNull() ?: return
        val before = lastNets
        lastNets = now
        if (before == null) { PlayerLog.write("[net] adapters: ${now.joinToString(" · ")}"); return }
        (now - before).forEach { PlayerLog.write("[net] up: $it") }
        (before - now).forEach { PlayerLog.write("[net] DOWN: $it") }
    }

    // ---- [http] ------------------------------------------------------------------------------------------------
    fun http(method: String, path: String, code: Int?, ms: Long, bytes: Int, error: Throwable? = null) {
        if (!on) return
        val p = path.substringBefore('?')
        PlayerLog.write(if (error != null) "[http] $method $p FAILED after $ms ms (${error.javaClass.simpleName}: ${error.message})"
            else "[http] $method $p → $code in $ms ms, %.1f KB".format(bytes / 1024.0))
    }

    // ---- the journal as one file -------------------------------------------------------------------------------
    /** player.log (+ the previous part, crash reports of the JVM) zipped to the Desktop: suslik-debug-DDMM-HHMM.zip. */
    fun zipJournal(): File? = runCatching {
        val desk = File(System.getProperty("user.home"), "Desktop").takeIf { it.isDirectory } ?: AppDir.dir
        val zip = File(desk, "suslik-debug-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("ddMM-HHmm"))}.zip")
        val files = listOfNotNull(AppDir.file("player.old.log"), PlayerLog.file).filter { it.exists() } +
            (File(System.getProperty("compose.application.resources.dir") ?: ".").parentFile?.listFiles { f -> f.name.startsWith("hs_err") }?.toList().orEmpty())
        ZipOutputStream(zip.outputStream()).use { z ->
            for (f in files) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        zip
    }.getOrNull()
}
