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
import com.stocknote.data.platform.todayIso
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **标的域契约测试**（2026-09-29，第 11 批搬迁后补）。
 *
 * 补它的直接原因：`deleteSecurity` 是 M15 报告里点名的**潜在地雷** ——
 * 级联清理一旦漏掉一张表，就会留下孤儿流水（既不进持仓也不进统计，且**没有任何提示**）。
 * 而它在搬迁后**零测试**，属于"拆完没兜住"的空白，所以优先补上。
 *
 * 同时钉住另外三条口径：
 *  1. `findOrCreateSecurity` 按 symbol 去重（「合并展示」成立的前提，技术说明书 6.1）；
 *  2. 本地搜索的通配符转义（用户输入 `%` 不能命中全部）；
 *  3. `excludeFromStats` 是**账务口径开关**，且只在新建时生效。
 *
 * ⚠️ 用 JVM `JdbcSqliteDriver`（内存库、不加密），schema 由测试自建。
 */
class SecurityRepositoryContractTest {

    private fun newRepo(): Triple<PortfolioRepository, StockNoteDb, SqlDriver> {
        val driver: SqlDriver = createEncryptedDriver("security-test", ByteArray(0))
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

    private fun secOf(repo: PortfolioRepository, db: StockNoteDb) =
        SecurityRepository(db, repo, QuoteClient(createHttpClient()))

    @Test
    fun findOrCreateSecurity按symbol去重() = runBlocking {
        // 「同一标的多笔交易归集到同一个 security_id」是合并展示的前提（技术说明书 6.1）。
        // 若这里不幂等，同一只股票会变出两个标的，持仓被拆成两摊。
        val (repo, db, _) = newRepo()
        val sec = secOf(repo, db)

        val a = sec.findOrCreateSecurity("sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY)
        val b = sec.findOrCreateSecurity("sh600519", "贵州茅台（改名了）", Market.A_SHARE, Currency.CNY)

        assertEquals(a.id, b.id, "⚠️ 同一 symbol 必须复用同一个 security_id")
        assertEquals(1, db.securityQueries.selectAll().executeAsList().size, "库里不该出现两个同名标的")
        // 已存在时原样返回，连 name 都不改（改名不是这条路）
        assertEquals("贵州茅台", b.name)
    }

    @Test
    fun 删除标的级联清理全部引用() = runBlocking {
        // ⚠️ M15：deleteAll() 只删标的行，会在 trade / dividend / watchlist / trade_plan
        // 留下孤儿；孤儿既不进持仓也不进统计，「钱和股数」对不上却没有任何提示。
        // 所以要验证：**一个不剩**。
        val (repo, db, _) = newRepo()
        val sec = secOf(repo, db)
        val sid = sec.findOrCreateSecurity("sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY).id

        // 造出四类引用：交易 ×2 / 分红 ×1 / 自选 ×1 / 计划（通过 tradePlan 表）
        val cashRepo = CashRepository(db)
        db.accountQueries.upsert("acc-t", "测试账户", "CNY", 0.0)
        cashRepo.addCashFlow("acc-t", "2026-08-31", true, 1_000_000.0, Currency.CNY, 1.0)
        val trade = TradeRepository(db, repo, cashRepo, sec)
        trade.addTransaction(sid, TradeSide.BUY, 100.0, 1600.0, 0.0, "2026-09-01")
        trade.addTransaction(sid, TradeSide.BUY, 50.0, 1650.0, 0.0, "2026-09-02")
        repo.addDividend(
            securityId = sid, exDate = "2026-09-03", type = "CASH",
            quantity = 100.0, perShare = 1.0, bonusShares = 0.0, rightsPrice = 0.0,
            currency = Currency.CNY,
        )
        db.watchlistQueries.insertItem(
            id = "wl-t", security_id = sid, group_name = "未分组",
            sort_order = 1, pinned = 0L, created_at = todayIso(), target_price = null,
        )

        val removed = sec.deleteSecurity(sid)

        assertEquals(2, removed, "应返回被连带删除的交易笔数（供界面提示）")
        assertTrue(
            db.securityQueries.selectById(sid).executeAsOneOrNull() == null,
            "⚠️ 标的本身应被删除",
        )
        assertEquals(0, db.tradeQueries.selectAll().executeAsList().size, "⚠️ 交易必须清干净（否则是孤儿流水）")
        assertEquals(0, db.dividendQueries.selectAll().executeAsList().size, "⚠️ 分红必须清干净")
        assertEquals(0, db.watchlistQueries.selectAll().executeAsList().size, "⚠️ 自选必须清干净")
        assertEquals(0, db.tradePlanQueries.selectAll().executeAsList().size, "⚠️ 计划必须清干净")
    }

    @Test
    fun 本地搜索的通配符被转义() = runBlocking {
        // ⚠️ 轻微-5 修复（2026-09-28）：LIKE 里 `%` 是通配符，用户输入它不该命中全部。
        val (repo, db, _) = newRepo()
        val sec = secOf(repo, db)
        sec.findOrCreateSecurity("sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY)
        sec.findOrCreateSecurity("sh600036", "招商银行", Market.A_SHARE, Currency.CNY)

        val all = sec.searchSecurities("")
        assertEquals(2, all.size, "空关键字返回全部")

        val withPercent = sec.searchSecurities("%")
        assertEquals(
            0, withPercent.size,
            "⚠️ 输入 `%` 应被当作普通字符转义，不能命中全部标的",
        )
    }

    @Test
    fun 统计开关是账务口径且只在新建时生效() = runBlocking {
        // 「不计入统计与分析」= 完全账外（买卖不动现金、市值不进总资产），不是展示选项。
        val (repo, db, _) = newRepo()
        val sec = secOf(repo, db)

        // ① 新建时带上 → 生效
        val off = sec.findOrCreateSecurity(
            "of000001", "某场外基金", Market.A_SHARE, Currency.CNY, excludeFromStats = true,
        )
        assertEquals(
            1L,
            db.securityQueries.selectById(off.id).executeAsOneOrNull()?.exclude_from_stats,
            "新建时应写入 exclude_from_stats",
        )

        // ② 对**已存在**的标的，findOrCreate 不会改它的开关（否则"在表单里选了一只已存在的基金"
        //    会按表单缺省值把它悄悄关掉 —— 这是明确的口径要求）
        val again = sec.findOrCreateSecurity("of000001", "某场外基金", Market.A_SHARE, Currency.CNY)
        assertEquals(
            1L,
            db.securityQueries.selectById(again.id).executeAsOneOrNull()?.exclude_from_stats,
            "⚠️ 已存在的标的应原样返回，开关不能被改掉",
        )

        // ③ 改开关要走专门的方法
        sec.setSecurityExcludeFromStats(again.id, false)
        assertEquals(
            0L,
            db.securityQueries.selectById(again.id).executeAsOneOrNull()?.exclude_from_stats,
            "setSecurityExcludeFromStats 应能改回",
        )
    }
}
