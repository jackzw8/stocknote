package com.stocknote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stocknote.core.model.TradeSide
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 现金联动折算的**数据层真机验证**（老周 2026-09-17 要求复核"港股交易对现金影响"）。
 *
 * 为什么走数据层而不是点 UI：UI 自动化在多级导航下不稳（前置几轮已多次踩坑），
 * 而老周要核对的是**账本数字**本身 —— 直接调 `addTransaction` / `availableCashNow`
 * 前后取差，能精确断言"是否按汇率折算到人民币"。
 *
 * 判定口径：
 *   港股买入应扣 = (价 × 量 + 费) × HKD→CNY 汇率
 *   汇率取「设置里维护的最新汇率」，缺失时用 FxTable 兜底（当前 0.92）。
 *   若没折算（按港元数字当人民币扣），差值会差出一个汇率的量级（约 8%），断言必然失败。
 */
@RunWith(AndroidJUnit4::class)
class CashImpactDataTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 确保测试所需标的在位。
     * ⚠️ 2026-09-17：debug 构建已**不再播种演示数据**（老周要求，便于验证全新空账本），
     * 所以测试自己插入标的，不依赖种子。
     */
    private fun ensureTestSecurity(container: com.stocknote.data.AppContainer) {
        if (container.db.securityQueries.selectAll().executeAsList().none { it.symbol == "hk00700" }) {
            container.db.securityQueries.upsert(
                id = "sec_hk00700_test",
                symbol = "hk00700",
                name = "腾讯控股",
                market = "HK",
                currency = "HKD",
                industry = "互联网",
                is_cash_equivalent = 0L,
                exclude_from_stats = 0L,
                lot_size = 100L,
            )
        }
    }

    @Test
    fun hkBuyAndSell_cashConvertedToCny() = runBlocking {
        val container = com.stocknote.data.AppContainer()
        val repo = container.portfolio
        val trade = container.trade
        val _cashCtr = container.cash
        try {
            // 环境准备：① 自建标的（debug 不播种演示数据）② 先入金（空账本现金为 0，
            // 不先入金会被"现金不足"正确拦截）
            ensureTestSecurity(container)
            container.db.cashFlowQueries.selectAll().executeAsList()
                .filter { it.note?.contains("autotest") == true }
                .forEach { container.db.cashFlowQueries.deleteById(it.id) }
            val seedCfId = container.cash.addCashFlow(
                accountId = "acc_autotest",
                flowDate = "2026-09-17",
                isDeposit = true,
                amountOrig = 100000.0,
                currency = com.stocknote.core.model.Currency.CNY,
                fxRate = 1.0,
                note = "autotest-deposit-c1",
            )
            val sec = container.security.findBySymbol("hk00700") ?: error("缺少腾讯控股(hk00700)标的")
            val rate = repo.latestFxRates()["HKD"] ?: com.stocknote.core.model.FxTable.HKD_TO_CNY
            println("[[CASH]] 使用汇率 HKD→CNY = $rate")

            // ---------- 买入：100 股 @ 400 HKD，费 12 HKD ----------
            val beforeBuy = container.cash.availableCashNow()
            val buyId = trade.addTransaction(
                securityId = sec.id,
                side = TradeSide.BUY,
                quantity = 100.0,
                price = 400.0,
                fee = 12.0,
                tradeDate = "2026-09-17",
                note = "cashcheck-buy",
            )
            val afterBuy = container.cash.availableCashNow()
            val buyDelta = beforeBuy - afterBuy            // 应为正（扣款）
            val buyExpected = (100.0 * 400.0 + 12.0) * rate
            println("[[CASH]] 买入前=$beforeBuy 买入后=$afterBuy 实扣=$buyDelta 期望=$buyExpected")
            assertTrue(
                "❌ 港股买入未按汇率折算：实扣 $buyDelta，期望 $buyExpected（未折算会是 ${100.0 * 400.0 + 12.0}）",
                abs(buyDelta - buyExpected) < 1.0,
            )

            // ---------- 卖出：100 股 @ 400 HKD，费 12 HKD（回款应加现金） ----------
            val beforeSell = container.cash.availableCashNow()
            val sellId = trade.addTransaction(
                securityId = sec.id,
                side = TradeSide.SELL,
                quantity = 100.0,
                price = 400.0,
                fee = 12.0,
                tradeDate = "2026-09-17",
                note = "cashcheck-sell",
            )
            val afterSell = container.cash.availableCashNow()
            val sellDelta = afterSell - beforeSell          // 应为正（回款）
            val sellExpected = (100.0 * 400.0 - 12.0) * rate
            println("[[CASH]] 卖出前=$beforeSell 卖出后=$afterSell 实收=$sellDelta 期望=$sellExpected")
            assertTrue(
                "❌ 港股卖出未按汇率折算：实收 $sellDelta，期望 $sellExpected",
                abs(sellDelta - sellExpected) < 1.0,
            )

            // ---------- 清理测试数据 ----------
            trade.deleteTransaction(sellId)
            trade.deleteTransaction(buyId)
            val afterClean = container.cash.availableCashNow()
            println("[[CASH]] 清理后可用现金=$afterClean（应回到买入前 $beforeBuy）")
            assertTrue("❌ 删除交易未正确回滚现金：清理后 $afterClean，期望 $beforeBuy", abs(afterClean - beforeBuy) < 1.0)
            // 断言之后再清理测试数据（放在断言前会连带把差额算进去导致误判）
            container.cash.deleteCashFlow(seedCfId)
            container.db.cashFlowQueries.selectAll().executeAsList()
                .filter { it.note?.contains("autotest") == true }
                .forEach { container.db.cashFlowQueries.deleteById(it.id) }
        } finally {
            // 清理出入金残留（用例1 未改动账户表，故不动 account）
            runCatching {
                container.db.cashFlowQueries.selectAll().executeAsList()
                    .filter { it.note?.contains("autotest") == true }
                    .forEach { container.db.cashFlowQueries.deleteById(it.id) }
            }
            container.close()
        }
    }

    /**
     * 🔴 BUG-03 回归（老周 2026-09-17 真机实测发现）：
     * **全新账本没有 account 记录时，交易对现金的影响被静默丢弃** ——
     * 买入不扣现金，而持仓市值照常计入，总资产虚增。
     *
     * 复现步骤（与老周操作一致）：清空 account 表 → 入金 10 万 → 买 100 股腾讯 → 现金必须减少。
     * 演示数据带种子账户，所以此前所有测试都绕过了这条路径。
     */
    @Test
    fun noAccountScenario_cashStillDeducted(): Unit = runBlocking {
        val container = com.stocknote.data.AppContainer()
        val repo = container.portfolio
        val trade = container.trade
        val _cashCtr = container.cash
        val backup = container.db.accountQueries.selectAll().executeAsList()
        try {
            // 环境准备：测试自建标的（debug 已不播种演示数据）
            ensureTestSecurity(container)
            // 清理历史测试残留（note 含 autotest 的出入金），避免累计干扰
            container.db.cashFlowQueries.selectAll().executeAsList()
                .filter { it.note?.contains("autotest") == true }
                .forEach { container.db.cashFlowQueries.deleteById(it.id) }

            // 复现：全新账本（release 空库 / 新用户首次使用）—— 没有任何 account 记录
            container.driver.execute(null, "DELETE FROM account", 0)
            println("[[CASH]] 已清空 account 表（模拟全新账本），现有账户数=${container.db.accountQueries.selectAll().executeAsList().size}")

            // 入金 10 万（走 cash_flow）
            val beforeDeposit = container.cash.availableCashNow()
            val cfId = container.cash.addCashFlow(
                accountId = "acc_autotest",
                flowDate = "2026-09-17",
                isDeposit = true,
                amountOrig = 100000.0,
                currency = com.stocknote.core.model.Currency.CNY,
                fxRate = 1.0,
                note = "autotest-deposit",
            )
            val afterDeposit = container.cash.availableCashNow()
            val depositDelta = afterDeposit - beforeDeposit
            println("[[CASH]] 空账户 + 入金10万：入金前=$beforeDeposit 入金后=$afterDeposit 增量=$depositDelta")
            assertTrue("入金增量应为 100000，实际 $depositDelta", abs(depositDelta - 100000.0) < 1.0)

            // 买 100 股腾讯 @426 HKD（与老周真机同参数）
            val sec = container.security.findBySymbol("hk00700") ?: error("缺少腾讯控股(hk00700)标的")
            val rate = repo.latestFxRates()["HKD"] ?: com.stocknote.core.model.FxTable.HKD_TO_CNY
            val txId = trade.addTransaction(
                securityId = sec.id,
                side = TradeSide.BUY,
                quantity = 100.0,
                price = 426.0,
                fee = 12.78,
                tradeDate = "2026-09-17",
                note = "autotest-noaccount-buy",
            )
            val afterBuy = container.cash.availableCashNow()
            val expected = afterDeposit - (100.0 * 426.0 + 12.78) * rate
            println("[[CASH]] 空账户买港股后 = $afterBuy（期望 $expected，汇率 $rate）")
            assertTrue(
                "❌ BUG-03 复现：无账户时买入未扣现金，实际 $afterBuy，期望 $expected",
                abs(afterBuy - expected) < 1.0,
            )

            // 清理
            trade.deleteTransaction(txId)
            container.cash.deleteCashFlow(cfId)
        } finally {
            // 先清空再恢复备份：否则测试中**自动创建**的账户会残留
            // （2026-09-17 实测：残留导致设置页账户卡片出现重复条目）
            runCatching { container.driver.execute(null, "DELETE FROM account", 0) }
            backup.forEach { container.db.accountQueries.upsert(it.id, it.name, it.currency, it.cash) }
            runCatching {
                container.db.cashFlowQueries.selectAll().executeAsList()
                    .filter { it.note?.contains("autotest") == true }
                    .forEach { container.db.cashFlowQueries.deleteById(it.id) }
            }
            container.close()
        }
    }
}
