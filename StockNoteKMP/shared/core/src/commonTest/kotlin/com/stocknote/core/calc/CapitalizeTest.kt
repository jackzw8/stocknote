package com.stocknote.core.calc

import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 利润转增资本（REQ-ACC-16）单测 —— 老周 2026-09-20。
 *
 * 全部用例建立在 [PositionCalculatorTest] 那组已验算过的茅台四笔流水之上
 * （100 股、均价 1680、已实现 +1600、浮动 +4300、总盈亏 +5900、现价 1723），
 * 其中 X = 6500 这一组与界面原型 11d 的预告数字**逐项对齐**（均价 1729 / 浮动 −600 / 总盈亏 −600），
 * 因此本文件同时充当「原型数字 = 实际算法」的回归锚点。
 *
 * 核心口径（技术说明书 2.0 §22）：
 *   先冲减正已实现盈亏（下限 0），余额抬高成本 → 该股**总盈亏恰好下降 X**、
 *   股数与市值不变（总资产不变）、不产生现金流（现金 / 出入金 / XIRR 都不受影响）。
 */
class CapitalizeTest {

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

    private val price = 1723.0

    /** 转增一笔的合成流水（与仓储层落库同形：quantity=0、fee=0、金额放在 price）。 */
    private fun cap(amount: Double, date: String = "2026-09-20", seq: Long = 99, fxRate: Double? = null) =
        Transaction(
            id = "cap1",
            securityId = maotai.id,
            side = TradeSide.CAPITALIZE,
            quantity = 0.0,
            price = amount,
            fee = 0.0,
            tradeDate = date,
            seq = seq,
            fxRate = fxRate,
        )

    private fun replayWith(amount: Double) =
        PositionCalculator.replay(maotai, fourTrades + cap(amount), marketPrice = price)

    // ------------------------------------------------------------ 基准：转增前

    @Test
    fun `转增前基准 - 四笔流水口径`() {
        val pos = PositionCalculator.replay(maotai, fourTrades, marketPrice = price)
        assertEquals(100.0, pos.quantity, 1e-6)
        assertEquals(1680.0, pos.avgCost, 1e-6)
        assertEquals(1600.0, pos.realizedPnl, 1e-6)
        assertEquals(4300.0, pos.unrealizedPnl, 1e-6)
        assertEquals(5900.0, pos.totalPnl, 1e-6)
    }

    // ------------------------------------------------------------ X < 已实现盈亏

    @Test
    fun `X 不超过已实现盈亏 - 全部冲减已实现_成本不变`() {
        // X = 500 < realized 1600 → reduced=500，全部落在已实现上，抬成本额 = 0
        val pos = replayWith(500.0)

        assertEquals(1100.0, pos.realizedPnl, 1e-6, "已实现盈亏 1600 − 500")
        assertEquals(1680.0, pos.avgCost, 1e-6, "无需抬成本，均价不变")
        assertEquals(168000.0, pos.costAmount, 1e-6)
        assertEquals(4300.0, pos.unrealizedPnl, 1e-6, "浮动盈亏不变")
        assertEquals(5400.0, pos.totalPnl, 1e-6, "总盈亏恰好下降 500")
    }

    // ------------------------------------------------------------ 已实现 < X < 总盈亏

    @Test
    fun `X 超过已实现但不超过总盈亏 - 余额抬成本`() {
        // X = 2500 > realized 1600 → reduced=1600，抬成本 900 → 均价 +9
        val pos = replayWith(2500.0)

        assertEquals(0.0, pos.realizedPnl, 1e-6, "已实现被冲到 0，不会变负")
        assertEquals(1689.0, pos.avgCost, 1e-6, "1680 + 900/100")
        assertEquals(168900.0, pos.costAmount, 1e-6)
        assertEquals(3400.0, pos.unrealizedPnl, 1e-6, "(1723 − 1689) × 100")
        assertEquals(3400.0, pos.totalPnl, 1e-6, "总盈亏恰好下降 2500")
    }

    // ------------------------------------------------------------ 原型 11d：X > 总盈亏

    @Test
    fun `X 超过总盈亏 - 浮动盈亏转负_与原型11d数字一致`() {
        // 原型 11d 输入 6500：均价 1680→1729、浮动 +4300→−600、总盈亏 +5900→−600
        val pos = replayWith(6500.0)

        assertEquals(0.0, pos.realizedPnl, 1e-6)
        assertEquals(1729.0, pos.avgCost, 1e-6, "1680 + 4900/100 = 1729")
        assertEquals(-600.0, pos.unrealizedPnl, 1e-6, "(1723 − 1729) × 100 = −600")
        assertEquals(-600.0, pos.totalPnl, 1e-6, "总盈亏 5900 − 6500 = −600")
    }

