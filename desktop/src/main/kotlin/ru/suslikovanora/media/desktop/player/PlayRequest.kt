package ru.suslikovanora.media.desktop.player

/** One stream to play plus everything the library knows about it. */
data class PlayRequest(
    val url: String,
    val title: String,
    val playlist: String?,        // signed .m3u of this episode and the following ones (mirror only)
    val subUrls: List<String>,    // subtitle files that lay next to the video in the torrent
    val watchKeys: List<String>,  // "magnetId|path" for this and the following episodes (progress in the library)
    val audioUrls: List<String> = emptyList(), // external voice-overs (.mka/.ac3…) next to the video
    /** Playlist episode index -> (voice-overs, subtitles) of that episode. */
    val sidecars: (suspend (Int) -> Pair<List<String>, List<String>>)? = null,
    /** «С начала» chosen in the play dialog: ignore the remembered place this time. */
    val fromStart: Boolean = false,
    /** Poster lookups of the film (LibraryItem.posterQueries): its poster goes to the Windows media controls. */
    val posterQueries: List<String> = emptyList(),
    /** This episode's place in [playlist] (the whole season is there, so «previous» works); [url] and the side files are its. */
    val startIndex: Int = 0,
    /** Discord: the library's title and the episode of each [playlist] entry (one for a single file); empty — [title]. */
    val presence: List<Presence> = emptyList(),
    /** Предпросмотр озвучки до привязки: эта дорожка выбирается сама, задержка из плеера уходит в [onPreviewDelay]. */
    val preview: ru.suslikovanora.media.core.audio.CustomTrack? = null,
    val onPreviewDelay: ((Long) -> Unit)? = null,
    /** The [playlist] is the films of a collection: the end-credits card says «Следующий фильм». */
    val collection: Boolean = false,
    /** Per [playlist] entry (one for a single file): what to ask the server for the intro and credits; null — nothing. */
    val markerQueries: List<MarkerQuery?> = emptyList()
)

/** An episode (or, [episode] null, a film of a collection) for `GET /api/markers` (core.MarkersDetector.serverPath). */
data class MarkerQuery(val title: String?, val alt: String?, val season: Int?, val episode: Int?)

/** What Discord shows: the title from the library (the AI's one once accepted), not the torrent's file name, and the episode. */
data class Presence(val title: String, val episode: String? = null)
