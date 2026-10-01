package com.stocknote.core.io

/**
 * 极简 ZIP 打包器（**仅 STORED 存储模式**，不压缩）—— 纯 Kotlin，无平台依赖。
 *
 * 用途：把「交易记录 / 出入金 / 交易计划」三个空白模板 CSV 打成一个 zip 一次下载
 * （老周 2026-09-21）。模板文件都是几百字节的纯文本，压缩与否没有意义；
 * 而 STORED 模式不需要实现 DEFLATE，几十行就能写对，两端（Android/iOS）行为一致。
 *
 * 格式依据 PKWARE APPNOTE：
 *  - 本地文件头 `0x04034B50`；中央目录头 `0x02014B50`；结尾 `0x06054B50`
 *  - 通用位标记置 `0x0800`（文件名按 UTF-8 解释）
 *  - 时间戳固定 1980-01-01 00:00（不引入时区/日期依赖，打包结果可复现）
 */
object SimpleZip {

    data class Entry(val name: String, val content: String)

    /** 把若干「文件名 → 文本」打包成 zip 字节。 */
    fun of(entries: List<Entry>): ByteArray {
        require(entries.isNotEmpty()) { "zip 至少要有一个文件" }
        // ⚠️ 轻微-10 修复（2026-09-28）：**校验文件名**。当前没有解压端所以没有 zip-slip 面，
        // 但 `../`、绝对路径、重名会被原样写进包里 —— 将来新增解压功能时这就是现成的注入点。
        // 防御写在这里（构造端），成本几乎为零。
        entries.forEach { e ->
            require(e.name.isNotEmpty()) { "zip 条目名不能为空" }
            require(!e.name.startsWith('/')) { "zip 条目名不能是绝对路径：${e.name}" }
            require(!e.name.contains("..")) { "zip 条目名不能包含路径穿越：${e.name}" }
        }
        require(entries.map { it.name }.toSet().size == entries.size) {
            "zip 条目名重复：${entries.groupBy { it.name }.filterValues { v -> v.size > 1 }.keys}"
        }
        val out = mutableListOf<Byte>()
        val central = mutableListOf<Byte>()

        entries.forEach { e ->
            // ⚠️ 不能用 `toByteArray(Charsets.UTF_8)` —— `Charsets` 是 **JVM 专有**，
            // Kotlin/Native（iOS）上不存在（2026-09-30 首次 iOS 打包实测编译失败）。
            // `encodeToByteArray()` 是 Kotlin 标准库的跨平台 API，默认就是 UTF-8。
            val nameBytes = e.name.encodeToByteArray()
            val data = e.content.encodeToByteArray()
            val crc = crc32(data)
            val offset = out.size

            // ---- 本地文件头 ----
            out.le32(0x04034B50)
            out.le16(20)          // version needed to extract
            out.le16(0x0800)      // flags: UTF-8 文件名
            out.le16(0)           // method: 0 = stored
            out.le16(0)           // mod time
            out.le16(0x0021)      // mod date: 1980-01-01
            out.le32(crc)
            out.le32(data.size.toLong())   // compressed size
            out.le32(data.size.toLong())   // uncompressed size
            out.le16(nameBytes.size)
            out.le16(0)           // extra length
            out += nameBytes.toList()
            out += data.toList()

            // ---- 中央目录项 ----
            central.le32(0x02014B50)
            central.le16(20)      // version made by
            central.le16(20)      // version needed
            central.le16(0x0800)
            central.le16(0)
            central.le16(0)
            central.le16(0x0021)
            central.le32(crc)
            central.le32(data.size.toLong())
            central.le32(data.size.toLong())
            central.le16(nameBytes.size)
            central.le16(0)       // extra
            central.le16(0)       // comment
            central.le16(0)       // disk number start
            central.le16(0)       // internal attrs
            central.le32(0)       // external attrs
            central.le32(offset.toLong())
            central += nameBytes.toList()
        }

        val centralOffset = out.size
        out += central

        // ---- 结尾记录 EOCD ----
        out.le32(0x06054B50)
        out.le16(0)                                  // this disk
        out.le16(0)                                  // disk with central dir
        out.le16(entries.size)                       // entries on this disk
        out.le16(entries.size)                       // total entries
        out.le32(central.size.toLong())              // central dir size
        out.le32(centralOffset.toLong())             // central dir offset
        out.le16(0)                                  // comment length
        return out.toByteArray()
    }

    /** 标准 CRC-32（多项式 0xEDB88320），zip 每项必需。 */
    fun crc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        data.forEach { b ->
            val idx = ((crc xor (b.toLong() and 0xFF)) and 0xFF).toInt()
            crc = (crc shr 8) xor TABLE[idx]
        }
        return (crc xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }

    private val TABLE: LongArray = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) {
            c = if (c and 1L != 0L) 0xEDB88320L xor (c shr 1) else c shr 1
        }
        c and 0xFFFFFFFFL
    }

    // ---- 小端写入 ----

    private fun MutableList<Byte>.le16(v: Int) {
        add((v and 0xFF).toByte())
        add(((v shr 8) and 0xFF).toByte())
    }

    private fun MutableList<Byte>.le32(v: Long) {
        add((v and 0xFF).toByte())
        add(((v shr 8) and 0xFF).toByte())
        add(((v shr 16) and 0xFF).toByte())
        add(((v shr 24) and 0xFF).toByte())
    }
}
