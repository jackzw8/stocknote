package com.stocknote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仅供真机 UI 自测的**造数据**工具（老周 2026-09-17）。
 *
 * 用途：debug 版不播种演示数据，但验证「标的详情页左滑操作」需要有交易记录。
 * 跑一次即写入：现金 + 两笔茅台买入（同一标的，便于看到多行）。
 * 验证完请 pm clear 清掉。
 */
@RunWith(AndroidJUnit4::class)
class SeedForUiTest {

    @Test
    fun seedTradesForUiCheck(): Unit = runBlocking {
        val container = com.stocknote.data.AppContainer()
        val repo = container.portfolio
        val trade = container.trade
        try {
            if (container.db.securityQueries.selectAll().executeAsList().none { it.symbol == "sh600519" }) {
                container.db.securityQueries.upsert(
                    id = "sec_sh600519_seed",
                    symbol = "sh600519",
                    name = "贵州茅台",
                    market = Market.A_SHARE.name,
                    currency = Currency.CNY.code,
                    industry = "白酒",
                    is_cash_equivalent = 0L,
                    exclude_from_stats = 0L,
                    lot_size = 100L,
                )
            }
            val sec = container.security.findBySymbol("sh600519") ?: error("缺茅台标的")
            container.cash.addCashFlow(
                accountId = "acc_seed",
                flowDate = "2026-09-17",
                isDeposit = true,
                amountOrig = 300000.0,
                currency = Currency.CNY,
                fxRate = 1.0,
                note = "seed-deposit",
            )
            trade.addTransaction(
                securityId = sec.id, side = TradeSide.BUY, quantity = 100.0,
                price = 1600.0, fee = 40.0, tradeDate = "2026-09-15",
                note = "第一条：突破年线买入（内容较长用于验证是否顶出图标）",
            )
            trade.addTransaction(
                securityId = sec.id, side = TradeSide.BUY, quantity = 50.0,
                price = 1650.0, fee = 20.0, tradeDate = "2026-09-16",
                note = "第二条",
            )
            println("[[SEED]] 已写入 2 笔交易，可用现金=${container.cash.availableCashNow()}")
        } finally {
            container.close()
        }
    }
}
