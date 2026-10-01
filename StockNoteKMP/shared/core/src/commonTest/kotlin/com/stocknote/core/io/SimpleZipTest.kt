package com.stocknote.core.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleZipTest {

    @Test
    fun crc32MatchesKnownVector() {
        // CRC-32("123456789") = 0xCBF43926 —— 标准校验向量，写错多项式必然对不上
        assertEquals(0xCBF43926L, SimpleZip.crc32("123456789".toByteArray(Charsets.UTF_8)))
        // CRC-32("") = 0
        assertEquals(0L, SimpleZip.crc32(ByteArray(0)))
    }

    @Test
    fun zipStartsWithLocalHeaderAndEndsWithEocd() {
        val bytes = SimpleZip.of(
            listOf(
                SimpleZip.Entry("a.csv", "x"),
                SimpleZip.Entry("b.csv", "y"),
            ),
        )
        // 本地文件头签名 0x04034B50（小端 PK\003\004）
        assertEquals(0x50, bytes[0].toInt() and 0xFF)
        assertEquals(0x4B, bytes[1].toInt() and 0xFF)
        // EOCD 签名 0x06054B50 位于结尾 22 字节处（无注释）
        val eocd = bytes.size - 22
        assertEquals(0x50, bytes[eocd].toInt() and 0xFF)
        assertEquals(0x4B, bytes[eocd + 1].toInt() and 0xFF)
        assertEquals(0x05, bytes[eocd + 2].toInt() and 0xFF)
        assertEquals(0x06, bytes[eocd + 3].toInt() and 0xFF)
        // 条目数 = 2：EOCD 里「本盘条目数」在偏移 8、「总条目数」在偏移 10（各 2 字节）
        assertEquals(2, bytes[eocd + 8].toInt())
        assertEquals(2, bytes[eocd + 10].toInt())
    }

    @Test
    fun contentIsStoredVerbatim() {
        // 用纯 ASCII 文本：STORED 模式下原文按字节出现，压缩过的输出不可能含这段字面量。
        // （中文要用 UTF-8 解码后才等价，那种往返由 SimpleZipJvmTest 用真实中文验证。）
        val text = "date,code\r\n2026-09-16,sh600519\r\n"
        val bytes = SimpleZip.of(listOf(SimpleZip.Entry("trades.csv", text)))
        val haystack = bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
        assertTrue(haystack.contains(text), "原文应原样出现在包里")
    }

    @Test
    fun emptyEntryListIsRejected() {
        val failed = runCatching { SimpleZip.of(emptyList()) }.isFailure
        assertTrue(failed, "空 zip 不该被生成")
    }
}
