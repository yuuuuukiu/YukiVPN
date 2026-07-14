package dev.yukivpn.protocol.ppp

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object Md4 {
    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val padding = (56 - (input.size + 1) % 64 + 64) % 64
        val message = ByteBuffer.allocate(input.size + 1 + padding + 8).order(ByteOrder.LITTLE_ENDIAN)
            .put(input).put(0x80.toByte()).apply { repeat(padding) { put(0) } }.putLong(bitLength).array()
        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476
        message.asList().chunked(64).forEach { chunk ->
            val x = IntArray(16)
            val buffer = ByteBuffer.wrap(chunk.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(16) { x[it] = buffer.int }
            val aa = a; val bb = b; val cc = c; val dd = d
            fun f(xv: Int, y: Int, z: Int) = (xv and y) or (xv.inv() and z)
            fun g(xv: Int, y: Int, z: Int) = (xv and y) or (xv and z) or (y and z)
            fun h(xv: Int, y: Int, z: Int) = xv xor y xor z
            fun r1(v: Int, x1: Int, x2: Int, x3: Int, k: Int, s: Int) = Integer.rotateLeft(v + f(x1, x2, x3) + x[k], s)
            fun r2(v: Int, x1: Int, x2: Int, x3: Int, k: Int, s: Int) = Integer.rotateLeft(v + g(x1, x2, x3) + x[k] + 0x5a827999, s)
            fun r3(v: Int, x1: Int, x2: Int, x3: Int, k: Int, s: Int) = Integer.rotateLeft(v + h(x1, x2, x3) + x[k] + 0x6ed9eba1, s)
            a = r1(a,b,c,d,0,3); d = r1(d,a,b,c,1,7); c = r1(c,d,a,b,2,11); b = r1(b,c,d,a,3,19)
            a = r1(a,b,c,d,4,3); d = r1(d,a,b,c,5,7); c = r1(c,d,a,b,6,11); b = r1(b,c,d,a,7,19)
            a = r1(a,b,c,d,8,3); d = r1(d,a,b,c,9,7); c = r1(c,d,a,b,10,11); b = r1(b,c,d,a,11,19)
            a = r1(a,b,c,d,12,3); d = r1(d,a,b,c,13,7); c = r1(c,d,a,b,14,11); b = r1(b,c,d,a,15,19)
            a = r2(a,b,c,d,0,3); d = r2(d,a,b,c,4,5); c = r2(c,d,a,b,8,9); b = r2(b,c,d,a,12,13)
            a = r2(a,b,c,d,1,3); d = r2(d,a,b,c,5,5); c = r2(c,d,a,b,9,9); b = r2(b,c,d,a,13,13)
            a = r2(a,b,c,d,2,3); d = r2(d,a,b,c,6,5); c = r2(c,d,a,b,10,9); b = r2(b,c,d,a,14,13)
            a = r2(a,b,c,d,3,3); d = r2(d,a,b,c,7,5); c = r2(c,d,a,b,11,9); b = r2(b,c,d,a,15,13)
            a = r3(a,b,c,d,0,3); d = r3(d,a,b,c,8,9); c = r3(c,d,a,b,4,11); b = r3(b,c,d,a,12,15)
            a = r3(a,b,c,d,2,3); d = r3(d,a,b,c,10,9); c = r3(c,d,a,b,6,11); b = r3(b,c,d,a,14,15)
            a = r3(a,b,c,d,1,3); d = r3(d,a,b,c,9,9); c = r3(c,d,a,b,5,11); b = r3(b,c,d,a,13,15)
            a = r3(a,b,c,d,3,3); d = r3(d,a,b,c,11,9); c = r3(c,d,a,b,7,11); b = r3(b,c,d,a,15,15)
            a += aa; b += bb; c += cc; d += dd
        }
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(a).putInt(b).putInt(c).putInt(d).array()
    }
}
