package com.stocknote.core.calc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CivilDateTest {

    @Test
    fun `闰年判定`() {
        assertTrue(CivilDate.isLeapYear(2024))
        assertTrue(CivilDate.isLeapYear(2000))
        assertTrue(!CivilDate.isLeapYear(1900))
        assertTrue(!CivilDate.isLeapYear(2026))
    }

    @Test
    fun `天数差 - 跨闰年`() {
        assertEquals(365L, CivilDate.daysBetween("2026-01-01", "2027-01-01"))
        assertEquals(366L, CivilDate.daysBetween("2024-01-01", "2025-01-01"))
        assertEquals(-366L, CivilDate.daysBetween("2025-01-01", "2024-01-01"))
    }

    @Test
    fun `天数差 - 项目实际用到的区间`() {
        assertEquals(255L, CivilDate.daysBetween("2026-01-01", "2026-09-13"))
        assertEquals(0L, CivilDate.daysBetween("2026-09-13", "2026-09-13"))
    }

    @Test
    fun `非法日期被拒绝`() {
        assertFailsWith<IllegalArgumentException> { CivilDate.parseIso("2026-02-30") }
        assertFailsWith<IllegalArgumentException> { CivilDate.parseIso("2026-13-01") }
        // 斜杠刚好落在下标 4 / 7，"只按位置截取"的解析器会误判为 2026-01-01，
        // 因此必须显式校验分隔符 —— 这条用例就是为了钉住这个行为
        assertFailsWith<IllegalArgumentException> { CivilDate.parseIso("2026/01/01") }
        assertFailsWith<IllegalArgumentException> { CivilDate.parseIso("2026-1-1") }
        assertFailsWith<IllegalArgumentException> { CivilDate.parseIso("abcd-01-01") }
    }

    @Test
    fun `减天数 - 跨月跨年（汇率保留策略用）`() {
        // 只保留最近 3 天 → 截止日 = 今天 − 2
        assertEquals("2026-09-16", CivilDate.minusDays("2026-09-18", 2))
        assertEquals("2026-09-30", CivilDate.minusDays("2026-10-02", 2))
        assertEquals("2026-02-28", CivilDate.minusDays("2026-03-02", 2))   // 2026 非闰年
        assertEquals("2025-12-31", CivilDate.minusDays("2026-01-02", 2))   // 跨年
        assertEquals("2026-09-18", CivilDate.minusDays("2026-09-18", 0))   // n=0 原样返回
        assertEquals("2026-09-17", CivilDate.minusDays("2026-10-01", 14))  // 跨月回退
    }
}

class ReturnCalculatorTest {

    @Test
    fun `XIRR - 一年期正好 10 个点`() {
        val flows = listOf(
            CashFlow("2025-01-01", -1000.0),
            CashFlow("2026-01-01", 1100.0),
        )
        assertEquals(0.10, ReturnCalculator.xirr(flows)!!, 1e-6)
    }

    @Test
    fun `XIRR - 两年期年化 10 个点`() {
        val flows = listOf(
            CashFlow("2025-01-01", -1000.0),
            CashFlow("2027-01-01", 1210.0),
        )
        assertEquals(0.10, ReturnCalculator.xirr(flows)!!, 1e-6)
    }

    @Test
    fun `XIRR - 亏损场景返回负值`() {
        val flows = listOf(
            CashFlow("2025-01-01", -1000.0),
            CashFlow("2026-01-01", 900.0),
        )
        assertEquals(-0.10, ReturnCalculator.xirr(flows)!!, 1e-6)
    }

    @Test
    fun `XIRR - 现金流同号时无解`() {
        assertNull(ReturnCalculator.xirr(listOf(CashFlow("2025-01-01", -100.0), CashFlow("2026-01-01", -200.0))))
        assertNull(ReturnCalculator.xirr(listOf(CashFlow("2025-01-01", 100.0))))
    }

