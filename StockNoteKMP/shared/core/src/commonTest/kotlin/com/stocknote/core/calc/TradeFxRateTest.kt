package com.stocknote.core.calc

import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 成交日汇率 / 本位币成本口径（REQ-ACC-15 路径 1，老周 2026-09-19）。
 *
 * 核心不变量：
 *  1. 有成交日汇率时，本位币成本按**各笔自己的汇率**累加（不是统一用最新汇率）；
 *  2. 没有汇率的笔（A股 / 旧数据）回退最新汇率 → **老账本数字不变**；
 *  3. 原币口径（avgCost / costAmount / realizedPnl）**完全不受影响**。
 */
class TradeFxRateTest {

    private val tencent = Security(
        id = "sec_hk",
        symbol = "hk00700",
        name = "腾讯控股",
        market = Market.HK,
        currency = Currency.HKD,
        industry = null,
        isCashEquivalent = false,
    )

    private fun buy(id: String, qty: Double, price: Double, date: String, fx: Double?, seq: Long) =
        Transaction(
            id = id,
            securityId = tencent.id,
            side = TradeSide.BUY,
            quantity = qty,
            price = price,
            fee = 0.0,
            tradeDate = date,
            seq = seq,
            fxRate = fx,
        )

    @Test
    fun `本位币成本按各笔成交日汇率累加`() {
        // 第一笔：100 股 @100 HKD，汇率 0.85 → 8,500 CNY
        // 第二笔：100 股 @120 HKD，汇率 0.86 → 10,320 CNY
        val pos = PositionCalculator.replay(
            security = tencent,
            transactions = listOf(
                buy("t1", 100.0, 100.0, "2026-01-10", 0.85, 1),
                buy("t2", 100.0, 120.0, "2026-02-10", 0.86, 2),
            ),
            marketPrice = 130.0,
            latestFxRate = 0.87,
        )
        assertEquals(22_000.0, pos.costAmount, 1e-6)      // 原币成本：100×100 + 100×120
        assertEquals(18_820.0, pos.costInBase, 1e-6)      // 8,500 + 10,320（各按自己汇率）
    }

    @Test
    fun `未记录汇率回退最新汇率（老数据不追溯）`() {
        // 两笔都没有 fxRate，latestFxRate = 0.85 → 22,000 × 0.85 = 18,700
        val pos = PositionCalculator.replay(
            security = tencent,
            transactions = listOf(
                buy("t1", 100.0, 100.0, "2026-01-10", null, 1),
                buy("t2", 100.0, 120.0, "2026-02-10", null, 2),
            ),
            marketPrice = 130.0,
            latestFxRate = 0.85,
        )
        assertEquals(18_700.0, pos.costInBase, 1e-6)
        // 与「最新汇率 × 原币成本」完全一致 → 旧账本的折算结果不变
        assertEquals(22_000.0 * 0.85, pos.costInBase, 1e-6)
    }

    @Test
    fun `卖出按比例扣减本位币成本并计汇兑损益`() {
        val pos = PositionCalculator.replay(
            security = tencent,
            transactions = listOf(
                buy("t1", 100.0, 100.0, "2026-01-10", 0.85, 1),
                Transaction(
                    id = "t2",
                    securityId = tencent.id,
                    side = TradeSide.SELL,
                    quantity = 50.0,
                    price = 130.0,
                    fee = 0.0,
                    tradeDate = "2026-03-10",
                    seq = 2,
                    fxRate = 0.87,
                ),
            ),
            marketPrice = 140.0,
            latestFxRate = 0.88,
        )
        // 成本：8,500 × (1 - 50/100) = 4,250
        assertEquals(4_250.0, pos.costInBase, 1e-6)
        // 已实现（本位币）= 卖出净额 × 卖出日汇率 − 扣减的成本 = 6,500×0.87 − 4,250 = 1,405
        assertEquals(1_405.0, pos.realizedPnlInBase, 1e-6)
        // 原币口径不受影响：原币已实现 = (130 − 100) × 50 = 1,500
        assertEquals(1_500.0, pos.realizedPnl, 1e-6)
    }

    @Test
    fun `A股（本位币）汇率恒为1_成本等于原币成本`() {
        val maotai = Security(
            id = "sec_cn",
            symbol = "sh600519",
            name = "贵州茅台",
            market = Market.A_SHARE,
            currency = Currency.CNY,
            industry = null,
            isCashEquivalent = false,
        )
        val pos = PositionCalculator.replay(
            security = maotai,
            transactions = listOf(
                Transaction(
                    id = "t1",
                    securityId = maotai.id,
                    side = TradeSide.BUY,
                    quantity = 100.0,
                    price = 1723.0,
                    fee = 43.075,
                    tradeDate = "2026-01-10",
                    seq = 1,
                    fxRate = null,
                ),
            ),
            marketPrice = 1800.0,
            latestFxRate = 1.0,
        )
        assertEquals(pos.costAmount, pos.costInBase, 1e-6)
        assertEquals(172_343.075, pos.costInBase, 1e-6)
    }
}
