package ru.suslikovanora.media.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Library as JnaLibrary
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Frame
import java.awt.Window

/**
 * Native Windows borderless fullscreen controller and window geometry coordinator.
 *
 * Avoids AWT Full-Screen Exclusive Mode (GraphicsDevice.setFullScreenWindow) which breaks whenever
 * transparent ComposeDialog overlays (topDialog / bottomDialog) are shown or hidden.
 */
object WindowMode {
    var isFullscreen by mutableStateOf(false)
        private set

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private interface User32 : JnaLibrary {
        companion object {
            val INSTANCE: User32? = if (!isWindows) null else runCatching {
                Native.load("user32", User32::class.java)
            }.getOrNull()
        }

        @Structure.FieldOrder("left", "top", "right", "bottom")
        class RECT : Structure() {
            @JvmField var left: Int = 0
            @JvmField var top: Int = 0
            @JvmField var right: Int = 0
            @JvmField var bottom: Int = 0
        }

        @Structure.FieldOrder("x", "y")
        class POINT : Structure() {
            @JvmField var x: Int = 0
            @JvmField var y: Int = 0
        }

        @Structure.FieldOrder("length", "flags", "showCmd", "ptMinPosition", "ptMaxPosition", "rcNormalPosition")
        class WINDOWPLACEMENT : Structure() {
            @JvmField var length: Int = 0
            @JvmField var flags: Int = 0
            @JvmField var showCmd: Int = 0
            @JvmField var ptMinPosition: POINT = POINT()
            @JvmField var ptMaxPosition: POINT = POINT()
            @JvmField var rcNormalPosition: RECT = RECT()

            init { length = size() }
        }

        @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
        class MONITORINFO : Structure() {
            @JvmField var cbSize: Int = 0
            @JvmField var rcMonitor: RECT = RECT()
            @JvmField var rcWork: RECT = RECT()
            @JvmField var dwFlags: Int = 0

            init { cbSize = size() }
        }

        fun interface WNDENUMPROC : com.sun.jna.win32.StdCallLibrary.StdCallCallback {
            fun callback(hwnd: Pointer, lParam: Pointer?): Boolean
        }

        fun GetWindowLongW(hwnd: Pointer, nIndex: Int): Int
        fun SetWindowLongW(hwnd: Pointer, nIndex: Int, dwNewLong: Int): Int
        fun GetWindowRect(hwnd: Pointer, rect: RECT): Boolean
        fun GetWindowPlacement(hwnd: Pointer, lpwndpl: WINDOWPLACEMENT): Boolean
        fun SetWindowPlacement(hwnd: Pointer, lpwndpl: WINDOWPLACEMENT): Boolean
        fun MonitorFromWindow(hwnd: Pointer, dwFlags: Int): Pointer?
        fun GetMonitorInfoW(hMonitor: Pointer, lpmi: MONITORINFO): Boolean
        fun SetWindowPos(hwnd: Pointer, hWndInsertAfter: Pointer?, x: Int, y: Int, cx: Int, cy: Int, uFlags: Int): Boolean
        fun SetForegroundWindow(hwnd: Pointer): Boolean
        fun EnableWindow(hwnd: Pointer, bEnable: Boolean): Boolean
        fun EnumChildWindows(hWndParent: Pointer, lpEnumFunc: WNDENUMPROC, lParam: Pointer?): Boolean
        fun SetFocus(hwnd: Pointer): Pointer?
        fun SetWindowRgn(hwnd: Pointer, hRgn: Pointer?, bRedraw: Boolean): Int
    }

    private interface Gdi32 : JnaLibrary {
        companion object {
            val INSTANCE: Gdi32? = if (!isWindows) null else runCatching {
                Native.load("gdi32", Gdi32::class.java)
            }.getOrNull()
        }

        fun CreateRectRgn(x1: Int, y1: Int, x2: Int, y2: Int): Pointer?
    }

