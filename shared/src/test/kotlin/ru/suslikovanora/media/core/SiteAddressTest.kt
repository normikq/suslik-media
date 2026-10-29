package ru.suslikovanora.media.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteAddressTest {
    @Test fun normalizesOneSetting() {
        assertEquals("https://site.test", SiteAddress.normalize("https://site.test"))
        assertEquals("https://site.test", SiteAddress.normalize(" https://Site.Test/ "))
        assertEquals("https://site.test", SiteAddress.normalize("site.test")) // only the domain, as in the old secret
        assertEquals("https://site.test/suslik", SiteAddress.normalize("https://site.test/suslik/"))
        assertEquals("https://site.test/a/b", SiteAddress.normalize("https://site.test//a/b//?x=1#y"))
        assertEquals("https://site.test", SiteAddress.normalize("http://site.test")) // plain http only for a local server
        assertEquals("http://localhost:3000", SiteAddress.normalize("http://localhost:3000/"))
        assertEquals("", SiteAddress.normalize(""))
        assertEquals("", SiteAddress.normalize(null))
        assertEquals("", SiteAddress.normalize("https://"))
        assertEquals("", SiteAddress.normalize("https://user@site.test"))
    }

    @Test fun hostAndPath() {
        assertEquals("site.test", SiteAddress.host("https://site.test/suslik"))
        assertEquals("/suslik", SiteAddress.path("https://site.test/suslik"))
        assertEquals("", SiteAddress.path("https://site.test"))
        assertEquals("localhost:3000", SiteAddress.host("http://localhost:3000"))
    }

    @Test fun ownServerIsInsideThePath() {
        val base = "https://site.test/suslik"
        assertTrue(SiteAddress.isOwn("$base/api/library", base))
        assertTrue(SiteAddress.isOwn(base, base))
        assertFalse(SiteAddress.isOwn("https://site.test/other/api", base)) // another service on the same domain
        assertFalse(SiteAddress.isOwn("https://site.test/suslik-evil/x", base))
        assertFalse(SiteAddress.isOwn("https://site.test.evil.com/suslik/x", base))
        assertFalse(SiteAddress.isOwn("https://site.test/x", ""))
        assertTrue(SiteAddress.isOwn("https://site.test/api/x", "https://site.test"))
    }
}
