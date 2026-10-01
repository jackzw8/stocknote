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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **交易主线契约测试**（2026-09-29）—— 为「交易主线整体搬迁」打底。
 *
 * 为什么单独写这一批：交易主线（addTransaction / updateTransaction / deleteTransaction /
 * previewAfterDelete / insertTradeForCsv）依赖 4 个 private 辅助
 *（truncateSell / cashDeltaInCny / isOffBook / moneyCny），而它们**互相共用、必须整块搬**。
 * 搬之前要把这几条关键口径钉死 —— 它们直接决定账本数字，错了很难发现。
 *
 * 已有覆盖（不重复）：买入扣现金 / 卖出加现金 / 手续费 / 编辑按差额调整 / 删除退回 /
 * 自选不级联 / 删标签清引用 / 完成标记 —— 见 [CashContractTest] 与 [PortfolioRepositoryContractTest]。
 *
 * ⚠️ 本文件专门覆盖**这 4 条此前没有断言的**：
 *  1. 卖出**超卖截断**（proportion + 手续费按比例）
 *  2. 外币交易的**折算**（cashDeltaInCny）
 *  3. **删除预览与实际删除结果一致**（KDoc 声称"共用同一套重放"，值得验证）
 *  4. CSV 导入**不校验现金充足**（insertTradeForCsv 与 addTransaction 的核心差异）
 */
class TradeMainFlowContractTest {

    private fun newRepo(): Triple<PortfolioRepository, StockNoteDb, SqlDriver> {
        val driver: SqlDriver = createEncryptedDriver("trade-main-test", ByteArray(0))
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

    /** 现金域（第 10 批后原类转发已删除，直接用 CashRepository）。 */
    private fun cashOf(db: StockNoteDb) = CashRepository(db)
    private fun secOf(repo: PortfolioRepository, db: StockNoteDb) = SecurityRepository(db, repo, QuoteClient(createHttpClient()))

    private fun tradeOf(repo: PortfolioRepository, db: StockNoteDb) =
        TradeRepository(db, repo, CashRepository(db), SecurityRepository(db, repo, QuoteClient(createHttpClient())))

    private suspend fun PortfolioRepository.seedAccount(db: StockNoteDb, cash: Double = 1_000_000.0) {
        db.accountQueries.upsert("acc-main", "测试账户", "CNY", 0.0)
        cashOf(db).addCashFlow("acc-main", "2026-08-31", true, cash, Currency.CNY, 1.0)
    }

    private suspend fun seedSecurity(
        repo: PortfolioRepository,
        db: StockNoteDb,
        symbol: String = "sh600519",
        market: Market = Market.A_SHARE,
        currency: Currency = Currency.CNY,
    ): String = SecurityRepository(db, repo, QuoteClient(createHttpClient())).findOrCreateSecurity(symbol, "测试标的", market, currency).id

    @Test
    fun 卖出超卖时按实际持仓截断_且手续费按比例折算() = runBlocking {
        // ⚠️ 兜 H7 / M16：入库、现金联动都要用**截断后**的数量，否则
        // 「持仓被截断、现金却按全量回款」→ 账本失衡。
        // 这里持仓 60、报卖 100 → 实际成交 60，手续费按 60% 折算。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db)
        val sid = seedSecurity(repo, db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 60.0, 10.0, 0.0, "2026-09-01")

        // 报 100 股、手续费 10 元（按全量算的话是 10；实际只成交 60% → 应为 6）
        tradeOf(repo, db).addTransaction(sid, TradeSide.SELL, 100.0, 12.0, 10.0, "2026-09-02")

        val sells = tradeOf(repo, db).transactionsOf(sid).filter { it.side == TradeSide.SELL }
        assertEquals(1, sells.size, "应记下这笔卖出")
        assertEquals(60.0, sells.first().quantity, 1e-6, "⚠️ 数量应被截断为实际持仓 60")
        assertEquals(6.0, sells.first().fee, 1e-6, "⚠️ 手续费应按 60% 折算为 6")

        // 持仓必须归零（卖出全部）—— 若入库用了 100，重放会算出负持仓
        assertEquals(0.0, tradeOf(repo, db).positionQuantityOf(sid), 1e-6, "卖光后持仓应为 0")
    }

    @Test
    fun 外币买入按汇率折算成本位币() = runBlocking {
        // ⚠️ 兜 cashDeltaInCny / rateToBaseOf：港股按港元数字当人民币扣会少扣约 15%。
        // 这里用显式 fxRate=0.9：100 股 × 100 HKD × 0.9 = 9000 CNY。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 100_000.0)
        val sid = seedSecurity(repo, db, "hk00700", Market.HK, Currency.HKD)
        val before = cashOf(db).availableCash()

        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 100.0, 0.0, "2026-09-01", fxRate = 0.9)

        assertEquals(before - 9000.0, cashOf(db).availableCash(), 0.01, "⚠️ 港股买入应折本位币扣 9000，而不是 10000")
    }

