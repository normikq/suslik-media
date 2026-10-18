package ru.suslikovanora.media.desktop

import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * [resize] in the debug journal (Suslik Debug only): how far the surfaces lag behind the window while it is resized.
 *
 * Every surface reports its size when it changes (the window's content pane, the Skia layer, the Compose root, the
 * video canvas, the two overlay windows). After 0.5 s of quiet the burst is summarised: per surface, the share of the
 * burst in which its size differed from the window's, the longest such stretch and the largest gap in pixels. A gap
 * is what the viewer sees as a black or empty strip, so this says which surface is late: Skia/Compose (the menu is
 * affected too) or only the player's separate windows.
 */
object ResizeLog {
    private class Ev(val at: Long, val src: String, val w: Int, val h: Int)

    private const val WINDOW = "window"
    private const val QUIET_MS = 500L

    private val events = ArrayList<Ev>()
    private val watched = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Component, Boolean>())
    private val pool = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "resize-log").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    private var headerWritten = false

    /** [src] now has the size [w]×[h]. [WINDOW] is the reference the others are compared with. */
    fun surface(src: String, w: Int, h: Int) {
        if (!Diag.on) return
        synchronized(events) {
            events += Ev(System.nanoTime(), src, w, h)
            pending?.cancel(false)
            pending = pool.schedule({ runCatching { flush() } }, QUIET_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Reports every size change of [c] under the name [src] (once per component). */
    fun watch(c: Component?, src: String) {
        if (!Diag.on || c == null) return
        if (!synchronized(watched) { watched.add(c) }) return
        c.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = surface(src, c.width, c.height)
        })
    }

    /** Finds the Compose (Skia) layer under [root] and reports its size as "skia". */
    fun watchSkia(root: java.awt.Container?) {
        if (!Diag.on) return
        fun find(c: java.awt.Container?): Component? {
            for (k in c?.components ?: return null) {
                var t: Class<*>? = k.javaClass
                while (t != null) { if (t.name == "org.jetbrains.skiko.SkiaLayer") return k; t = t.superclass }
                if (k is java.awt.Container) find(k)?.let { return it }
            }
            return null
        }
        watch(find(root), "skia")
    }

    private fun flush() {
        val list = synchronized(events) { val l = ArrayList(events); events.clear(); l }
        val windowEvents = list.count { it.src == WINDOW }
        if (windowEvents == 0 || list.size < 3) return
        if (!headerWritten) {
            headerWritten = true
            PlayerLog.write("[resize] renderApi=${System.getProperty("skiko.renderApi")} " +
                "noerasebackground=${System.getProperty("sun.awt.noerasebackground")} " +
                "syncLiveResize=${System.getProperty("skiko.rendering.windows.direct3DSynchronousLiveResize")}")
        }
        val t0 = list.first().at
        val t1 = list.last().at
        val counts = list.groupBy { it.src }.entries.joinToString(" | ") { "${it.key} ${it.value.size}" }
        PlayerLog.write("[resize] burst %.1f s | %s".format((t1 - t0) / 1e9, counts))

        for (src in list.map { it.src }.distinct().filter { it != WINDOW }) {
            var win: Ev? = null
            var cur: Ev? = null
            var behindSince = -1L
            var behindTotal = 0L
            var longest = 0L
            var worstW = 0
            var worstH = 0
            fun inStep() = win == null || cur == null || (win!!.w == cur!!.w && win!!.h == cur!!.h)
            for (e in list) {
                val before = inStep()
                if (e.src == WINDOW) win = e else if (e.src == src) cur = e else continue
                val after = inStep()
                if (before && !after) behindSince = e.at
                if (!before && after && behindSince >= 0) {
                    val d = e.at - behindSince; behindTotal += d; longest = maxOf(longest, d); behindSince = -1
                }
                if (!after) {
                    worstW = maxOf(worstW, abs(win!!.w - cur!!.w)); worstH = maxOf(worstH, abs(win!!.h - cur!!.h))
                }
            }
            val endedBehind = !inStep()
            if (endedBehind && behindSince >= 0) { val d = t1 - behindSince; behindTotal += d; longest = maxOf(longest, d) }
            val share = if (t1 > t0) behindTotal * 100 / (t1 - t0) else 0
            PlayerLog.write("[resize] %s vs window: out of step %d%% of the burst, longest %d ms, worst gap %dx%d px%s".format(
                src, share, longest / 1_000_000, worstW, worstH,
                if (endedBehind) ", ENDED OUT OF STEP (${cur!!.w}x${cur!!.h} vs ${win!!.w}x${win!!.h})" else ""))
        }
    }
}
