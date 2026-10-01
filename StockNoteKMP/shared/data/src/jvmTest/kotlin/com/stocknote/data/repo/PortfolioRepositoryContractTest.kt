package com.stocknote.data.repo

import app.cash.sqldelight.db.SqlDriver
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.createEncryptedDriver
import com.stocknote.data.platform.createHttpClient
import com.stocknote.data.platform.createSecureKeyStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **`PortfolioRepository` 的契约测试**（2026-09-29 新建基础设施）。
 *
 * 这是 data 层**第一批真数据库行为测试**。此前它只有静态检查类，
 * 而账本数字错了很难发现 —— H2（跨币种直接相加）、H3（送股不进曲线）都是实例。
 *
 * ⚠️ 用的是 JVM `JdbcSqliteDriver`（**内存库、不加密**），schema 由 `newRepo()` 自己建。
 * 因此**不能**用这里验证 sqlcipher 相关行为。
 * ⚠️ 每个测试一个全新空库，互不干扰。
 */
class PortfolioRepositoryContractTest {

    /** 建一个空库 + 仓储。Schema.create 保证表结构就是迁移后的最终形态。 */
    private fun newRepo(): Triple<PortfolioRepository, StockNoteDb, SqlDriver> {
        val driver: SqlDriver = createEncryptedDriver("contract-test", ByteArray(0))
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

    /** 交易域（第 6 批后查询类实现已搬到 TradeRepository）。 */
    private fun tradeOf(repo: PortfolioRepository, db: StockNoteDb) = TradeRepository(db, repo, CashRepository(db), SecurityRepository(db, repo, QuoteClient(createHttpClient())))

    /**
     * 建账户 + 入金 + 建标的。
     *
     * ⚠️ 首次跑这批测试时**全部失败**在「现金不足」—— 这恰恰说明测试跑的是**真业务逻辑**
     * （`addTransaction` 会校验可用现金并抛异常）。所以这里按真实流程先入金，
     * 而不是绕过校验 —— 校验本身也是要守住的行为。
     */
    private suspend fun PortfolioRepository.seed(
        db: StockNoteDb,
        symbol: String = "sh600519",
        cash: Double = 100_000.0,
    ): String {
        val accountId = "acc-test"
        db.accountQueries.upsert(accountId, "测试账户", "CNY", 0.0)
        cashOf(db).addCashFlow(
            accountId = accountId,
            flowDate = "2026-08-31",
            isDeposit = true,
            amountOrig = cash,
            currency = Currency.CNY,
            fxRate = 1.0,
        )
        return SecurityRepository(db, this, QuoteClient(createHttpClient())).findOrCreateSecurity(symbol, "测试标的", Market.A_SHARE, Currency.CNY).id
    }

    @Test
    fun 买入一笔后_持仓数量正确() = runBlocking {
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)

        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 5.0, "2026-09-01")

