package ru.suslikovanora.media.core.party

import kotlin.math.abs

/** Плеер, которым управляет комната (приложение отдаёт свои функции: панели и SMTC остаются верными). */
interface SyncPlayer {
    /** Где картинка (сразу после перемотки — куда она идёт). */
    val timeMs: Long
    /** Пользователь тянет полосу времени: ждём, пока отпустит. */
    val busy: Boolean
    /** Место самого движка как есть (после перемотки: ещё грузится или уже там). */
    val engineMs: Long
    /** Метка последней перемотки (пользователя или нашей): меняется при каждой. */
    val seekStamp: Long
    /** Пауза пользователя (или комнаты). */
    val paused: Boolean
    /** Кадры идут: не открывается и не буферизуется. */
    val running: Boolean
    /** Скорость, выбранная в плеере. */
    val userRate: Float
    fun applyPaused(paused: Boolean)
    fun seekTo(ms: Long)
    /** Скорость движка (подстройка дрейфа); свою скорость плеера возвращаем через [userRate]. */
    fun setRate(rate: Float)
}

/** Комната для [PartySyncEngine]: состояние и команды серверу (приложение шлёт их по очереди, по порядку). */
interface PartyLink {
    val state: PartyState
    /** Пауза, пуск и перемотка всей комнаты — только ведущий. Пауза гостя остаётся у него. */
    val canControl: Boolean
    val canSeek: Boolean
    /** Где комната сейчас, мс ([PartyState.targetMs] с откалиброванными часами). */
    fun targetMs(): Long
    /** Грузится кто-то, кроме меня. */
    fun othersBuffering(): Boolean
    /** Я только что поставил фильм [key] в комнату: true один раз, комната стартует с моего места. */
    fun takeStartedByMe(key: String): Boolean
    fun play(posMs: Long)
    fun pause(posMs: Long)
    fun seek(posMs: Long)
    /** «Мой плеер грузится» / «готов». Повторы одного и того же приложение отбрасывает само. */
    fun reportBuffering(loading: Boolean)
}

/**
 * Держит плеер в фильме комнаты: следует за пуском, паузой и перемоткой комнаты и отправляет свои.
 * Малый дрейф выравнивает скоростью (0,95–1,05×), большой — перемоткой ([DriftController]).
 *
 * Без потоков и корутин: приложение вызывает [tick] каждые [TICK_MS], пока плеер показывает фильм комнаты,
 * и [idle] — пока нет (не в комнате, другой фильм, плеер ещё открывается или закрывается).
 *
 * Перемотка по сети идёт секундами (сначала грузится новое место: видео и каждая озвучка). Гнаться за комнатой
 * в это время — только хуже (перемотка → загрузка → «отстали» → перемотка … чёрный экран). Поэтому грузящийся
 * плеер сообщает об этом, и комната его ЖДЁТ: остальные стоят на месте комнаты, пока все не готовы.
 */
class PartySyncEngine(private val link: PartyLink, private val log: (String) -> Unit = {}) {
    private var lastSeq = -1L
    private var ownUntil = 0L
    private var prevT = 0L
    private var prevP = true
    private var prevAt = 0L
    private var drift = 1f
    private var pendingAt = 0L        // наша перемотка к месту комнаты: когда началась
    private var pendingTo = 0L
    private var pendingT = -1L
    private var stalledSince = 0L     // кадры встали сами (сеть), а не по паузе
    private var lastEngineMs = Long.MIN_VALUE
    private var engineMovedAt = 0L    // когда время движка последний раз сдвинулось (или стояло на паузе)
    private var localHold = false     // гость поставил паузу себе: комната идёт без него
    private var seenSeek: Long? = null // последняя уже учтённая перемотка (наша или пользователя)

    /** Последнее увиденное место: для паузы комнаты, когда ведущий закрыл фильм. */
    var lastTimeMs = 0L
        private set

    /** Плеер не в фильме комнаты: вернуть свою скорость, забыть команды, сказать «не грузимся». */
    fun idle(player: SyncPlayer) {
        if (seenSeek == null) seenSeek = player.seekStamp
        restoreRate(player)
        lastSeq = -1L; pendingAt = 0L; stalledSince = 0L; lastEngineMs = Long.MIN_VALUE
        link.reportBuffering(false)
    }

