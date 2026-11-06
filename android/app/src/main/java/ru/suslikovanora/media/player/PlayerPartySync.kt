package ru.suslikovanora.media.player

import android.os.Handler
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.clockText
import ru.suslikovanora.media.core.party.PartyFileRef
import ru.suslikovanora.media.core.party.PartyLink
import ru.suslikovanora.media.core.party.PartyNotice
import ru.suslikovanora.media.core.party.PartyState
import ru.suslikovanora.media.core.party.PartySyncEngine
import ru.suslikovanora.media.core.party.SyncPlayer
import ru.suslikovanora.media.party.Party
import ru.suslikovanora.media.tr

/** The room for the shared sync engine. */
private object AndroidPartyLink : PartyLink {
    override val state: PartyState get() = Party.state
    override val canControl get() = Party.canControl
    override val canSeek get() = Party.canSeek
    override fun targetMs() = Party.targetMs()
    override fun othersBuffering() = Party.othersBuffering()
    override fun takeStartedByMe(key: String): Boolean {
        val started = Party.startedByMe ?: return false
        // The engine ticks with the active quality key, putFilm remembers the watch key: both mean the same film
        if (started != key && Party.file?.let { it.key == started && it.variants.any { v -> v.key == key } } != true) return false
        Party.startedByMe = null
        return true
    }
    override fun play(posMs: Long) = Party.play(posMs)
    override fun pause(posMs: Long) = Party.pause(posMs)
    override fun seek(posMs: Long) = Party.seek(posMs)
    override fun reportBuffering(loading: Boolean) = Party.reportBuffering(loading)
}

/**
 * Keeps [PlayerActivity][ru.suslikovanora.media.PlayerActivity] in the room's film: the loop is the shared
 * [PartySyncEngine] (the PC port is `desktop-qt/core/src/Party.cpp`), ticked every 400 ms on the main thread.
 * Works only while the player shows the room's film; otherwise it does nothing (and tells the room «not loading»).
 */
class PlayerPartySync(private val handler: Handler, private val cb: Callback) {
    interface Callback {
        /** Watch key (`magnetId|path`) of what is playing, null — not from the library. */
        fun watchKey(): String?
        /** Active variant key (`magnetId|path`) if the viewer switched quality, else null. */
        fun activeVariantKey(): String? = null
        /** Canonical ID of the current film/series, if known. */
        fun canonicalId(): String? = null
        /** The playlist's watch keys: index = the playlist's entry. */
        fun keys(): List<String>
        fun title(): String
        /** The film is open and on screen (not opening the next episode's tracks, not parked behind the lock screen). */
        fun ready(): Boolean
        /** The playlist's entries are known: another episode can be opened. */
        fun canPlayIndex(): Boolean
        fun playIndex(i: Int)
        fun notice(text: String)
        /** Called every tick: the player's own look for a guest («● Вместе», locked seeking). */
        fun onTick()
        /** The host closed the room's film: a guest's player closes too. */
        fun close()
        fun variantsFor(key: String): List<ru.suslikovanora.media.core.party.PartyVariant> = emptyList()
    }