        val detail = repo.loadSecurityDetail(sid)
        assertEquals(100.0, detail?.position?.quantity ?: 0.0, "持仓数量应为 100")
    }

    @Test
    fun 卖出后_持仓减少() = runBlocking {
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        tradeOf(repo, db).addTransaction(sid, TradeSide.SELL, 40.0, 12.0, 0.0, "2026-09-02")

        val detail = repo.loadSecurityDetail(sid)
        assertEquals(60.0, detail?.position?.quantity ?: 0.0, "卖出 40 后应剩 60")
    }

    @Test
    fun 交易笔数与列表一致() = runBlocking {
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 50.0, 11.0, 0.0, "2026-09-02")

        assertEquals(2L, tradeOf(repo, db).tradeCount(), "应记到 2 笔")
        assertEquals(2, tradeOf(repo, db).transactionsOf(sid).size, "该标的交易列表应有 2 条")
    }

    @Test
    fun 记过一笔的标的会自动加入自选() = runBlocking {
        // ⚠️ 这条兜的是 addTransaction 里那段「自动加自选」——
        // 第 5 批搬迁时它从「调用 isWatched/addWatch」改成了「就地写库」，
        // 属于**改了实现但没改行为**的改动，必须有断言守住。
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        val watch = WatchlistRepository(db)
        assertEquals(false, watch.isWatched(sid), "初始不在自选")

        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")

        assertEquals(true, watch.isWatched(sid), "记过一笔后应自动进自选")
    }

    // ---- 以下为口径护栏：都是历史上真出过错、或容易被"顺手改坏"的地方 ----

    @Test
    fun 自选不级联账本_移除自选后交易与持仓仍在() = runBlocking {
        // ⚠️ 关键产品决策：自选只表示「关不关注」，与账本互不干涉。
        // 移除自选**不能**删交易/持仓 —— 这正是容易被"顺手级联"破坏的地方。
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        val watch = WatchlistRepository(db)
        assertEquals(true, watch.isWatched(sid), "记过一笔后应在自选里")

        watch.removeBySecurity(sid)

        assertEquals(false, watch.isWatched(sid), "自选已移除")
        assertEquals(1, tradeOf(repo, db).transactionsOf(sid).size, "⚠️ 交易不能跟着被删")
        assertEquals(100.0, repo.loadSecurityDetail(sid)?.position?.quantity ?: 0.0, "持仓也不能变")
    }

    @Test
    fun 删标签_同时清理交易与交易计划里的引用() = runBlocking {
        // ⚠️ 兜的是 M11（2026-09-28 修复）：此前删标签只清 trade.tag_ids，
        // 计划里的引用留着 → 计划筛选下拉出现"幽灵标签"、点进去 0 条。
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        val tag = TagRepository(db)
        val plan = PlanRepository(db, repo, SecurityRepository(db, repo, QuoteClient(createHttpClient())))

        tag.add("突破")
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01", tags = listOf("突破"))
        plan.upsert(
            com.stocknote.core.model.TradePlan(
                id = "p1", securityId = sid, side = TradeSide.BUY,
                targetPrice = 9.0, discount = null, plannedPrice = 9.0, quantity = 100.0,
                feeRate = 2.5, note = "", tags = listOf("突破"),
                createdAt = "2026-09-01", updatedAt = "2026-09-01", doneAt = null,
            ),
        )

        tag.delete("突破")

        assertEquals(0, tradeOf(repo, db).transactionsOf(sid).first().tags.size, "交易里的标签应被清掉")
        assertEquals(0, plan.byId("p1")?.tags?.size ?: 0, "⚠️ 计划里的引用也要清掉（M11）")
    }

    @Test
    fun 计划完成标记_只写完成日期不动更新时间() = runBlocking {
        // ⚠️ 完成标记是「行上快捷圆点」，不是「编辑」—— 不该让 updatedAt 变化
        //（否则列表看起来被动过、排序会跳）。
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        val plan = PlanRepository(db, repo, SecurityRepository(db, repo, QuoteClient(createHttpClient())))
        plan.upsert(
            com.stocknote.core.model.TradePlan(
                id = "p2", securityId = sid, side = TradeSide.BUY,
                targetPrice = 9.0, discount = null, plannedPrice = 9.0, quantity = 100.0,
                feeRate = 2.5, note = "", tags = emptyList(),
                createdAt = "2026-09-01", updatedAt = "2026-09-01", doneAt = null,
            ),
        )
        val before = plan.byId("p2")

        plan.setDone("p2", true)

        val after = plan.byId("p2")
        assertEquals(true, after?.doneAt?.isNotEmpty(), "应写上完成日期")
        assertEquals(before?.updatedAt, after?.updatedAt, "⚠️ updatedAt 不应被改动")
    }

    @Test
    fun 删除交易后_持仓回退() = runBlocking {
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        val txId = tradeOf(repo, db).transactionsOf(sid).first().id

        tradeOf(repo, db).deleteTransaction(txId)

        val detail = repo.loadSecurityDetail(sid)
        assertEquals(0.0, detail?.position?.quantity ?: 0.0, "删掉唯一一笔后持仓应为 0")
    }

    // ---- 分红检测的「as-of 持股数」（2026-09-30 老周报"分红提示从来没出现过"）----

    /**
     * ⚠️ **回归测试**：`quantityAsOf` 的**首笔买入必须计入**。
     *
     * 修复前（H3 那版 2026-09-28）：买入也被写成「已有持仓（qty > 1e-9）才加」，
     * 于是首笔买入被吞、之后只剩卖出 → 持股数**恒为 0** →
     * `detectPendingDividends` 在每个候选分红上都命中 `qty <= 1e-9` 而丢弃，
     * 结果**永远检测不出任何未登记分红**（真机表现就是"重启和刷新都没有提示"）。
     */
    @Test
    fun 分红asOf持股数_首笔买入即计入() = runBlocking {
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")

        val trades = db.tradeQueries.selectAll().executeAsList().map { it.toDomain() }
        // 买入之后（如除权日当天）= 100
        assertEquals(100.0, repo.quantityAsOf(trades, "2026-09-02"), 1e-9)
        // 买入之前 = 0
        assertEquals(0.0, repo.quantityAsOf(trades, "2026-08-31"), 1e-9)
    }

    /** 回归：as-of 持股数不允许出现负数（卖出超仓按 0 截断，与全库口径一致）。 */
    @Test
    fun 分红asOf持股数_卖出不留负持仓() = runBlocking {
        val (repo, db, _) = newRepo()
        val sid = repo.seed(db)
        tradeOf(repo, db).addTransaction(sid, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")
        tradeOf(repo, db).addTransaction(sid, TradeSide.SELL, 100.0, 12.0, 0.0, "2026-09-02")

        val trades = db.tradeQueries.selectAll().executeAsList().map { it.toDomain() }
        assertEquals(0.0, repo.quantityAsOf(trades, "2026-09-03"), 1e-9)
        // 卖出当天之前仍是 100（as-of 口径：只算 tradeDate <= dateIso 的流水）
        assertEquals(100.0, repo.quantityAsOf(trades, "2026-09-01"), 1e-9)
    }
}
