package dev.yukivpn.protocol.ipsec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

class EspL2tpTransportTest {
    @Test
    fun `quick mode ESP proposal round trips`() {
        val expected = EspTransform(IpsecSa.ESP_AES_CBC, 128)
        val spi = 0x12345678
        val selected = IpsecSa.selected(IpsecSa.quickMode(spi, listOf(expected)))
        assertEquals(spi, selected.first)
        assertEquals(expected, selected.second)
    }

    @Test
    fun `ESP transports exchange L2TP datagrams in both directions`() {
        val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
        DatagramSocket(0, loopback).use { firstSocket ->
            DatagramSocket(0, loopback).use { secondSocket ->
                firstSocket.connect(loopback, secondSocket.localPort)
                secondSocket.connect(loopback, firstSocket.localPort)
                val transform = EspTransform(IpsecSa.ESP_AES_CBC, 128)
                val firstEncryption = ByteArray(16) { (it + 1).toByte() }
                val secondEncryption = ByteArray(16) { (it + 21).toByte() }
                val firstAuth = ByteArray(20) { (it + 41).toByte() }
                val secondAuth = ByteArray(20) { (it + 61).toByte() }
                val firstPhase = phase(firstSocket, loopback)
                val secondPhase = phase(secondSocket, loopback)
                EspL2tpTransport(
                    firstPhase,
                    EspSecurityAssociations(
                        inboundSpi = FIRST_INBOUND_SPI,
                        inboundEncryptionKey = firstEncryption,
                        inboundAuthenticationKey = firstAuth,
                        outboundSpi = SECOND_INBOUND_SPI,
                        outboundEncryptionKey = secondEncryption,
                        outboundAuthenticationKey = secondAuth,
                        transform = transform,
                    ),
                ).use { first ->
                    EspL2tpTransport(
                        secondPhase,
                        EspSecurityAssociations(
                            inboundSpi = SECOND_INBOUND_SPI,
                            inboundEncryptionKey = secondEncryption,
                            inboundAuthenticationKey = secondAuth,
                            outboundSpi = FIRST_INBOUND_SPI,
                            outboundEncryptionKey = firstEncryption,
                            outboundAuthenticationKey = firstAuth,
                            transform = transform,
                        ),
                    ).use { second ->
                        first.timeoutMillis = 1_000
                        second.timeoutMillis = 1_000
                        val request = "SCCRQ test payload".toByteArray()
                        first.send(request)
                        assertArrayEquals(request, second.receive())
                        val response = "SCCRP test payload".toByteArray()
                        second.send(response)
                        assertArrayEquals(response, first.receive())
                    }
                }
            }
        }
    }

    private fun phase(socket: DatagramSocket, loopback: Inet4Address) = IkeV1PskClient.Phase1Session(
        socket = socket,
        remoteAddress = loopback,
        localAddress = loopback,
        initiatorCookie = ByteArray(8) { 1 },
        responderCookie = ByteArray(8) { 2 },
        transform = IkeTransform(IkeSa.AES_CBC, IkeSa.SHA1, IkeSa.GROUP2, 128),
        keys = IkeCrypto.Phase1Keys(ByteArray(20), ByteArray(20), ByteArray(20), ByteArray(20), ByteArray(16)),
        phase1Iv = ByteArray(16),
    )

    private companion object {
        const val FIRST_INBOUND_SPI = 0x10203040
        const val SECOND_INBOUND_SPI = 0x50607080
    }
}
