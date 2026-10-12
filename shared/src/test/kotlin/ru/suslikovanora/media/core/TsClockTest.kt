package ru.suslikovanora.media.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TsClockTest {
    /** One TS packet; with a PCR (90 kHz [base]) in its adaptation field when [base] ≥ 0. */
    private fun packet(pid: Int, base: Long = -1L): ByteArray {
        val b = ByteArray(188) { 0xFF.toByte() }
        b[0] = 0x47; b[1] = ((pid shr 8) and 0x1F).toByte(); b[2] = (pid and 0xFF).toByte()
        if (base < 0) { b[3] = 0x10; return b } // payload only
        b[3] = 0x30; b[4] = 7; b[5] = 0x10
        b[6] = (base shr 25).toByte(); b[7] = (base shr 17).toByte(); b[8] = (base shr 9).toByte(); b[9] = (base shr 1).toByte()
        b[10] = (((base and 1) shl 7) or 0x7E).toByte(); b[11] = 0
        return b
    }

    /** A stream: packets with PCRs every few packets from [from] in steps of [step]; M2TS adds a 4-byte header each. */
    private fun stream(pcrs: List<Long>, m2ts: Boolean, junkAtStart: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        repeat(junkAtStart) { out.write(0x12) } // a piece cut from the middle of a file starts anywhere
        for (pcr in pcrs) {
            for (pk in listOf(packet(0x1011), packet(0x1100), packet(0x1001, pcr), packet(0x1011))) {
                if (m2ts) out.write(byteArrayOf(0, 0, 0, 0))
                out.write(pk)
            }
        }
        return out.toByteArray()
    }

    @Test fun durationOfABlurayStream() {
        val start = 90_000L * 600                 // the clock does not start at 0 on a disc
        val head = stream((0..20).map { start + it * 9_000L }, m2ts = true)
        val end = start + 90_000L * 8_000         // 2 h 13 min 20 s later
        val tail = stream((0..20).map { end - (20 - it) * 9_000L }, m2ts = true, junkAtStart = 77)
        assertEquals(8_000_000L, TsClock.durationMs(head, tail))
    }

    @Test fun plainTsAndAClockThatWraps() {
        val wrap = 1L shl 33
        val head = stream(listOf(wrap - 90_000L * 10, wrap - 90_000L * 9), m2ts = false)
        val tail = stream(listOf(90_000L * 50, 90_000L * 51), m2ts = false, junkAtStart = 5)
        assertEquals(61_000L, TsClock.durationMs(head, tail)) // 10 s before the wrap + 51 s after
    }

    @Test fun noClockNoDuration() {
        assertNull(TsClock.durationMs(ByteArray(4096), ByteArray(4096)))
        assertTrue(TsClock.pcrs(ByteArray(10)).isEmpty())
    }

    @Test fun recognisesBlurayFiles() {
        assertTrue(TsClock.isTs("https://x/api/file/proxy/00800.m2ts?link=a&sig=b"))
        assertTrue(!TsClock.isTs("https://x/api/file/proxy/Film.mkv?link=a"))
    }
}
