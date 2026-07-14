package dev.yukivpn.protocol.ipsec

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object IkeCrypto {
    data class DhKeyPair(val group: Int, val privateValue: BigInteger, val publicValue: ByteArray)
    data class Phase1Keys(
        val skeyid: ByteArray,
        val skeyidD: ByteArray,
        val skeyidA: ByteArray,
        val skeyidE: ByteArray,
        val encryptionKey: ByteArray,
    )

    fun generateDh(group: Int, random: SecureRandom = SecureRandom()): DhKeyPair {
        val prime = prime(group)
        val privateValue = BigInteger(256, random).setBit(255)
        return DhKeyPair(group, privateValue, fixed(BigInteger.valueOf(2).modPow(privateValue, prime), byteLength(prime)))
    }

    fun sharedSecret(pair: DhKeyPair, peerPublic: ByteArray): ByteArray {
        val prime = prime(pair.group)
        val peer = BigInteger(1, peerPublic)
        require(peer > BigInteger.ONE && peer < prime - BigInteger.ONE) { "Invalid DH public value" }
        return fixed(peer.modPow(pair.privateValue, prime), byteLength(prime))
    }

    fun derivePhase1(
        psk: ByteArray,
        nonceI: ByteArray,
        nonceR: ByteArray,
        sharedSecret: ByteArray,
        cookieI: ByteArray,
        cookieR: ByteArray,
        keyBytes: Int,
    ): Phase1Keys {
        val skeyid = hmacSha1(psk, nonceI + nonceR)
        val common = sharedSecret + cookieI + cookieR
        val d = hmacSha1(skeyid, common + byteArrayOf(0))
        val a = hmacSha1(skeyid, d + common + byteArrayOf(1))
        val e = hmacSha1(skeyid, a + common + byteArrayOf(2))
        return Phase1Keys(skeyid, d, a, e, expand(e, keyBytes))
    }

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA1").run {
        init(SecretKeySpec(key, "HmacSHA1"))
        doFinal(data)
    }

    fun initialIv(publicI: ByteArray, publicR: ByteArray, blockSize: Int) =
        MessageDigest.getInstance("SHA-1").digest(publicI + publicR).copyOf(blockSize)

    fun crypt(encrypt: Boolean, transform: IkeTransform, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val algorithm = when (transform.encryption) {
            IkeSa.AES_CBC -> "AES"
            IkeSa.TRIPLE_DES -> "DESede"
            else -> error("Unsupported IKE encryption ${transform.encryption}")
        }
        val blockSize = if (algorithm == "AES") 16 else 8
        val data = if (encrypt) input + ByteArray((blockSize - input.size % blockSize) % blockSize) else input
        require(data.size % blockSize == 0)
        return Cipher.getInstance("$algorithm/CBC/NoPadding").run {
            init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, algorithm),
                IvParameterSpec(iv),
            )
            doFinal(data)
        }
    }

    private fun expand(key: ByteArray, size: Int): ByteArray {
        if (size <= key.size) return key.copyOf(size)
        var previous = byteArrayOf(0)
        var output = byteArrayOf()
        while (output.size < size) {
            previous = hmacSha1(key, previous)
            output += previous
        }
        return output.copyOf(size)
    }

    private fun prime(group: Int) = BigInteger(
        when (group) {
            IkeSa.GROUP2 -> GROUP2
            IkeSa.GROUP14 -> GROUP14
            else -> error("Unsupported DH group $group")
        }.filterNot(Char::isWhitespace),
        16,
    )

    private fun byteLength(value: BigInteger) = (value.bitLength() + 7) / 8
    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val unsigned = if (raw.size > size) raw.copyOfRange(raw.size - size, raw.size) else raw
        return ByteArray(size - unsigned.size) + unsigned
    }

    private const val GROUP2 = """
        FFFFFFFF FFFFFFFF C90FDAA2 2168C234 C4C6628B 80DC1CD1
        29024E08 8A67CC74 020BBEA6 3B139B22 514A0879 8E3404DD
        EF9519B3 CD3A431B 302B0A6D F25F1437 4FE1356D 6D51C245
        E485B576 625E7EC6 F44C42E9 A637ED6B 0BFF5CB6 F406B7ED
        EE386BFB 5A899FA5 AE9F2411 7C4B1FE6 49286651 ECE65381
        FFFFFFFF FFFFFFFF
    """
    private const val GROUP14 = """
        FFFFFFFF FFFFFFFF C90FDAA2 2168C234 C4C6628B 80DC1CD1
        29024E08 8A67CC74 020BBEA6 3B139B22 514A0879 8E3404DD
        EF9519B3 CD3A431B 302B0A6D F25F1437 4FE1356D 6D51C245
        E485B576 625E7EC6 F44C42E9 A637ED6B 0BFF5CB6 F406B7ED
        EE386BFB 5A899FA5 AE9F2411 7C4B1FE6 49286651 ECE45B3D
        C2007CB8 A163BF05 98DA4836 1C55D39A 69163FA8 FD24CF5F
        83655D23 DCA3AD96 1C62F356 208552BB 9ED52907 7096966D
        670C354E 4ABC9804 F1746C08 CA18217C 32905E46 2E36CE3B
        E39E772C 180E8603 9B2783A2 EC07A28F B5C55DF0 6F4C52C9
        DE2BCBF6 95581718 3995497C EA956AE5 15D22618 98FA0510
        15728E5A 8AACAA68 FFFFFFFF FFFFFFFF
    """
}
