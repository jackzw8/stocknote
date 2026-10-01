package com.stocknote.core.calc

/**
 * **纯 Kotlin 的 AES-128-CBC + PKCS7** —— 专用于**巨潮资讯**接口的动态鉴权头 `Accept-Enckey`。
 *
 * ## 算法（《东财F10个股风险扫雷技术方案》§2.4）
 * ```
 * Accept-Enckey = Base64( AES-128-CBC( 明文 = 当前秒级 Unix 时间戳(字符串),
 *                                     key  = "1234567887654321",
 *                                     iv   = "1234567887654321",
 *                                     padding = PKCS7 ) )
 * ```
 * **每次请求都要重新生成**（含当前时间戳）。
 *
 * ## 为什么自己实现（而不是用现成的）
 * 共享层（`shared:core`）**不能依赖平台 API** —— `javax.crypto` 只在 JVM/Android 有，iOS 没有；
 * 为一个鉴权头引入三方加密库也不划算。而这里算法固定、明文只是一个时间戳，实现量很小。
 *
 * ## 正确性
 * 用 FIPS-197 附录 B 的标准测试向量做单测：
 * `key = 000102...0f`、`明文 = 00112233...ff` → 密文应为 `69c4e0d8 6a7b0430 ... 3dfb c55a`。
 */
object Aes128 {

    /** S-box（FIPS-197 标准表；与 saolei.py 内置实现一致，便于逐值对照） */
    private val SBOX = intArrayOf(
        0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
        0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
        0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
        0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
        0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
        0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
        0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
        0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
        0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
        0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
        0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
        0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
        0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
        0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
        0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
        0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16,
    )

    private val RCON = intArrayOf(0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36)

    /** GF(2^8) 乘法（AES 的 xtime 递推） */
    private fun mul(x0: Int, y0: Int): Int {
        var x = x0
        var y = y0
        var r = 0
        repeat(8) {
            if (y and 1 != 0) r = r xor x
            val hi = x and 0x80
            x = (x shl 1) and 0xff
            if (hi != 0) x = x xor 0x1b
            y = y shr 1
        }
        return r
    }

    /** 轮密钥扩展（AES-128 → 11 组 16 字节） */
    private fun expandKey(key: IntArray): Array<IntArray> {
        val w = Array(44) { IntArray(4) }
        for (i in 0 until 4) for (j in 0 until 4) w[i][j] = key[i * 4 + j]
        for (i in 4 until 44) {
            var t = w[i - 1].copyOf()
            if (i % 4 == 0) {
                t = intArrayOf(t[1], t[2], t[3], t[0]).map { SBOX[it] }.toIntArray()
                t[0] = t[0] xor RCON[i / 4 - 1]
            }
            for (j in 0 until 4) w[i][j] = w[i - 4][j] xor t[j]
        }
        return Array(11) { r -> IntArray(16) { k -> w[r * 4 + k / 4][k % 4] } }
    }

    /** 单块加密（16 字节） */
    private fun encryptBlock(block: IntArray, rk: Array<IntArray>): IntArray {
        val s = IntArray(16) { block[it] xor rk[0][it] }
        for (round in 1..10) {
            for (i in 0 until 16) s[i] = SBOX[s[i]]
            // ShiftRows（列主序：state[4*col + row]）
            val shifted = IntArray(16)
            for (c in 0 until 4) for (r in 0 until 4) shifted[c * 4 + r] = s[((c + r) % 4) * 4 + r]
            if (round != 10) {
                // MixColumns
                for (c in 0 until 4) {
                    val a = IntArray(4) { shifted[c * 4 + it] }
                    s[c * 4 + 0] = mul(a[0], 2) xor mul(a[1], 3) xor a[2] xor a[3]
                    s[c * 4 + 1] = a[0] xor mul(a[1], 2) xor mul(a[2], 3) xor a[3]
                    s[c * 4 + 2] = a[0] xor a[1] xor mul(a[2], 2) xor mul(a[3], 3)
                    s[c * 4 + 3] = mul(a[0], 3) xor a[1] xor a[2] xor mul(a[3], 2)
                }
            } else {
                for (i in 0 until 16) s[i] = shifted[i]
            }
            for (i in 0 until 16) s[i] = s[i] xor rk[round][i]
        }
        return s
    }

    /**
     * AES-128-CBC 加密（PKCS7 填充）。
     * @param key 16 字节；iv 16 字节；data 任意长度（自动补齐到 16 的倍数）
     */
    fun cbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 需要 16 字节密钥" }
        require(iv.size == 16) { "IV 需要 16 字节" }
        val rk = expandKey(IntArray(16) { key[it].toInt() and 0xff })

        // PKCS7 填充
        val pad = 16 - (data.size % 16)
        val padded = ByteArray(data.size + pad)
        data.copyInto(padded)
        for (i in data.size until padded.size) padded[i] = pad.toByte()

        val out = ByteArray(padded.size)
        var prev = IntArray(16) { iv[it].toInt() and 0xff }
        var offset = 0
        while (offset < padded.size) {
            val block = IntArray(16) { padded[offset + it].toInt() and 0xff xor prev[it] }
            val enc = encryptBlock(block, rk)
            for (i in 0 until 16) out[offset + i] = enc[i].toByte()
            prev = enc
            offset += 16
        }
        return out
    }

    /**
     * 标准 Base64 编码（把 AES 密文转成鉴权头字符串用）。
     * KMP 共享层没有内置 Base64，故自带一份（20 行，无依赖）。
     */
    fun base64(data: ByteArray): String {
        val t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(t[b0 shr 2])
            sb.append(t[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(if (b1 >= 0) t[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) t[b2 and 0x3f] else '=')
            i += 3
        }
        return sb.toString()
    }
}
