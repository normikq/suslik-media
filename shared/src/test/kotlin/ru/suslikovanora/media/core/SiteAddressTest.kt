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
        assertTrue(SiteAddress.isOwn("https://site.test", "https://site.test"))
        assertTrue(SiteAddress.isOwn("$base?x=1", base))
        assertTrue(SiteAddress.isOwn("$base/api/file/proxy/Film.Name..2024.mkv?link=a", base)) // dots inside a name
    }

    @Test fun pathCannotLeaveTheBase() {
        val base = "https://site.test/suslik"
        assertFalse(SiteAddress.isOwn("$base/../other/api", base))
        assertFalse(SiteAddress.isOwn("$base/api/..", base))
        assertFalse(SiteAddress.isOwn("$base/./../other", base))
        assertFalse(SiteAddress.isOwn("$base/%2e%2e/other", base))
        assertFalse(SiteAddress.isOwn("$base/%2E./other", base))
        assertFalse(SiteAddress.isOwn("$base/a%2f..%2fb", base))
        assertFalse(SiteAddress.isOwn("$base/..\\other", base))
        assertFalse(SiteAddress.isOwn("$base/%zz", base)) // broken escape: not trusted
    }

    @Test fun schemeAndHostIgnoreCase() {
        val base = "https://site.test/suslik"
        assertTrue(SiteAddress.isOwn("HTTPS://Site.Test/suslik/api", base))
        assertFalse(SiteAddress.isOwn("https://site.test/Suslik/api", base)) // the path keeps its case
        assertFalse(SiteAddress.isOwn("http://site.test/suslik/api", base))
        assertFalse(SiteAddress.isOwn("https://site.test@evil.test/suslik/api", base))
        assertFalse(SiteAddress.isOwn("https://evil.test/x?https://site.test/suslik/", base))
        assertFalse(SiteAddress.isOwn("https://site.test:8443/suslik/api", base))
        assertFalse(SiteAddress.isOwn("site.test/suslik/api", base))
    }
}
