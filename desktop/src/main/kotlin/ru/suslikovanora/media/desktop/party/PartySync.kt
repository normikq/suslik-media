package ru.suslikovanora.media.desktop.party

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import ru.suslikovanora.media.core.party.PartyFileRef
import ru.suslikovanora.media.core.clockText
import ru.suslikovanora.media.core.party.PartyLink
import ru.suslikovanora.media.core.party.PartyNotice
import ru.suslikovanora.media.core.party.PartyState
import ru.suslikovanora.media.core.party.PartySyncEngine
import ru.suslikovanora.media.core.party.SyncPlayer
import ru.suslikovanora.media.desktop.PlayerLog
import ru.suslikovanora.media.desktop.tr

/** What the player lets the room do with it (PlayerView passes its own functions: the bars and SMTC stay right). */
class SyncHooks(
    val timeMs: () -> Long,              // where the picture is (right after a seek — where it is going)
    val busy: () -> Boolean,             // the user is dragging the timeline: wait for the release
    val engineMs: () -> Long,            // mpv's own place, as is (after a seek: still loading or already there)
    val seekStamp: () -> Long,           // when the player last seeked (by the user or by us)
    val paused: () -> Boolean,           // paused by the user (or by the room)
    val running: () -> Boolean,          // frames are moving: not opening / buffering
    val userRate: () -> Float,           // speed chosen in the player
    val setPaused: (Boolean) -> Unit,
    val seekTo: (Long) -> Unit,
    val setRate: (Float) -> Unit,        // engine speed (drift correction); the player's own speed is restored with userRate
    val playIndex: (Int) -> Unit,        // another episode of this playlist
    val notice: (String) -> Unit = {},   // a short line over the picture: what the host did, whom the room waits for
    val close: () -> Unit = {}           // the host closed the room's film: a guest's player closes too
)

/** The player for the shared sync engine: always the latest hooks (PlayerView passes new ones on recomposition). */
private class HooksPlayer(private val h: () -> SyncHooks) : SyncPlayer {
    override val timeMs get() = h().timeMs()
    override val busy get() = h().busy()
    override val engineMs get() = h().engineMs()
    override val seekStamp get() = h().seekStamp()
    override val paused get() = h().paused()
    override val running get() = h().running()
    override val userRate get() = h().userRate()
    override fun applyPaused(paused: Boolean) = h().setPaused(paused)
    override fun seekTo(ms: Long) = h().seekTo(ms)
    override fun setRate(rate: Float) = h().setRate(rate)
}

/** The room for the shared sync engine. */
private object DesktopPartyLink : PartyLink {
    override val state: PartyState get() = Party.state
    override val canControl get() = Party.canControl
    override val canSeek get() = Party.canSeek
    override fun targetMs() = Party.targetMs()
    override fun othersBuffering() = Party.members.any { it.buffering && it.key != Party.myKey }
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
 * Keeps this player in the room's film: the loop itself is the shared [PartySyncEngine] (Android uses the same one).
 * Works only while the player shows the room's film ([key] = its watch key); otherwise it does nothing.
 */
@Composable
fun PartySync(key: String?, title: String, keys: List<String>, started: Boolean, closing: Boolean, hooks: SyncHooks) {
    val k by rememberUpdatedState(key)
    val ready by rememberUpdatedState(started && !closing)
    val isClosing by rememberUpdatedState(closing)
    val h by rememberUpdatedState(hooks)
    val engine = remember {
        PartySyncEngine(DesktopPartyLink, log = { PlayerLog.write(it) }, notice = { n -> noticeText(n)?.let { h.notice(it) } })
    }
    // The host put another episode: said once the new one plays (opening it would cover the line at once)
    val episodeNotice = remember { arrayOfNulls<String>(1) }
    val player = remember { HooksPlayer { h } }

    // The host went on to the next episode: the room follows; a guest's player follows the room's episode.
    LaunchedEffect(key) {
        val f = Party.file ?: return@LaunchedEffect
        if (key != null && key != f.key && Party.isHost && f.key in keys) Party.putFilm(PartyFileRef(key, title))
    }
    LaunchedEffect(Party.file?.key) {
        val rk = Party.file?.key ?: return@LaunchedEffect
        val i = keys.indexOf(rk)
        if (rk != k && i >= 0 && !closing) {
            PlayerLog.write("[party] room episode -> $i"); h.playIndex(i)
            if (!Party.isHost) episodeNotice[0] = tr("party.vklyuchaet", "who" to hostWho(), "title" to Party.file?.title.orEmpty())
        }
    }

    // The host closed the film: the room pauses (nobody is left watching it otherwise). A moment later, so that
    // re-creating the player (subtitle size) does not pause it.
    DisposableEffect(Unit) {
        Party.watching++
        onDispose {
            Party.watching--
            if (Party.isHost && Party.file?.key == k) Party.closeIfNobodyWatches()
        }
    }

    LaunchedEffect(Unit) {
        var showedRoomFilm = false
        while (true) {
            delay(PartySyncEngine.TICK_MS)
            val kk = k
            // The host closed the film (the room has none now): a guest who watched it with them closes it too
            if (Party.inRoom && kk != null && Party.file?.key == kk) showedRoomFilm = true
            else if (!Party.inRoom || Party.file != null) showedRoomFilm = false
            else if (showedRoomFilm && !Party.isHost && !isClosing) {
                showedRoomFilm = false
                PlayerLog.write("[party] host closed the film: closing the player")
                h.close()
                continue
            }
            if (Party.inRoom && Party.file?.key == kk && kk != null && ready) {
                episodeNotice[0]?.let { episodeNotice[0] = null; h.notice(it) }
                engine.tick(player, kk, System.currentTimeMillis())
            } else engine.idle(player)
        }
    }
}

private fun hostWho() = Party.hostName.ifBlank { tr("party.sozdatel_komnaty") }

/** Present tense: the name says nothing of a person's gender, «ставит» fits everyone. */
private fun noticeText(n: PartyNotice): String? = when (n) {
    PartyNotice.Paused -> tr("party.stavit_pauzu", "who" to hostWho())
    PartyNotice.Resumed -> tr("party.prodolzhaet", "who" to hostWho())
    is PartyNotice.Seeked -> tr("party.peremativaet_na", "who" to hostWho(), "time" to clockText(n.posMs))
    PartyNotice.Waiting -> Party.members.filter { it.buffering && it.key != Party.myKey }.map { it.name }
        .takeIf { it.isNotEmpty() }?.let { tr("party.zhdyom_zagruzku", "names" to it.joinToString()) }
}
