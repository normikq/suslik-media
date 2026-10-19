package ru.suslikovanora.media.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import ru.suslikovanora.media.desktop.PlayerLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Prevents Windows from dimming the screen, turning off the display, or putting the system to sleep
 * while video playback is active.
 *
 * Uses both:
 *  1. Modern Windows Power Requests API (PowerCreateRequest / PowerSetRequest / PowerClearRequest):
 *     Process-wide kernel power request object visible to Windows Power Manager and `powercfg /requests`.
 *  2. Thread Execution State (SetThreadExecutionState):
 *     Tells Windows that active playback is ongoing (ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED),
 *     and periodically resets the idle timer (every 30 s) so aggressive OEM battery profiles / Modern Standby
 *     cannot dim or turn off the screen during playback.
 */
object DisplayAwake {

    private interface KernelPowerApi : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int
        fun PowerCreateRequest(context: Pointer): Pointer?
        fun PowerSetRequest(powerRequest: Pointer, requestType: Int): Boolean
        fun PowerClearRequest(powerRequest: Pointer, requestType: Int): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002
    private const val ES_CONTINUOUS = 0x80000000.toInt()

    private const val POWER_REQUEST_DISPLAY_REQUIRED = 0
    private const val POWER_REQUEST_SYSTEM_REQUIRED = 1

    private val kernel: KernelPowerApi? by lazy {
        if (!isWindows) null
        else runCatching { Native.load("kernel32", KernelPowerApi::class.java) }.getOrNull()
    }

    private val executor by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "power-awake").apply { isDaemon = true }
        }
    }

    private var powerHandle: Pointer? = null
    private var pulseTask: ScheduledFuture<*>? = null
    @Volatile private var isAwake = false

    private fun getOrCreatePowerHandle(k: KernelPowerApi): Pointer? {
        powerHandle?.let { return it }
        return runCatching {
            val reason = "Suslik Media: воспроизведение видео"
            val chars = (reason + "\u0000").toCharArray()
            val strMem = Memory((chars.size * 2).toLong())
            for (i in chars.indices) {
                strMem.setChar((i * 2).toLong(), chars[i])
            }

            val ctx = Memory(64)
            ctx.clear()
            ctx.setInt(0, 0) // Version = POWER_REQUEST_CONTEXT_VERSION (0)
            ctx.setInt(4, 1) // Flags = POWER_REQUEST_CONTEXT_SIMPLE_STRING (1)
            ctx.setPointer(8, strMem)

            val h = k.PowerCreateRequest(ctx)
            if (h != null && Pointer.nativeValue(h) != 0L && Pointer.nativeValue(h) != -1L) {
                powerHandle = h
                h
            } else null
        }.getOrNull()
    }

    private fun acquireNow(k: KernelPowerApi) {
        if (isAwake) return
        isAwake = true

        val h = getOrCreatePowerHandle(k)
        if (h != null) {
            runCatching {
                k.PowerSetRequest(h, POWER_REQUEST_DISPLAY_REQUIRED)
                k.PowerSetRequest(h, POWER_REQUEST_SYSTEM_REQUIRED)
            }
        }

        runCatching {
            k.SetThreadExecutionState(ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED)
        }

        pulseTask?.cancel(false)
        pulseTask = executor.scheduleWithFixedDelay({
            runCatching {
                if (isAwake) {
                    k.SetThreadExecutionState(ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED)
                }
            }
        }, 30, 30, TimeUnit.SECONDS)

        PlayerLog.write("[power] display awake: ON (handle=${h != null})")
    }

    private fun releaseNow(k: KernelPowerApi) {
        if (!isAwake) return
        isAwake = false

        pulseTask?.cancel(false)
        pulseTask = null

        powerHandle?.let { h ->
            runCatching {
                k.PowerClearRequest(h, POWER_REQUEST_DISPLAY_REQUIRED)
                k.PowerClearRequest(h, POWER_REQUEST_SYSTEM_REQUIRED)
            }
        }

        runCatching {
            k.SetThreadExecutionState(ES_CONTINUOUS)
        }

        PlayerLog.write("[power] display awake: OFF")
    }

    /**
     * Enables or disables keeping the display on and preventing system sleep.
     * Safe to call from any thread; execution is serialized on the background power thread.
     */
    fun setAwake(active: Boolean) {
        if (!isWindows) return
        executor.execute {
            val k = kernel ?: return@execute
            if (active) acquireNow(k) else releaseNow(k)
        }
    }

    /**
     * Complete cleanup on application shutdown.
     */
    fun release() {
        if (!isWindows) return
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute {
            try {
                val k = kernel
                if (k != null) {
                    releaseNow(k)
                    powerHandle?.let { h ->
                        runCatching { k.CloseHandle(h) }
                        powerHandle = null
                    }
                }
            } finally {
                done.countDown()
            }
        }
        runCatching { done.await(400, TimeUnit.MILLISECONDS) }
    }
}