    /** Один шаг: плеер показывает фильм комнаты [key]. [now] — локальные часы, мс. */
    fun tick(player: SyncPlayer, key: String, now: Long) {
        if (seenSeek == null) seenSeek = player.seekStamp
        if (player.busy) return // полосу тянут: место отправим после отпускания
        val t = player.timeMs
        val p = player.paused
        lastTimeMs = t

        // Пользователь перемотал здесь (стрелки, ±10 с, полоса, глава): комната идёт туда же. Ловим по метке перемотки
        // самого плеера, а не по скачку времени: наша идущая перемотка скрыла бы скачок.
        val stamp = player.seekStamp
        if (stamp != seenSeek) {
            seenSeek = stamp
            val expected = if (prevP) prevT else prevT + ((now - prevAt) * player.userRate * drift).toLong()
            if (lastSeq >= 0 && abs(t - expected) > 1_500L && link.canSeek) { // (смена дорожки тоже ставит метку: без скачка)
                log("[party] local seek at $t")
                restoreRate(player)
                if (p) link.pause(t) else link.play(t)
                lastSeq = link.state.seq + 1
                // Новое место и здесь грузится секунды: «грузимся» (комната ждёт, а не убегает) и не гонимся за ней.
                // Раньше через 3 с дрейф видел «отстали на 3 с» и перематывал снова: вторая загрузка.
                pendingAt = now; pendingTo = t; pendingT = -1L
                ownUntil = now + OWN_MS
                prevT = t; prevP = p; prevAt = now
                link.reportBuffering(!p)
                return
            }
        }

        // Наша перемотка к комнате: готова, когда движок снова идёт вперёд (далеко от цели — индекс файла ещё не
        // прочитан — засчитываем через 3 с: дрейф ниже перемотает снова).
        if (pendingAt > 0L) {
            val e = player.engineMs
            val moved = pendingT >= 0L && e - pendingT in 100L..2_000L && player.running &&
                (abs(e - pendingTo) < 5_000L || now - pendingAt > 3_000L)
            if (moved || p || now - pendingAt > 30_000L) {
                log("[party] seek done in ${now - pendingAt} ms")
                pendingAt = 0L; ownUntil = now + OWN_MS
            } else pendingT = e
        }
        // Сами грузимся, хотя должны играть (сеть): через 1 с комната ждёт и нас. «Играю» от движка мало: сразу после
        // открытия mpv сообщает «играю», а время стоит секунды, пока не пришли данные (журнал 20.10, 20:19:51) —
        // поэтому стоящее время без паузы тоже загрузка.
        val e = player.engineMs
        if (p || e != lastEngineMs) { lastEngineMs = e; engineMovedAt = now }
        val frozen = now - engineMovedAt >= FROZEN_MS
        stalledSince = if (!p && pendingAt == 0L && (!player.running || frozen))
            (if (stalledSince > 0L) stalledSince else if (frozen) engineMovedAt else now) else 0L
        val loading = pendingAt > 0L || (stalledSince > 0L && now - stalledSince > 1_000L)
        link.reportBuffering(loading && link.state.playing)

        // 1. Я только что поставил этот фильм в комнату: комната стартует с моего места.
        if (link.takeStartedByMe(key)) {
            if (p) link.pause(t) else link.play(t)
            lastSeq = link.state.seq + 1; ownUntil = now + OWN_MS
            // Фильм ещё открывается (секунды по сети): «грузимся», чтобы комната ждала, а не убегала —
            // иначе дрейф перематывает снова сразу после первых кадров (журнал 20.10, 19:02:00)
            if (!p && !player.running) { pendingAt = now; pendingTo = t; pendingT = -1L }
            prevT = t; prevP = p; prevAt = now
            return
        }

        // 2. Пользователь поставил паузу / пуск здесь (не комната): сообщаем комнате сразу,
        // не блокируя собственным окном ожидания ownUntil.
        if (lastSeq >= 0 && !loading && p != prevP) {
            if (link.canControl) {
                log("[party] local ${if (p) "pause" else "play"} at $t")
                if (p) link.pause(t) else link.play(t)
                lastSeq = link.state.seq + 1 // наше изменение вернётся: это не новость
                ownUntil = now + OWN_MS
                prevT = t; prevP = p; prevAt = now
                return
            }
            // Гость: пауза только у него; пуск возвращает его к месту ведущего
            localHold = p
            log(if (p) "[party] guest pause (local only)" else "[party] guest goes on: back to the room")
            if (!p) lastSeq = -1L // ниже берём состояние комнаты: перемотка к её месту
        }

        // 3. Следуем за комнатой. Она играет, если не на паузе и не ждёт кого-то другого (сами грузимся дальше).
        val s = link.state
        val target = link.targetMs()
        val othersLoading = link.othersBuffering()
        val wantPaused = !s.playing || (s.waiting && !loading && othersLoading) || localHold
        var nt = t
        var np = p
        if (lastSeq < 0 || s.seq > lastSeq) {
            // Новая команда (или мы только вошли, или комната начала / перестала ждать): берём состояние комнаты.
            lastSeq = s.seq
            restoreRate(player)
            val alreadyGoing = pendingAt > 0L && abs(pendingTo - target) < 1_000L
            val needSeek = abs(t - target) > 1_000L && !alreadyGoing
            // Комната играет, но ждёт других, а нам ещё перематываться: перемотку грузим без паузы. На паузе она
            // засчитывалась через такт, гость говорил «готов», комната шла, а он стоял (журнал 20.10, 20:16:54):
            // дрейф перематывал снова — вторая загрузка. Готовые встанут ниже, в «ждём остальных».
            val pauseNow = if (needSeek && s.playing && !localHold) false else wantPaused
            if (p != pauseNow && !(loading && !pauseNow)) { player.applyPaused(pauseNow); np = pauseNow }
            if (needSeek) nt = seekRoom(player, target, now)
            ownUntil = now + OWN_MS
            log("[party] apply seq=${s.seq} playing=${s.playing} waiting=${s.waiting} target=$target local=$t")
        } else if (s.playing && s.waiting && !loading && !p && othersLoading) {
            // Готовы, но комната ждёт кого-то ещё: стоим здесь. Если грузились только мы — играем дальше:
            // комната сейчас перестанет ждать (ответ на наше «готов»), пауза в это время была бы рывком на 0,4 с
            player.applyPaused(true); np = true; ownUntil = now + 1_000L
            log("[party] ready, waiting for the others at $t")
        } else if (s.playing && !s.waiting && !p && !loading && now > ownUntil && player.running && player.userRate == 1f) {
            when (val a = DriftController.evaluate(t / 1000f, target / 1000f)) {
                DriftAction.None -> restoreRate(player)
                is DriftAction.SlowDown -> if (drift != a.rate) { drift = a.rate; player.setRate(a.rate) }
                is DriftAction.SpeedUp -> if (drift != a.rate) { drift = a.rate; player.setRate(a.rate) }
                is DriftAction.HardSeek -> {
                    restoreRate(player); nt = seekRoom(player, target, now); ownUntil = now + OWN_MS
                    log("[party] drift seek local=$t target=$target")
                }
            }
        }
        prevT = nt; prevP = np; prevAt = now
    }

    private fun restoreRate(player: SyncPlayer) {
        if (drift != 1f) { drift = 1f; player.setRate(player.userRate) }
    }

    private fun seekRoom(player: SyncPlayer, target: Long, now: Long): Long {
        player.seekTo(target); pendingAt = now; pendingTo = target; pendingT = -1L
        seenSeek = player.seekStamp
        return target
    }

    companion object {
        const val TICK_MS = 400L
        /** После того как комната сдвинула нас, наши собственные изменения — не новость для комнаты. */
        const val OWN_MS = 2_500L
        /** Время движка стоит столько без паузы — значит, ждём данные (даже если движок говорит «играю»). */
        const val FROZEN_MS = 1_000L
    }
}