    private val engine = PartySyncEngine(AndroidPartyLink, log = { AppLog.write(it) }, notice = { n -> noticeText(n)?.let(cb::notice) })
    private var player: SyncPlayer? = null
    private var lastKey: String? = null
    private var switchedTo: String? = null
    private var episodeNotice: String? = null
    private var running = false
    /** This player showed the room's film (a guest's player closes when the host closes it). */
    private var showedRoomFilm = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            handler.postDelayed(this, PartySyncEngine.TICK_MS)
        }
    }

    fun start(p: SyncPlayer) {
        if (running) return
        player = p
        running = true
        Party.watching++
        handler.post(tick)
    }

    /** The player closes: when the host closes the room's film, the room pauses (nobody would be watching it). */
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        Party.watching--
        Party.playingKeys = emptyList()
        player?.let { engine.idle(it) }
        if (Party.isHost && lastKey != null && Party.file?.matches(lastKey, cb.canonicalId()) == true) Party.closeIfNobodyWatches()
    }

    /** The host's player went behind the lock screen / another app: the room waits for them on pause. */
    fun hostAway(posMs: Long) {
        val activeKey = cb.activeVariantKey()?.ifBlank { null } ?: cb.watchKey()
        if (Party.isHost && Party.file?.matches(activeKey, cb.canonicalId()) == true && Party.state.playing) {
            AppLog.write("[party] host left the player: pause")
            Party.pause(posMs)
        }
    }

    private fun findEpisodeIndex(keys: List<String>, f: PartyFileRef): Int = f.resolveIndex(keys, cb.canonicalId())

    private fun step() {
        val p = player ?: return
        val key = cb.watchKey()
        val activeKey = cb.activeVariantKey()?.ifBlank { null } ?: key
        val cid = cb.canonicalId()
        val keys = cb.keys()
        if (keys != Party.playingKeys) Party.playingKeys = keys
        // The host went on to another episode: the room follows
        if (key != lastKey) {
            lastKey = key
            val f = Party.file
            if (f != null && key != null && !f.matches(key, cid) && Party.isHost && (f.key in keys || findEpisodeIndex(keys, f) >= 0)) {
                val nextVariants = cb.variantsFor(key)
                Party.putFilm(PartyFileRef(key, cb.title(), canonicalId = cid, variants = nextVariants))
                val curVar = cb.activeVariantKey()?.ifBlank { null }
                if (curVar != null && curVar != key) {
                    Party.sendVariantChange(curVar)
                }
            }
        }
        // The room's episode changed (the host picked another one): this player follows, once per change
        val rf = Party.file
        val rk = rf?.key
        val roomEpIdx = if (rf != null) findEpisodeIndex(keys, rf) else -1
        // The guest's player follows the room's file in its playlist: MainActivity must not open it again after the
        // player closes (it does not recompose while the player is in front, and playingKeys are empty then)
        if (!Party.isHost && rk != null && roomEpIdx >= 0 && Party.followed != rk) Party.followed = rk
        // The host closed the film (the room has none now): a guest who watched it with them closes it too
        val matchesFilm = (activeKey != null && rf?.matches(activeKey, cid) == true) || (key != null && rf?.matches(key, cid) == true)
        if (Party.inRoom && matchesFilm) showedRoomFilm = true
        else if (showedRoomFilm && !Party.isHost && Party.inRoom && rk != null && roomEpIdx < 0 && !matchesFilm && cb.ready() && cb.canPlayIndex()) {
            // The host put a film that is not in this playlist (another season, another film): this player cannot follow
            // it in place, and MainActivity behind it does not recompose while the player is in front. The guest's player
            // closes, and MainActivity opens the host's film at the room's place (as for any film the host puts)
            showedRoomFilm = false
            AppLog.write("[party] the room's film is not in this playlist: closing the player to follow the host")
            cb.close()
            return
        }
        else if (!Party.inRoom || rk != null) showedRoomFilm = false
        else if (showedRoomFilm && !Party.isHost) {
            showedRoomFilm = false
            AppLog.write("[party] host closed the film: closing the player")
            cb.close()
            return
        }
        if (rk == null || matchesFilm) switchedTo = null
        else if (Party.inRoom && switchedTo != rk && cb.ready() && cb.canPlayIndex()) {
            val i = roomEpIdx
            if (i >= 0) {
                switchedTo = rk
                AppLog.write("[party] room episode -> $i")
                cb.playIndex(i)
                if (!Party.isHost) episodeNotice = tr("party.vklyuchaet", "who" to hostWho(), "title" to Party.file?.title.orEmpty())
            }
        }
        cb.onTick()
        if (Party.inRoom && matchesFilm && activeKey != null && cb.ready()) {
            episodeNotice?.let { episodeNotice = null; cb.notice(it) }
            engine.tick(p, activeKey, System.currentTimeMillis())
        } else engine.idle(p)
    }

    private fun hostWho() = Party.hostName.ifBlank { tr("party.sozdatel_komnaty") }

    /** Present tense: a name says nothing of a person's gender, «ставит» fits everyone. */
    private fun noticeText(n: PartyNotice): String? = when (n) {
        PartyNotice.Paused -> tr("party.stavit_pauzu", "who" to hostWho())
        PartyNotice.Resumed -> tr("party.prodolzhaet", "who" to hostWho())
        is PartyNotice.Seeked -> tr("party.peremativaet_na", "who" to hostWho(), "time" to clockText(n.posMs))
        PartyNotice.Waiting -> Party.members.filter { it.buffering && it.key != Party.myKey }.map { it.name }
            .takeIf { it.isNotEmpty() }?.let { tr("party.zhdyom_zagruzku", "names" to it.joinToString()) }
    }
}