    /**
     * Оставляет у окна «живой» (рисуемый и принимающий мышь) только нижний прямоугольник высотой [visibleDip];
     * остальное окно не перехватывает клики. Размер окна не меняется, поэтому Compose ничего не перестраивает.
     */
    fun clipToBottom(win: Window, visibleDip: Int) {
        if (!isWindows || !win.isDisplayable) return
        val user32 = User32.INSTANCE ?: return
        val gdi = Gdi32.INSTANCE ?: return
        runCatching {
            val hwnd = Native.getWindowPointer(win) ?: return
            val tr = win.graphicsConfiguration?.defaultTransform
            val sx = tr?.scaleX ?: 1.0
            val sy = tr?.scaleY ?: 1.0
            val w = kotlin.math.ceil(win.width * sx).toInt() + 2
            val h = kotlin.math.ceil(win.height * sy).toInt()
            val vis = kotlin.math.ceil(visibleDip * sy).toInt().coerceIn(0, h)
            val rgn = gdi.CreateRectRgn(0, h - vis, w, h + 2)
            user32.SetWindowRgn(hwnd, rgn, true)
        }
    }

    private var lastToggleAt = 0L
    private var savedStyle = 0
    private var savedPlacement: User32.WINDOWPLACEMENT? = null

    private const val GWL_STYLE = -16
    private const val WS_DISABLED = 0x08000000
    private const val WS_POPUP = 0x80000000.toInt()
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val WS_SYSMENU = 0x00080000

    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SWP_NOOWNERZORDER = 0x0200
    private const val SWP_SHOWWINDOW = 0x0040
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_ASYNCWINDOWPOS = 0x4000

    private val HWND_TOP = Pointer.createConstant(0)
    private val HWND_NOTOPMOST = Pointer.createConstant(-2)
    private val HWND_TOPMOST = Pointer.createConstant(-1)

    var currentWindow: Window? = null

    fun toggleFullscreen(window: Window? = null) {
        val target = window ?: currentWindow ?: runCatching { Frame.getFrames().firstOrNull { it.isShowing } }.getOrNull()
        setFullscreen(target, !isFullscreen)
    }

