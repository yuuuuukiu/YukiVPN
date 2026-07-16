package dev.yukivpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileExchangeCodecTest {
    @Test
    fun `portable profile excludes all credentials`() {
        val source = VpnProfile(
            id = "local-id",
            name = "Office",
            protocol = VpnProtocol.L2TP_IPSEC_PSK,
            server = "vpn.example.com",
            port = 1701,
            username = "secret-user",
            password = "secret-password",
            preSharedKey = "secret-psk",
            dnsServers = listOf("1.1.1.1"),
        )

        val encoded = ProfileExchangeCodec.encode(source)
        val imported = ProfileExchangeCodec.decode(encoded).single()

        assertFalse(encoded.contains("secret-user"))
        assertFalse(encoded.contains("secret-password"))
        assertFalse(encoded.contains("secret-psk"))
        assertFalse(encoded.contains("username"))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("preSharedKey"))
        assertEquals("", imported.username)
        assertEquals("", imported.password)
        assertEquals("", imported.preSharedKey)
        assertEquals(source.name, imported.name)
        assertEquals(source.protocol, imported.protocol)
        assertEquals(source.server, imported.server)
        assertEquals(source.port, imported.port)
        assertEquals(source.dnsServers, imported.dnsServers)
        assertNotEquals(source.id, imported.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unrelated JSON`() {
        ProfileExchangeCodec.decode("{\"server\":\"vpn.example.com\"}")
    }

    @Test
    fun `ignores credentials supplied by external files`() {
        val content = """
            {
              "format":"yukivpn-profile",
              "version":1,
              "profile":{
                "name":"Imported",
                "protocol":"L2TP",
                "server":"vpn.example.com",
                "port":1701,
                "username":"untrusted-user",
                "password":"untrusted-password",
                "preSharedKey":"untrusted-psk",
                "dnsServers":[]
              }
            }
        """.trimIndent()

        val imported = ProfileExchangeCodec.decode(content).single()

        assertEquals("", imported.username)
        assertEquals("", imported.password)
        assertEquals("", imported.preSharedKey)
    }
}
