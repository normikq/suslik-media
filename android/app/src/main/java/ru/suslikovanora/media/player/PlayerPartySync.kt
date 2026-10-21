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
        if (Party.startedByMe != key) return false
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
 * [PartySyncEngine] (the PC uses the same one, `desktop/party/PartySync.kt`), ticked every 400 ms on the main thread.
 * Works only while the player shows the room's film; otherwise it does nothing (and tells the room «not loading»).
 */
class PlayerPartySync(private val handler: Handler, private val cb: Callback) {
    interface Callback {
        /** Watch key (`magnetId|path`) of what is playing, null — not from the library. */
        fun watchKey(): String?
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
    }

    private val engine = PartySyncEngine(AndroidPartyLink, log = { AppLog.write(it) }, notice = { n -> noticeText(n)?.let(cb::notice) })
    private var player: SyncPlayer? = null
    private var lastKey: String? = null
    private var switchedTo: String? = null
    private var episodeNotice: String? = null
    private var running = false

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
        player?.let { engine.idle(it) }
        if (Party.isHost && Party.file?.key == lastKey) Party.pauseIfNobodyWatches(engine.lastTimeMs)
    }

    /** The host's player went behind the lock screen / another app: the room waits for them on pause. */
    fun hostAway(posMs: Long) {
        if (Party.isHost && Party.file?.key == cb.watchKey() && Party.state.playing) {
            AppLog.write("[party] host left the player: pause")
            Party.pause(posMs)
        }
    }

    private fun step() {
        val p = player ?: return
        val key = cb.watchKey()
        val keys = cb.keys()
        // The host went on to another episode: the room follows
        if (key != lastKey) {
            lastKey = key
            val f = Party.file
            if (f != null && key != null && key != f.key && Party.isHost && f.key in keys) Party.putFilm(PartyFileRef(key, cb.title()))
        }
        // The room's episode changed (the host picked another one): this player follows, once per change
        val rk = Party.file?.key
        if (rk == null || rk == key) switchedTo = null
        else if (Party.inRoom && switchedTo != rk && cb.ready() && cb.canPlayIndex()) {
            val i = keys.indexOf(rk)
            if (i >= 0) {
                switchedTo = rk
                AppLog.write("[party] room episode -> $i")
                cb.playIndex(i)
                if (!Party.isHost) episodeNotice = tr("party.vklyuchaet", "who" to hostWho(), "title" to Party.file?.title.orEmpty())
            }
        }
        cb.onTick()
        if (Party.inRoom && rk == key && key != null && cb.ready()) {
            episodeNotice?.let { episodeNotice = null; cb.notice(it) }
            engine.tick(p, key, System.currentTimeMillis())
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
