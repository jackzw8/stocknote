package com.stocknote.core.calc

import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 绩效统计测试（REQ-ANA-01/02/05）。
 * FIFO 配平仓口径，与移动加权（PositionCalculatorTest）互相独立、互不覆盖。
 */
class PerformanceCalculatorTest {

    private val maotai = Security(
        id = "sec_600519",
        symbol = "sh600519",
        name = "贵州茅台",
        market = Market.A_SHARE,
        currency = Currency.CNY,
    )

    private fun tx(
        id: String,
        side: TradeSide,
        qty: Double,
        price: Double,
        date: String,
        seq: Long,
        fee: Double = 0.0,
        tags: List<String> = emptyList(),
        securityId: String = maotai.id,
    ) = Transaction(
        id = id,
        securityId = securityId,
        side = side,
        quantity = qty,
        price = price,
        fee = fee,
        tradeDate = date,
        seq = seq,
        tags = tags,
    )

    // ------------------------------------------------ FIFO 配对

    @Test
    fun `FIFO - 一买一卖配平且持仓周期正确`() {
        val res = PerformanceCalculator.fifoClose(
            maotai,
            listOf(
                tx("b1", TradeSide.BUY, 100.0, 10.0, "2026-01-05", 1),
                tx("s1", TradeSide.SELL, 100.0, 12.0, "2026-02-05", 2),
            ),
        )
        assertEquals(1, res.closed.size)
        assertEquals(0, res.openLots)
        val lot = res.closed.first()
        assertEquals(100.0, lot.quantity, 1e-9)
        assertEquals(200.0, lot.pnl, 1e-9)          // (12-10)*100
        assertEquals(31L, lot.holdingDays)          // 1/5 → 2/5
        assertTrue(lot.isWin)
    }

    @Test
    fun `FIFO - 卖出按最早买入先配且费率按数量分摊`() {
        val res = PerformanceCalculator.fifoClose(
            maotai,
            listOf(
                tx("b1", TradeSide.BUY, 50.0, 10.0, "2026-01-05", 1, fee = 50.0),   // 每股买费 1.0
                tx("b2", TradeSide.BUY, 50.0, 20.0, "2026-01-06", 2, fee = 0.0),
                tx("s1", TradeSide.SELL, 60.0, 15.0, "2026-01-10", 3, fee = 60.0),  // 每股卖费 1.0
            ),
        )
        // FIFO：先配 b1 的 50 股，再配 b2 的 10 股
        assertEquals(2, res.closed.size)
        assertEquals(50.0, res.closed[0].quantity, 1e-9)
        assertEquals(10.0, res.closed[0].buyPrice, 1e-9)
        assertEquals(10.0, res.closed[1].quantity, 1e-9)
        assertEquals(20.0, res.closed[1].buyPrice, 1e-9)

        // 段1：(15-10)*50 − 卖费 50 − 买费 50 = 250 − 100 = 150
        assertEquals(150.0, res.closed[0].pnl, 1e-9)
        // 段2：(15-20)*10 − 卖费 10 − 买费 0 = −60
        assertEquals(-60.0, res.closed[1].pnl, 1e-9)
        assertEquals(0.0, res.oversellQty, 1e-9)
        assertEquals(1, res.openLots)   // b2 还剩 40 股
    }

    @Test
    fun `FIFO - 超卖部分不入统计但计入警示`() {
        val res = PerformanceCalculator.fifoClose(
            maotai,
            listOf(tx("s1", TradeSide.SELL, 30.0, 10.0, "2026-01-05", 1)),
        )
        assertEquals(0, res.closed.size)
        assertEquals(30.0, res.oversellQty, 1e-9)
    }

    // ------------------------------------------------ 绩效统计

    @Test
    fun `绩效 - 胜率盈亏比与平均持仓`() {
        val lots = listOf(
            lot(pnl = 200.0, days = 10, sell = "2026-01-10", buy = "2026-01-01"),
            lot(pnl = 100.0, days = 20, sell = "2026-01-20", buy = "2026-01-01"),
            lot(pnl = -100.0, days = 30, sell = "2026-02-01", buy = "2026-01-01"),
            lot(pnl = 300.0, days = 40, sell = "2026-02-10", buy = "2026-01-01"),
        )
        val s = PerformanceCalculator.stats(lots)
        assertEquals(4, s.closedCount)
        assertEquals(3, s.winCount)
        assertEquals(0.75, s.winRate!!, 1e-9)
        assertEquals(200.0, s.avgWin!!, 1e-9)
        assertEquals(-100.0, s.avgLose!!, 1e-9)
        assertEquals(2.0, s.profitFactor!!, 1e-9)
        assertEquals(25.0, s.avgHoldingDays!!, 1e-9)
        assertEquals(500.0, s.totalRealized, 1e-9)
    }

