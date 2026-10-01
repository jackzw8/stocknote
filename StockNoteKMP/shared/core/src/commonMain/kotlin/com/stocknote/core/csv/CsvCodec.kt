package com.stocknote.core.csv

/**
 * 极简 CSV 编解码（RFC 4180 兼容子集）—— 纯函数，便于单测。
 *
 * 支持：
 *  - 逗号分隔、`"` 包裹字段（字段内含逗号/引号/换行时必需）
 *  - 双引号转义：`""` 表示一个 `"`
 *  - 行尾 `\r\n` 与 `\n` 均识别
 *  - 开头 UTF-8 BOM 自动忽略（Excel 导出常见）
 *
 * 不支持：自定义分隔符、跨行未加引号的字段（非法 CSV）。
 */
object CsvCodec {

    /** 解析为「行 × 列」。空行跳过；每行长度可能不一致（调用方按表头长度处理）。 */
    fun parse(text: String): List<List<String>> {
        val src = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var seenAny = false

        fun endField() {
            row.add(field.toString())
            field.clear()
        }

        fun endRow() {
            endField()
            // 全空且只有一个字段的行视为空行，跳过
            if (row.size > 1 || row[0].isNotEmpty()) rows.add(row.toList())
            row = mutableListOf()
        }

        while (i < src.length) {
            val c = src[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        // 连续两个引号 = 一个引号（转义）
                        if (i + 1 < src.length && src[i + 1] == '"') {
                            field.append('"'); i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == ',' -> endField()
                c == '\n' -> { endRow(); seenAny = true }
                c == '\r' -> { /* 等下一个 \n 或忽略 */ }
                else -> field.append(c)
            }
            i++
        }
        // 收尾（最后一行没有换行符时）
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** 单个字段转义：含逗号/引号/换行的加引号并把 `"` → `""`。 */
    /**
     * ⚠️ 轻微-9 修复（2026-09-28）：**前后空格也加引号**。
     * 此前只对逗号/引号/换行加引号，而 `parse` 端对每个值**无条件 `trim()`** →
     * 带前后空格的字段**往返有损**（导出 ` abc `，导入变 `abc`）。
     *
     * ⚠️ **公式注入（CSV Injection）此处刻意未做**：以 `=`/`+`/`-`/`@` 开头的值在 Excel 打开会被
     * 当公式执行（OWASP 明列），标准防法是导出时前置 `'`——但那会**破坏往返对称**
     * （导入端必须同步剥前缀，且备注以 `-` 开头很常见），牵动导出→导入→备份整条链，
     * 老周 2026-09-28 决定本轮不做，留待 CSV 栏位设计专项处理。
     */
    fun escape(v: String): String {
        val padded = v != v.trim()   // 前后有空格 → 必须加引号，否则往返被 trim 裁掉
        val quoted = padded || v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (quoted) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    /**
     * **CSV 里的数字格式化**：4 位小数、去尾零、空串兜底为 `"0"`。
     *
     * ⚠️ 2026-09-28：从 `PortfolioRepository` 的私有 `trimNum()` **上提到这里**
     *（老周指出"上帝类里塞着 CSV 数字格式化私有函数"—— 问题成立，只是行号不同）。
     * 放在 core 之后**可以在 jvmTest 里直接单测**，不再依赖数据库。
     *
     * 例：`1234.5000 → "1234.5"`、`0.0 → "0"`、`-0.0 → "0"`。
     */
    fun num(v: Double): String {
        val s = com.stocknote.core.format.Format.fixedPlain(v, 4).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }

    /** 一行拼成 CSV 文本（不含换行）。 */
    fun joinRow(cells: List<String>): String = cells.joinToString(",") { escape(it) }

    /** 多行拼成完整 CSV（CRLF 换行，Excel 友好）。 */
    fun build(rows: List<List<String>>): String =
        rows.joinToString("\r\n") { joinRow(it) } + "\r\n"

    /**
     * 把「表头 + 数据行」转成「列名 → 值」的映射（表头按名称匹配，顺序无关）。
     * 缺列时值为 ""。
     */
    fun toRecords(rows: List<List<String>>): List<Map<String, String>> {
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        return rows.drop(1).map { r ->
            header.indices.associate { idx -> header[idx] to (r.getOrNull(idx) ?: "").trim() }
        }
    }
}
