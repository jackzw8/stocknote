package com.stocknote.data.repo

import app.cash.sqldelight.db.SqlDriver
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.createEncryptedDriver
import com.stocknote.data.platform.createHttpClient
import com.stocknote.data.platform.createSecureKeyStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **现金口径契约测试**（2026-09-29）—— 为「解开现金联动」这步重构打底。
 *
 * 为什么要先写它：`applyCashDelta` 是所有剩余"搬不动"方法的**共同瓶颈**
 *（`addTransaction` / `updateTransaction` / `deleteTransaction` / `insertTradeForCsv` 都在用它），
 * 而它是 private、直接读改 `account.cash`。要把它独立出来，先得把**现金口径钉死**，
 * 否则改完只能靠真机对账，而账本数字错了很难发现。
 *
 * 口径（`availableCash`）：**可用现金 = 账户现金 + 出入金净额 + 现金分红**
 * ⚠️ 其中"账户现金"只被**买卖**增减；出入金与分红走各自的表，不写 account.cash。
 *
 * ⚠️ 用 JVM `JdbcSqliteDriver`（内存库、不加密），schema 由测试自建。
 */
class CashContractTest {

    private fun newRepo(): Triple<PortfolioRepository, StockNoteDb, SqlDriver> {
        val driver: SqlDriver = createEncryptedDriver("cash-test", ByteArray(0))
        StockNoteDb.Schema.create(driver)
        val db = StockNoteDb(driver)
        val repo = PortfolioRepository(
            db = db,
            quoteClient = QuoteClient(createHttpClient()),
            driver = driver,
            cash = CashRepository(db),
            keyStore = createSecureKeyStore(),
        )
        return Triple(repo, db, driver)
    }

    /** 建账户 + 入金（真实路径：走 cash_flow，不直接改 account.cash）。 */
    private suspend fun PortfolioRepository.seedAccount(
        db: StockNoteDb,
        cash: Double = 100_000.0,
    ): String {
        val accountId = "acc-cash"
        db.accountQueries.upsert(accountId, "测试账户", "CNY", 0.0)
        cashOf(db).addCashFlow(accountId, "2026-08-31", true, cash, Currency.CNY, 1.0)
        return accountId
    }

    private suspend fun seedSecurity(repo: PortfolioRepository, db: StockNoteDb, symbol: String = "sh600519"): String =
        SecurityRepository(db, repo, QuoteClient(createHttpClient())).findOrCreateSecurity(symbol, "测试标的", Market.A_SHARE, Currency.CNY).id

    @Test
    fun 买入扣现金_卖出加回现金() = runBlocking {
        val (repo, db, _) = newRepo()
        repo.seedAccount(db)
        val sid = seedSecurity(repo, db, )
        val before = cashOf(db).availableCash()

        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        assertEquals(before - 1000.0, cashOf(db).availableCash(), 0.01, "买入 100×10 应扣 1000")

        tradeOf(repo, db).addTransaction(sid, TradeSide.SELL, 100.0, 12.0, 0.0, "2026-09-02")
        assertEquals(before + 200.0, cashOf(db).availableCash(), 0.01, "卖出回款 1200 → 净 +200")
    }

    @Test
    fun 手续费计入现金变动() = runBlocking {
        // 买入时现金变动 = 成交额 + 手续费（费用是额外支出）
        val (repo, db, _) = newRepo()
        repo.seedAccount(db)
        val sid = seedSecurity(repo, db, )
        val before = cashOf(db).availableCash()

        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 5.0, "2026-09-01")

