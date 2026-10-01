package com.stocknote.core.calc

import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **送股 / 配股进持仓时间线**的护栏（H3 修复，2026-09-28）。
 *
 * 背景：`PositionCalculator.replay` 正确处理了 BONUS/RIGHTS，但资产曲线的持仓份数
 * 此前**只按交易累加** → A 股 10 送 10 后曲线仍按**原股数**估值，叠加除权日收盘价同步下调，
 * 表现为「总资产莫名其妙掉一半」，用户会当成数据丢失。
 */
class EquityCurveShareEventTest {

    private val sec = Security(
        id = "s1",
        symbol = "sz000001",
        name = "平安银行",
        market = Market.A_SHARE,
        currency = Currency.CNY,
    )

    private fun buy100(date: String) = Transaction(
        id = "t1",
        securityId = "s1",
        side = TradeSide.BUY,
        quantity = 100.0,
        price = 10.0,
        fee = 0.0,
        tradeDate = date,
        seq = 1L,
    )

    /** 10 送 10：除权日股价从 10 掉到 5，但股数翻倍 → 市值**不应**腰斩。 */
    @Test
    fun 送股当天市值不应腰斩() {
        val closes = linkedMapOf(
            "s1" to listOf(
                "2026-06-01" to 10.0,
                "2026-06-02" to 5.0,   // 除权日：价格减半
                "2026-06-03" to 5.0,
            ),
        )
        val events = listOf(EquityCurve.ShareEvent("2026-06-02", "s1", 100.0))

        val withBonus = EquityCurve.build(
            transactions = listOf(buy100("2026-06-01")),
            cashFlows = emptyList(),
            currentCash = 0.0,
            closesBySecurityId = closes,
            shareEvents = events,
        )
        val before = withBonus.first { it.date == "2026-06-01" }.totalAsset
        val onExDate = withBonus.first { it.date == "2026-06-02" }.totalAsset

        // 修复前：6-02 只有 100 股 × 5 = 500（腰斩）；修复后：200 股 × 5 = 1000，与除权前持平
        assertTrue(before > 0.0, "除权前市值应大于 0，实际 $before")
        assertEquals(
            before, onExDate, before * 0.02,
            "送股后市值应基本不变（除权价已同步下调）：除权前=$before 除权日=$onExDate",
        )
    }

    /** 不传 shareEvents 时应保持旧行为（回归保护：不影响未使用该参数的调用方）。 */
    @Test
    fun 不传送股事件时行为不变() {
        val closes = linkedMapOf(
            "s1" to listOf("2026-06-01" to 10.0, "2026-06-02" to 5.0),
        )
        val points = EquityCurve.build(
            transactions = listOf(buy100("2026-06-01")),
            cashFlows = emptyList(),
            currentCash = 0.0,
            closesBySecurityId = closes,
        )
        val onExDate = points.first { it.date == "2026-06-02" }.totalAsset
        // 旧行为：仍按 100 股估值 → 500
        assertEquals(500.0, onExDate, 1.0, "未传事件时应保持旧口径（100 股 × 5 = 500）")
    }

    /** 0 持仓时送股不生效（与 replay 口径一致：无持仓不产生送股）。 */
    @Test
    fun 清仓后的送股不生效() {
        val closes = linkedMapOf(
            "s1" to listOf("2026-06-01" to 10.0, "2026-06-02" to 5.0, "2026-06-03" to 5.0),
        )
        val sellAll = Transaction(
            id = "t2",
            securityId = "s1",
            side = TradeSide.SELL,
            quantity = 100.0,
            price = 10.0,
            fee = 0.0,
            tradeDate = "2026-06-02",
            seq = 2L,
        )
        val points = EquityCurve.build(
            transactions = listOf(buy100("2026-06-01"), sellAll),
            cashFlows = emptyList(),
            currentCash = 0.0,
            closesBySecurityId = closes,
            shareEvents = listOf(EquityCurve.ShareEvent("2026-06-03", "s1", 100.0)),
        )
        // 已清仓 → 送股不应凭空产生持仓 → 6-03 市值仍为 0
        assertEquals(0.0, points.first { it.date == "2026-06-03" }.totalAsset, 1.0)
    }
}
