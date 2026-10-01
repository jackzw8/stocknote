package com.stocknote.core.calc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 复盘周期纯函数测试（REQ-NOTE-06）。
 */
class ReviewPeriodTest {

    @Test
    fun 月周期提取() {
        assertEquals("2026-08", ReviewPeriod.monthOf("2026-08-14"))
        assertEquals("2026-01", ReviewPeriod.monthOf("2026-01-01"))
        assertEquals("2026-12", ReviewPeriod.monthOf("2026-12-31"))
    }

    @Test
    fun 季周期提取_边界月份() {
        assertEquals("2026Q1", ReviewPeriod.quarterOf("2026-01-15"))
        assertEquals("2026Q1", ReviewPeriod.quarterOf("2026-03-31"))
        assertEquals("2026Q2", ReviewPeriod.quarterOf("2026-04-01"))
        assertEquals("2026Q3", ReviewPeriod.quarterOf("2026-09-14"))
        assertEquals("2026Q4", ReviewPeriod.quarterOf("2026-12-01"))
    }

    @Test
    fun 非法输入返回null() {
        assertNull(ReviewPeriod.monthOf("abc"))
        assertNull(ReviewPeriod.monthOf("2026-13-01"))
        assertNull(ReviewPeriod.quarterOf("2026-00-10"))
    }

    @Test
    fun 显示名转换() {
        assertEquals("2026年8月", ReviewPeriod.display("2026-08"))
        assertEquals("2026年第3季度", ReviewPeriod.display("2026Q3"))
        assertEquals("怪值", ReviewPeriod.display("怪值"))
    }

    @Test
    fun 周期日期区间() {
        assertEquals("2026-08-01" to "2026-08-31", ReviewPeriod.range("2026-08"))
        assertEquals("2026-02-01" to "2026-02-28", ReviewPeriod.range("2026-02"))
        assertEquals("2024-02-01" to "2024-02-29", ReviewPeriod.range("2024-02"))
        assertEquals("2026-07-01" to "2026-09-30", ReviewPeriod.range("2026Q3"))
        assertEquals("2026-10-01" to "2026-12-31", ReviewPeriod.range("2026Q4"))
    }
}