    @Test
    fun 删除预览与实际删除结果一致() = runBlocking {
        // ⚠️ KDoc 声称"预告结果 = 实际结果"（共用 PositionCalculator.replay）。
        // 这是用户按下删除前看到的数字，若与实际不符会误导人。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db)
        val sid = seedSecurity(repo, db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 50.0, 20.0, 0.0, "2026-09-02")
        val txs = tradeOf(repo, db).transactionsOf(sid)
        val target = txs.first { it.quantity == 50.0 }

        val preview = tradeOf(repo, db).previewAfterDelete(sid, target.id)
        assertNotNull(preview, "应能算出预览")

        tradeOf(repo, db).deleteTransaction(target.id)

        // 实际结果
        val actualQty = tradeOf(repo, db).positionQuantityOf(sid)
        assertEquals(100.0, actualQty, 1e-6, "删掉后剩第一笔的 100 股")
        // ⚠️ 预览的 after 是删除后的持仓（DeletePreview.after: Position）
        assertEquals(actualQty, preview!!.after.quantity, 1e-6,
            "⚠️ 预览的剩余持仓必须等于实际删除后的持仓")
    }

    @Test
    fun CSV导入不校验现金充足_且不自动加自选() = runBlocking {
        // ⚠️ 兜 insertTradeForCsv 与 addTransaction 的**两点核心差异**：
        //  ① 不校验现金充足（CSV 是整本账搬运，行序不代表资金先后）
        //  ② 不做自动加自选（批量导入每行查一次纯属浪费）
        val (repo, db, _) = newRepo()
        // ⚠️ 刻意不入金：走"现金不足"路径，验证 CSV 导入不会被拦
        val sid = seedSecurity(repo, db, "sh600036")
        val watch = WatchlistRepository(db)
        assertEquals(false, watch.isWatched(sid), "初始不在自选")

        val csv = listOf(
            "交易ID,日期,标的代码,标的名称,方向,数量,成交价,手续费,币种,金额合计,备注/理由,情绪,执行评分,标签,质量评级",
            ",2026-09-01,sh600036,招商银行,买入,100,30.00,0.00,CNY,3000.00,CSV导入,,,,",
        ).joinToString("\n")

        tradeOf(repo, db).applyTradesCsv(csv)

        val txs = tradeOf(repo, db).transactionsOf(sid)
        assertEquals(1, txs.size, "⚠️ 现金为 0 时 CSV 导入仍应成功（不校验现金）")
        assertTrue(watch.isWatched(sid).not(), "⚠️ CSV 导入不应自动加自选")
    }

    @Test
    fun 外币交易现金折算优先用成交日汇率() = runBlocking {
        // ⚠️ 老周 2026-09-29 收口 REQ-ACC-15：此前现金折算**无视** trade.fx_rate ——
        // 买入港股按"最新汇率"扣现金，而成本/盈亏按成交日汇率 → 两套口径打架。
        // 这里把库里最新汇率故意设成 0.8，本笔成交日汇率填 0.9 → 现金必须按 0.9 扣 9,000。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 100_000.0)
        val sid = seedSecurity(repo, db, "hk00700", Market.HK, Currency.HKD)
        db.fxRateQueries.upsert("HKD", "2026-09-01", 0.8, "2026-09-01")
        val before = cashOf(db).availableCash()

        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 100.0, 0.0, "2026-09-02", fxRate = 0.9)

        assertEquals(
            before - 9_000.0, cashOf(db).availableCash(), 0.01,
            "⚠️ 应按成交日汇率 0.9 扣 9,000（不是最新汇率 0.8 的 8,000）",
        )

