package com.stocknote.data.repo

import com.stocknote.core.calc.CashEquivalents
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **标的域 Repository**（阶段 2：实现已搬迁，2026-09-29，第 11 批）。
 *
 * 本地 CRUD / 搜索 / 联网搜索 / 删除（级联）/ 统计开关。
 *
 * ⚠️ **`loadSecurityDetail` 留在了 `PortfolioRepository`**：它要拼持仓（全量重放），是聚合方法 ——
 * 同 `loadSnapshot` 的道理，聚合逻辑归「账本聚合层」，不进本域。
 *
 * ⚠️ 依赖方向：本类依赖 `repo` **仅为了**删除/改开关后的 `invalidateCurveCache()`。
 * 原类不持有本类（单向，不会循环）。
 *
 * ## 口径要点
 *  - `findOrCreateSecurity` 按 **symbol 去重**（同一标的多笔交易归集到同一 id，
 *    「合并展示」成立的前提，技术说明书 6.1）；
 *  - `excludeFromStats` **只在新建时生效**，已存在的原样返回（改开关走 [setSecurityExcludeFromStats]）；
 *  - 命中设置页「现金等价物」名单会**自动打标**（老周 2026-09-20）；
 *  - `deleteSecurity` 是唯一删除入口：**同一事务**内清掉全部引用（交易/笔记/截图/分红/自选/计划），
 *    避免孤儿记录（M15）。
 */