        assertEquals(before - 1005.0, cashOf(db).availableCash(), 0.01, "买入应扣 1000 + 手续费 5")
    }

    @Test
    fun 出入金只进流水_不改账户现金() = runBlocking {
        // ⚠️ 这是口径的关键：account.cash 只被买卖增减，出入金记录在 cash_flow 表，
        // 二者在 availableCash 里**相加**。若有人顺手把出入金也写进 account.cash，
        // 就会**重复计算**。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 50_000.0)

        val cashInAccount = db.accountQueries.selectAll().executeAsList().sumOf { it.cash }
        assertEquals(0.0, cashInAccount, 0.01, "⚠️ 入金不应写进 account.cash")
        assertEquals(50_000.0, cashOf(db).availableCash(), 0.01, "但可用现金应含它")
    }

    @Test
    fun 现金分红进可用现金_但不改账户现金() = runBlocking {
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 10_000.0)
        val sid = seedSecurity(repo, db, )
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        val afterBuy = cashOf(db).availableCash()

        repo.addDividend(
            securityId = sid,
            exDate = "2026-09-10",
            type = "CASH",
            quantity = 100.0,
            perShare = 0.5,
            bonusShares = 0.0,
            rightsPrice = 0.0,
            currency = Currency.CNY,
        )

        assertEquals(afterBuy + 50.0, cashOf(db).availableCash(), 0.01, "现金分红 100×0.5 = 50 应进可用现金")
    }

    @Test
    fun 送股不动现金() = runBlocking {
        // 送股只改持仓数量，现金不变 —— 这是"H3 送股不进曲线"那个 bug 的邻居口径。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 10_000.0)
        val sid = seedSecurity(repo, db, )
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        val before = cashOf(db).availableCash()

        repo.addDividend(
            securityId = sid,
            exDate = "2026-09-10",
            type = "STOCK",
            quantity = 100.0,
            perShare = 0.0,
            bonusShares = 20.0,
            rightsPrice = 0.0,
            currency = Currency.CNY,
        )

        assertEquals(before, cashOf(db).availableCash(), 0.01, "⚠️ 送股不动现金")
    }

    @Test
    fun 账外备忘标的_买卖与分红都不动现金() = runBlocking {
        // ⚠️ 老周 2026-09-20 口径：账外备忘标的（场外基金「不计入统计」）
        // 的买卖都不动现金，分红自然也不该动。这是很容易被"顺手统一处理"破坏的地方。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 10_000.0)
        val sid = seedSecurity(repo, db, "of000001")
        secOf(repo, db).setSecurityExcludeFromStats(sid, true)
        val before = cashOf(db).availableCash()

        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        assertEquals(before, cashOf(db).availableCash(), 0.01, "⚠️ 账外标的买入不动现金")

        repo.addDividend(
            securityId = sid,
            exDate = "2026-09-10",
            type = "CASH",
            quantity = 100.0,
            perShare = 0.5,
            bonusShares = 0.0,
            rightsPrice = 0.0,
            currency = Currency.CNY,
        )
        assertEquals(before, cashOf(db).availableCash(), 0.01, "⚠️ 账外标的分红也不动现金")
    }

    @Test
    fun 编辑交易按差额调整现金_而不是重复扣一次() = runBlocking {
        // ⚠️ 兜的是 applyCashDelta 的 `newDelta - oldDelta` 那行（原类 :1641）。
        // 若实现写成"再扣一次新金额"，编辑一笔买入后现金会被扣两遍 —— 而且金额看起来
        // 还挺合理，很难一眼看出。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 100_000.0)
        val sid = seedSecurity(repo, db, )
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        val afterBuy = cashOf(db).availableCash()          // 100000 - 1000 = 99000
        val txId = tradeOf(repo, db).transactionsOf(sid).first().id

        // 数量从 100 改成 50 → 占用应为 500，现金应回到 99500（而不是再扣 500）
        tradeOf(repo, db).updateTransaction(
            txId = txId,
            side = TradeSide.BUY,
            quantity = 50.0,
            price = 10.0,
            fee = 0.0,
            tradeDate = "2026-09-01",
        )

        assertEquals(afterBuy + 500.0, cashOf(db).availableCash(), 0.01,
            "⚠️ 改数量应只退差额 500（原为多扣 500 就是 bug）")
    }

    @Test
    fun 删除交易退回全部现金() = runBlocking {
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 100_000.0)
        val sid = seedSecurity(repo, db, )
        val before = cashOf(db).availableCash()
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 5.0, "2026-09-01")
        val txId = tradeOf(repo, db).transactionsOf(sid).first().id

        tradeOf(repo, db).deleteTransaction(txId)

        assertEquals(before, cashOf(db).availableCash(), 0.01, "⚠️ 删掉买入应退回 1000 + 手续费 5")
    }

    /** 现金域（第 10 批后原类转发已删除，直接用 CashRepository）。 */
    private fun cashOf(db: StockNoteDb) = CashRepository(db)
    private fun secOf(repo: PortfolioRepository, db: StockNoteDb) = SecurityRepository(db, repo, QuoteClient(createHttpClient()))

    /** 交易域（第 6 批后查询类实现已搬到 TradeRepository）。 */
    private fun tradeOf(repo: PortfolioRepository, db: StockNoteDb) = TradeRepository(db, repo, CashRepository(db), SecurityRepository(db, repo, QuoteClient(createHttpClient())))
}
