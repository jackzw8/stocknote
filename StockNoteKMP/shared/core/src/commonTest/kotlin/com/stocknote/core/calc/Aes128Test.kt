package com.stocknote.core.calc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Aes128] 单测：用 **FIPS-197 附录 C.1 的标准测试向量**验证实现正确性。
 *
 * 为什么必须测：巨潮资讯接口的鉴权头 `Accept-Enckey` 就是它的输出 ——
 * 这个实现只要错一位，鉴权就永远失败，而且现象只是"拉不到数据"，极难反查。
 */
class Aes128Test {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun toHex(b: ByteArray): String {
        val t = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        b.forEach { v ->
            val x = v.toInt() and 0xff
            sb.append(t[x shr 4]).append(t[x and 0xf])
        }
        return sb.toString()
    }

    @Test
    fun fips197C1Vector() {
        // FIPS-197 C.1：AES-128 单块加密
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex("00112233445566778899aabbccddeeff")
        val expect = "69c4e0d86a7b0430d8cdb78070b4c55a"
        // CBC + 全零 IV 且明文恰 16 字节时，第一块 == ECB(plain)（PKCS7 会再补一整块，故取前 16 字节）
        val out = Aes128.cbcEncrypt(key, ByteArray(16), plain)
        assertEquals(32, out.size, "PKCS7 对 16 字节明文应补满一整块")
        assertEquals(expect, toHex(out.copyOf(16)))
    }

    @Test
    fun base64Standard() {
        assertEquals("aGVsbG8=", Aes128.base64("hello".encodeToByteArray()))
        assertEquals("AQIDBA==", Aes128.base64(byteArrayOf(1, 2, 3, 4)))
        // 巨潮用的正是「时间戳字符串 → AES → Base64」，这里顺手验证长度随输入增长
        val ts = Aes128.base64(Aes128.cbcEncrypt(
            "1234567887654321".encodeToByteArray(),
            "1234567887654321".encodeToByteArray(),
            "1727193600".encodeToByteArray(),
        ))
        assertEquals(24, ts.length, "10 字节明文 → 补 6 字节 → 16 字节密文 → Base64 24 字符")
    }
}