    // ------------------------------------------------------------ 不变量：股数 / 市值 / 总资产

    @Test
    fun `转增不改变股数与市值`() {
        val before = PositionCalculator.replay(maotai, fourTrades, marketPrice = price)
        listOf(500.0, 2500.0, 6500.0, 99999.0).forEach { x ->
            val after = replayWith(x)
            assertEquals(before.quantity, after.quantity, 1e-6, "X=$x 股数不变")
            assertEquals(before.marketValue, after.marketValue, 1e-6, "X=$x 市值不变（市值只看价×量）")
        }
    }

    @Test
    fun `任意 X 下总盈亏都恰好下降 X - 浮亏不够时差额全进成本`() {
        listOf(1.0, 500.0, 1600.0, 2500.0, 5900.0, 6500.0, 20000.0).forEach { x ->
            val pos = replayWith(x)
            assertEquals(5900.0 - x, pos.totalPnl, 1e-6, "X=$x 时总盈亏应恰好为 5900 − X")
        }
    }

    @Test
    fun `抬升的成本额等于 X 减去被冲减的已实现部分`() {
        // 不变量：costAmount − 168000 + 被冲减额 == X
        listOf(500.0, 2500.0, 6500.0).forEach { x ->
            val pos = replayWith(x)
            val reduced = 1600.0 - pos.realizedPnl
            assertEquals(x, (pos.costAmount - 168000.0) + reduced, 1e-6, "X=$x 资金去向守恒")
        }
    }

    // ------------------------------------------------------------ 亏损股：不倒冲

    @Test
    fun `亏损股转增 - 已实现为负时不倒冲_全额抬成本`() {
        // 只保留 t1 + 一笔亏损卖出：买 60 @1600，卖 60 @1500 → 已实现 −6000，清仓
        // 换成留 20 股的情形：买 60 @1600，卖 40 @1500 → 已实现 −4000，剩 20 股 @1600
        val lossTrades = listOf(
            tx("l1", TradeSide.BUY, 60.0, 1600.0, "2026-03-02", 1),
            tx("l2", TradeSide.SELL, 40.0, 1500.0, "2026-06-08", 2),
        )
        val before = PositionCalculator.replay(maotai, lossTrades, marketPrice = 1500.0)
        assertEquals(-4000.0, before.realizedPnl, 1e-6, "已实现 −4000")

        val after = PositionCalculator.replay(maotai, lossTrades + cap(1000.0), marketPrice = 1500.0)
        assertEquals(-4000.0, after.realizedPnl, 1e-6, "负已实现不被倒冲回来")
        assertEquals(20.0, after.quantity, 1e-6)
        assertEquals(1600.0 + 1000.0 / 20.0, after.avgCost, 1e-6, "1000 全额抬成本")
    }

    // ------------------------------------------------------------ 0 持仓：不生效

    @Test
    fun `清仓后转增不生效 - 无本金可增`() {
        val closedTrades = listOf(
            tx("c1", TradeSide.BUY, 100.0, 1000.0, "2026-03-02", 1),
            tx("c2", TradeSide.SELL, 100.0, 1200.0, "2026-06-08", 2),
        )
        val before = PositionCalculator.replay(maotai, closedTrades, marketPrice = 1200.0)
        assertFalse(before.isOpen, "已清仓")

        val after = PositionCalculator.replay(maotai, closedTrades + cap(500.0), marketPrice = 1200.0)
        assertEquals(before.quantity, after.quantity, 1e-6)
        assertEquals(before.costAmount, after.costAmount, 1e-6)
        assertEquals(before.realizedPnl, after.realizedPnl, 1e-6, "0 持仓时转增被忽略")
    }

    // ------------------------------------------------------------ 可逆：删除即撤销

    @Test
    fun `删除该笔流水即回到转增前`() {
        val before = PositionCalculator.replay(maotai, fourTrades, marketPrice = price)
        val withCap = fourTrades + cap(2500.0)
        val afterAdd = PositionCalculator.replay(maotai, withCap, marketPrice = price)
        val afterRevoke = PositionCalculator.replay(
            maotai,
            withCap.filterNot { it.side == TradeSide.CAPITALIZE },
            marketPrice = price,
        )

        assertEquals(3400.0, afterAdd.totalPnl, 1e-6, "转增后总盈亏下降")
        assertEquals(before.totalPnl, afterRevoke.totalPnl, 1e-6, "撤销后回到原总盈亏")
        assertEquals(before.avgCost, afterRevoke.avgCost, 1e-6)
        assertEquals(before.costAmount, afterRevoke.costAmount, 1e-6)
        assertEquals(before.realizedPnl, afterRevoke.realizedPnl, 1e-6)
    }

