package dev.yukivpn.protocol.ipsec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class IkeCodecTest {
    @Test
    fun `ISAKMP payload chain round trips`() {
        val packet = IkePacket(
            header = IkeHeader(
                initiatorCookie = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                responderCookie = ByteArray(8),
                firstPayload = IkePayloadType.SA,
                exchangeType = IkePacket.MAIN_MODE,
                flags = 0,
                messageId = 0,
                length = 0,
            ),
            payloads = listOf(
                IkePayload(IkePayloadType.SA, byteArrayOf(1, 2, 3)),
                IkePayload(IkePayloadType.VENDOR_ID, byteArrayOf(4, 5, 6, 7)),
            ),
        )

        val decoded = IkePacket.decode(packet.encode())

        assertArrayEquals(packet.header.initiatorCookie, decoded.header.initiatorCookie)
        assertEquals(IkePacket.MAIN_MODE, decoded.header.exchangeType)
        assertEquals(listOf(IkePayloadType.SA, IkePayloadType.VENDOR_ID), decoded.payloads.map { it.type })
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), decoded.payloads[1].body)
    }

    @Test
    fun `phase one transform is encoded and selected`() {
        val expected = IkeTransform(IkeSa.AES_CBC, IkeSa.SHA1, IkeSa.GROUP14, 128)
        val selected = IkeSa.selected(IkeSa.phase1(listOf(expected)))
        assertEquals(expected, selected)
    }

    @Test
    fun `DH groups derive identical shared secret`() {
        listOf(IkeSa.GROUP2 to 128, IkeSa.GROUP14 to 256).forEach { (group, bytes) ->
            val first = IkeCrypto.generateDh(group)
            val second = IkeCrypto.generateDh(group)
            assertEquals(bytes, first.publicValue.size)
            assertArrayEquals(IkeCrypto.sharedSecret(first, second.publicValue), IkeCrypto.sharedSecret(second, first.publicValue))
        }
    }

    @Test
    fun `phase one AES and 3DES encryption round trip`() {
        val input = "YukiVPN IKE identity payload".toByteArray()
        val cases = listOf(
            IkeTransform(IkeSa.AES_CBC, IkeSa.SHA1, IkeSa.GROUP14, 128) to ByteArray(16) { it.toByte() },
            IkeTransform(IkeSa.TRIPLE_DES, IkeSa.SHA1, IkeSa.GROUP2, null) to ByteArray(24) { it.toByte() },
        )
        cases.forEach { (transform, key) ->
            val blockSize = if (transform.encryption == IkeSa.AES_CBC) 16 else 8
            val iv = ByteArray(blockSize) { (it + 9).toByte() }
            val encrypted = IkeCrypto.crypt(true, transform, key, iv, input)
            val decrypted = IkeCrypto.crypt(false, transform, key, iv, encrypted)
            assertArrayEquals(input, decrypted.copyOf(input.size))
            assertTrue(encrypted.size % blockSize == 0)
        }
    }
}
