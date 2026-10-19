package ru.suslikovanora.media.desktop.ui

import ru.suslikovanora.media.desktop.Store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * «Упрощённые анимации» (Настройки → Анимации) for weak machines: the glow behind the window is painted once and
 * only blitted, glass panels are opaque, tiles appear / move without animation, the library does not fade behind the
 * player, pages switch without sliding, the logo does not pulse.
 */
object Motion {
    private val store = Store("ui")

    var simple by mutableStateOf(store.get("simple_motion") == "1")
        private set

    fun set(on: Boolean) { simple = on; store.put("simple_motion", if (on) "1" else null) }
}