        // 删除回滚必须用**同一笔的**汇率，否则会留下换算差额
        val tx = tradeOf(repo, db).transactionsOf(sid).first()
        tradeOf(repo, db).deleteTransaction(tx.id)
        assertEquals(before, cashOf(db).availableCash(), 0.01, "删除后现金应精确还原")
    }

    @Test
    fun 编辑清仓卖出只改备注_数量不被截断() = runBlocking {
        // ⚠️ 兜 truncateSell 的 keepAtLeastQty（老周 2026-09-29 报：清仓后回头只改备注，
        // 界面提示"数量不够"）。买 1000 → 卖 1000（清仓，当前持仓 0）：
        // 上限 = max(剔除本笔后的持仓 1000, 本笔原数量 1000) = 1000 → 不截断、也不报错。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db)
        val sid = seedSecurity(repo, db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 1000.0, 10.0, 0.0, "2026-09-01")
        tradeOf(repo, db).addTransaction(sid, TradeSide.SELL, 1000.0, 12.0, 0.0, "2026-09-02")
        assertEquals(0.0, tradeOf(repo, db).positionQuantityOf(sid), 1e-6, "清仓后持仓应为 0")

        val sell = tradeOf(repo, db).transactionsOf(sid).first { it.side == TradeSide.SELL }
        tradeOf(repo, db).updateTransaction(
            txId = sell.id,
            side = TradeSide.SELL,
            quantity = 1000.0,
            price = 12.0,
            fee = 0.0,
            tradeDate = "2026-09-02",
            note = "补个备注",
        )

        val after = tradeOf(repo, db).transactionsOf(sid).first { it.id == sell.id }
        assertEquals(1000.0, after.quantity, 1e-6, "⚠️ 只改备注时数量不该被截断")
        assertEquals("补个备注", after.note, "备注应已更新")
        assertEquals(0.0, tradeOf(repo, db).positionQuantityOf(sid), 1e-6, "持仓仍应为 0")
    }

    @Test
    fun 编辑卖出改大数量_按剔除本笔后的持仓截断() = runBlocking {
        // ⚠️ 兜"上限只保底、**不放大**"（2026-09-29 修正）：此前是「剔除本笔后的持仓 + 本笔原数量」，
        // 等于放行超卖 —— 入库数量 > 实际持仓，现金按全量回款而重放只卖得掉持仓，
        // 差额**永久留在现金里**（正是 H7 要防的失衡）。
        // 买 1000 → 卖 400（持仓 600）→ 卖 600；把第二笔改成 800：
        // 上限 = max(剔除本笔后的持仓 600, 原数量 600) = 600 → 应截断为 600。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db)
        val sid = seedSecurity(repo, db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 1000.0, 10.0, 0.0, "2026-09-01")
        tradeOf(repo, db).addTransaction(sid, TradeSide.SELL, 400.0, 12.0, 0.0, "2026-09-02")
        tradeOf(repo, db).addTransaction(sid, TradeSide.SELL, 600.0, 12.0, 0.0, "2026-09-03")
        // ⚠️ transactionsOf 是**日期倒序**（trade_date DESC），别想当然用 last()
        val second = tradeOf(repo, db).transactionsOf(sid)
            .first { it.side == TradeSide.SELL && it.quantity == 600.0 }

        tradeOf(repo, db).updateTransaction(
            txId = second.id,
            side = TradeSide.SELL,
            quantity = 800.0,
            price = 12.0,
            fee = 0.0,
            tradeDate = "2026-09-03",
        )

        val after = tradeOf(repo, db).transactionsOf(sid).first { it.id == second.id }
        assertEquals(600.0, after.quantity, 1e-6, "⚠️ 应截断为剔除本笔后的持仓 600，而不是放行 800")
        assertEquals(0.0, tradeOf(repo, db).positionQuantityOf(sid), 1e-6, "持仓应恰好归零，不能为负")
    }

    @Test
    fun 交易CSV导出含印花税列且能原样导回() = runBlocking {
        // ⚠️ 老周 2026-09-30：导出/导入都要有印花税一栏（REQ-ACC-17）——
        // 「导出 → 清空 → 导入」这条链必须把税金额原样带回来，否则备份还原会静默丢税。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 1_000_000.0)
        val sid = seedSecurity(repo, db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        tradeOf(repo, db).addTransaction(
            sid, TradeSide.SELL, 100.0, 12.0, 0.0, "2026-09-02", stampDuty = 0.60,
        )

        val csv = CsvRepository(db).buildTradesCsv()
        assertTrue(csv.contains("印花税"), "导出的表头要有「印花税」列")

        repo.clearAllLedger()
        val r = tradeOf(repo, db).applyTradesCsv(csv)
        assertEquals(2, r.okRows, "两笔都应导入成功：${r.skipped}")

        val back = tradeOf(repo, db).transactionsOf(sid).first { it.side == TradeSide.SELL }
        assertEquals(0.60, back.stampDuty, 1e-9, "⚠️ 印花税应随 CSV 往返原样保留")
    }

    @Test
    fun 印花税计入现金且随交易入库() = runBlocking {
        // ⚠️ 老周 2026-09-30（REQ-ACC-17）：印花税与手续费同等计入现金 ——
        // A 股卖出收万分之 5；漏掉会让"账本回款"比券商多、可用现金虚高。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 1_000_000.0)
        val sid = seedSecurity(repo, db)   // sh600519（A 股）
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        val afterBuy = cashOf(db).availableCash()

        // 卖出 100 股 @12：回款 1,200 − 印花税 1,200 × 万分之 5 = 0.60
        tradeOf(repo, db).addTransaction(
            sid, TradeSide.SELL, 100.0, 12.0, 0.0, "2026-09-02",
            stampDuty = 0.60,
        )

        assertEquals(
            afterBuy + 1_200.0 - 0.60, cashOf(db).availableCash(), 0.01,
            "⚠️ 卖出回款必须扣掉印花税",
        )
        val sell = tradeOf(repo, db).transactionsOf(sid).first { it.side == TradeSide.SELL }
        assertEquals(0.60, sell.stampDuty, 1e-9, "印花税金额应随交易入库（CSV / 备份也带着它）")
    }

    @Test
    fun 一键清空标的流水_现金回冲且标的保留() = runBlocking {
        // ⚠️ 老周 2026-09-30：标的详情页「一键清空」= 清交易 + 分红，**现金必须同步回冲**，
        // 而**标的本身保留**（持仓归零，可重新记一笔）。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 100_000.0)
        val sid = seedSecurity(repo, db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 50.0, 12.0, 0.0, "2026-09-02")
        val cashAfterBuy = cashOf(db).availableCash()

        val cleared = tradeOf(repo, db).clearSecurityLedger(sid)

        assertEquals(2, cleared, "应清掉 2 笔交易")
        assertEquals(0, tradeOf(repo, db).transactionsOf(sid).size, "交易应清空")
        assertEquals(0.0, tradeOf(repo, db).positionQuantityOf(sid), 1e-6, "持仓应归零")
        // 买入共花 100×10 + 50×12 = 1,600 → 回冲后现金应回到买入前的水平
        assertEquals(cashAfterBuy + 1_600.0, cashOf(db).availableCash(), 0.01, "⚠️ 现金应按原值回冲")
        // ⚠️ 用 assertTrue 而不是 assertNotNull：@Test 方法必须是 Unit 返回，
        // 而 `runBlocking { ... }` 会把**最后一条表达式**的返回值带出来（assertNotNull 返回实体 → 非法测试类）
        assertTrue(db.securityQueries.selectById(sid).executeAsOneOrNull() != null, "⚠️ 标的本身必须保留")
    }

    @Test
    fun 清空整个账本_流水清空现金归零但标的保留() = runBlocking {
        // ⚠️ 老周 2026-09-30（设置页红色按钮）：全部交易 + 分红 + 出入金清空、账户现金归零；
        // **标的保留** —— 重新导入 CSV 时 findOrCreateSecurity 会按 symbol 复用，不会多出一批重复标的。
        val (repo, db, _) = newRepo()
        repo.seedAccount(db, cash = 100_000.0)
        val sid = seedSecurity(repo, db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        val secCount = db.securityQueries.countAll().executeAsOne()

        val n = repo.clearAllLedger()

        assertEquals(1, n, "应清掉 1 笔交易")
        assertEquals(0, db.tradeQueries.selectAll().executeAsList().size, "交易应清空")
        assertEquals(0, db.cashFlowQueries.selectAll().executeAsList().size, "出入金应清空")
        assertEquals(0.0, cashOf(db).availableCash(), 0.01, "⚠️ 账户现金应归零")
        assertEquals(
            secCount, db.securityQueries.countAll().executeAsOne(),
            "⚠️ 标的必须保留（重新导入时按 symbol 复用）",
        )
    }
}
