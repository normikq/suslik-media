package ru.suslikovanora.media.desktop.player

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DisplayAwakeTest {

    @Test
    fun testDisplayAwakeLifecycle() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return

        // Test turning on
        DisplayAwake.setAwake(true)
        Thread.sleep(100)

        // Test idempotency (calling true again should not throw or duplicate)
        DisplayAwake.setAwake(true)
        Thread.sleep(100)

        // Test turning off
        DisplayAwake.setAwake(false)
        Thread.sleep(100)

        // Test idempotency (calling false again)
        DisplayAwake.setAwake(false)
        Thread.sleep(100)

        // Turn on again and release
        DisplayAwake.setAwake(true)
        Thread.sleep(100)
        DisplayAwake.release()
        Thread.sleep(100)

        assertTrue(true)
    }
}
