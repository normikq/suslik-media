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
        fun GetWindowPlacement(hwnd: Pointer, lpwndpl: WINDOWPLACEMENT): Boolean
        fun SetWindowPlacement(hwnd: Pointer, lpwndpl: WINDOWPLACEMENT): Boolean
        fun MonitorFromWindow(hwnd: Pointer, dwFlags: Int): Pointer?
        fun GetMonitorInfoW(hMonitor: Pointer, lpmi: MONITORINFO): Boolean
        fun SetWindowPos(hwnd: Pointer, hWndInsertAfter: Pointer?, x: Int, y: Int, cx: Int, cy: Int, uFlags: Int): Boolean
        fun SetForegroundWindow(hwnd: Pointer): Boolean
        fun EnableWindow(hwnd: Pointer, bEnable: Boolean): Boolean
        fun EnumChildWindows(hWndParent: Pointer, lpEnumFunc: WNDENUMPROC, lParam: Pointer?): Boolean
        fun SetFocus(hwnd: Pointer): Pointer?
    }

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

        val user32 = User32.INSTANCE
        val hwnd = if (isWindows) runCatching { Native.getWindowPointer(win) }.getOrNull() else null

        if (user32 != null && hwnd != null) {
            runCatching {
                if (enable) {
                    val wp = User32.WINDOWPLACEMENT()
                    user32.GetWindowPlacement(hwnd, wp)
                    savedPlacement = wp
                    savedStyle = user32.GetWindowLongW(hwnd, GWL_STYLE)

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
                        prevPlacement.showCmd = if (prevPlacement.showCmd == 3) 3 else 1
                        user32.SetWindowPlacement(hwnd, prevPlacement)
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

            // Ищем внутренний слой Compose (SkiaLayer или внутренний Canvas), исключая наш VLC canvas
            fun findFocusable(c: java.awt.Container): java.awt.Component? {
                for (comp in c.components) {
                    if (!comp.isFocusable) continue
                    if (comp is java.awt.Container) {
                        val found = findFocusable(comp)
                        if (found != null) return found
                    }
                    return comp
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