    @Test
    fun `XIRR - 分批投入的真实收益`() {
        // 2025-01-01 投 1000，2025-07-01 再投 1000，2026-01-01 值 2200
        // 手工核算：第二笔资金只占用半年，年化应高于简单口径的 10%
        val flows = listOf(
            CashFlow("2025-01-01", -1000.0),
            CashFlow("2025-07-01", -1000.0),
            CashFlow("2026-01-01", 2200.0),
        )
        val r = ReturnCalculator.xirr(flows)!!
        assertTrue(r > 0.10, "资金加权年化应高于简单收益率，实际 $r")
        assertTrue(r < 0.25, "年化不应高得离谱，实际 $r")
    }

    @Test
    fun `账户现金流 - 期初现金计入且锚定最早事实`() {
        val flows = ReturnCalculator.accountFlows(
            openingCash = 401_920.0,
            openingAnchorIso = "2026-02-11",
            explicit = listOf(CashFlow("2026-09-14", -100_000.0)),
            terminalValue = 1_321_285.84,
            terminalDateIso = "2026-09-14",
        )
        assertEquals(3, flows.size)
        assertEquals(CashFlow("2026-02-11", -401_920.0), flows.first())
        // 真机场景手工核算：215 天资金翻了 3.04 倍 → 年化 ≈ 560%
        val r = ReturnCalculator.xirr(flows)!!
        assertEquals(5.60, r, 0.01)
    }

    @Test
    fun `账户现金流 - 全程同日无法年化返回 null`() {
        // 回归：此前只有「当日存入 + 当日总资产」两条流，跨度为零，图上显示 —
        val flows = ReturnCalculator.accountFlows(
            openingCash = 0.0,
            openingAnchorIso = null,
            explicit = listOf(CashFlow("2026-09-14", -100_000.0)),
            terminalValue = 1_321_285.84,
            terminalDateIso = "2026-09-14",
        )
        assertNull(ReturnCalculator.xirr(flows))
    }

    @Test
    fun `账户现金流 - 无期初无流出时只保留显式与期末`() {
        val flows = ReturnCalculator.accountFlows(
            openingCash = 0.0,
            openingAnchorIso = "2026-02-11", // 开口现金为 0 时不产生锚定流
            explicit = emptyList(),
            terminalValue = 0.0, // 期末为 0 不计
            terminalDateIso = "2026-09-14",
        )
        assertEquals(0, flows.size)
        assertNull(ReturnCalculator.xirr(flows))
    }

    @Test
    fun `账户现金流 - 一年期正好 10 个点（期初口径）`() {
        // 期初 1000 投入满一年，期末值 1100 —— 与直接构造现金流等价
        val flows = ReturnCalculator.accountFlows(
            openingCash = 1000.0,
            openingAnchorIso = "2025-01-01",
            explicit = emptyList(),
            terminalValue = 1100.0,
            terminalDateIso = "2026-01-01",
        )
        assertEquals(0.10, ReturnCalculator.xirr(flows)!!, 1e-6)
    }

    @Test
    fun `最大回撤`() {
        assertEquals(0.25, ReturnCalculator.maxDrawdown(listOf(1.0, 1.2, 0.9, 1.1)), 1e-9)
        assertEquals(0.0, ReturnCalculator.maxDrawdown(listOf(1.0, 1.1, 1.2)), 1e-9)
        assertEquals(0.0, ReturnCalculator.maxDrawdown(emptyList()), 1e-9)
    }

    @Test
    fun `累计收益率 - 成本为零时不返回 Infinity`() {
        assertNull(ReturnCalculator.cumulativeReturn(100.0, 0.0))
        assertEquals(0.1, ReturnCalculator.cumulativeReturn(100.0, 1000.0)!!, 1e-9)
    }
}

class SeriesBuilderTest {

