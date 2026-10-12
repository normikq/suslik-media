package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * A guaranteed, clean exit. The app used to just close its window and let the JVM end by itself. If VLC was still
 * stopping a stream (a slow network read) or its audio thread was busy, the process could stay in Task Manager
 * with no window. Now:
 *  1. the window closes at once;
 *  2. players get up to 1.5 s to stop and release VLC, then the VLC engine is released (1 s at most);
 *  3. settings are written and the process ends;
 *  4. a watchdog kills the process outright if anything above still hangs.
 */
object AppExit {
    private val players = CopyOnWriteArrayList<ExecutorService>()
    @Volatile private var started = false

    /** A player's VLC thread: on exit we wait (briefly) for its stop/release to finish. */
    fun track(executor: ExecutorService) { players += executor }
    fun untrack(executor: ExecutorService) { players -= executor }

    /** After the Compose application has ended (window disposed). Never returns. */
    fun finish(code: Int = 0): Nothing {
        if (!started) {
            started = true
            // Last resort: whatever happens below, the process is gone after 8 s (time enough for the JVM to save
            // its start-up archive on the way out).
            Thread({
                runCatching { Thread.sleep(8_000) }
                terminateNow(code)
            }, "exit-watchdog").apply { isDaemon = true; start() }
            val deadline = System.currentTimeMillis() + 1_500
            for (e in players) runCatching { e.awaitTermination((deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS) }
            Thread({ runCatching { VlcFactory.releaseAll() } }, "vlc-release").apply { isDaemon = true; start(); join(1_000) }
            Store.flushAll()
            CrashReports.stop() // clean exit: nothing to report next time
            runCatching { BurstProxy.dropAll() } // the films' temporary reserve on disk
        }
        kotlin.system.exitProcess(code)
    }

    private interface Kernel32 : Library {
        fun GetCurrentProcess(): Pointer
        fun TerminateProcess(process: Pointer, code: Int): Boolean
    }

    /** Ends the process without waiting for anything (Windows: TerminateProcess; elsewhere: halt). */
    private fun terminateNow(code: Int) {
        runCatching {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
                val k = Native.load("kernel32", Kernel32::class.java)
                k.TerminateProcess(k.GetCurrentProcess(), code)
            }
        }
        Runtime.getRuntime().halt(code)
    }
}