    @Test
    fun `绩效 - 空样本返回 null 而不是 0`() {
        val s = PerformanceCalculator.stats(emptyList())
        assertEquals(0, s.closedCount)
        assertNull(s.winRate)
        assertNull(s.profitFactor)
        assertNull(s.avgHoldingDays)
    }

    // ------------------------------------------------ 连胜连亏

    @Test
    fun `连胜连亏 - 按卖出日期排序统计游程`() {
        val lots = listOf(
            lot(pnl = 100.0, sell = "2026-01-05", buy = "2026-01-01"),
            lot(pnl = 100.0, sell = "2026-01-06", buy = "2026-01-02"),
            lot(pnl = -50.0, sell = "2026-01-07", buy = "2026-01-03"),
            lot(pnl = -50.0, sell = "2026-01-08", buy = "2026-01-04"),
            lot(pnl = -50.0, sell = "2026-01-09", buy = "2026-01-05"),
            lot(pnl = 80.0, sell = "2026-01-10", buy = "2026-01-06"),
        )
        val s = PerformanceCalculator.streaks(lots)
        // 排序后：+ + − − − +  → 当前连胜 1，当前连亏 0，历史最大连胜 2，历史最大连亏 3
        assertEquals(1, s.currentWin)
        assertEquals(0, s.currentLose)
        assertEquals(2, s.maxWin)
        assertEquals(3, s.maxLose)
    }

    // ------------------------------------------------ 分组

    @Test
    fun `按策略分组 - 以卖出交易的标签为准`() {
        val lots = listOf(
            lot(pnl = 100.0, sell = "2026-01-05", buy = "2026-01-01", tags = listOf("价值投资")),
            lot(pnl = -50.0, sell = "2026-01-06", buy = "2026-01-02", tags = listOf("价值投资")),
            lot(pnl = 80.0, sell = "2026-01-07", buy = "2026-01-03", tags = listOf("突破")),
            lot(pnl = 60.0, sell = "2026-01-08", buy = "2026-01-04"),
        )
        val groups = PerformanceCalculator.byTag(lots)
        assertEquals(3, groups.size)
        val v = groups.first { it.label == "价值投资" }
        assertEquals(2, v.count)
        assertEquals(50.0, v.pnl, 1e-9)
        assertEquals(0.5, v.winRate!!, 1e-9)
        assertTrue(groups.any { it.label == PerformanceCalculator.UNTAGGED })
    }

    @Test
    fun `按市场分组 - 不同标的互不配对`() {
        val etf = Security("sec_etf", "sh510300", "沪深300ETF", Market.ETF, Currency.CNY)
        val closed = PerformanceCalculator.fifoCloseAll(
            listOf(maotai, etf),
            listOf(
                tx("b1", TradeSide.BUY, 100.0, 10.0, "2026-01-05", 1),
                tx("b2", TradeSide.BUY, 100.0, 3.0, "2026-01-06", 2, securityId = etf.id),
                tx("s1", TradeSide.SELL, 100.0, 12.0, "2026-01-08", 3),
                tx("s2", TradeSide.SELL, 100.0, 4.0, "2026-01-09", 4, securityId = etf.id),
            ),
        )
        assertEquals(2, closed.size)
        val groups = PerformanceCalculator.byMarket(listOf(maotai, etf), closed)
        assertEquals(2, groups.size)
        val a = groups.first { it.label == "A股" }
        assertEquals(200.0, a.pnl, 1e-9)
        val e = groups.first { it.label == "ETF" }
        assertEquals(100.0, e.pnl, 1e-9)
    }

    // ------------------------------------------------ 工具

    private fun lot(
        pnl: Double,
        days: Long = 10,
        sell: String,
        buy: String,
        tags: List<String> = emptyList(),
    ) = PerformanceCalculator.ClosedLot(
        securityId = maotai.id,
        symbol = maotai.symbol,
        name = maotai.name,
        quantity = 100.0,
        buyPrice = 10.0,
        sellPrice = 10.0 + pnl / 100.0,
        buyDate = buy,
        sellDate = sell,
        pnl = pnl,
        holdingDays = days,
        sellTags = tags,
    )
}
