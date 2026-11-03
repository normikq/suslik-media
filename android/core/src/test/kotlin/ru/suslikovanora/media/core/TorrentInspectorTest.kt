package ru.suslikovanora.media.core

import ru.suslikovanora.media.core.torrent.TorrentInspector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentInspectorTest {
    @Test fun videoInList_ok() =
        assertNull(TorrentInspector.rejectionForPaths(listOf("Film/readme.txt", "Film/movie.MKV")))

    @Test fun noVideo_rejectedWithExtensions() {
        val r = TorrentInspector.rejectionForPaths(listOf("Game/setup.exe", "Game/data.bin", "Game/readme.txt"))
        assertNotNull(r)
        assertTrue(r.contains(".exe") && r.contains(".bin") && r.contains(".txt"))
    }

    @Test fun emptyList_notJudged() = assertNull(TorrentInspector.rejectionForPaths(emptyList()))

    @Test fun torrentBytes() {
        val game = "d4:infod4:name4:game5:filesld6:lengthi9e4:pathl9:setup.exeeeeee".toByteArray(Charsets.ISO_8859_1)
        assertNotNull(TorrentInspector.rejectionForTorrent(game))
        val film = "d4:infod6:lengthi1e4:name9:video.mkvee".toByteArray(Charsets.ISO_8859_1)
        assertNull(TorrentInspector.rejectionForTorrent(film))
        assertNull(TorrentInspector.rejectionForTorrent("мусор".toByteArray())) // не разобрали — не мешаем
    }

    @Test fun magnetName() {
        assertEquals("Отклонено: не является видеофайлом (.exe)", TorrentInspector.rejectionForMagnetName("setup.exe"))
        assertNull(TorrentInspector.rejectionForMagnetName("Some.Movie.2020.1080p"))
    }
}
