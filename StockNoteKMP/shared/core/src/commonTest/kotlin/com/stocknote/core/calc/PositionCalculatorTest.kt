package com.stocknote.core.calc

import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.core.model.DividendRecord
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 持仓重放测试。
 * 用例数据取自界面原型已验算过的真实场景（茅台 4 笔流水），
 * 期望值与 界面原型-新版/holdings.html、dashboard.html 完全同源。
 */
class PositionCalculatorTest {

    private val maotai = Security(
        id = "sec_600519",
        symbol = "sh600519",
        name = "贵州茅台",
        market = Market.A_SHARE,
        currency = Currency.CNY,
    )

    private fun tx(id: String, side: TradeSide, qty: Double, price: Double, date: String, seq: Long) =
        Transaction(
            id = id,
            securityId = maotai.id,
            side = side,
            quantity = qty,
            price = price,
            fee = 0.0,
            tradeDate = date,
            seq = seq,
        )

    private val fourTrades = listOf(
        tx("t1", TradeSide.BUY, 60.0, 1600.0, "2026-03-02", 1),
        tx("t2", TradeSide.BUY, 60.0, 1700.0, "2026-04-15", 2),
        tx("t3", TradeSide.SELL, 40.0, 1690.0, "2026-06-08", 3),
        tx("t4", TradeSide.BUY, 20.0, 1800.0, "2026-08-20", 4),
    )

    @Test
    fun `四笔流水重放 - 净持仓与移动加权均价`() {
        val pos = PositionCalculator.replay(maotai, fourTrades, marketPrice = 1723.0)

        assertEquals(100.0, pos.quantity, 1e-6, "净持仓应为 100 股")
        assertEquals(1680.0, pos.avgCost, 1e-6, "移动加权成本均价应为 1680.00")
        assertEquals(168000.0, pos.costAmount, 1e-6)
        assertEquals(1600.0, pos.realizedPnl, 1e-6, "已实现盈亏应为 +1,600")
        assertEquals(172300.0, pos.marketValue, 1e-6, "市值 100 × 1723 = 172,300")
        assertEquals(4300.0, pos.unrealizedPnl, 1e-6, "浮动盈亏应为 +4,300")
        assertEquals(5900.0, pos.totalPnl, 1e-6)
        assertEquals(4, pos.tradeCount)
    }

    @Test
    fun `删除一笔买入后全量重放 - 均价与市值同步变化`() {
        // 删除 t2（买 60 股 @1700），这正是「不做增量修补、整标的全量重放」要覆盖的场景
        val afterDelete = fourTrades.filterNot { it.id == "t2" }
        val pos = PositionCalculator.replay(maotai, afterDelete, marketPrice = 1723.0)

        assertEquals(40.0, pos.quantity, 1e-6, "剩余 40 股")
        assertEquals(1700.0, pos.avgCost, 1e-6, "均价应重算为 1700.00")
        assertEquals(3600.0, pos.realizedPnl, 1e-6, "已实现盈亏随重放变为 +3,600")
        assertEquals(68920.0, pos.marketValue, 1e-6, "40 × 1723 = 68,920")
        assertEquals(920.0, pos.unrealizedPnl, 1e-6, "浮盈 (1723-1700) × 40 = +920")
    }

    @Test
    fun `重放结果与流水顺序无关`() {
        val shuffled = fourTrades.reversed()
        val a = PositionCalculator.replay(maotai, fourTrades)
        val b = PositionCalculator.replay(maotai, shuffled)

        assertEquals(a.quantity, b.quantity, 1e-9)
        assertEquals(a.avgCost, b.avgCost, 1e-9)
        assertEquals(a.realizedPnl, b.realizedPnl, 1e-9)
    }

    @Test
    fun `买入手续费计入成本`() {
        val withFee = listOf(
            Transaction("f1", maotai.id, TradeSide.BUY, 100.0, 10.0, fee = 5.0, tradeDate = "2026-01-05", seq = 1),
        )
        val pos = PositionCalculator.replay(maotai, withFee)
        assertEquals(10.05, pos.avgCost, 1e-9, "(100×10 + 5) / 100 = 10.05")
    }

    @Test
    fun `全部卖出后持仓归零且不留残值`() {
        val closed = listOf(
            Transaction("c1", maotai.id, TradeSide.BUY, 100.0, 10.0, tradeDate = "2026-01-05", seq = 1),
            Transaction("c2", maotai.id, TradeSide.SELL, 100.0, 12.0, tradeDate = "2026-02-05", seq = 2),
        )
        val pos = PositionCalculator.replay(maotai, closed)
        assertEquals(0.0, pos.quantity, 1e-9)
        assertEquals(0.0, pos.costAmount, 1e-9)
        assertEquals(200.0, pos.realizedPnl, 1e-9)
        assertTrue(!pos.isOpen)
    }

