package ru.suslikovanora.media.core.torrent

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BencodeTest {

    @Test
    fun testSingleFileBencode() {
        // d 4:info d 6:length i123456e 4:name 9:video.mkv e e
        val bencode = "d4:infod6:lengthi123456e4:name9:video.mkvee".toByteArray(Charsets.ISO_8859_1)
        val files = TorrentInspector.extractFilesFromTorrentBytes(bencode)
        assertEquals(1, files.size)
        assertEquals("video.mkv", files[0].path)
        assertEquals(123456L, files[0].size)
        assertTrue(TorrentInspector.hasVideoFiles(files))
    }

    @Test
    fun testMultiFileBencode() {
        // d 4:info d 4:name 4:game 5:files l d 6:length i999e 4:path l 9:setup.exe e e d 6:length i111e 4:path l 7:doc.txt e e e e e
        val bencode = "d4:infod4:name4:game5:filesld6:lengthi999e4:pathl9:setup.exeeed6:lengthi111e4:pathl7:doc.txteeeee".toByteArray(Charsets.ISO_8859_1)
        val files = TorrentInspector.extractFilesFromTorrentBytes(bencode)
        assertEquals(2, files.size)
        assertEquals("setup.exe", files[0].path)
        assertEquals("doc.txt", files[1].path)
        assertFalse(TorrentInspector.hasVideoFiles(files))
    }

    @Test
    fun testPathUtf8Support() {
        // "фильм.mkv" is 14 bytes in UTF-8
        val bencode = "d4:infod4:name4:test5:filesld6:lengthi500e10:path.utf-8l14:фильм.mkveeeee".toByteArray(Charsets.UTF_8)
        val files = TorrentInspector.extractFilesFromTorrentBytes(bencode)
        assertEquals(1, files.size)
        assertEquals("фильм.mkv", files[0].path)
        assertEquals(500L, files[0].size)
        assertTrue(TorrentInspector.hasVideoFiles(files))
    }

    @Test
    fun testCorruptedBencodeDoesNotThrow() {
        val corrupted = "d4:infod6:lengthi123e".toByteArray()
        val inspection = TorrentInspector.inspect(corrupted)
        assertFalse(inspection.isParsedSuccessfully)
        assertFalse(inspection.hasVideo)
        assertEquals(0, inspection.files.size)
    }
}
