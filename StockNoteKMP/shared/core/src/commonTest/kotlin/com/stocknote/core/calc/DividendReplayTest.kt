package com.stocknote.core.calc

import com.stocknote.core.model.Currency
import com.stocknote.core.model.DividendRecord
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 分红送股参与全量重放的测试（REQ-ACC-04）。
 * 规则（技术说明书 5.2）：现金分红降成本、送股摊薄均价、配股按配股价计入成本。
 */
class DividendReplayTest {

    private val sec = Security(
        id = "sec_x",
        symbol = "sh600000",
        name = "测试标的",
        market = Market.A_SHARE,
        currency = Currency.CNY,
    )

    private fun buy(qty: Double, price: Double, date: String, seq: Long) = Transaction(
        id = "b$seq", securityId = sec.id, side = TradeSide.BUY,
        quantity = qty, price = price, tradeDate = date, seq = seq,
    )

    private fun div(exDate: String, type: String, perShare: Double = 0.0, bonus: Double = 0.0, qty: Double = 0.0, rights: Double = 0.0) =
        DividendRecord(
            id = "d_$type$exDate", securityId = sec.id, exDate = exDate, type = type,
            quantity = qty, perShare = perShare, bonusShares = bonus, rightsPrice = rights, fxRate = 1.0,
        )

    @Test
    fun `现金分红 - 每股成本下调且现金累计`() {
        val pos = PositionCalculator.replay(
            sec,
            listOf(buy(100.0, 10.0, "2026-01-05", 1)),
            dividends = listOf(div("2026-02-01", "CASH", perShare = 0.5)),
        )
        // 成本 1000 − 分红 50 = 950 → 均价 9.50；现金 +50
        assertEquals(100.0, pos.quantity, 1e-9)
        assertEquals(9.50, pos.avgCost, 1e-9)
        assertEquals(50.0, pos.dividendCash, 1e-9)
    }

    @Test
    fun `送股 - 股数增加且均价摊薄`() {
        val pos = PositionCalculator.replay(
            sec,
            listOf(buy(100.0, 10.0, "2026-01-05", 1)),
            dividends = listOf(div("2026-02-01", "BONUS", bonus = 50.0)),   // 10送5
        )
        // 股数 150，成本总额不变 1000 → 均价 6.67
        assertEquals(150.0, pos.quantity, 1e-9)
        assertEquals(1000.0, pos.costAmount, 1e-9)
        assertEquals(1000.0 / 150.0, pos.avgCost, 1e-9)
        assertEquals(0.0, pos.dividendCash, 1e-9)
    }

    @Test
    fun `配股 - 按配股价计入成本`() {
        val pos = PositionCalculator.replay(
            sec,
            listOf(buy(100.0, 10.0, "2026-01-05", 1)),
            dividends = listOf(div("2026-02-01", "RIGHTS", qty = 30.0, rights = 5.0)),
        )
        // 成本 1000 + 30×5 = 1150，股数 130 → 均价 ≈ 8.846
        assertEquals(130.0, pos.quantity, 1e-9)
        assertEquals(1150.0, pos.costAmount, 1e-9)
        assertEquals(1150.0 / 130.0, pos.avgCost, 1e-6)
    }

    @Test
    fun `分红按 ex_date 穿插在流水时间线中`() {
        // 1/5 买 100@10 → 2/1 每股分红 1.0 → 3/1 卖 100@9.2
        // 分红后成本 = 1000 − 100 = 900 → 卖出已实现 = (9.2 − 9.0) × 100 = +20
        val pos = PositionCalculator.replay(
            sec,
            listOf(
                buy(100.0, 10.0, "2026-01-05", 1),
                Transaction(
                    id = "s1", securityId = sec.id, side = TradeSide.SELL,
                    quantity = 100.0, price = 9.2, tradeDate = "2026-03-01", seq = 2,
                ),
            ),
            dividends = listOf(div("2026-02-01", "CASH", perShare = 1.0)),
        )
        assertEquals(0.0, pos.quantity, 1e-9)
        assertEquals(20.0, pos.realizedPnl, 1e-9)
        assertEquals(100.0, pos.dividendCash, 1e-9)
    }
}
