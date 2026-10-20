package ru.suslikovanora.media.core

import ru.suslikovanora.media.core.party.DriftAction
import ru.suslikovanora.media.core.party.DriftController
import ru.suslikovanora.media.core.party.PartyClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PartyTest {
    @Test
    fun partyClockCalculatesOffsetFromSamples() {
        val clock = PartyClock()
        // Local: 1000..1040 (RTT 40ms). Midpoint 1020. Server time was 5000.
        // Offset = 5000 - 1020 = +3980 ms.
        clock.addSample(localSendTime = 1000L, localRecvTime = 1040L, serverTime = 5000L)
        assertEquals(3980L, clock.offsetMs)
        assertEquals(40L, clock.bestRttMs)
        assertEquals(13980L, clock.serverNow(localNow = 10000L))

        // A sample with worse RTT (80ms) should be ignored
        clock.addSample(localSendTime = 2000L, localRecvTime = 2080L, serverTime = 6000L)
        assertEquals(40L, clock.bestRttMs)
        assertEquals(3980L, clock.offsetMs)

        // A sample with better RTT (20ms) should update the offset
        // Midpoint: 3010. Server time: 7000. Offset = 7000 - 3010 = 3990 ms.
        clock.addSample(localSendTime = 3000L, localRecvTime = 3020L, serverTime = 7000L)
        assertEquals(20L, clock.bestRttMs)
        assertEquals(3990L, clock.offsetMs)
    }

    @Test
    fun driftControllerDecisions() {
        // Drift within 0.3s -> None
        assertEquals(DriftAction.None, DriftController.evaluate(localPosSec = 10.1f, targetPosSec = 10.0f))
        assertEquals(DriftAction.None, DriftController.evaluate(localPosSec = 9.8f, targetPosSec = 10.0f))

        // Drift ahead by 0.5s -> SlowDown (0.95x)
        val slow = DriftController.evaluate(localPosSec = 10.5f, targetPosSec = 10.0f)
        assertTrue(slow is DriftAction.SlowDown)
        assertEquals(0.95f, slow.rate)

        // Drift behind by 0.8s -> SpeedUp (1.05x)
        val fast = DriftController.evaluate(localPosSec = 9.2f, targetPosSec = 10.0f)
        assertTrue(fast is DriftAction.SpeedUp)
        assertEquals(1.05f, fast.rate)

        // Hard desync > 2s -> HardSeek
        val hardAhead = DriftController.evaluate(localPosSec = 25.0f, targetPosSec = 10.0f)
        assertTrue(hardAhead is DriftAction.HardSeek)
        assertEquals(10.0f, hardAhead.targetSec)

        val hardBehind = DriftController.evaluate(localPosSec = 5.0f, targetPosSec = 10.0f)
        assertTrue(hardBehind is DriftAction.HardSeek)
        assertEquals(10.0f, hardBehind.targetSec)
    }
}
