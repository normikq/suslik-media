package ru.suslikovanora.media.desktop.party

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import ru.suslikovanora.media.core.party.DriftAction
import ru.suslikovanora.media.core.party.DriftController
import ru.suslikovanora.media.desktop.PlayerLog
import kotlin.math.abs

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
    val playIndex: (Int) -> Unit         // another episode of this playlist
)

private const val TICK_MS = 400L
private const val OWN_MS = 2500L         // after the room moved us, our own changes are not news for the room

/**
 * Keeps this player in the room's film: follows play / pause / seek of the room and sends the user's own ones.
 * Small drift is evened out by speed (0.95–1.05×), a big one by a seek (DriftController).
 * Works only while the player shows the room's film ([key] = its watch key); otherwise it does nothing.
 */
@Composable
fun PartySync(key: String?, title: String, keys: List<String>, started: Boolean, closing: Boolean, hooks: SyncHooks) {
    val k by rememberUpdatedState(key)
    val ready by rememberUpdatedState(started && !closing)
    val h by rememberUpdatedState(hooks)

    // The host went on to the next episode: the room follows; a guest's player follows the room's episode.
    LaunchedEffect(key) {
        val f = Party.file ?: return@LaunchedEffect
        if (key != null && key != f.key && Party.isHost && f.key in keys) Party.putFilm(Party.FileRef(key, title))
    }
    LaunchedEffect(Party.file?.key) {
        val rk = Party.file?.key ?: return@LaunchedEffect
        val i = keys.indexOf(rk)
        if (rk != k && i >= 0 && !closing) { PlayerLog.write("[party] room episode -> $i"); h.playIndex(i) }
    }

    // The host closed the film: the room pauses (nobody is left watching it otherwise). A moment later, so that
    // re-creating the player (subtitle size) does not pause it.
    val lastT = remember { LongArray(1) } // the last place seen, for the pause on close
    DisposableEffect(Unit) {
        Party.watching++
        onDispose {
            Party.watching--
            if (Party.isHost && Party.file?.key == k) Party.pauseIfNobodyWatches(lastT[0])
        }
    }

    LaunchedEffect(Unit) {
        var lastSeq = -1L
        var ownUntil = 0L
        var prevT = 0L; var prevP = true; var prevAt = 0L
        var drift = 1f
        fun restoreRate() { if (drift != 1f) { drift = 1f; h.setRate(h.userRate()) } }
        // A seek over the network takes seconds (the new place is loaded first: video and every voice-over). Chasing the
        // room meanwhile only made it worse (seek → load → «behind» → seek … a black screen). Now a loading player says so
        // and the room WAITS for it: everyone else stands still at the room's place until all are ready.
        var pendingAt = 0L; var pendingTo = 0L; var pendingT = -1L
        var stalledSince = 0L        // frames stopped by themselves (network), not by a pause
        var localHold = false        // a guest paused for themselves: the room goes on without them
        var seenSeek = h.seekStamp() // the last seek already accounted for (ours or the user's)
        fun seekRoom(target: Long, now: Long): Long {
            h.seekTo(target); pendingAt = now; pendingTo = target; pendingT = -1L
            seenSeek = h.seekStamp()
            return target
        }
        while (true) {
            delay(TICK_MS)
            val hook = h
            val active = Party.inRoom && Party.file?.key == k && k != null && ready
            if (!active) { restoreRate(); lastSeq = -1L; pendingAt = 0L; stalledSince = 0L; Party.reportBuffering(false); continue }
            if (hook.busy()) continue // a drag of the timeline: the place is sent after the release
            val now = System.currentTimeMillis()
            val t = hook.timeMs(); val p = hook.paused()
            lastT[0] = t

            // The user seeked here (arrows, ±10 s, timeline, chapter): the room goes there too. Caught by the player's
            // own seek mark, not by a jump of time: a seek of ours in progress would hide a jump.
            val stamp = hook.seekStamp()
            if (stamp != seenSeek) {
                seenSeek = stamp
                val expected = if (prevP) prevT else prevT + ((now - prevAt) * hook.userRate() * drift).toLong()
                if (lastSeq >= 0 && abs(t - expected) > 1_500L && Party.canSeek) { // (a track switch also marks a seek: no jump)
                    PlayerLog.write("[party] local seek at $t")
                    restoreRate()
                    if (p) Party.seek(t) else Party.play(t)
                    lastSeq = Party.state.seq + 1
                    // The new place loads for seconds here too: say «loading» (the room waits, not runs ahead) and do not
                    // chase it meanwhile. Before, 3 s later the drift saw «3 s behind» and seeked again: a second load.
                    pendingAt = now; pendingTo = t; pendingT = -1L
                    ownUntil = now + OWN_MS
                    prevT = t; prevP = p; prevAt = now
                    continue
                }
            }

            // Our seek to the room's place: done when mpv goes forward again (far from the aim — the index of the file was
            // not read yet — counts after 3 s: the drift below seeks again).
            if (pendingAt > 0L) {
                val e = hook.engineMs()
                val moved = pendingT >= 0L && e - pendingT in 100L..2_000L && hook.running() &&
                    (abs(e - pendingTo) < 5_000L || now - pendingAt > 3_000L)
                if (moved || p || now - pendingAt > 30_000L) {
                    PlayerLog.write("[party] seek done in ${now - pendingAt} ms")
                    pendingAt = 0L; ownUntil = now + 1_000L
                } else pendingT = e
            }
            // Loading by itself while it should play (network): after 1 s the room waits for us too
            stalledSince = if (!p && pendingAt == 0L && !hook.running()) (if (stalledSince > 0L) stalledSince else now) else 0L
            val loading = pendingAt > 0L || (stalledSince > 0L && now - stalledSince > 1_000L)
            Party.reportBuffering(loading && Party.state.playing)

            // 1. I have just put this film into the room: the room starts from my place.
            if (Party.startedByMe == k) {
                Party.startedByMe = null
                if (p) Party.pause(t) else Party.play(t)
                lastSeq = Party.state.seq + 1; ownUntil = now + OWN_MS
                // The film is still opening (seconds over the network): «loading», so the room waits instead of running
                // ahead — otherwise the drift seeks again right after the first frames (journal 20.10, 19:02:00)
                if (!p && !hook.running()) { pendingAt = now; pendingTo = t; pendingT = -1L }
                prevT = t; prevP = p; prevAt = now
                continue
            }

            // 2. The user paused / resumed here (not the room): tell the room.
            // (the room's own pauses are already in prevP: for a guest no waiting window is needed)
            if (lastSeq >= 0 && (now > ownUntil || !Party.canControl) && !loading && p != prevP) {
                if (Party.canControl) {
                    PlayerLog.write("[party] local ${if (p) "pause" else "play"} at $t")
                    if (p) Party.pause(t) else Party.play(t)
                    lastSeq = Party.state.seq + 1 // our own change will come back: it is not news
                    ownUntil = now + OWN_MS
                    prevT = t; prevP = p; prevAt = now
                    continue
                }
                // A guest: the pause is theirs only; going on again returns them to the host's place
                localHold = p
                PlayerLog.write(if (p) "[party] guest pause (local only)" else "[party] guest goes on: back to the room")
                if (!p) lastSeq = -1L // take the room's state below: seek to its place
            }

            // 3. Follow the room. It plays unless paused, or waiting for someone else (we ourselves load on).
            val s = Party.state
            val target = Party.targetMs()
            val wantPaused = !s.playing || (s.waiting && !loading) || localHold
            var nt = t; var np = p
            if (lastSeq < 0 || s.seq > lastSeq) {
                // A new command (or we have just come in, or the room starts / stops waiting): take the room's state.
                lastSeq = s.seq
                restoreRate()
                if (p != wantPaused && !(loading && !wantPaused)) { hook.setPaused(wantPaused); np = wantPaused }
                val alreadyGoing = pendingAt > 0L && abs(pendingTo - target) < 1_000L
                if (abs(t - target) > 1_000L && !alreadyGoing) nt = seekRoom(target, now)
                ownUntil = now + OWN_MS
                PlayerLog.write("[party] apply seq=${s.seq} playing=${s.playing} waiting=${s.waiting} target=$target local=$t")
            } else if (s.playing && s.waiting && !loading && !p &&
                Party.members.any { it.buffering && it.key != Party.myKey }) {
                // Ready, but the room still waits for someone else: stand still here. If only we were loading, play on:
                // the room stops waiting in a moment (the answer to our «ready»), a pause meanwhile was a 0.4 s stall
                hook.setPaused(true); np = true; ownUntil = now + 1_000L
                PlayerLog.write("[party] ready, waiting for the others at $t")
            } else if (s.playing && !s.waiting && !p && !loading && now > ownUntil && hook.running() && hook.userRate() == 1f) {
                when (val a = DriftController.evaluate(t / 1000f, target / 1000f)) {
                    DriftAction.None -> restoreRate()
                    is DriftAction.SlowDown -> if (drift != a.rate) { drift = a.rate; hook.setRate(a.rate) }
                    is DriftAction.SpeedUp -> if (drift != a.rate) { drift = a.rate; hook.setRate(a.rate) }
                    is DriftAction.HardSeek -> {
                        restoreRate(); nt = seekRoom(target, now); ownUntil = now + OWN_MS
                        PlayerLog.write("[party] drift seek local=$t target=$target")
                    }
                }
            }
            prevT = nt; prevP = np; prevAt = now
        }
    }
}
