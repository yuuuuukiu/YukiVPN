package dev.yukivpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProfileTest {
    @Test
    fun parsesAndDeduplicatesDnsServers() {
        assertEquals(
            listOf("1.1.1.1", "8.8.8.8", "9.9.9.9"),
            parseDnsServers("1.1.1.1, 8.8.8.8；1.1.1.1\n9.9.9.9"),
        )
    }

    @Test
    fun validatesIpv4DnsAddresses() {
        assertTrue(isValidIpv4Address("1.1.1.1"))
        assertTrue(isValidIpv4Address("223.5.5.5"))
        assertFalse(isValidIpv4Address("1.1.1"))
        assertFalse(isValidIpv4Address("256.1.1.1"))
        assertFalse(isValidIpv4Address("dns.example.com"))
    }
}
