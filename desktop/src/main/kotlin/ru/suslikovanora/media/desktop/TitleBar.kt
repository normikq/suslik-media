package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.ui.Sm

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * Paints the native Windows title bar in the app's colours (Windows 11: caption, text and border colour;
 * Windows 10: at least the dark mode). The system bar is kept, so snapping, resizing and the buttons work as usual.
 */
object TitleBar {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    /** 0xRRGGBB -> COLORREF (0x00BBGGRR). */
    private fun ref(rgb: Int) = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)

    fun style(window: Window) {
        val api = dwm ?: return
        runCatching {
            val hwnd = Native.getWindowPointer(window) ?: return
            fun set(attr: Int, v: Int) = api.DwmSetWindowAttribute(hwnd, attr, IntByReference(v), 4)
            set(20, 1)                // DWMWA_USE_IMMERSIVE_DARK_MODE
            set(35, ref(0x060911))    // DWMWA_CAPTION_COLOR  = Sm.backdrop (flows into the window)
            set(36, ref(0x8E96A8))    // DWMWA_TEXT_COLOR     = Sm.muted (title stays quiet)
            set(34, ref(0x0F1420))    // DWMWA_BORDER_COLOR   = barely lighter than the backdrop
            set(33, 0)                // DWMWA_WINDOW_CORNER_PREFERENCE = DWMWCP_DEFAULT
        }
    }

    fun setFullscreenDwm(window: Window) {
        val api = dwm ?: return
        runCatching {
            val hwnd = Native.getWindowPointer(window) ?: return
            fun set(attr: Int, v: Int) = api.DwmSetWindowAttribute(hwnd, attr, IntByReference(v), 4)
            set(20, 1)                // DWMWA_USE_IMMERSIVE_DARK_MODE
            set(34, 0xFFFFFFFE.toInt()) // DWMWA_BORDER_COLOR = DWMWA_COLOR_NONE (no accent border)
            set(33, 1)                // DWMWA_WINDOW_CORNER_PREFERENCE = DWMWCP_DONOTROUND (rectangular)
        }
    }

    fun setMiniDwm(window: Window) {
        val api = dwm ?: return
        runCatching {
            val hwnd = Native.getWindowPointer(window) ?: return
            fun set(attr: Int, v: Int) = api.DwmSetWindowAttribute(hwnd, attr, IntByReference(v), 4)
            set(20, 1)                // DWMWA_USE_IMMERSIVE_DARK_MODE
            set(34, ref(0x283042))    // DWMWA_BORDER_COLOR = subtle border (Sm.line)
            set(33, 2)                // DWMWA_WINDOW_CORNER_PREFERENCE = DWMWCP_ROUND
        }
    }
}
