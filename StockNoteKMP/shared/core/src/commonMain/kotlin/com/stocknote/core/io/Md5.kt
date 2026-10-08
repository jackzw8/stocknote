package com.stocknote.core.io

/**
 * 纯 Kotlin 的 **MD5**（跨平台）—— 老周 2026-10-04。
 *
 * **用途**：财联社 7×24 快讯接口的 `sign`（`sign = MD5(SHA1(参数字典序拼接))`，
 * SHA1 见 [Sha1]，组装见 `com.stocknote.data.net.FlashNewsSource`）。
 *
 * **为什么自己写**：`java.security.MessageDigest` 是 **JVM 专有 API**，
 * Kotlin/Native（iOS）上不存在 → 用了 iOS 直接编译失败。与 [Sha256] 同一套路子。
 *
 * ⚠️ 这里**只是**过接口校验用的摘要，不是安全边界 —— MD5 早已不适合做完整性/凭证。
 *
 * ⚠️ 写法注意（与 [Sha256] 同一个坑）：常量表里 `0xd76aa478` 这类**超过 `Int.MAX_VALUE`
 * 的十六进制字面量**，Kotlin 会推断成 `Long`（放进 `intArrayOf` 直接报类型错）——
 * 所以**每个字面量都显式 `.toInt()`**。
 */
object Md5 {

    private val K = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db.toInt(), 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a.toInt(), 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8.toInt(), 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122.toInt(), 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821.toInt(),
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51.toInt(), 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453.toInt(), 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6.toInt(), 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed.toInt(),
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9.toInt(), 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122.toInt(), 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9.toInt(), 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6.toInt(), 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05.toInt(),
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8.toInt(), 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97.toInt(), 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3.toInt(), 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f.toInt(), 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1.toInt(),
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb.toInt(), 0xeb86d391.toInt(),
    )

    /** 每轮左移位数（RFC 1321 §3.4）。 */
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** 输入字节 → **32 位小写十六进制串**（MD5 摘要固定 16 字节）。 */
    fun hex(bytes: ByteArray): String =
        digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** 输入字符串（**UTF-8**）→ 32 位小写十六进制串。 */
    fun hex(text: String): String = hex(text.encodeToByteArray())

    /** 输入字节 → 16 字节摘要（RFC 1321）。 */
    fun digest(message: ByteArray): ByteArray {
        // ---- 填充：先补 0x80，再补 0 到 (len ≡ 56 mod 64)，末尾 8 字节**小端**位长 ----
        val bitLen = message.size.toLong() * 8L
        val padLen = ((56 - (message.size + 1) % 64) + 64) % 64
        val total = ByteArray(message.size + 1 + padLen + 8)
        message.copyInto(total)
        total[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            total[total.size - 8 + i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }

        var a0 = 0x67452301
        var b0 = 0xEFCDAB89.toInt()
        var c0 = 0x98BADCFE.toInt()
        var d0 = 0x10325476

        val m = IntArray(16)
        var offset = 0
        while (offset < total.size) {
            for (i in 0 until 16) {
                val p = offset + i * 4
                // ⚠️ MD5 的块字是**小端**（与 SHA 系列相反）
                m[i] = (total[p].toInt() and 0xFF) or
                    ((total[p + 1].toInt() and 0xFF) shl 8) or
                    ((total[p + 2].toInt() and 0xFF) shl 16) or
                    ((total[p + 3].toInt() and 0xFF) shl 24)
            }

            var a = a0
            var b = b0
            var c = c0
            var d = d0

            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }
                    i < 32 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) % 16
                    }
                    i < 48 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }
                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                val tmp = d
                d = c
                c = b
                // Int 溢出即 32 位模运算（Kotlin 不抛异常），正是算法要的
                b = b + (f + a + K[i] + m[g]).rotateLeft(S[i])
                a = tmp
            }

            a0 += a; b0 += b; c0 += c; d0 += d
            offset += 64
        }

        val out = ByteArray(16)
        val hs = intArrayOf(a0, b0, c0, d0)
        for (idx in hs.indices) {
            val v = hs[idx]
            // ⚠️ 输出同样是**小端**
            out[idx * 4] = v.toByte()
            out[idx * 4 + 1] = (v ushr 8).toByte()
            out[idx * 4 + 2] = (v ushr 16).toByte()
            out[idx * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }
}
