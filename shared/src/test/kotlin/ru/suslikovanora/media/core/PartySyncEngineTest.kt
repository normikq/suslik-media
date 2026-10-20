package ru.suslikovanora.media.core

import ru.suslikovanora.media.core.party.PartyLink
import ru.suslikovanora.media.core.party.PartyState
import ru.suslikovanora.media.core.party.PartySyncEngine
import ru.suslikovanora.media.core.party.SyncPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartySyncEngineTest {
    private class FakePlayer : SyncPlayer {
        override var timeMs = 0L
        override var busy = false
        override var engineMs = 0L
        override var seekStamp = 0L
        override var paused = true
        override var running = true
        override var userRate = 1f
        val seeks = mutableListOf<Long>()
        val rates = mutableListOf<Float>()
        override fun applyPaused(paused: Boolean) { this.paused = paused }
        override fun seekTo(ms: Long) { seeks += ms; timeMs = ms; engineMs = ms; seekStamp++ }
        override fun setRate(rate: Float) { rates += rate }
        /** Пользователь перемотал сам. */
        fun userSeek(ms: Long) { timeMs = ms; engineMs = ms; seekStamp++ }
        /** Прошло [ms] воспроизведения. */
        fun play(ms: Long) { if (!paused && running) { timeMs += ms; engineMs += ms } }
    }

    private class FakeLink(var host: Boolean) : PartyLink {
        override var state = PartyState.Idle
        var target = 0L
        var others = false
        var startedKey: String? = null
        val sent = mutableListOf<String>()
        val buffering = mutableListOf<Boolean>()
        override val canControl get() = host
        override val canSeek get() = host
        override fun targetMs() = target
        override fun othersBuffering() = others
        override fun takeStartedByMe(key: String) = (startedKey == key).also { if (it) startedKey = null }
        override fun play(posMs: Long) { sent += "play $posMs" }
        override fun pause(posMs: Long) { sent += "pause $posMs" }
        override fun seek(posMs: Long) { sent += "seek $posMs" }
        override fun reportBuffering(loading: Boolean) { if (buffering.lastOrNull() != loading) buffering += loading }
    }

    private val key = "42|a.mkv"

    /** Гость входит в идущую комнату: снимает паузу и перематывает к её месту, сам ничего не шлёт. */
    @Test
    fun guestJoinsPlayingRoom() {
        val p = FakePlayer(); val l = FakeLink(host = false)
        l.state = PartyState(true, 60.0, 1.0, 0L, seq = 3); l.target = 60_000L
        val e = PartySyncEngine(l)
        e.tick(p, key, 1_000L)
        assertFalse(p.paused)
        assertEquals(listOf(60_000L), p.seeks)
        assertTrue(l.sent.isEmpty())
        e.tick(p, key, 1_400L)
        assertEquals(listOf(false, true), l.buffering) // пока грузится новое место — комната ждёт
    }

    /** Пауза гостя — только у него: комнате ничего не уходит, и комната её не снимает. */
    @Test
    fun guestPauseStaysLocal() {
        val p = FakePlayer(); val l = FakeLink(host = false)
        l.state = PartyState(true, 10.0, 1.0, 0L, seq = 1); l.target = 10_000L
        p.timeMs = 10_000L; p.engineMs = 10_000L
        val e = PartySyncEngine(l)
        var now = 1_000L
        e.tick(p, key, now)                       // применили состояние комнаты: играем
        assertFalse(p.paused)
        repeat(3) { now += 400; p.play(400); l.target += 400; e.tick(p, key, now) }
        p.paused = true                           // пауза пользователя
        repeat(10) { now += 400; l.target += 400; e.tick(p, key, now) }
        assertTrue(p.paused)
        assertTrue(l.sent.isEmpty())
        // Продолжил — возвращается к месту комнаты
        p.paused = false
        now += 400; l.target += 400; e.tick(p, key, now)
        assertFalse(p.paused)
        assertEquals(l.target, p.seeks.last())
    }

    /** Пауза и перемотка ведущего уходят комнате. */
    @Test
    fun hostPauseAndSeekGoToRoom() {
        val p = FakePlayer(); val l = FakeLink(host = true)
        l.state = PartyState(false, 5.0, 1.0, 0L, seq = 1); l.target = 5_000L
        p.timeMs = 5_000L; p.engineMs = 5_000L
        val e = PartySyncEngine(l)
        e.tick(p, key, 1_000L)
        assertTrue(p.paused)
        p.paused = false
        e.tick(p, key, 4_000L)                    // окно OWN_MS прошло
        assertEquals(listOf("play 5000"), l.sent)

        l.state = PartyState(true, 5.0, 1.0, 0L, seq = 2)
        p.userSeek(90_000L)
        e.tick(p, key, 4_400L)
        assertEquals("play 90000", l.sent.last())  // играем: «пуск отсюда»
    }

    /** Я поставил фильм в комнату: комната стартует с моего места. */
    @Test
    fun startedByMeStartsRoomFromHere() {
        val p = FakePlayer(); val l = FakeLink(host = true)
        p.paused = false; p.timeMs = 1_234L
        l.startedKey = key
        PartySyncEngine(l).tick(p, key, 1_000L)
        assertEquals(listOf("play 1234"), l.sent)
    }

    /** Небольшой дрейф выравнивается скоростью, а не перемоткой. */
    @Test
    fun smallDriftChangesRate() {
        val p = FakePlayer(); val l = FakeLink(host = false)
        l.state = PartyState(true, 10.0, 1.0, 0L, seq = 1); l.target = 10_500L
        p.timeMs = 10_000L; p.engineMs = 10_000L
        val e = PartySyncEngine(l)
        e.tick(p, key, 1_000L)                    // разница 0,5 с: без перемотки
        assertTrue(p.seeks.isEmpty())
        l.target = 10_800L
        e.tick(p, key, 4_000L)                    // после OWN_MS: отстаём на 0,8 с
        assertEquals(listOf(1.05f), p.rates)
        l.target = 10_050L
        e.tick(p, key, 4_400L)                    // догнали: своя скорость
        assertEquals(listOf(1.05f, 1f), p.rates)
        assertTrue(p.seeks.isEmpty())
    }

    /** Кадры встали сами (сеть): через секунду комната ждёт нас. */
    @Test
    fun stallReportsBuffering() {
        val p = FakePlayer(); val l = FakeLink(host = false)
        l.state = PartyState(true, 10.0, 1.0, 0L, seq = 1); l.target = 10_000L
        p.timeMs = 10_000L; p.engineMs = 10_000L
        val e = PartySyncEngine(l)
        e.tick(p, key, 1_000L)
        p.running = false
        e.tick(p, key, 1_400L)
        assertEquals(listOf(false), l.buffering)
        e.tick(p, key, 2_600L)
        assertEquals(listOf(false, true), l.buffering)
        p.running = true
        e.tick(p, key, 3_000L)
        assertEquals(listOf(false, true, false), l.buffering)
    }

    /** Готовы, а комната ждёт другого: стоим. Вне фильма комнаты — «не грузимся». */
    @Test
    fun waitsForOthersAndIdles() {
        val p = FakePlayer(); val l = FakeLink(host = false)
        l.state = PartyState(true, 10.0, 1.0, 0L, seq = 1); l.target = 10_000L
        p.timeMs = 10_000L; p.engineMs = 10_000L
        val e = PartySyncEngine(l)
        e.tick(p, key, 1_000L)
        assertFalse(p.paused)
        l.state = l.state.copy(waiting = true); l.others = true
        e.tick(p, key, 1_400L)                    // тот же seq: «ждём остальных»
        assertTrue(p.paused)
        e.idle(p)
        assertEquals(false, l.buffering.last())
    }

    /** Своя загрузка после перемотки завершилась: если других ждущих нет, не встаём на паузу. */
    @Test
    fun seekDoesNotPauseWhenNoOthersBuffering() {
        val p = FakePlayer(); val l = FakeLink(host = true)
        l.state = PartyState(true, 10.0, 1.0, 0L, seq = 1); l.target = 10_000L
        p.timeMs = 10_000L; p.engineMs = 10_000L; p.paused = false
        val e = PartySyncEngine(l)
        e.tick(p, key, 1_000L)
        assertFalse(p.paused)

        // Перемотка на 50 с
        p.userSeek(50_000L)
        e.tick(p, key, 4_000L)
        // Сервер пометил waiting=true и увеличил seq
        l.state = PartyState(true, 50.0, 1.0, 0L, seq = 3, waiting = true)
        l.others = false
        l.target = 50_000L

        // Движок завершил переход на новую позицию
        p.engineMs = 50_200L
        p.running = true
        e.tick(p, key, 4_800L) // seek done

        // Плеер НЕ должен вставать на паузу, так как других ждущих нет
        assertFalse(p.paused)
    }

    /** Пауза ведущего срабатывает сразу, не дожидаясь окна ownUntil. */
    @Test
    fun hostPauseWorksImmediatelyWithoutWaitingOwnMs() {
        val p = FakePlayer(); val l = FakeLink(host = true)
        l.state = PartyState(true, 10.0, 1.0, 0L, seq = 1); l.target = 10_000L
        p.timeMs = 10_000L; p.engineMs = 10_000L; p.paused = false
        val e = PartySyncEngine(l)
        e.tick(p, key, 1_000L) // применили состояние комнаты (ownUntil = now + OWN_MS = 3500L)
        assertFalse(p.paused)

        // Пользователь нажал паузу через 400 мс (до истечения 3500L)
        p.paused = true
        e.tick(p, key, 1_400L)
        assertEquals(listOf("pause 10000"), l.sent)
    }

    /** Перемотка на паузе отправляет в комнату паузу на новом месте и не снимает паузу сама. */
    @Test
    fun hostSeekWhilePausedSendsPauseAndDoesNotUnpause() {
        val p = FakePlayer(); val l = FakeLink(host = true)
        l.state = PartyState(false, 10.0, 1.0, 0L, seq = 1); l.target = 10_000L
        p.timeMs = 10_000L; p.engineMs = 10_000L; p.paused = true
        val e = PartySyncEngine(l)
        e.tick(p, key, 1_000L)
        assertTrue(p.paused)

        // Перемотка на 60 с во время паузы
        p.userSeek(60_000L)
        e.tick(p, key, 1_400L)
        assertEquals("pause 60000", l.sent.last())

        // Сервер подтвердил новую позицию на паузе
        l.state = PartyState(false, 60.0, 1.0, 0L, seq = 2)
        l.target = 60_000L

        // По прошествии времени (даже после ownUntil) плеер остаётся на паузе
        repeat(10) { step ->
            e.tick(p, key, 2_000L + step * 400L)
            assertTrue(p.paused)
        }
    }
}