    @Test
    fun `超卖按实际持仓截断 - 不产生负仓`() {
        val oversell = listOf(
            Transaction("o1", maotai.id, TradeSide.BUY, 50.0, 10.0, tradeDate = "2026-01-05", seq = 1),
            Transaction("o2", maotai.id, TradeSide.SELL, 80.0, 11.0, tradeDate = "2026-01-06", seq = 2),
        )
        val pos = PositionCalculator.replay(maotai, oversell)
        assertEquals(0.0, pos.quantity, 1e-9)
        assertEquals(50.0, pos.realizedPnl, 1e-9, "只按 50 股结转，盈利 = (11-10)×50")
    }

    @Test
    fun `现金等价物不计入持仓 - 口径归现金`() {
        val money = Security("sec_511660", "sh511660", "银华日利", Market.ETF, Currency.CNY, isCashEquivalent = true)
        val securities = listOf(maotai, money)
        val txs = fourTrades + Transaction(
            id = "m1", securityId = money.id, side = TradeSide.BUY,
            quantity = 2000.0, price = 101.2, tradeDate = "2026-05-06", seq = 9,
        )

        val positions = PositionCalculator.replayAll(securities, txs, mapOf(maotai.id to 1723.0))
        assertEquals(1, positions.size, "持仓列表应只剩茅台，现金等价物不出现")
        assertEquals("sh600519", positions.first().symbol)

        val cashEquivalent = PositionCalculator.cashEquivalentValue(securities, txs, mapOf(money.id to 101.2))
        assertEquals(202400.0, cashEquivalent, 1e-6, "2000 × 101.2 = 202,400 归入现金口径")
    }
    @Test
    fun `账外备忘标的 - 不进持仓但可单独重放`() {
        // 场外基金勾了「不计入统计与分析」（老周 2026-09-20）：
        // replayAll 必须把它剔除，否则它会混进总资产 / 配置环；
        // 但直接 replay 单个标的仍要算出份额·成本·浮盈（备忘区要用）。
        val fund = Security(
            id = "sec_of000001", symbol = "of000001", name = "华夏成长混合",
            market = Market.FUND, currency = Currency.CNY, excludeFromStats = true,
        )
        val txs = listOf(
            Transaction("f1", fund.id, TradeSide.BUY, 10000.0, 1.5000, tradeDate = "2026-01-06", seq = 1),
            Transaction("f2", fund.id, TradeSide.BUY, 10000.0, 1.2000, tradeDate = "2026-03-09", seq = 2),
        )
        val all = listOf(maotai, fund) to (fourTrades + txs)

        val positions = PositionCalculator.replayAll(all.first, all.second, mapOf(fund.symbol to 1.4))
        assertEquals(1, positions.size, "账外标的必须从持仓列表里消失")
        assertEquals("sh600519", positions.first().symbol)

        val memo = PositionCalculator.replay(fund, txs, marketPrice = 1.4)
        assertEquals(20000.0, memo.quantity, 1e-6)
        assertEquals(1.35, memo.avgCost, 1e-9, "(1.5×10000 + 1.2×10000) / 20000 = 1.35")
        assertEquals(1000.0, memo.unrealizedPnl, 1e-6, "(1.4 − 1.35) × 20000")
        assertEquals(28000.0, memo.marketValue, 1e-6)
    }

    @Test
    fun `保本平仓的标的也出现在持仓列表`() {
        // 老周 2026-09-18 审查：原过滤条件 abs(realizedPnl)>EPS 会把「恰好盈亏为 0」
        // 的清仓标的隐藏掉。已改为有交易（tradeCount>0）就保留。
        val txs = listOf(
            tx("t1", TradeSide.BUY, 100.0, 10.0, "2026-01-05", 1),
            tx("t2", TradeSide.SELL, 100.0, 10.0, "2026-01-06", 2),
        )
        val positions = PositionCalculator.replayAll(listOf(maotai), txs, emptyMap())
        assertEquals(1, positions.size, "保本平仓也应显示（tradeCount>0）")
        assertEquals(0.0, positions.first().quantity, 1e-9)
        assertEquals(0.0, positions.first().realizedPnl, 1e-9)
    }

    @Test
    fun `零持仓时配股被忽略`() {
        // 老周 2026-09-18 审查：RIGHTS 原来没有持仓校验，0 持仓也能配出凭空持仓
        val div = DividendRecord(
            id = "d_rights", securityId = maotai.id, exDate = "2026-01-05", type = "RIGHTS",
            quantity = 100.0, perShare = 0.0, bonusShares = 0.0, rightsPrice = 8.0, fxRate = 1.0,
        )
        val pos = PositionCalculator.replay(maotai, emptyList(), dividends = listOf(div))
        assertEquals(0.0, pos.quantity, 1e-9, "0 持仓配股应被忽略")
        assertEquals(0.0, pos.costAmount, 1e-9)
    }

}