class SecurityRepository internal constructor(
    private val db: StockNoteDb,
    /** ⚠️ 仅用于写操作后的失效资产曲线缓存。 */
    private val repo: PortfolioRepository,
    /**
     * 联网搜索标的（REQ-TOOL-01）用。
     *
     * ⚠️ **由容器注入、与原类共用同一个实例** —— 不要在本域自建：
     * `createHttpClient()` 每次都会建一个新的 HttpClient（连带新的连接池），
     * 既浪费资源，也让连接无法复用。
     */
    private val quoteClient: QuoteClient,
) {
    /** 全部标的。 */
    suspend fun securities(): List<Security> = withContext(Dispatchers.Default) {
        db.securityQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    /** 记一笔表单的标的候选：按名称或代码做本地模糊搜索（离线可用）。 */
    suspend fun searchSecurities(keyword: String): List<Security> = withContext(Dispatchers.Default) {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            db.securityQueries.selectAll().executeAsList().map { it.toDomain() }
        } else {
            // ⚠️ 轻微-5 修复（2026-09-28）：LIKE 通配符转义（SQL 侧已加 ESCAPE '\'）——
            // 用户输入 `%` 会命中全部、`_` 被当单字符通配。先转义再传参。
            val escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            db.securityQueries.search(escaped, escaped).executeAsList().map { it.toDomain() }
        }
    }

    /** 按 symbol 查（找不到返回 null）。 */
    suspend fun findBySymbol(symbol: String): Security? = withContext(Dispatchers.Default) {
        db.securityQueries.selectBySymbol(symbol.trim()).executeAsOneOrNull()?.toDomain()
    }

    /** 标的实时网络搜索（REQ-TOOL-01）。失败返回空列表，本地搜索仍是兜底。 */
    suspend fun searchOnline(keyword: String): List<QuoteClient.SymbolHit> =
        withContext(Dispatchers.Default) { quoteClient.searchOnline(keyword) }


    /**
     * 找到或创建标的。按 symbol 去重 —— 这保证了同一标的多笔交易归集到同一个
     * security_id，是「合并展示」能成立的前提（技术说明书 6.1）。
     */
    suspend fun findOrCreateSecurity(
        symbol: String,
        name: String,
        market: Market,
        currency: Currency,
        industry: String? = null,
        isCashEquivalent: Boolean = false,
        /**
         * 不计入统计与分析（场外基金备忘账，老周 2026-09-20）。
         *
         * ⚠️ **只在「新建」时生效**：已存在的标的**原样返回**，不改它的开关 ——
         * 否则「在表单里选了一只已存在的基金」会按表单缺省值（=不计入）把它悄悄关掉。
         * 改开关请走 [setSecurityExcludeFromStats]。
         */
        excludeFromStats: Boolean = false,
    ): Security = withContext(Dispatchers.Default) {
        val sym = symbol.trim()
        val existing = db.securityQueries.selectBySymbol(sym).executeAsOneOrNull()?.toDomain()
        if (existing != null) return@withContext existing

        // 命中设置页的「现金等价物」名单则自动打标（老周 2026-09-20）：
        // 否则「先在设置页填名单、之后才记第一笔」的场景会漏标，标的会错误地落进持仓。
        val listedInSettings = CashEquivalents.matches(
            sym,
            CashEquivalents.parse(
                db.settingQueries.selectByKey(CASH_EQUIV_KEY).executeAsOneOrNull()?.setting_value ?: "",
            ),
        )

        val security = Security(
            id = Ids.next("sec"),
            symbol = sym,
            name = name.trim(),
            market = market,
            currency = currency,
            industry = industry?.trim()?.ifEmpty { null },
            isCashEquivalent = isCashEquivalent || listedInSettings,
            excludeFromStats = excludeFromStats,
        )
        db.securityQueries.upsert(
            id = security.id,
            symbol = security.symbol,
            name = security.name,
            market = security.market.name,
            currency = security.currency.code,
            industry = security.industry,
            is_cash_equivalent = if (security.isCashEquivalent) 1L else 0L,
            exclude_from_stats = if (security.excludeFromStats) 1L else 0L,
            // 每手股数（老周 2026-09-28）：新建时未知 → null，行情刷新时回写真实值
            lot_size = security.lotSize?.toLong(),
        )
        security
    }

    /**
     * 改「不计入统计与分析」开关（老周 2026-09-20）。
     *
     * 这是**账务口径**开关，不是展示选项：打开它等于把这本账改成「完全账外（纯备忘）」——
     * 买入不再扣现金、卖出不再加现金（**历史已扣的现金不会自动回退**），市值也不再进总资产。
     * 由于持仓 / 曲线 / 收益率都是**全量重放**派生的，改完只需失效曲线缓存即可生效。
     */
    suspend fun setSecurityExcludeFromStats(securityId: String, exclude: Boolean) =
        withContext(Dispatchers.Default) {
            db.securityQueries.setExcludeFromStats(
                exclude_from_stats = if (exclude) 1L else 0L,
                id = securityId,
            )
            repo.invalidateCurveCache()
        }

    /**
     * 删除标的（含级联清理）—— M15 方案 2（老周 2026-09-27 选定）。
     *
     * `securityQueries.deleteAll()` 只删标的行，会在 trade / dividend / watchlist / trade_plan
     * 留下孤儿；而重放以 securities 列表驱动，孤儿既不进持仓也不进统计，
     * 「钱和股数」对不上却没有任何提示。因此删除**只走这里**：同一事务内清掉全部引用。
     *
     * @return 连带删除的交易笔数（供界面提示）
     */
    suspend fun deleteSecurity(securityId: String): Int = withContext(Dispatchers.Default) {
        val txIds = db.tradeQueries.selectBySecurity(securityId).executeAsList().map { it.id }
        db.transaction {
            // ① 交易及其附属（笔记 / 截图）
            txIds.forEach { txId ->
                db.noteQueries.deleteByTransaction(txId)
                db.tradePhotoQueries.deleteByTx(txId)
            }
            db.tradeQueries.deleteBySecurity(securityId)
            // ② 分红 / 自选 / 计划
            db.dividendQueries.deleteBySecurity(securityId)
            db.watchlistQueries.deleteBySecurity(securityId)
            db.tradePlanQueries.deleteBySecurity(securityId)
            // ③ 标的本身
            db.securityQueries.deleteById(securityId)
        }
        repo.invalidateCurveCache()
        txIds.size
    }

    /** 全部标的（计划列表页用它把 security_id 换成名称/代码/市场标签）。 */
    suspend fun listSecurities(): List<Security> = withContext(Dispatchers.Default) {
        db.securityQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    private companion object {
        /** 现金等价物名单（REQ-ACC-07）：app_setting.cash_equivalent_symbols，CSV 保序。 */
        const val CASH_EQUIV_KEY = "cash_equivalent_symbols"
    }
}