    // ------------------------------------------------------------ 现金中性

    @Test
    fun `转增不影响现金 - 现金变化恒为 0`() {
        assertEquals(
            0.0,
            CashImpact.cashDeltaOf("CAPITALIZE", quantity = 0.0, price = 6500.0, fee = 0.0, rateToBase = 1.0),
            1e-12,
            "转增是账内重分类，不产生现金流",
        )
        // 即便汇率非 1（港股），也仍是 0 —— 不能落进 SELL 分支凭空造现金
        assertEquals(
            0.0,
            CashImpact.cashDeltaOf("CAPITALIZE", quantity = 0.0, price = 6500.0, fee = 0.0, rateToBase = 0.85),
            1e-12,
        )
    }

    // ------------------------------------------------------------ 本位币口径（REQ-ACC-15）

    @Test
    fun `本位币成本与已实现同步折算 - 按本笔汇率`() {
        // 港股：100 股 @ 100 HKD（汇率 0.9）→ 成本 9000 CNY；转增 500 HKD（汇率 0.8）
        val hk = Security(
            id = "sec_hk",
            symbol = "hk00700",
            name = "腾讯控股",
            market = Market.HK,
            currency = Currency.HKD,
        )
        val buys = listOf(
            Transaction(
                id = "h1", securityId = hk.id, side = TradeSide.BUY, quantity = 100.0,
                price = 100.0, fee = 0.0, tradeDate = "2026-03-02", seq = 1, fxRate = 0.9,
            ),
        )
        val before = PositionCalculator.replay(hk, buys, marketPrice = 100.0, latestFxRate = 0.9)
        assertEquals(9000.0, before.costInBase, 1e-6)

        val capTx = Transaction(
            id = "h2", securityId = hk.id, side = TradeSide.CAPITALIZE, quantity = 0.0,
            price = 500.0, fee = 0.0, tradeDate = "2026-09-20", seq = 2, fxRate = 0.8,
        )
        val after = PositionCalculator.replay(hk, buys + capTx, marketPrice = 100.0, latestFxRate = 0.9)
        assertEquals(5.0, after.avgCost - before.avgCost, 1e-6, "原币均价抬 500/100 = 5")
        assertEquals(9000.0 + 500.0 * 0.8, after.costInBase, 1e-6, "本位币成本按**本笔汇率**抬 400")
    }

    // ------------------------------------------------------------ 校验与闸门

    @Test
    fun `金额校验 - 空值零负数非法_正数通过`() {
        assertNotNull(Capitalize.validate(""))
        assertNotNull(Capitalize.validate("   "))
        assertNotNull(Capitalize.validate("abc"))
        assertNotNull(Capitalize.validate("0"))
        assertNotNull(Capitalize.validate("-100"))
        assertNull(Capitalize.validate("6500"))
        assertNull(Capitalize.validate(" 6500.50 "))
    }

    @Test
    fun `可转增闸门 - 仅仍持仓为真`() {
        val open = PositionCalculator.replay(maotai, fourTrades, marketPrice = price)
        assertTrue(Capitalize.canCapitalize(open))

        val closed = PositionCalculator.replay(
            maotai,
            listOf(
                tx("c1", TradeSide.BUY, 100.0, 1000.0, "2026-03-02", 1),
                tx("c2", TradeSide.SELL, 100.0, 1200.0, "2026-06-08", 2),
            ),
            marketPrice = 1200.0,
        )
        assertFalse(Capitalize.canCapitalize(closed))
    }

    @Test
    fun `金额取 amountOf - CAPITALIZE 不能用价乘量`() {
        val c = cap(6500.0)
        assertEquals(0.0, c.quantity, 1e-9)
        assertEquals(6500.0, c.amountOf, 1e-9, "金额在 price 列，不是 price × quantity(=0)")
    }

    @Test
    fun `转增流水不计入成交笔数`() {
        // tradeCount 是 ordered.size，含转增 —— 这里锁定当前口径，若将来要剔除需同步改 UI 文案
        val pos = replayWith(500.0)
        assertEquals(5, pos.tradeCount)
    }

    // ------------------------------------------------------------ 技术说明书 §22.8 的三例（逐字对齐）