    @Test
    fun `无资金进出时净值与总资产成正比`() {
        val nav = SeriesBuilder.timeWeightedNetValue(listOf(100.0, 110.0, 121.0))
        assertEquals(3, nav.size)
        assertEquals(1.0, nav[0], 1e-9)
        assertEquals(1.1, nav[1], 1e-9)
        assertEquals(1.21, nav[2], 1e-9)
    }

    @Test
    fun `大额入金不会被误算成收益`() {
        // 第二天入金 100，总资产从 100 涨到 210 —— 实际只赚了 5%
        val nav = SeriesBuilder.timeWeightedNetValue(
            marketValues = listOf(100.0, 210.0),
            netFlows = listOf(0.0, 100.0),
        )
        assertEquals(1.05, nav[1], 1e-9, "剥离入金影响后真实收益应为 5%")
    }

    @Test
    fun `归一化与移动平均`() {
        assertEquals(listOf(1.0, 2.0, 3.0), SeriesBuilder.normalize(listOf(10.0, 20.0, 30.0)))
        val ma = SeriesBuilder.movingAverage(listOf(1.0, 2.0, 3.0, 4.0), 2)
        assertNull(ma[0])
        assertEquals(1.5, ma[1]!!, 1e-9)
        assertEquals(3.5, ma[3]!!, 1e-9)
    }

    @Test
    fun `长度不一致时快速失败`() {
        assertFailsWith<IllegalArgumentException> {
            SeriesBuilder.timeWeightedNetValue(listOf(1.0, 2.0), listOf(0.0))
        }
    }
}

class ChartMathTest {

    @Test
    fun `值域留白`() {
        val r = ChartMath.rangeOf(listOf(1.0, 2.0, 3.0), padRatio = 0.05)
        assertEquals(0.9, r.min, 1e-9)
        assertEquals(3.1, r.max, 1e-9)
    }

    @Test
    fun `等值序列不会退化成零区间`() {
        val r = ChartMath.rangeOf(listOf(5.0, 5.0, 5.0))
        assertTrue(r.span > 0.0, "区间跨度必须为正，否则后面除零")
    }

    @Test
    fun `y 坐标 - 最大值靠上 最小值靠下`() {
        val r = ChartMath.Range(0.0, 10.0)
        val topPad = 10f
        val bottomPad = 20f
        val height = 130f
        assertEquals(10f, ChartMath.yFor(10.0, r, height, topPad, bottomPad), 1e-4f)
        assertEquals(110f, ChartMath.yFor(0.0, r, height, topPad, bottomPad), 1e-4f)
        assertEquals(60f, ChartMath.yFor(5.0, r, height, topPad, bottomPad), 1e-4f)
    }

    @Test
    fun `x 坐标 - 首尾贴边 单点居中 越界被夹取`() {
        assertEquals(10f, ChartMath.xFor(0, 3, 100f, 10f, 10f), 1e-4f)
        assertEquals(90f, ChartMath.xFor(2, 3, 100f, 10f, 10f), 1e-4f)
        assertEquals(50f, ChartMath.xFor(0, 1, 100f, 10f, 10f), 1e-4f)
        assertEquals(90f, ChartMath.xFor(99, 3, 100f, 10f, 10f), 1e-4f)
    }

    @Test
    fun `刻度步长归整到 1 2 5 10`() {
        assertEquals(1.0, ChartMath.niceStep(0.7), 1e-9)
        assertEquals(1.0, ChartMath.niceStep(1.0), 1e-9)
        assertEquals(2.0, ChartMath.niceStep(1.5), 1e-9)
        assertEquals(5.0, ChartMath.niceStep(3.0), 1e-9)
        assertEquals(10.0, ChartMath.niceStep(7.0), 1e-9)
        assertEquals(20.0, ChartMath.niceStep(12.0), 1e-9)
    }

    @Test
    fun `刻度覆盖值域且不越界`() {
        val ticks = ChartMath.ticks(ChartMath.Range(0.9, 3.1), maxTicks = 4)
        assertEquals(listOf(1.0, 2.0, 3.0), ticks)
    }
}
