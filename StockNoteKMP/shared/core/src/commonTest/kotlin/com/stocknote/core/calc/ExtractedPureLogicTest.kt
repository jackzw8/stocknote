package com.stocknote.core.calc

import com.stocknote.core.csv.CsvCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **从上帝类抽出来的纯逻辑的护栏测试**（2026-09-28 拆分配套）。
 *
 * 背景：`PortfolioRepository`（3200+ 行 / 117 方法）里塞着若干**纯逻辑私有函数**，
 * 它们不依赖数据库、本就该在 core 层 —— 但在 repo 里**没法单测**（data 模块没有 jvm target，
 * 也没有可用的 JDBC 驱动）。上提到 core 之后就能在这里直接测。
 *
 * 这也是后续「把实现搬进域 Repository」的安全网：先有断言，再搬代码。
 */
class ExtractedPureLogicTest {

    // ---- CsvCodec.num（原 PortfolioRepository.trimNum）----

    @Test
    fun csv数字格式化_去尾零并兜底() {
        assertEquals("1234.5", CsvCodec.num(1234.5000))
        assertEquals("100", CsvCodec.num(100.0))
        assertEquals("0", CsvCodec.num(0.0))
        // 负零：fixedPlain 会给出 "-0"，去尾零后剩 "-" —— 必须兜底成 "0"
        assertEquals("0", CsvCodec.num(-0.0))
        assertEquals("0.0001", CsvCodec.num(0.0001))
        assertEquals("-2.25", CsvCodec.num(-2.2500))
    }

    @Test
    fun csv数字格式化_四位截断() {
        // 超过 4 位的小数按 4 位定长（不四舍五入到更多位）
        assertEquals("1.2346", CsvCodec.num(1.23456789))
    }

    @Test
    fun csv数字格式化_往返可解析() {
        // 导出的数字必须能被原样读回来（CSV 导出的基本契约）
        for (v in listOf(0.0, 1.0, 1234.5, -2.25, 0.0001, 99.9999)) {
            val s = CsvCodec.num(v)
            val back = s.toDoubleOrNull()
            assertEquals(true, back != null, "导出的数字应可解析：$s")
            assertEquals(v, back!!, 1e-9, "往返应等值时：$v → $s")
        }
    }

    // ---- CivilDate.prevIso（原 PortfolioRepository.prevDayOf）----

    @Test
    fun 前一日_普通与跨月() {
        assertEquals("2026-09-27", CivilDate.prevIso("2026-09-28"))
        assertEquals("2026-09-30", CivilDate.prevIso("2026-10-01"))  // 跨月
        assertEquals("2026-08-31", CivilDate.prevIso("2026-09-01"))  // 跨月（8 月 31 天）
    }

    @Test
    fun 前一日_跨年与闰年() {
        assertEquals("2025-12-31", CivilDate.prevIso("2026-01-01"))  // 跨年
        assertEquals("2024-02-29", CivilDate.prevIso("2024-03-01"))  // 闰年 2 月 29 日
        assertEquals("2025-02-28", CivilDate.prevIso("2025-03-01"))  // 平年 2 月 28 日
    }

    @Test
    fun 前一日_与天数差自洽() {
        // prevIso 与既有的 daysBetween 口径必须一致（两个函数都用于 as-of 判断）
        val today = "2026-09-28"
        val prev = CivilDate.prevIso(today)
        assertEquals(1L, CivilDate.daysBetween(prev, today))
    }
}