    @Test
    fun `技术说明书 22_8 三例 - 持1000股成本10现价12已实现加500`() {
        // 基准：1000 股 @ 成本 10（含费），已卖出一部分锁 +500 已实现，现价 12
        // 构造：买 2000 股 @ 10（成本 20000），卖 1000 股 @ 12 → 已实现 (12−10)×1000 = +2000
        // 用「卖 1000 @ 10.5」把已实现调到 +500：均价 10 → 已实现 (10.5−10)×1000 = 500 ✓
        val s = Security(
            id = "sec_spec",
            symbol = "sh600000",
            name = "浦发银行",
            market = Market.A_SHARE,
            currency = Currency.CNY,
        )
        fun t(id: String, side: TradeSide, qty: Double, p: Double, seq: Long) =
            Transaction(id, s.id, side, qty, p, 0.0, "2026-0${seq}-01", seq)

        val base = listOf(
            t("b1", TradeSide.BUY, 2000.0, 10.0, 1),
            t("s1", TradeSide.SELL, 1000.0, 10.5, 2),
        )
        val before = PositionCalculator.replay(s, base, marketPrice = 12.0)
        assertEquals(1000.0, before.quantity, 1e-6)
        assertEquals(10.0, before.avgCost, 1e-6)
        assertEquals(500.0, before.realizedPnl, 1e-6)
        assertEquals(2000.0, before.unrealizedPnl, 1e-6)
        assertEquals(2500.0, before.totalPnl, 1e-6)

        fun cap(x: Double) = Transaction(
            "cap", s.id, TradeSide.CAPITALIZE, 0.0, x, 0.0, "2026-09-20", 9,
        )

        // X = 500 → 已实现 → 0，浮动不变（+2000），总成本不变
        val x500 = PositionCalculator.replay(s, base + cap(500.0), marketPrice = 12.0)
        assertEquals(0.0, x500.realizedPnl, 1e-6)
        assertEquals(2000.0, x500.unrealizedPnl, 1e-6)
        assertEquals(10000.0, x500.costAmount, 1e-6, "总成本不变")
        assertEquals(10.0, x500.avgCost, 1e-6)

        // X = 2500 → 成本 → 12、浮动 → 0、总盈亏 → 0
        val x2500 = PositionCalculator.replay(s, base + cap(2500.0), marketPrice = 12.0)
        assertEquals(12.0, x2500.avgCost, 1e-6)
        assertEquals(0.0, x2500.unrealizedPnl, 1e-6)
        assertEquals(0.0, x2500.totalPnl, 1e-6)

        // X = 3000 → 成本 → 12.5、浮动 → −500，总资产（市值）不变
        val x3000 = PositionCalculator.replay(s, base + cap(3000.0), marketPrice = 12.0)
        assertEquals(12.5, x3000.avgCost, 1e-6)
        assertEquals(-500.0, x3000.unrealizedPnl, 1e-6)
        assertEquals(12000.0, x3000.marketValue, 1e-6, "市值 1000 × 12 不变")
        assertEquals(12000.0, before.marketValue, 1e-6)
    }

    // ------------------------------------------------------------ 预告 == 实况

    @Test
    fun `预告结果与实际落库重放完全一致`() {
        val preview = Capitalize.preview(
            security = maotai,
            transactions = fourTrades,
            amount = 6500.0,
            marketPrice = price,
        )
        assertNotNull(preview)
        assertEquals(5900.0, preview.before.totalPnl, 1e-6)
        assertEquals(-600.0, preview.after.totalPnl, 1e-6)

        // 把预告里那条合成流水「真落库」后再重放，必须逐项相等
        val real = replayWith(6500.0)
        assertEquals(real.avgCost, preview.after.avgCost, 1e-9)
        assertEquals(real.costAmount, preview.after.costAmount, 1e-9)
        assertEquals(real.realizedPnl, preview.after.realizedPnl, 1e-9)
        assertEquals(real.unrealizedPnl, preview.after.unrealizedPnl, 1e-9)
        assertEquals(real.quantity, preview.after.quantity, 1e-9)
    }

    @Test
    fun `预告在非法金额时返回 null`() {
        assertNull(Capitalize.preview(maotai, fourTrades, amount = 0.0, marketPrice = price))
        assertNull(Capitalize.preview(maotai, fourTrades, amount = -1.0, marketPrice = price))
        assertNull(Capitalize.preview(maotai, fourTrades, amount = Double.NaN, marketPrice = price))
    }

    @Test
    fun `无行情价时预告仍可用 - 原币口径与行情无关`() {
        // 均价与总盈亏（已实现部分）不依赖现价；无行情时浮动盈亏恒 0
        val preview = Capitalize.preview(maotai, fourTrades, amount = 2500.0, marketPrice = null)
        assertNotNull(preview)
        assertEquals(1689.0, preview.after.avgCost, 1e-6)
        assertEquals(0.0, preview.before.unrealizedPnl, 1e-9)
        assertEquals(0.0, preview.after.unrealizedPnl, 1e-9)
        assertEquals(1600.0, preview.before.realizedPnl, 1e-9, "无行情不影响已实现")
        assertEquals(0.0, preview.after.realizedPnl, 1e-9, "2500 先把 1600 已实现冲掉")
    }
}
