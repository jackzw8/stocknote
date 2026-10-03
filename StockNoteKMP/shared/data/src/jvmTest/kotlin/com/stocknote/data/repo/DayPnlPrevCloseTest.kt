package com.stocknote.data.repo

import app.cash.sqldelight.db.SqlDriver
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.createEncryptedDriver
import com.stocknote.data.platform.createSecureKeyStore
import com.stocknote.data.platform.todayIso
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **「当日盈亏」必须用本次取到的昨收**（老周 2026-10-02 报「统计页当日盈亏 ≠ 盈亏日历当日盈亏」）。
 *
 * ## 缺陷
 * `loadSnapshot` 里 `prevCloses` 是**先**从 `quote` 表读出来的（＝**上一次刷新落库**的昨收），
 * 随后刷新交易所行情时只更新了 `prices`、**没更新 `prevCloses`**（场外基金那条分支更新了，两支写法不一致）
 * ⇒ `dayPnl = Σ(现价 − 昨收) × 持仓` 用的是**旧一天的昨收**。
 *
 * 典型症状：每天**冷启动第一次刷新**，统计页「当日盈亏」是**两天的涨跌**（明显偏大），
 * 同日再刷新一次才对；而盈亏日历是「逐日总资产差分」，天然只算一天 → 两边必然对不上。
 * 持仓 TOP5 的「当日涨跌%」同源，也一样偏。
 *
 * ⚠️ 用真内存库 + Ktor `MockEngine` 喂一段**行情响应**（不联网）；
 * 刻意先往 `quote` 表塞一条「旧昨收 9」，再让行情返回「昨收 11」。
 */
class DayPnlPrevCloseTest {

    /** qt 数组 5~29 号位的占位（真实响应 88 个字段，这里只造用到的 3/4/30 号位）。 */
    private val qtFiller = List(25) { "\"0\"" }.joinToString(",")

    /**
     * 腾讯行情响应：现价 12.0、昨收 11.0；day 给 6 根（≥ MIN_CANDLES=5，否则视为残数据）。
     * @param quoteTime 塞进 qt[30] 的**报价时间**（yyyyMMddHHmmss，交易所本地）——
     *   P3-36 报价日期守门的输入：loadSnapshot 会把报价日期 ≠ 今天的标的从当日盈亏剔除。
     */
    private fun quoteBody(quoteTime: String): String = """
    {"code":0,"msg":"","data":{"sh600519":{
      "day":[["2026-09-25","10.8","10.9","11.0","10.7","1000"],
             ["2026-09-28","10.9","11.0","11.1","10.8","1000"],
             ["2026-09-29","11.0","11.0","11.2","10.9","1000"],
             ["2026-09-30","11.0","11.1","11.3","11.0","1000"],
             ["2026-10-01","11.1","11.0","11.2","11.0","1000"],
             ["2026-10-02","11.0","12.0","12.1","11.0","1000"]],
      "qt":{"sh600519":["1","贵州茅台","600519","12.0","11.0",$qtFiller,"$quoteTime"]},
      "prec":"11.0","version":"16"}}}
    """.trimIndent()

    /** 今天（真实时钟）的交易所报价时间串，如 "2026-10-02" → "20261002150000"。 */
    private fun todayQuoteTime(): String = todayIso().replace("-", "") + "150000"

