package ru.suslikovanora.media.desktop.trackers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EdgeBrowserBridgeTest {
    @Test
    fun testBridgeAvailabilityAndStop() {
        val available = EdgeBrowserBridge.isAvailable()
        println("Edge available on this machine: $available")
        assertDoesNotThrow {
            EdgeBrowserBridge.stop()
        }
    }

    @Test
    fun testFormatTrackerCookies() {
        val cookies = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "bb_session")
                put("value", "0-12345-abcdef")
                put("domain", ".rutracker.org")
            })
            put(JSONObject().apply {
                put("name", "cf_clearance")
                put("value", "token987")
                put("domain", ".rutracker.org")
            })
            put(JSONObject().apply {
                put("name", "other_cookie")
                put("value", "some_val")
                put("domain", ".google.com")
            })
        }

        val formatted = EdgeBrowserBridge.formatTrackerCookies(cookies, "https://rutracker.org")
        assertNotNull(formatted)
        assertTrue(formatted!!.contains("bb_session=0-12345-abcdef"))
        assertTrue(formatted.contains("cf_clearance=token987"))
        assertTrue(formatted.contains("bb_ssl=1"))
        assertFalse(formatted.contains("other_cookie"))
    }

    @Test
    fun testBrowserDetection() {
        val detected = BrowserDetector.detect()
        println("Detected browsers: ${detected.map { it.title }}")
        assertTrue(detected.isNotEmpty(), "At least one browser should be detected on the system")
        val edge = detected.firstOrNull { it.type == BrowserType.EDGE }
        assertNotNull(edge, "Microsoft Edge should be detected")
    }
}
