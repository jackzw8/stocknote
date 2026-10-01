package com.stocknote.core.csv

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CsvCodec] 护栏（2026-09-27 补）。
 *
 * 为什么必须补：此前 CSV 编解码**零测试**，而它是「导入 / 导出」的唯一通道 ——
 * 转义错一处，导出的文件就再也导不回来（而用户往往是在换设备/备份时才用它）。
 * 重点覆盖：BOM、CRLF、引号内含逗号/换行/双引号、往返一致性。
 */
class CsvCodecTest {

    @Test
    fun 基本行解析() {
        assertEquals(listOf(listOf("a", "b", "c")), CsvCodec.parse("a,b,c"))
    }

    @Test
    fun 多行解析() {
        assertEquals(
            listOf(listOf("a", "b"), listOf("c", "d")),
            CsvCodec.parse("a,b\nc,d"),
        )
    }

    @Test
    fun 去BOM() {
        // Excel 另存为 CSV 会带 UTF-8 BOM —— 首格不能被污染成 "\uFEFF日期"
        assertEquals(listOf(listOf("a", "b")), CsvCodec.parse("\uFEFFa,b"))
    }

    @Test
    fun CRLF与LF等价() {
        assertEquals(CsvCodec.parse("a,b\nc,d"), CsvCodec.parse("a,b\r\nc,d"))
    }

    @Test
    fun 引号包裹的字段可含逗号() {
        assertEquals(listOf(listOf("a,b", "c")), CsvCodec.parse("\"a,b\",c"))
    }

    @Test
    fun 引号内的换行不当作行分隔() {
        val rows = CsvCodec.parse("\"第一行\n第二行\",x")
        assertEquals(1, rows.size, "引号内的换行必须留在同一字段里")
        assertEquals("第一行\n第二行", rows[0][0])
    }

    @Test
    fun 双引号转义() {
        assertEquals(listOf(listOf("he said \"hi\"")), CsvCodec.parse("\"he said \"\"hi\"\"\""))
    }

    @Test
    fun 空字段与尾随空字段() {
        assertEquals(listOf(listOf("a", "", "c")), CsvCodec.parse("a,,c"))
        assertEquals(listOf(listOf("a", "")), CsvCodec.parse("a,"))
    }

    @Test
    fun escape_必要时才加引号() {
        assertEquals("abc", CsvCodec.escape("abc"))
        assertEquals("\"a,b\"", CsvCodec.escape("a,b"))
        assertEquals("\"a\"\"b\"", CsvCodec.escape("a\"b"))
        assertEquals("\"a\nb\"", CsvCodec.escape("a\nb"))
    }

    @Test
    fun 往返_含逗号引号与换行() {
        val rows = listOf(
            listOf("600519", "买入", "1700.50", "备注：\n早盘冲高回落\n\"挂了两次单\""),
            listOf("00700", "卖出", "438.4", "简单备注"),
        )
        val text = CsvCodec.build(rows)
        assertEquals(rows, CsvCodec.parse(text), "导出再导入必须完全一致")
    }

    @Test
    fun toRecords_按表头名映射() {
        val text = "日期,标的,数量\n2026-09-27,600519,100"
        val recs = CsvCodec.toRecords(CsvCodec.parse(text))
        assertEquals(1, recs.size)
        assertEquals("600519", recs[0]["标的"])
        assertEquals("100", recs[0]["数量"])
    }
}