    private fun mockQuoteClient(quoteTime: String = todayQuoteTime()): QuoteClient {
        val engine = MockEngine {
            respond(
                content = quoteBody(quoteTime),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return QuoteClient(HttpClient(engine))
    }

    @Test
    fun `当日盈亏必须用本次取到的昨收_而不是上一次落库的旧昨收`() = runBlocking {
        val driver: SqlDriver = createEncryptedDriver("daypnl-prevclose", ByteArray(0))
        StockNoteDb.Schema.create(driver)
        val db = StockNoteDb(driver)
        val quotes = mockQuoteClient()
        val repo = PortfolioRepository(
            db = db,
            quoteClient = quotes,
            driver = driver,
            cash = CashRepository(db),
            keyStore = createSecureKeyStore(),
        )

        // 账本：1 个账户 + 入金 + 花 1000 买入 100 股
        db.accountQueries.upsert("acc-daypnl", "当日盈亏测试", "CNY", 0.0)
        CashRepository(db).addCashFlow("acc-daypnl", "2026-10-01", true, 100_000.0, Currency.CNY, 1.0)
        val security = SecurityRepository(db, repo, quotes)
            .findOrCreateSecurity("sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY)
        TradeRepository(db, repo, CashRepository(db), SecurityRepository(db, repo, quotes))
            .addTransaction(security.id, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-10-01")

        // ⚠️ 关键前置：库里留着**上一次刷新**的行情 —— 昨收 9（旧）；quote_date 为 null → 不设防
        db.quoteQueries.upsert("sh600519", 10.0, 9.0, "tencent", null, null)

        // 本次刷新取到：现价 12、昨收 11
        val snap = repo.loadSnapshot(refreshQuotes = true)

        // 当日盈亏 = (12 − 11) × 100 = 100
        // 若沿用库里那条旧昨收 9，(12 − 9) × 100 = 300 —— 就是"把两天的涨跌算成一天"的那个 bug
        assertEquals(
            100.0,
            snap.dayPnl,
            0.01,
            "当日盈亏必须用**本次**取到的昨收 11（用旧昨收 9 会得到 300 = 两天的涨跌）",
        )
        // 持仓 TOP5 的「当日涨跌%」同源，也必须拿到新昨收
        assertEquals(11.0, snap.positions.single().prevClose ?: 0.0, 1e-9, "持仓行的昨收也要跟着刷新")
        assertEquals(12.0, snap.positions.single().marketPrice ?: 0.0, 1e-9)
    }

    @Test
    fun `行情取不到时仍沿用库里缓存价与昨收_不虚增`() = runBlocking {
        // 离线场景：不刷新行情 → 完全用库里的缓存（含旧昨收），且不抛
        val driver: SqlDriver = createEncryptedDriver("daypnl-offline", ByteArray(0))
        StockNoteDb.Schema.create(driver)
        val db = StockNoteDb(driver)
        val quotes = mockQuoteClient()
        val repo = PortfolioRepository(db, quotes, driver, CashRepository(db), createSecureKeyStore())

        db.accountQueries.upsert("acc-off", "离线测试", "CNY", 0.0)
        // 买入要校验现金充足（CashRepository/TradeRepository 的硬约束），先入金
        CashRepository(db).addCashFlow("acc-off", "2026-10-01", true, 100_000.0, Currency.CNY, 1.0)
        val security = SecurityRepository(db, repo, quotes)
            .findOrCreateSecurity("sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY)
        TradeRepository(db, repo, CashRepository(db), SecurityRepository(db, repo, quotes))
            .addTransaction(security.id, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-10-01")
        db.quoteQueries.upsert("sh600519", 12.0, 11.0, "tencent", null, null)

        val snap = repo.loadSnapshot(refreshQuotes = false)

        assertEquals(100.0, snap.dayPnl, 0.01, "离线时用缓存里的 12/11 → 仍是 100")
        assertEquals(12.0, snap.positions.single().marketPrice ?: 0.0, 1e-9, "用缓存价，不联网")
    }

    @Test
    fun `报价日期不是今天的标的_当日盈亏计0_但市值照算`() = runBlocking {
        // P3-36（老周拍板 B 方案）：休市时行情快照停在最后一个交易日 —— 实测 2026-10-02
        // A 股快照停在 09-30，统计页把 09-30 的涨跌当成了"当日"（比盈亏日历多出 3,807）。
        // 这里喂一条**报价时间是 2020 年**的行情（必然 ≠ 今天），断言：
        // ① dayPnl == 0（昨收被守门拦下）；② 市值照算（marketPrice 仍是 12.0）。
        val driver: SqlDriver = createEncryptedDriver("daypnl-stale", ByteArray(0))
        StockNoteDb.Schema.create(driver)
        val db = StockNoteDb(driver)
        val quotes = mockQuoteClient(quoteTime = "20200101150000")
        val repo = PortfolioRepository(
            db = db,
            quoteClient = quotes,
            driver = driver,
            cash = CashRepository(db),
            keyStore = createSecureKeyStore(),
        )

        db.accountQueries.upsert("acc-stale", "休市测试", "CNY", 0.0)
        CashRepository(db).addCashFlow("acc-stale", "2026-10-01", true, 100_000.0, Currency.CNY, 1.0)
        val security = SecurityRepository(db, repo, quotes)
            .findOrCreateSecurity("sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY)
        TradeRepository(db, repo, CashRepository(db), SecurityRepository(db, repo, quotes))
            .addTransaction(security.id, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-10-01")

        val snap = repo.loadSnapshot(refreshQuotes = true)

        assertEquals(0.0, snap.dayPnl, 0.01, "报价日期 ≠ 今天 → 不算当日盈亏（不把旧交易日的涨跌当成今天）")
        assertEquals(12.0, snap.positions.single().marketPrice ?: 0.0, 1e-9, "市值照算：现价照用")
        assertEquals(null, snap.positions.single().prevClose, "昨收被拦 → 当日涨跌% 不显示（不误导）")
    }
}
