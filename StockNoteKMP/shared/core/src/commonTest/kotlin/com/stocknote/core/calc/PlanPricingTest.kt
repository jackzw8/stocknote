package com.stocknote.core.calc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 交易计划计价与校验的单测（REQ-PLAN-01）。
 * 口径来源：需求-交易计划功能.md §4（目标价 × 折扣 → 成交价）、§5（校验与边界）。
 */
class PlanPricingTest {

    // ---- §4.4 示例：目标价 = 100 ----
    @Test
    fun plannedPrice_target100_allDiscounts() {
        assertEquals(40.0, PlanPricing.plannedPriceOf("100", 0.4))
        assertEquals(90.0, PlanPricing.plannedPriceOf("100", 0.9))
        assertEquals(100.0, PlanPricing.plannedPriceOf("100", 1.0))
        assertEquals(120.0, PlanPricing.plannedPriceOf("100", 1.2))
    }

    // ---- 原型示例：1800 × 9折 = 1620 ----
    @Test
    fun plannedPrice_maotaiPrototypeCase() {
        assertEquals(1620.0, PlanPricing.plannedPriceOf("1800.00", 0.9))
    }

    // ---- 两者都有值才计算；缺任一保持原样（返回 null）----
    @Test
    fun plannedPrice_requiresBothValues() {
        assertNull(PlanPricing.plannedPriceOf("100", null), "未选折扣时不计算")
        assertNull(PlanPricing.plannedPriceOf("", 0.9), "目标价为空时不计算")
        assertNull(PlanPricing.plannedPriceOf("abc", 0.9), "目标价非数字时不计算")
        assertNull(PlanPricing.plannedPriceOf("0", 0.9), "目标价须 > 0")
    }

    // ---- 2 位小数（REQ-VIEW-19）：1234.567 × 0.7 = 864.1969 → 864.20 ----
    @Test
    fun plannedPrice_roundsToTwoDecimals() {
        assertEquals(864.2, PlanPricing.plannedPriceOf("1234.567", 0.7))
    }

    // ---- 折扣档位共 9 档，值与文案对齐 ----
    @Test
    fun discountOptions_areNineAndOrdered() {
        val opts = PlanPricing.DISCOUNT_OPTIONS
        assertEquals(9, opts.size)
        assertEquals(
            listOf(0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2),
            opts.map { it.second },
        )
        assertEquals("9折", opts.first { it.second == 0.9 }.first)
        assertEquals("1.2倍", opts.last().first)
    }

    // ---- 距达成空间 ----
    @Test
    fun gapOf_positiveAndNegative() {
        // 计划价高于现价 → 需上涨（正）
        assertEquals(0.2, PlanPricing.gapOf(currentPrice = 100.0, plannedPrice = 120.0))
        // 计划价低于现价 → 已可接（负）
        val gap = PlanPricing.gapOf(currentPrice = 100.0, plannedPrice = 90.0)
        assertNotNull(gap)
        assertTrue(gap < 0.0)
        // 无行情价 → 不展示
        assertNull(PlanPricing.gapOf(null, 90.0))
        assertNull(PlanPricing.gapOf(0.0, 90.0), "现价为 0 不做除法")
    }

    // ---- 校验：备注放开、其余必填 ----
    @Test
    fun validate_noteIsOptional() {
        val issues = PlanPricing.validate(
            securityId = "sec_1",
            targetPriceText = "1800",
            plannedPriceText = "1620",
            quantityText = "100",
            feeRateText = "2.5",
        )
        assertTrue(issues.isEmpty(), "合法表单不应有错误，实际=$issues")
    }

    @Test
    fun validate_blocksMissingFields() {
        val issues = PlanPricing.validate(
            securityId = null,
            targetPriceText = "",
            plannedPriceText = "",
            quantityText = "",
            feeRateText = "",
        )
        val fields = issues.map { it.field }.toSet()
        assertTrue(fields.contains("security"))
        assertTrue(fields.contains("targetPrice"))
        assertTrue(fields.contains("plannedPrice"))
        assertTrue(fields.contains("quantity"))
        assertTrue(fields.contains("feeRate"))
        // 备注不在拦截范围内（区别于记一笔的必填拦截）
        assertTrue(fields.none { it == "note" })
    }

    @Test
    fun validate_rejectsNonPositiveNumbers() {
        val issues = PlanPricing.validate(
            securityId = "sec_1",
            targetPriceText = "0",
            plannedPriceText = "-1",
            quantityText = "-5",
            feeRateText = "-0.1",
        )
        assertEquals(4, issues.size)
    }
}
