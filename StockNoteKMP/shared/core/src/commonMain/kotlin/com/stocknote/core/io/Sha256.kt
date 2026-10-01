package com.stocknote.core.io

/**
 * 纯 Kotlin 的 **SHA-256**（跨平台）—— 老周 2026-09-30。
 *
 * **为什么自己写**：备份校验值原先用 `java.security.MessageDigest`，那是 **JVM 专有 API**，
 * Kotlin/Native（iOS）上不存在 → `:shared:data:compileKotlinIosArm64` **直接编译失败**
 *（2026-09-30 首次 iOS 打包实测；Android/JVM 一直正常，所以潜伏了很久）。
 *
 * ⚠️ **算法不能换成 CRC32 之类**：checksum 会写进备份文件、恢复时逐字节比对，
 * 换了算法会让**已有备份全部校验失败**。所以这里按 FIPS 180-4 补一份**等价**实现。
 *
 * ⚠️ 用途说明：备份的**机密性与完整性**由 AES-GCM 认证标签保证；SHA-256 这里只做
 * "文件有没有被截断/改坏"的快速校验，**不是**密码学安全边界。
 *
 * ⚠️ 写法注意：常量表里像 `0xb5c0fbcf` 这种**超过 Int.MAX 的十六进制字面量**，
 * Kotlin 会推断成 `Long`（`intArrayOf` 里直接报 "actual type is 'Long'"）——
 * 所以**每个字面量都显式 `.toInt()`**（对本来就是 Int 的值也合法，避免逐个分辨）。
 */
object Sha256 {

    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    /**
     * 输入字节 → **64 位小写十六进制串**。
     *
     * 输出与原先 `MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }`
     * **完全一致**（含前导零），所以历史备份文件仍能通过校验。
     */
    fun hex(bytes: ByteArray): String =
        digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** 输入字节 → 32 字节摘要。 */
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

        var h0 = 0x6a09e667.toInt()
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372.toInt()
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f.toInt()
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19.toInt()

        val w = IntArray(64)
        var offset = 0
        while (offset < total.size) {
            for (i in 0 until 16) {
                val p = offset + i * 4
                w[i] = ((total[p].toInt() and 0xFF) shl 24) or
                    ((total[p + 1].toInt() and 0xFF) shl 16) or
                    ((total[p + 2].toInt() and 0xFF) shl 8) or
                    (total[p + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val x = w[i - 15]
                val y = w[i - 2]
                // rotateRight 用标准库的（跨平台可用），别再自己写 —— 会与 stdlib 重名
                val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
                val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = h + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                h = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }

            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += h
            offset += 64
        }

        val out = ByteArray(32)
        val hs = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
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
