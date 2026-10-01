package com.stocknote.core.calc

import com.stocknote.core.model.Market
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 印花税规则（REQ-ACC-17，老周 2026-09-30）。
 *
 * 法定的市场差异是这块唯一的复杂点：A 股**只有卖出**收、港股**买卖都收**、
 * ETF / 场外基金 / 美股**不收**；金额四舍五入到分（与券商账单一致）。
 */
class StampDutyTest {

    @Test
    fun A股买入不收_卖出万分之五() {
        // 买入 40 股 @1320：A 股买入免印花税
        assertEquals(
            0.0,
            StampDuty.dutyOf(Market.A_SHARE, isBuy = true, price = 1320.0, quantity = 40.0),
            1e-9,
        )
        // 卖出 40 股 @1320 = 52,800 × 万分之 5 = 26.40
        assertEquals(
            26.40,
            StampDuty.dutyOf(Market.A_SHARE, isBuy = false, price = 1320.0, quantity = 40.0),
            1e-9,
        )
    }

    @Test
    fun 港股买卖双向千分之一() {
        // 200 股 @385 HKD = 77,000 × 千分之 1 = 77.00（买、卖都一样）
        assertEquals(77.00, StampDuty.dutyOf(Market.HK, isBuy = true, price = 385.0, quantity = 200.0), 1e-9)
        assertEquals(77.00, StampDuty.dutyOf(Market.HK, isBuy = false, price = 385.0, quantity = 200.0), 1e-9)
    }

    @Test
    fun ETF与场外基金与美股都不收() {
        listOf(Market.ETF, Market.FUND, Market.US).forEach { m ->
            assertEquals(
                0.0,
                StampDuty.dutyOf(m, isBuy = false, price = 4.32, quantity = 30_000.0),
                1e-9,
                "$m 不应收印花税",
            )
        }
    }

    @Test
    fun 税率可配置_金额四舍五入到分() {
        // 港股税率改成万分之 5（= 千分之 0.5）：200 × 385 × 0.0005 = 38.50
        assertEquals(38.50, StampDuty.dutyOf(Market.HK, true, 385.0, 200.0, hkRate = 5.0), 1e-9)
        // 四舍五入到分：100 × 3.333 × 0.001 = 0.3333 → 0.33
        assertEquals(0.33, StampDuty.dutyOf(Market.HK, true, 3.333, 100.0), 1e-9)
        // 非法输入不抛异常、也不计税
        assertEquals(0.0, StampDuty.dutyOf(Market.HK, true, -1.0, 100.0), 1e-9)
        assertEquals(0.0, StampDuty.dutyOf(Market.HK, true, 10.0, 0.0), 1e-9)
    }

    @Test
    fun 税率查询与计税同源() {
        assertEquals(0.0, StampDuty.rateOf(Market.A_SHARE, isBuy = true), 1e-9)
        assertEquals(5.0, StampDuty.rateOf(Market.A_SHARE, isBuy = false), 1e-9)
        assertEquals(10.0, StampDuty.rateOf(Market.HK, isBuy = true), 1e-9)
        assertEquals(0.0, StampDuty.rateOf(Market.US, isBuy = false), 1e-9)
    }
}
