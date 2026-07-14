package dev.yukivpn.protocol.ipsec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EspReplayWindowTest {
    @Test
    fun acceptsAuthenticatedPacketsArrivingOutOfOrder() {
        val window = EspReplayWindow()

        assertTrue(window.accept(1))
        assertTrue(window.accept(3))
        assertTrue(window.accept(2))
        assertFalse(window.accept(2))
    }

    @Test
    fun rejectsPacketsOutsideWindowAndSequenceZero() {
        val window = EspReplayWindow()

        assertFalse(window.accept(0))
        assertTrue(window.accept(1))
        assertTrue(window.accept(65))
        assertFalse(window.accept(1))
    }
}
