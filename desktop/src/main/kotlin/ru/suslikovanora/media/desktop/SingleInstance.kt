package ru.suslikovanora.media.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

/**
 * Only one Suslik Media at a time. A second start (double click on the shortcut, the installer's "launch" while the
 * app is open) does not open a second window: it asks the running app to come to the front and quits.
 *
 *  - an OS file lock in the app's folder says "one is running" (released by the OS even after a crash);
 *  - the running app listens on a local port (127.0.0.1 only) written next to the lock; the second start sends "show".
 */
object SingleInstance {
    private var lock: FileLock? = null
    private var server: ServerSocket? = null
    /** Set by the window: bring it to the front (called on the UI thread). */
    @Volatile var onShow: (() -> Unit)? = null
    @Volatile private var pendingShow = false

    /** true = this is the only instance (keep going); false = another one is running and was asked to show itself. */
    fun acquire(): Boolean {
        val dir = AppDir.dir
        val portFile = File(dir, "instance.port")
        val attempt = runCatching {
            val ch = RandomAccessFile(File(dir, "instance.lock"), "rw").channel
            ch.tryLock().also { if (it == null) ch.close() }
        }
        // The lock itself failed (folder not writable…): never refuse to start because of that.
        if (attempt.isFailure) return true
        val got = attempt.getOrNull()
        if (got == null) {
            // Another instance holds the lock: wake it up. Its port may still be being written — a few tries.
            repeat(10) {
                val port = runCatching { portFile.readText().trim().toInt() }.getOrNull()
                if (port != null && runCatching {
                        Socket(InetAddress.getLoopbackAddress(), port).use { s -> s.soTimeout = 1500; s.getOutputStream().write("show\n".toByteArray()) }
                    }.isSuccess) return false
                Thread.sleep(200)
            }
            return false
        }
        lock = got
        runCatching {
            val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            server = s
            portFile.writeText(s.localPort.toString())
            Thread({
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    runCatching {
                        c.use { it.soTimeout = 2000; it.getInputStream().bufferedReader().readLine() }
                    }.getOrNull()?.takeIf { it == "show" }?.let {
                        val f = onShow
                        if (f != null) javax.swing.SwingUtilities.invokeLater(f) else pendingShow = true
                    }
                }
            }, "single-instance").apply { isDaemon = true; start() }
        }
        return true
    }

    /** The window registers itself; a "show" that came before the window existed is applied now. */
    fun register(show: () -> Unit) {
        onShow = show
        if (pendingShow) { pendingShow = false; javax.swing.SwingUtilities.invokeLater(show) }
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release(); lock?.channel()?.close() }
    }
}
