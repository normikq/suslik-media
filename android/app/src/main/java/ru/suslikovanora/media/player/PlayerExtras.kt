package ru.suslikovanora.media.player

/** What the app hands the player in its Intent: one list for [ru.suslikovanora.media.PlayerActivity] and its helpers. */
object PlayerExtras {
    const val URL = "media_url"
    const val TITLE = "media_title"
    const val PLAYLIST = "media_playlist"
    const val FROM_START = "media_from_start"
    const val SUB_URLS = "media_sub_urls"
    const val AUDIO_URLS = "media_audio_urls"
    const val WATCH_KEYS = "media_watch_keys"
    /** Watch key of the episode the player opens with (the playlist is the whole season, it may be in the middle). */
    const val WATCH_KEY = "media_watch_key"
    /** The playlist's other episodes' subtitles and voice-overs (JSON from the play sheet), signed when each starts. */
    const val EPISODE_SIDES = "media_episode_sides"
    /** Uploaded voice-overs of the film and its playlist: name and delay by signed link (see [CustomAudioInfo]). */
    const val CUSTOM_AUDIO = "media_custom_audio"
    /** The playlist is the films of a collection (shared isCollection), not episodes: the panel says «Фильмы». */
    const val COLLECTION = "media_collection"
    /**
     * Intro and credits from the server (core.MarkersDetector.serverPath): JSON array, one per [WATCH_KEYS] entry —
     * `{title, alt, season, episode}` of a series episode, or null.
     */
    const val MARKERS = "media_markers"
}
