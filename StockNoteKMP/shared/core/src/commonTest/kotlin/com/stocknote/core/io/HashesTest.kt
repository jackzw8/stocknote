package com.stocknote.core.io

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Sha1] / [Md5] 的标准向量回归（老周 2026-10-04，为财联社快讯接口的 `sign` 补的）。
 *
 * **为什么必须测**：这两份是**手写的**纯 Kotlin 实现（iOS 上不能用
 * `java.security.MessageDigest`）。手写哈希最典型的错法是"看着像、结果是错的"——
 * 而错的摘要会表现成**接口一直报签名错误**（`errno=10012`），排查时根本猜不到是哈希写错了。
 * 这里用公开标准向量钉死，并覆盖三个最容易翻车的边界：
 *  1. **空输入**（填充分支）；
 *  2. **len ≡ 56 (mod 64)**（必须**多补一整块**，否则长度字段会溢出到新块）；
 *  3. **跨块**（> 64 字节）；
 *  4. **UTF-8**（中文按字节哈希，不是按字符）。
 */
class HashesTest {

    // ------------------------------------------------------------------ SHA-1
    @Test
    fun `SHA1 标准向量`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.hex(ByteArray(0)), "空输入")
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.hex("abc"))
        assertEquals(
            "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12",
            Sha1.hex("The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun `SHA1 边界_56 字节要多补一整块`() {
        assertEquals("c1c8bbdc22796e28c0e15163d20899b65621d65a", Sha1.hex("a".repeat(55)))
        assertEquals("c2db330f6083854c99d4b5bfb6e8f29f201be699", Sha1.hex("a".repeat(56)))
        assertEquals("7f9000257a4918d7072655ea468540cdcbd42e0c", Sha1.hex("a".repeat(100)))
    }

    @Test
    fun `SHA1 按 UTF-8 字节计算`() {
        // "财联社" = 9 字节 UTF-8；按字符哈希会得到完全不同的值
        assertEquals("5b281f33d1bb11ee2a386752c0f79bed8ccd8d0e", Sha1.hex("财联社"))
    }

    // -------------------------------------------------------------------- MD5
    @Test
    fun `MD5 标准向量`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex(ByteArray(0)), "空输入")
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc"))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            Md5.hex("The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun `MD5 边界_56 字节要多补一整块`() {
        assertEquals("ef1772b6dff9a122358552954ad0df65", Md5.hex("a".repeat(55)))
        assertEquals("3b0c8ac703f828b04c6c197006d17218", Md5.hex("a".repeat(56)))
        assertEquals("36a92cc94a9e0fa21f625f8bfb007adf", Md5.hex("a".repeat(100)))
    }

    @Test
    fun `MD5 按 UTF-8 字节计算`() {
        assertEquals("1680f53a48e96a9532ff0b1974775392", Md5.hex("财联社"))
    }

    // -------------------------------------------------------- 两级哈希（接口 sign）
    @Test
    fun `MD5_of_SHA1 两级哈希`() {
        // 财联社接口的 sign = MD5(SHA1(拼接串))，两级都要对才可能过服务端校验
        val sha1 = Sha1.hex("app=CailianpressWeb&os=web&rn=10&sv=8.4.6")
        assertEquals("2aef5b58b5776913be1d8989ec2e5ebc", Md5.hex(sha1))
    }
}
