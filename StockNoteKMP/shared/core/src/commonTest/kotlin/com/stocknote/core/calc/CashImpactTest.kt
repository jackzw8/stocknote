package com.stocknote.core.calc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 现金联动（交易对现金账户的影响）单测 —— 2026-09-16 补。
 *
 * 背景：老周发现「港股交易没按汇率折算就增减人民币现金」。
 * 之前的多轮测试**没有覆盖"多币种 × 现金联动"这个组合**，因此补上这组用例，
 * 让"改币种/改汇率"这类改动一定会被测试兜住。
 */
class CashImpactTest {

    // ---------------------------------------------------------------- A股（本位币，不折算）

    @Test
    fun `A股买入_扣价量加费_不折算`() {
        val d = CashImpact.cashDeltaOf("BUY", quantity = 100.0, price = 10.0, fee = 0.25, rateToBase = 1.0)
        assertEquals(-1000.25, d, 1e-9)
    }

    @Test
    fun `A股卖出_加价量减费_不折算`() {
        val d = CashImpact.cashDeltaOf("SELL", quantity = 100.0, price = 10.0, fee = 0.25, rateToBase = 1.0)
        assertEquals(999.75, d, 1e-9)
    }

    // ---------------------------------------------------------------- 港股（必须折算）

    @Test
    fun `港股买入_按汇率折算成人民币扣减`() {
        // 100 股 @ 200 港元，费率万分之3 → 费 6 港元；汇率 0.8547
        val d = CashImpact.cashDeltaOf("BUY", quantity = 100.0, price = 200.0, fee = 6.0, rateToBase = 0.8547)
        // (100×200 + 6) × 0.8547 = 20006 × 0.8547 = 17100.1282
        assertEquals(-(20006.0 * 0.8547), d, 1e-6)
        // 关键断言：不能等于原币数值本身（这正是修复前的缺陷）
        assertEquals(false, d == -20006.0)
    }

    @Test
    fun `港股卖出_按汇率折算成人民币回款`() {
        val d = CashImpact.cashDeltaOf("SELL", quantity = 100.0, price = 200.0, fee = 6.0, rateToBase = 0.8547)
        assertEquals((20000.0 - 6.0) * 0.8547, d, 1e-6)
    }

    @Test
    fun `美股买入_按汇率折算`() {
        // 60 股 @ 210 美元，费 1 美元；汇率 7.2
        val d = CashImpact.cashDeltaOf("BUY", quantity = 60.0, price = 210.0, fee = 1.0, rateToBase = 7.2)
        assertEquals(-(60.0 * 210.0 + 1.0) * 7.2, d, 1e-6)
    }

    @Test
    fun `汇率变化时现金影响同步变化`() {
        val low = CashImpact.cashDeltaOf("BUY", 100.0, 200.0, 0.0, rateToBase = 0.80)
        val high = CashImpact.cashDeltaOf("BUY", 100.0, 200.0, 0.0, rateToBase = 0.90)
        // 汇率越高，同样一笔港股买入扣的人民币越多
        assertTrueAbs(low, high)
    }

    private fun assertTrueAbs(low: Double, high: Double) {
        assertEquals(true, kotlin.math.abs(low) < kotlin.math.abs(high), "汇率越高扣款应越多")
    }

    @Test
    fun `布尔重载与字符串重载一致`() {
        val a = CashImpact.cashDeltaOf("BUY", 100.0, 200.0, 6.0, 0.8547)
        val b = CashImpact.cashDeltaOf(isBuy = true, quantity = 100.0, price = 200.0, fee = 6.0, rateToBase = 0.8547)
        assertEquals(a, b, 1e-12)
    }
}
