package ru.suslikovanora.media.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorLinkTest {
    private val url = "https://media.test/api/file/proxy/Show%20S01E02.mkv?link=https%3A%2F%2Falldebrid.com%2Ff%2Fabc&exp=1000&sig=ff"

    @Test fun readsTheSignedAddress() {
        assertEquals("https://alldebrid.com/f/abc", MirrorLink.param(url, "link"))
        assertEquals(1000L, MirrorLink.expiresAt(url))
        assertEquals("Show S01E02.mkv", MirrorLink.fileName(url))
    }

    @Test fun expiresWithAMargin() {
        assertTrue(MirrorLink.isExpired(url, nowSec = 1000))
        assertTrue(MirrorLink.isExpired(url, nowSec = 950))          // within the minute before
        assertFalse(MirrorLink.isExpired(url, nowSec = 900))
        assertFalse(MirrorLink.isExpired("https://media.test/film.mkv", nowSec = 5_000)) // no signature, never
    }

    @Test fun renewPathAsksForTheSameFile() {
        assertEquals("/api/file/mirror-url?link=https%3A%2F%2Falldebrid.com%2Ff%2Fabc&name=Show+S01E02.mkv", MirrorLink.renewPath(url))
        assertNull(MirrorLink.renewPath("https://media.test/film.mkv?exp=1"))
    }
}
