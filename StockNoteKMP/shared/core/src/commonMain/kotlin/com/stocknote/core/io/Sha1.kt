package com.stocknote.core.io

/**
 * 纯 Kotlin 的 **SHA-1**（跨平台）—— 老周 2026-10-04。
 *
 * **用途**：财联社 7×24 快讯接口的 `sign` 参数（`sign = MD5(SHA1(参数按字典序拼接))`，
 * 见 [Md5] 与 `com.stocknote.data.net.FlashNewsSource`）。
 *
 * **为什么自己写**：`java.security.MessageDigest` 是 **JVM 专有 API**，
 * Kotlin/Native（iOS）上不存在 → 用了 iOS 直接编译失败。
 * 做法与 [Sha256] 完全一致（那一份是 2026-09-30 为备份校验值补的）。
 *
 * ⚠️ 这里**不是**密码学安全边界：签名只是过服务端校验用的，别拿它存密码。
 * SHA-1 本身也早已不适合抗碰撞场景。
 */
object Sha1 {

    /** 输入字节 → **40 位小写十六进制串**（SHA-1 摘要固定 20 字节）。 */
    fun hex(bytes: ByteArray): String =
        digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** 输入字符串（**UTF-8**）→ 40 位小写十六进制串。 */
    fun hex(text: String): String = hex(text.encodeToByteArray())

    /** 输入字节 → 20 字节摘要（FIPS 180-1）。 */
    fun digest(message: ByteArray): ByteArray {
        // ---- 填充：先补 0x80，再补 0 到 (len ≡ 56 mod 64)，末尾 8 字节**大端**位长 ----
        val bitLen = message.size.toLong() * 8L
        val padLen = ((56 - (message.size + 1) % 64) + 64) % 64
        val total = ByteArray(message.size + 1 + padLen + 8)
        message.copyInto(total)
        total[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            total[total.size - 1 - i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }

        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val w = IntArray(80)
        var offset = 0
        while (offset < total.size) {
            for (i in 0 until 16) {
                val p = offset + i * 4
                w[i] = ((total[p].toInt() and 0xFF) shl 24) or
                    ((total[p + 1].toInt() and 0xFF) shl 16) or
                    ((total[p + 2].toInt() and 0xFF) shl 8) or
                    (total[p + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) {
                w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4

            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    i < 20 -> {
                        f = (b and c) or (b.inv() and d)
                        k = 0x5A827999
                    }
                    i < 40 -> {
                        f = b xor c xor d
                        k = 0x6ED9EBA1
                    }
                    i < 60 -> {
                        f = (b and c) or (b and d) or (c and d)
                        k = 0x8F1BBCDC.toInt()
                    }
                    else -> {
                        f = b xor c xor d
                        k = 0xCA62C1D6.toInt()
                    }
                }
                // Int 溢出即 32 位模运算，正是算法要的（Kotlin 不会抛异常）
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d
                d = c
                c = b.rotateLeft(30)
                b = a
                a = temp
            }

            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            offset += 64
        }

        val out = ByteArray(20)
        val hs = intArrayOf(h0, h1, h2, h3, h4)
        for (idx in hs.indices) {
            val v = hs[idx]
            out[idx * 4] = (v ushr 24).toByte()
            out[idx * 4 + 1] = (v ushr 16).toByte()
            out[idx * 4 + 2] = (v ushr 8).toByte()
            out[idx * 4 + 3] = v.toByte()
        }
        return out
    }
}
