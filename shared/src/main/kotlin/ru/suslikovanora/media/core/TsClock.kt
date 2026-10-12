package ru.suslikovanora.media.core

/**
 * Blu-ray films (.m2ts) and other MPEG transport streams carry no duration field: a player learns the length by reading
 * the stream's clock (PCR) at the very end of the file. VLC does that only for local files — over HTTP (and the app
 * always plays through the local reserve) the length stays 0, so the player showed «--:--» and could not seek.
 *
 * Here the two ends of the file are read (two small requests) and the duration is the difference between the first
 * and the last clock value. With it the player can show the length and seek by position (see the players).
 */
object TsClock {
    private const val PIECE = 1 shl 20          // read this much at each end of the file
    private const val WRAP = 1L shl 33           // the 90 kHz clock wraps after ~26.5 hours

    fun isTs(url: String): Boolean = url.substringBefore('?').substringAfterLast('/').lowercase().let {
        it.endsWith(".m2ts") || it.endsWith(".mts") || it.endsWith(".ts")
    }

    /**
     * Duration (ms) and size (bytes) of the stream at [url], or null. Blocking: the start of the file (its answer also
     * tells the size), then the end — two small requests, 1–3 s on mobile internet.
     */
    fun probe(url: String): Pair<Long, Long>? {
        val (head, total) = BurstProxy.readRange(url, 0, PIECE) ?: return null
        if (total <= 0) return null
        val tail = if (total <= PIECE) head else BurstProxy.readRange(url, total - PIECE, PIECE)?.first ?: return null
        return durationMs(head, tail)?.let { it to total }
    }

    /** Duration from the first clock value in [head] to the last one (of the same stream) in [tail]. */
    fun durationMs(head: ByteArray, tail: ByteArray): Long? {
        val (pid, first) = pcrs(head).firstOrNull() ?: return null
        val last = pcrs(tail).lastOrNull { it.first == pid }?.second ?: return null
        val d = ((last - first) % WRAP + WRAP) % WRAP
        return (d / 90).takeIf { it > 0 }
    }

    /** (PID, clock in 90 kHz units) of every PCR in [buf], in order. Works for 188-byte TS and 192-byte M2TS packets. */
    fun pcrs(buf: ByteArray): List<Pair<Int, Long>> {
        val (start, stride) = sync(buf) ?: return emptyList()
        val out = ArrayList<Pair<Int, Long>>()
        var p = start
        while (p + 188 <= buf.size) {
            if (buf[p].toInt() and 0xFF == 0x47) pcrAt(buf, p)?.let { out += it }
            p += stride
        }
        return out
    }

    /** Where the first packet starts and the packet size: three sync bytes 0x47 in a row at that distance. */
    private fun sync(buf: ByteArray): Pair<Int, Int>? {
        for (stride in intArrayOf(188, 192)) {
            for (o in 0 until minOf(stride, buf.size)) {
                if ((0..3).all { k -> val i = o + k * stride; i < buf.size && buf[i].toInt() and 0xFF == 0x47 }) return o to stride
            }
        }
        return null
    }

    private fun pcrAt(b: ByteArray, p: Int): Pair<Int, Long>? {
        val pid = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
        val adaptation = (b[p + 3].toInt() shr 4) and 0x2
        if (adaptation == 0) return null
        val len = b[p + 4].toInt() and 0xFF
        if (len < 7 || (b[p + 5].toInt() and 0x10) == 0) return null
        fun u(i: Int) = (b[p + i].toLong() and 0xFF)
        val base = (u(6) shl 25) or (u(7) shl 17) or (u(8) shl 9) or (u(9) shl 1) or (u(10) shr 7)
        return pid to base
    }
}