    fun setFullscreen(window: Window? = null, enable: Boolean) {
        val win = window ?: currentWindow ?: runCatching { Frame.getFrames().firstOrNull { it.isShowing } }.getOrNull() ?: return
        currentWindow = win
        if (enable == isFullscreen) return
        // Быстрые повторы F11: окно ещё перестраивается, и новая смена режима запоминала «промежуточное» состояние
        // (без рамки, размером с экран) как обычное — после выхода окно вылезало за экран. Пропускаем такие нажатия.
        val now = System.nanoTime()
        if (now - lastToggleAt < 450_000_000L) return
        lastToggleAt = now

        val user32 = User32.INSTANCE
        val hwnd = if (isWindows) runCatching { Native.getWindowPointer(win) }.getOrNull() else null

        if (user32 != null && hwnd != null) {
            runCatching {
                if (enable) {
                    val curStyle = user32.GetWindowLongW(hwnd, GWL_STYLE)
                    // Запоминаем только настоящее оконное состояние (с рамкой), а не уже полноэкранное
                    if (curStyle and WS_CAPTION == WS_CAPTION || savedPlacement == null) {
                        val wp = User32.WINDOWPLACEMENT()
                        user32.GetWindowPlacement(hwnd, wp)
                        savedPlacement = wp
                        savedStyle = curStyle
                    }

                    val hMon = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)
                    if (hMon != null) {
                        val mi = User32.MONITORINFO()
                        user32.GetMonitorInfoW(hMon, mi)

                        // 1. Убираем только рамки, НЕ добавляя WS_POPUP (чтобы не ломать DWM и AWT Insets)
                        val borderStyles = WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU
                        val newStyle = savedStyle and borderStyles.inv()
                        user32.SetWindowLongW(hwnd, GWL_STYLE, newStyle)

                        TitleBar.setFullscreenDwm(win)

                        val m = mi.rcMonitor
                        val monW = m.right - m.left
                        // 2. +1 пиксель по высоте запрещает драйверу OpenGL/DWM уходить в Exclusive Fullscreen (убирает белый экран)
                        val monH = (m.bottom - m.top) + 1

                        user32.SetWindowPos(
                            hwnd, HWND_TOP,
                            m.left, m.top, monW, monH,
                            SWP_FRAMECHANGED or SWP_NOOWNERZORDER or SWP_SHOWWINDOW
                        )
                        isFullscreen = true
                    }
                } else {
                    val prevStyle = savedStyle
                    val prevPlacement = savedPlacement
                    if (prevStyle != 0 && prevPlacement != null) {
                        TitleBar.style(win)
                        user32.SetWindowLongW(hwnd, GWL_STYLE, prevStyle)
                        val wasMaximized = prevPlacement.showCmd == 3
                        // Для Windows окно, открытое на весь экран, всё ещё «развёрнуто» (мы меняли только размер), и повторное
                        // «развернуть» ничего не пересчитывает: окно с рамкой оставалось размером с монитор и уходило за экран.
                        // Сначала возвращаем обычный размер, затем разворачиваем заново.
                        prevPlacement.showCmd = 1
                        user32.SetWindowPlacement(hwnd, prevPlacement)
                        if (wasMaximized) {
                            prevPlacement.showCmd = 3
                            user32.SetWindowPlacement(hwnd, prevPlacement)
                        }
                        user32.SetWindowPos(
                            hwnd, if (win.isAlwaysOnTop) HWND_TOPMOST else HWND_NOTOPMOST,
                            0, 0, 0, 0,
                            SWP_NOMOVE or SWP_NOSIZE or SWP_FRAMECHANGED or SWP_SHOWWINDOW
                        )
                    }
                    isFullscreen = false
                }

                // Заставляем AWT Peer синхронизировать новые размеры и сбросить старые Insets (шапку и рамки)
                javax.swing.SwingUtilities.invokeLater {
                    win.invalidate()
                    win.validate()
                    (win as? javax.swing.RootPaneContainer)?.apply {
                        contentPane.invalidate()
                        contentPane.validate()
                        rootPane.revalidate()
                    }
                    win.repaint()
                    restoreComposeFocus(win)
                }
            }
        } else {
            // Fallback for non-Windows
            isFullscreen = enable
            if (win is Frame) {
                if (enable) {
                    win.extendedState = Frame.MAXIMIZED_BOTH
                } else {
                    win.extendedState = Frame.NORMAL
                }
            }
            restoreComposeFocus(win)
        }
    }

    var isMini by mutableStateOf(false)
        private set

    var miniAnimating by mutableStateOf(false)
        private set

    private var savedMiniStyle = 0
    private var savedMiniPlacement: User32.WINDOWPLACEMENT? = null

    /**
     * Мини-плеер: только нативные вызовы (как в полноэкранном режиме), состояние Compose-окна не трогаем —
     * иначе Compose и Win32 спорят за размер и окно «ломается». Размер [miniW]×[miniH] задаётся в dip, угол —
     * правый нижний рабочей области монитора, где сейчас окно (физические пиксели, поэтому учитываем масштаб DPI).
     */
    fun setMiniMode(window: Window? = null, enable: Boolean, miniW: Int = 560, miniH: Int = 315, onFinished: (() -> Unit)? = null) {
        val win = window ?: currentWindow ?: runCatching { Frame.getFrames().firstOrNull { it.isShowing } }.getOrNull() ?: return
        currentWindow = win
        if (enable == isMini) return

        val user32 = User32.INSTANCE
        val hwnd = if (isWindows) runCatching { Native.getWindowPointer(win) }.getOrNull() else null

        if (user32 != null && hwnd != null) {
            runCatching {
                if (enable) {
                    // Из полного экрана берём стиль и положение, сохранённые при его включении (там уже нет рамки)
                    val fsBefore = isFullscreen
                    if (isFullscreen && savedPlacement != null) {
                        savedMiniStyle = savedStyle
                        savedMiniPlacement = savedPlacement
                        isFullscreen = false
                    } else {
                        savedMiniStyle = user32.GetWindowLongW(hwnd, GWL_STYLE)
                        val saved = User32.WINDOWPLACEMENT()
                        user32.GetWindowPlacement(hwnd, saved)
                        savedMiniPlacement = saved
                    }

                    // Убираем заголовок И толстую рамку (она давала полупрозрачные полосы по контуру)
                    val borderStyles = WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_SYSMENU
                    user32.SetWindowLongW(hwnd, GWL_STYLE, savedMiniStyle and borderStyles.inv())
                    TitleBar.setMiniDwm(win)

                    val scale = win.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
                    val w = (miniW * scale).toInt()
                    val h = (miniH * scale).toInt()
                    val margin = (24 * scale).toInt()
                    // Откуда «улетаем»: текущие границы окна (из полного экрана — весь монитор), в физических пикселях
                    val hMon = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)
                    val mi = User32.MONITORINFO()
                    if (hMon != null) user32.GetMonitorInfoW(hMon, mi)
                    val fromFs = fsBefore
                    val cur = User32.RECT()
                    user32.GetWindowRect(hwnd, cur)
                    var sx = cur.left
                    var sy = cur.top
                    var sw = cur.right - cur.left
                    var sh = cur.bottom - cur.top
                    if (fromFs) {
                        sx = mi.rcMonitor.left; sy = mi.rcMonitor.top
                        sw = mi.rcMonitor.right - mi.rcMonitor.left; sh = mi.rcMonitor.bottom - mi.rcMonitor.top
                    }
                    val x = (mi.rcWork.right - w - margin).coerceAtLeast(mi.rcWork.left)
                    val y = (mi.rcWork.bottom - h - margin).coerceAtLeast(mi.rcWork.top)

                    // Сбрасываем «развёрнуто», иначе Windows держит окно во весь экран, и сразу ставим стартовые границы
                    val wp = User32.WINDOWPLACEMENT()
                    user32.GetWindowPlacement(hwnd, wp)
                    wp.showCmd = 1 // SW_SHOWNORMAL
                    user32.SetWindowPlacement(hwnd, wp)
                    user32.SetWindowPos(hwnd, HWND_TOPMOST, sx, sy, sw, sh, SWP_FRAMECHANGED or SWP_SHOWWINDOW)
                    PlayerLog.write("[ui] mini on: from=$sx,$sy ${sw}x$sh to=$x,$y ${w}x$h scale=$scale")
                    isMini = true
                    miniAnimating = true

                    // Плавный «уезд» в правый нижний угол (ease-out, ~320 мс)
                    // Старт откладываем на один проход очереди событий: пусть сначала отработает перестройка Compose
                    // от mini=true, иначе она попадает в середину полёта и окно «застывает»
                    val dur = 320_000_000.0
                    // Анимацию крутим в отдельном потоке: SetWindowPos безопасен вне EDT, а EDT в это время занят
                    // перестройкой Compose после mini=true (до секунды) — в таймере Swing окно стояло на месте
                    Thread {
                        var t0 = 0L
                        var lastTick = 0L
                        var lastW = -1
                        var lastH = -1
                        while (true) {
                            if (!isMini) { miniAnimating = false; break }
                            val now = System.nanoTime()
                            if (t0 == 0L) t0 = now
                            if (lastTick != 0L && now - lastTick > 100_000_000L) PlayerLog.write("[ui] mini frame gap ${(now - lastTick) / 1_000_000} ms at ${(now - t0) / 1_000_000} ms")
                            lastTick = now
                            val p = ((now - t0) / dur).coerceIn(0.0, 1.0)
                            val e = 1 - (1 - p) * (1 - p) * (1 - p)
                            fun lerp(a: Int, b: Int) = (a + (b - a) * e).toInt()
                            // Размер меняем только когда он сдвинулся заметно: каждая смена размера — перестройка Compose и холста VLC
                            val cw = lerp(sw, w)
                            val ch = lerp(sh, h)
                            val resize = p >= 1.0 || lastW < 0 || kotlin.math.abs(cw - lastW) >= 24 || kotlin.math.abs(ch - lastH) >= 24
                            if (resize) { lastW = cw; lastH = ch }
                            runCatching {
                                // Вызов из чужого потока синхронный (ждёт владельца окна); ASYNCWINDOWPOS — не ждать.
                                // Последний кадр — обычный, чтобы финальный размер точно встал до синхронизации с AWT
                                user32.SetWindowPos(
                                    hwnd, null, lerp(sx, x), lerp(sy, y), cw, ch,
                                    SWP_NOZORDER or SWP_NOACTIVATE or (if (p < 1.0) SWP_ASYNCWINDOWPOS else 0) or (if (resize) 0 else SWP_NOSIZE)
                                )
                            }
                            if (p >= 1.0) {
                                miniAnimating = false
                                PlayerLog.write("[ui] mini done")
                                javax.swing.SwingUtilities.invokeLater {
                                    // Синхронизируем AWT/Compose с нативным размером (в dip), иначе они держат старый большой размер
                                    runCatching {
                                        win.setBounds(Math.round(x / scale).toInt(), Math.round(y / scale).toInt(), miniW, miniH)
                                    }
                                    PlayerLog.write("[ui] mini synced: awt=${win.bounds}")
                                    win.invalidate(); win.validate(); win.repaint()
                                    restoreComposeFocus(win)
                                    onFinished?.invoke()
                                }
                                break
                            }
                            Thread.sleep(8)
                        }
                    }.apply { isDaemon = true; name = "mini-fly"; start() }
                } else {
                    val prevStyle = savedMiniStyle
                    val prev = savedMiniPlacement
                    if (prevStyle != 0 && prev != null) {
                        TitleBar.style(win)
                        user32.SetWindowLongW(hwnd, GWL_STYLE, prevStyle)
                        val wasMaximized = prev.showCmd == 3
                        prev.showCmd = 1
                        user32.SetWindowPlacement(hwnd, prev)
                        if (wasMaximized) {
                            prev.showCmd = 3
                            user32.SetWindowPlacement(hwnd, prev)
                        }
                        user32.SetWindowPos(
                            hwnd, if (win.isAlwaysOnTop) HWND_TOPMOST else HWND_NOTOPMOST,
                            0, 0, 0, 0,
                            SWP_NOMOVE or SWP_NOSIZE or SWP_FRAMECHANGED or SWP_SHOWWINDOW
                        )
                    }
                    isMini = false
                    miniAnimating = false
                    javax.swing.SwingUtilities.invokeLater {
                        win.invalidate()
                        win.validate()
                        (win as? javax.swing.RootPaneContainer)?.apply {
                            contentPane.invalidate()
                            contentPane.validate()
                            rootPane.revalidate()
                        }
                        win.repaint()
                        restoreComposeFocus(win)
                        onFinished?.invoke()
                    }
                }
            }.onFailure { PlayerLog.write("[ui] setMiniMode failed: $it") }
        } else {
            isFullscreen = false
            isMini = enable
        }
    }

    /**
     * Возвращает фокус окну и внутреннему слою Compose (SkiaLayer),
     * игнорируя нативный java.awt.Canvas (VLC), чтобы гарантировать работу hotkeys.
     */
    fun restoreComposeFocus(win: Window? = null) {
        val w = win ?: currentWindow ?: runCatching { Frame.getFrames().firstOrNull { it.isShowing } }.getOrNull() ?: return
        val user32 = User32.INSTANCE
        val hwnd = if (isWindows) runCatching { Native.getWindowPointer(w) }.getOrNull() else null

        javax.swing.SwingUtilities.invokeLater {
            if (hwnd != null && user32 != null) {
                runCatching {
                    user32.SetForegroundWindow(hwnd)
                    user32.SetFocus(hwnd) // Принудительно возвращаем Win32 фокус с HWND плеера на главное окно
                }
            }
            w.requestFocus()

            // Ищем именно слой Skia, который получает клавиши Compose. «Первый фокусируемый» тут нельзя:
            // им оказывалась обёртка SwingPanel с холстом VLC, фокус уходил к ней, и горячие клавиши в плеере молчали.
            fun isSkiaLayer(c: java.awt.Component): Boolean {
                var k: Class<*>? = c.javaClass
                while (k != null) { if (k.name == "org.jetbrains.skiko.SkiaLayer") return true; k = k.superclass }
                return false
            }
            fun findFocusable(c: java.awt.Container): java.awt.Component? {
                for (comp in c.components) {
                    if (isSkiaLayer(comp)) return comp
                    if (comp is java.awt.Container) findFocusable(comp)?.let { return it }
                }
                return null
            }
            val target = (w as? javax.swing.RootPaneContainer)?.contentPane?.let { findFocusable(it) }
            (target ?: w).requestFocusInWindow()
        }
    }

    /**
     * Запрещает холсту VLC и всем его внутренним дочерним окнам (Direct3D/Vout)
     * перехватывать системный фокус клавиатуры и мыши.
     */
    fun disableNativeCanvasInput(canvas: java.awt.Canvas) {
        if (!isWindows) return
        val user32 = User32.INSTANCE ?: return
        runCatching {
            val canvasHwnd = Native.getComponentPointer(canvas) ?: return
            fun disableHwnd(h: Pointer) {
                val style = user32.GetWindowLongW(h, GWL_STYLE)
                user32.SetWindowLongW(h, GWL_STYLE, style or WS_DISABLED)
                user32.EnableWindow(h, false)
            }
            disableHwnd(canvasHwnd)
            user32.EnumChildWindows(canvasHwnd, { child, _ ->
                disableHwnd(child)
                true
            }, null)
        }
    }
}
