package ru.suslikovanora.media.player

import ru.suslikovanora.media.core.audio.CustomAudioIndex

/**
 * Voice-overs uploaded from other torrents (/api/custom-audio): their name for the menu and the delay
 * (`offsetMs`, same sign as on the PC: + — the sound starts later, − — its start is skipped). From [PlayerExtras.CUSTOM_AUDIO].
 * The index itself is shared with the PC ([CustomAudioIndex]).
 */
val CustomAudioInfo = CustomAudioIndex()
