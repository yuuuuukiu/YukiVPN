package dev.yukivpn.protocol.ipsec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class StrongSwanInteropTest {
    @Test
    fun `negotiates IKEv1 PSK and Quick Mode with strongSwan`() {
        val host = System.getenv("YUKIVPN_IKE_TEST_HOST").orEmpty()
        assumeTrue("YUKIVPN_IKE_TEST_HOST is not configured", host.isNotBlank())

        IkeV1PskClient(protectSocket = { true }).negotiate(host, "yukivpn-test-psk").use { phase1 ->
            val associations = IkeQuickMode().negotiate(phase1)
            assertNotEquals(0, associations.inboundSpi)
            assertNotEquals(0, associations.outboundSpi)
            EspL2tpTransport(phase1, associations).use { transport ->
                transport.timeoutMillis = 3_000
                val payload = "YukiVPN ESP data-plane check".toByteArray()
                transport.send(payload)
                assertArrayEquals(payload, transport.receive())
            }
        }
    }
}
