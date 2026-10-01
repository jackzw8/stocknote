package com.stocknote.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {

    @Test
    fun `金额千分位与两位小数`() {
        assertEquals("¥1,286,540.00", Format.money(1_286_540.0))
        assertEquals("¥0.00", Format.money(0.0))
        assertEquals("¥4,300.00", Format.money(4300.0))
        assertEquals("¥604,320.00", Format.money(604_320.0))
    }

    @Test
    fun `负号在货币符外侧`() {
        assertEquals("-¥1,200.50", Format.money(-1200.5))
        assertEquals("+¥4,300.00", Format.moneySigned(4300.0))
        assertEquals("-¥1,200.50", Format.moneySigned(-1200.5))
        assertEquals("¥0.00", Format.moneySigned(0.0))
    }

    @Test
    fun `数量 - 整数不补零 小数去尾零`() {
        assertEquals("100", Format.quantity(100.0))
        assertEquals("30,000", Format.quantity(30_000.0))
        assertEquals("3.92", Format.quantity(3.92))
        assertEquals("3.5", Format.quantity(3.5))
        assertEquals("-40", Format.quantity(-40.0))
    }

    @Test
    fun `百分比 - 带符号与不可计算`() {
        assertEquals("+9.72%", Format.percent(0.0972))
        assertEquals("-5.70%", Format.percent(-0.057))
        assertEquals("0.00%", Format.percent(0.0))
        assertEquals("—", Format.percent(null))
        assertEquals("1.23%", Format.percent(0.0123, signed = false))
    }

    @Test
    fun `时间戳格式化`() {
        // 20709 天 = 2026-09-13；+12h
        assertEquals("2026-09-13 12:00", Format.epochMillisToDateTime(1_789_300_800_000L))
        assertEquals("1970-01-01 00:00", Format.epochMillisToDateTime(0L))
    }
}
