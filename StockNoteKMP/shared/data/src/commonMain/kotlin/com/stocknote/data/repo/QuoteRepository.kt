package com.stocknote.data.repo

import com.stocknote.core.model.Quote

/**
 * **行情域 Repository**（阶段 1：委托式拆分，2026-09-28）。
 *
 * 说明同 [FxRepository]：当前**只转发**，行为零变化；先让调用方依赖到域接口。
 *
 * 域内职责：行情缓存与刷新、历史收盘价、资产曲线与收益率、分析师目标价、近期标的。
 * ⚠️ 口径提醒：曲线现金锚走 `availableCash`（与统计页同源），
 * 不要再用 `openingCash`（语义已是"期初本金"）。
 */
class QuoteRepository internal constructor(
    private val db: com.stocknote.data.db.StockNoteDb,
    private val repo: PortfolioRepository,
) {
    /** 本地缓存的行情（无网也能看）。 */
    suspend fun cached(symbol: String): Quote? = repo.cachedQuote(symbol)

    /** 联网刷新行情。 */
    suspend fun refresh(symbol: String): Quote? = repo.refreshQuote(symbol)

    /** 资产曲线（带缓存）。 */
    suspend fun equityCurve(
        refresh: Boolean = false,
        days: Int = repo.CURVE_FETCH_DAYS,
    ) = repo.equityCurveCached(refresh, days)

    // ---- 历史收盘价（M4 逐日估值的数据底座）★ 实现已搬迁 ----

    /** 批量缓存历史收盘（拉取后落库，去重由 PK 兜底）。 */
    suspend fun saveDailyCloses(symbol: String, candles: List<com.stocknote.data.net.QuoteClient.Candle>) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            candles.forEach { db.fxRateQueries.upsertClose(symbol, it.date, it.close) }
        }

    /** 某标的的历史收盘价序列（升序）。 */
    suspend fun closesBySymbol(symbol: String): List<Pair<String, Double>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            db.fxRateQueries.selectClosesBySymbol(symbol).executeAsList()
                .map { it.date to it.close }
        }

    // ⚠️ 2026-09-29 拆分（第 5 批）：`recentSecurities` 已归**自选域**（WatchlistRepository），
    // 从此处移除 —— 它读的是自选/交易/计划表，本质是"最近用过的标的"而非行情数据。

    /** 分析师目标价建议（仅 A 股有数据）。 */
    suspend fun analystTargetSuggestion(
        symbol: String,
        months: Int = com.stocknote.data.net.AnalystTargetSource.DEFAULT_MONTHS,
    ) = repo.analystTargetSuggestion(symbol, months)

    /** 使曲线缓存失效（数据变动后调用）。 */
    fun invalidateCache() = repo.invalidateCurveCache()
}
