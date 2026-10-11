package ru.suslikovanora.media

import ru.suslikovanora.media.core.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Is the app's main screen on screen? Download-status polling waits here: nothing is asked over the network while the
 * app is in the background (or a film is playing), and coming back wakes every poll at once with a fresh check.
 */
object Foreground {
    val visible = MutableStateFlow(true)

    /** Pause between two polls: [ms] normally; in the background — until the app is back (then immediately). */
    suspend fun pause(ms: Long) {
        // Went to the background and came back during the pause: no need to wait the rest of it.
        withTimeoutOrNull(ms) { visible.first { !it }; visible.first { it } }
        if (!visible.value) visible.first { it }
    }
}
