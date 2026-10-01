package com.stocknote.data.repo

import com.stocknote.core.calc.CashEquivalents
import com.stocknote.core.calc.PositionCalculator
import com.stocknote.core.calc.TradeForms
import com.stocknote.core.model.Currency
import com.stocknote.core.model.CashFlowRecord
import com.stocknote.core.model.DeletePreview
import com.stocknote.core.model.DividendRecord
import com.stocknote.core.model.FxTable
import com.stocknote.core.model.Market
import com.stocknote.core.model.PortfolioSnapshot
import com.stocknote.core.model.Quote
import com.stocknote.core.model.Security
import com.stocknote.core.model.SecurityDetail
import com.stocknote.core.model.TradeQuality
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import com.stocknote.core.model.WatchItem
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.todayIso
import com.stocknote.data.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # 账本聚合层（Ledger Aggregation Layer）
 *
 * 本类**不是**普通的数据仓储，而是整个 App 唯一的「跨域聚合点」——
 * 它同时看得到全部域（标的 / 交易 / 分红 / 汇率 / 行情 / 现金），并在此之上做聚合与派生。
 * 2026-09-29 完成拆分：从 3209 行的上帝类瘦身到约 1630 行，16 个域能独立出去的都已迁出。
 *
 * ## 读路径的分层（对应技术说明书 2.0 第 5.13 节，口径未变）
 *
 *   行情缓存（先读，保证离线可用） → 在线刷新（失败静默保留缓存） → 全量重放算持仓 → 汇总
 *
 * 写入口在 [TradeRepository]（addTransaction / updateTransaction / deleteTransaction，
 * 以及 CSV 导入）。**一律不做增量修补** —— 下次读取时整标的重放。
 * 这是「Transaction 是唯一事实源」的直接推论。
 *
 * ## ✅ 什么该放这里
 *
 *  1. **跨域聚合**：需要同时看两个以上域才能算出的东西 ——
 *     [loadSnapshot]（标的+现金+行情+汇率）、[equityCurve]（含现金锚）、
 *     [returnStats] / [openingCash]（XIRR 口径）、[loadSecurityDetail]（标的页要拼持仓）。
 *  2. **全 App 共用的口径入口**：[latestFxRates]（所有折算的唯一入口，7+ 处内部使用）。
 *  3. **跨域缓存**：[invalidateCurveCache] / cachedCurve —— 曲线被几乎所有写操作影响，
 *     失效开关必须放在所有域能到达的位置。
 *
 * ## ❌ 什么不该放这里
 *
 *  1. **单一域能独立完成的读写** → 去对应的域 Repository（见下表）。
 *  2. **新写的业务逻辑**：优先放域；放进本类会让它永远无法再被拆出（聚合层只进不出）。
 *  3. **给某个域"顺手"加的辅助函数** —— 那是域的私有实现，不是聚合。
 *
 * ## 已迁出的域（16 个，构造见 AppContainer）
 *
 * | 域 | 职责 |
 * |---|---|
 * | `PlanRepository` | 交易计划 CRUD + 计划 CSV |
 * | `TradeRepository` | 交易主线（增改删/预览/截图/质量）+ 交易与出入金 CSV 导入 |
 * | `SecurityRepository` | 标的 CRUD / 搜索 / 删除（级联）/ 统计开关 |
 * | `CashRepository` | 可用现金口径 + 账户现金落账 + 出入金 CRUD |
 * | `QuoteRepository` | 历史收盘价 |
 * | `FxRepository` | 汇率（**纯转发**，实现仍在本类；转正需单开一轮） |
 * | `SettingsRepository` | 字体缩放 / 缺省费率 |
 * | `CninfoRepository` | 巨潮扫雷 |
 * | `NewsFavoriteRepository` | 资讯收藏 |
 * | `ReviewRepository` | 复盘 |
 * | `TagRepository` | 标签 |
 * | `WatchlistRepository` | 自选 |
 * | `CsvRepository` | CSV 生成侧（导出与模板） |
 * | `EmotionRepository` | 情绪标签 |
 * | `BackupRepository` | 加密备份 / 恢复 |
 *
 * **仍留在本类的**：分红（6 个方法，`addDividend` 参与现金口径）、现金等价物（4 个）、
 * 汇率（8 个，`FxRepository` 目前纯转发）、行情缓存、以及上面列的聚合方法。
 *
 * ## ⚠️ 铁律（拆分过程中用真金白银换来的）
 *
 *  1. **依赖方向**：本类可持有域（单向）；域**不得**反向持有本类（会循环）。
 *     例外路径：域需要 `invalidateCurveCache` 时，通过构造传入的本类实例调用（见 `SecurityRepository`）。
 *  2. **两份实现，改一处必须同步另一处**（拆分留下的过渡期债务，注释各自标了"同源"）：
 *     - [positionQuantityOf]：`addCapitalize` 等 2 处内部使用
 *     - [cashDeltaInCny] / [rateToBaseOf]：[openingCash]（XIRR 期初锚）在用
 *     域里那三份在 `TradeRepository`。收拢成单份前，两边不许分叉。
 *  3. **缓存失效**：`cash.addCashFlow / updateCashFlow / deleteCashFlow` **不调**
 *     `invalidateCurveCache`（第 10 批删转发后），失效由**调用方**负责；
 *     本类的写转发已删光，新增写调用方时别漏。
 *  4. **`applyDelta` 必须同步**（非 suspend）：调用点在 `db.transaction {}` 内，事务 lambda 调不了 suspend。
 *  5. **改公共函数语义必须 grep 全部调用方**（硬教训 21）：编译不报、测试测不出的口径回归，
 *     只能靠人工过调用方 + 真机对账（曲线末点 ≈ 统计页总资产，残差 ~0.04% 属收盘价/实时价口径差）。
 */
class PortfolioRepository(
    private val db: StockNoteDb,
    private val quoteClient: QuoteClient,
    private val driver: app.cash.sqldelight.db.SqlDriver,
    /**
     * ⚠️ **现金域**（2026-09-29 第 7 批）：可用现金口径与"买卖增减账户现金"的落点。
     *
     * 它此前是本类的 `availableCash` / `applyCashDelta`（后者 private）——
     * 四个方法（addTransaction / updateTransaction / deleteTransaction / insertTradeForCsv）
     * 的共同瓶颈。搬出去后本类反向依赖它，**方向是单向的**（CashRepository 不依赖任何 Repository）。
     */
    private val cash: CashRepository,
    /**
     * 加密 KV：目前用于记「资产曲线上次成功拉取日」（老周 2026-09-23 的「当天已拉过就跳过」）。
     * 可空 = 不传时退化为「每次都拉」（单测等场景无需关心该优化）。
     */
    private val keyStore: com.stocknote.data.security.SecureKeyStore? = null,
) {

    // ---------------------------------------------------------------- 读

    suspend fun loadSnapshot(
        base: Currency = Currency.CNY,
        refreshQuotes: Boolean = true,
    ): PortfolioSnapshot = withContext(Dispatchers.Default) {
        val securities = db.securityQueries.selectAll().executeAsList().map { it.toDomain() }
        val trades = db.tradeQueries.selectAll().executeAsList().map { it.toDomain() }

        // M4 数据真值：fx_rate 表每币种最新生效汇率；缺币种时 FxTable.convertWith 自动兜底固定值
        val fxRates = latestFxRates()

        val prices = LinkedHashMap<String, Double>()
        val prevCloses = LinkedHashMap<String, Double>()
        db.quoteQueries.selectAll().executeAsList().forEach {
            prices[it.symbol] = it.price
            it.prev_close?.let { pc -> prevCloses[it.symbol] = pc }
        }

        if (refreshQuotes) {
            val quoteable = securities.filterNot { it.isCashEquivalent }
            // 场外基金（Market.FUND）没有交易所行情 —— 腾讯 K 线接口认不出它，
            // 只能走天天基金**历史单位净值**。不分开走，场外基金的浮盈永远算不出来（老周 2026-09-20）
            val funds = quoteable.filter { it.market == Market.FUND }
            val listed = quoteable.filterNot { it.market == Market.FUND }
            if (listed.isNotEmpty()) {
                // 失败不抛：行情是外部依赖，拿不到就用缓存价 / 成本价
                val fetched = runCatching { quoteClient.fetchMany(listed.map { it.symbol }) }
                    .getOrDefault(emptyMap())
                fetched.forEach { (symbol, quote) ->
                    writeQuote(symbol, quote)
                    prices[symbol] = quote.price
                }
            }
            funds.forEach { sec ->
                val q = runCatching { quoteClient.fetchFundQuote(sec.symbol) }.getOrNull() ?: return@forEach
                writeQuote(sec.symbol, q)
                prices[sec.symbol] = q.price
                q.prevClose?.let { prevCloses[sec.symbol] = it }
            }
            // 港股每手股数（老周 2026-09-28）：港股每手**不固定**（腾讯控股 100 / 小米 200 /
            // 中国移动 500 / 建设银行 1000），整手校验必须用真实值 —— 顺带从行情接口的
            // 第 61 字段取一次并回写。**只对尚未取到的标的发请求**，取到后不再重复；
            // 离线/失败时保持 null，由 LotRule 按市场兜底（不影响功能）。
            listed.filter { it.market == Market.HK && it.lotSize == null }.forEach { sec ->
                val lot = runCatching { quoteClient.fetchLotSize(sec.symbol, sec.market) }.getOrNull()
                if (lot != null) db.securityQueries.setLotSize(lot, sec.id)
            }
        }

        val dividends = db.dividendQueries.selectAll().executeAsList().map { it.toDomain() }

        // fxRates 一并传入（REQ-ACC-15 路径 1）：重放时给「没有成交日汇率」的笔
        // （A股 / 老数据 / 分红配股）兜底折算本位币成本
        val positions = PositionCalculator.replayAll(securities, trades, prices, dividends, fxRates)
            // 昨收注入（老周 2026-09-23）：统计页持仓 TOP5 要显示**当日涨跌%**。
            // 重放只管账本、不碰行情，所以行情字段在重放结果上补；行情源没昨收的
            // （手工价 / 场外基金净值）保持 null → UI 不显示涨跌，不拿成本价冒充。
            .map { it.copy(prevClose = prevCloses[it.symbol]) }

        // 现金等价物明细 + 合计（归「现金」子类，不计持仓明细；持仓页现金区展示，老周反馈 2026-09-14）
        val cashEquivalentPositions = securities
            .filter { it.isCashEquivalent }
            .map { sec ->
                PositionCalculator.replay(
                    security = sec,
                    transactions = trades.filter { it.securityId == sec.id },
                    marketPrice = prices[sec.symbol],
                )
            }
        // M3 修复（2026-09-27）：现金等价物**逐币种折算**后再汇总 ——
        // 此前是「各持仓原币市值直接相加，再整体按 CNY→base 折一次」，
        // 一旦出现**非人民币**的现金等价物（如港币/美元货币基金）就会算错。
        val cashEquivalent = cashEquivalentPositions.sumOf {
            FxTable.convertWith(it.marketValue, it.currency, base, fxRates)
        }

        // 账外备忘持仓（老周 2026-09-20）：勾了「不计入统计与分析」的场外基金。
        // 与现金等价物同理，**单独重放**——只是它既不算持仓也算不进现金，是一本平行备忘账：
        // 份额 / 成本 / 净值 / 浮盈照常算，但**不进** totalAsset / totalPnl / 配置环。
        val memoPositions = securities
            .filter { it.excludeFromStats && !it.isCashEquivalent }
            .map { sec ->
                PositionCalculator.replay(
                    security = sec,
                    transactions = trades.filter { it.securityId == sec.id },
                    marketPrice = prices[sec.symbol],
                    dividends = dividends.filter { it.securityId == sec.id },
                    latestFxRate = fxRates[sec.currency.code] ?: 1.0,
                )
            }
            .filter { it.isOpen || it.tradeCount > 0 }
            .sortedByDescending {
                FxTable.convertWith(it.marketValue, it.currency, base, fxRates)
            }

        // 可用现金：**统一走 CashRepository.availableCash()**（M1 / M2 修复，2026-09-27）。
        // ⚠️ 此前这里自己算过一遍（只累加 base 币种账户 + 分红取 positions 重放），
        //    而买入校验走另一个口径 → 两处可能算出不同的数（外币账户 / 分红后清仓场景）。
        // ⚠️ 2026-09-29 第 10 批：原类的同名转发已删除，直接走域。
        val availableCash = cash.availableCash(base)
        // 分红累计：同样改用 **dividend 表**口径（钱已实际到账，不因后来清仓而消失），
        // 并显式排除账外备忘标的 —— 与 availableCash 内部第 ③ 项同口径。
        val offBookIds = offBookSecurityIds()
        val dividendCashTotal = db.dividendQueries.selectAll().executeAsList()
            .filter { it.type == "CASH" && it.security_id !in offBookIds }
            .sumOf { it.quantity * it.per_share * it.fx_rate }

        // 当日盈亏（5.11）：Σ(现价 − 昨收) × 持仓 × 币种折算率。缺昨收（停牌/无行情）的标的计 0，不虚增
        // ⚠️ 2026-09-18 审查修复（老周对账发现）：此前未折算 —— 港股的当日盈亏是 HKD 原币值，
        // 直接混进 CNY 合计（哔哩当日盈亏差 1 USD≈0.15 的那种量级）。
        val dayPnl = positions.sumOf { pos ->
            val prev = prevCloses[pos.symbol]
            val cur = prices[pos.symbol] ?: pos.marketPrice
            if (prev != null && prev > 0 && cur != null) {
                (cur - prev) * pos.quantity * FxTable.convertWith(1.0, pos.currency, base, fxRates)
            } else 0.0
        }

        // 按本位币市值排序 —— marketValue 是原币，直接排序会让 HKD/USD 持仓虚高
        PortfolioSnapshot(
            fxRates = fxRates,
            positions = positions.sortedByDescending {
                FxTable.convertWith(it.marketValue, it.currency, base, fxRates)
            },
            availableCash = availableCash,
            cashAmount = availableCash + cashEquivalent,   // M3：cashEquivalent 已在上面折成本位币
            baseCurrency = base,
            dayPnl = dayPnl,
            cashEquivalentPositions = cashEquivalentPositions,
            memoPositions = memoPositions,
            dividendCashTotal = dividendCashTotal,
        )
    }

    /** 行情落库 —— 收在一处，免得重开一份 upsert 参数（行情刷新分「交易所行情」与「基金净值」两路）。 */
    private fun writeQuote(symbol: String, quote: com.stocknote.core.model.Quote) {
        runCatching {
            db.quoteQueries.upsert(
                symbol = symbol,
                price = quote.price,
                prev_close = quote.prevClose,
                source = quote.source,
                updated_at = quote.updatedAtEpochMs,
            )
        }
    }

    // ------------------------------------------------- 汇率（M4 数据真值，REQ-ACC-05）

    /** 每币种最新生效汇率（code -> 兑 CNY）。给 FxTable.convertWith 用。 */
    suspend fun latestFxRates(onOrBefore: String = todayIso()): Map<String, Double> =
        withContext(Dispatchers.Default) {
            // L1 修复（2026-09-27）：查询改成显式子查询后有两个占位符（外层 + 子查询），传同一个基准日
            db.fxRateQueries.latestPerCurrency(onOrBefore, onOrBefore).executeAsList()
                .associate { it.currency to it.rate }
        }

    suspend fun fxRates(): List<com.stocknote.core.model.FxRateRecord> = withContext(Dispatchers.Default) {
        db.fxRateQueries.selectAll().executeAsList().map {
            com.stocknote.core.model.FxRateRecord(
                currency = it.currency,
                effectiveDate = it.effective_date,
                rate = it.rate,
                updatedAt = it.updated_at,
            )
        }
    }

    /** 手动录入/修正某币种某日的汇率（对 CNY）。存的是 **4 位小数**（FxTable.roundRate）。 */
    suspend fun saveFxRate(currency: String, effectiveDate: String, rate: Double) =
        withContext(Dispatchers.Default) {
            db.fxRateQueries.upsert(
                currency,
                effectiveDate,
                com.stocknote.core.model.FxTable.roundRate(rate),
                todayIso(),
            )
            // M5 修复（2026-09-27）：汇率变了 → 曲线的外币折算与现金锚都会变，必须让缓存失效
            invalidateCurveCache()
        }

    /**
     * **成交日汇率**（原币 → CNY，REQ-ACC-15 路径 1，老周 2026-09-19）：
     * 录入非本位币交易时按 `trade_date` 回填，用户可手改。
     *
     * 走腾讯外汇**历史日 K**（与行情同源），不写 fx_rate 表——它只服务这一笔交易的折算。
     * 拉不到就返回 null（表单留空由用户手填，**不阻塞保存**）。
     */
    suspend fun fxRateOn(dateIso: String, currency: Currency): Double? {
        if (currency == Currency.CNY) return 1.0
        return runCatching { quoteClient.fetchFxRateOn(currency.code, dateIso) }.getOrNull()
            ?.let { com.stocknote.core.model.FxTable.roundRate(it) }
    }

    /**
     * 汇率自动更新（老周 2026-09-16）：每天**首次打开 App** 时，
     * 对港币(HKD)与美元(USD)各拉一次最新汇率；当天已更新过则跳过；
     * 若该币种还没有任何记录，则**新增**一条（effective_date = 今天）。
     *
     * 汇率源：腾讯外汇接口 `hkUSD`/`hkHKD` 之类；拿不到就静默跳过（离线可用，保留上一次的值）。
     */
    suspend fun autoUpdateFxRatesIfNeeded() = withContext(Dispatchers.Default) {
        val today = com.stocknote.data.platform.todayIso()
        // HKD/USD：启动时各拉一次，成功就写今天这条（upsert 幂等覆盖，
        // 可自动修正此前写错的记录）；拉不到就保留旧值，不阻塞启动。
        //
        // ⚠️ 2026-09-17 修复（老周实测发现"港股现金扣减仍不对"）：
        //   原先**只拉一次、失败静默吞掉** —— 冷启动时首个请求（HKD）常因网络未就绪失败，
        //   于是 fx_rate 表长期只有 USD、没有 HKD；买港股时 `rateToBaseOf` 只能退回
        //   FxTable 兜底 0.92（真实约 0.855）→ **每笔港股交易现金多扣约 7.6%**。
        //   现改为**最多 3 次重试**（递增退避 0.7s / 1.4s），显著提高冷启动成功率。
        listOf("HKD", "USD").forEach { code ->
            var rate: Double? = null
            for (attempt in 1..3) {
                rate = runCatching { quoteClient.fetchFxRate(code) }.getOrNull()
                if (rate != null && rate > 0.0) break
                kotlinx.coroutines.delay(700L * attempt)
            }
            if (rate != null && rate > 0.0) {
                // 4 位小数入库（老周 2026-09-21 口径，见 FxTable.roundRate）
                runCatching { db.fxRateQueries.upsert(code, today, com.stocknote.core.model.FxTable.roundRate(rate), today) }
            }
        }
        // 顺手清理过期汇率：只保留最近 3 天（老周 2026-09-18）
        pruneFxRates()
    }

    /**
     * 账户列表（设置页「账户」卡片展示用，老周 2026-09-17 要求改为真实数据驱动）。
     * 当前产品是**单账户**记账；多账户（A股/港股/美股）已排入后续版本。
     */
    suspend fun accounts(): List<com.stocknote.data.db.Account> = withContext(Dispatchers.Default) {
        db.accountQueries.selectAll().executeAsList()
    }

    suspend fun deleteFxRate(currency: String, effectiveDate: String) =
        withContext(Dispatchers.Default) {
            db.fxRateQueries.deleteById(currency, effectiveDate)
            // M5 修复（2026-09-27）：删汇率同样改变折算结果 → 缓存失效
            invalidateCurveCache()
        }

    /**
     * 汇率记录只保留最近 [keepDays] 天（老周 2026-09-18）：
     * 生效日早于「今天 − (keepDays−1)」的记录全部删除。
     *
     * 取值永远是**最新一条**（`latestPerCurrency`），更早的历史记录没有用途，
     * 留着只会让表无限增长（每天自动更新会新增一条）。
     */
    /**
     * 汇率表裁剪：**每个币种只保留最新 [keepPerCurrency] 条**（L2 修复，2026-09-27）。
     *
     * ⚠️ 此前是**按天裁**（删掉 3 天以前的全部记录）。问题：一旦离线超过 3 天、期间又拉不到汇率，
     * 整张表会被清空 → `latestFxRates()` 落回 `FxTable` 的**固定兜底值**（HKD 0.90 / USD 7.1，
     * 与实测值偏差约 5%）→ 现金与盈亏**静默算错**。
     * 改成"每币种留最新 N 条"后：**不管离线多久，最后几次成功拉到的值都还在**，
     * 固定兜底只在"从未成功拉取过"时才生效。
     */
    suspend fun pruneFxRates(keepPerCurrency: Int = FX_KEEP_PER_CURRENCY) =
        withContext(Dispatchers.Default) {
            val keep = keepPerCurrency.coerceAtLeast(1)
            // 按币种分组 → 日期倒序 → 丢弃前 keep 条之外的（用 Kotlin 做，不依赖 SQLite 窗口函数的版本）
            val doomed = db.fxRateQueries.selectAll().executeAsList()
                .groupBy { it.currency }
                .flatMap { (cur, rows) ->
                    rows.sortedByDescending { it.effective_date }
                        .drop(keep)
                        .map { cur to it.effective_date }
                }
            doomed.forEach { (cur, date) ->
                runCatching { db.fxRateQueries.deleteById(cur, date) }
            }
        }

    /** 手动刷新的精简结果（老周 2026-09-18，统计页 🔄 用） */
    data class RefreshReport(
        /** 行情成功条数 */
        val quoteOk: Int,
        /** 持仓标的（非现金等价物）总数 */
        val quoteTotal: Int,
        /** 成功取到的汇率：币种 -> 兑 CNY */
        val fx: Map<String, Double>,
        /** 没取到的币种 */
        val fxFailed: List<String>,
    )

    /**
     * 手动全量刷新（统计页 🔄，老周 2026-09-18）。
     *
     * 与启动时的自动更新区别：**忽略一切缓存与幂等判断**，汇率和行情全部重新联网取一遍，
     * 并顺手清理 3 天前的汇率记录。失败不抛异常（外部依赖），结果如实汇总返回。
     */
    suspend fun refreshRemoteData(): RefreshReport = withContext(Dispatchers.Default) {
        val today = todayIso()

        // ① 汇率：强制重取（3 次重试，与启动逻辑同口径）
        val fx = LinkedHashMap<String, Double>()
        val fxFailed = mutableListOf<String>()
        listOf("HKD", "USD").forEach { code ->
            var rate: Double? = null
            for (attempt in 1..3) {
                rate = runCatching { quoteClient.fetchFxRate(code) }.getOrNull()
                if (rate != null && rate > 0.0) break
                kotlinx.coroutines.delay(500L * attempt)
            }
            if (rate != null && rate > 0.0) {
                val r4 = com.stocknote.core.model.FxTable.roundRate(rate)
                fx[code] = r4
                runCatching { db.fxRateQueries.upsert(code, today, r4, today) }
            } else {
                fxFailed += code
            }
        }

        // ② 顺手清理过期汇率（只留最近 3 天）
        pruneFxRates()

        // ③ 行情：强制重取全部持仓标的（不含现金等价物）
        val securities = db.securityQueries.selectAll().executeAsList().map { it.toDomain() }
        val symbols = securities.filterNot { it.isCashEquivalent }.map { it.symbol }
        var ok = 0
        if (symbols.isNotEmpty()) {
            val fetched = runCatching { quoteClient.fetchMany(symbols) }.getOrDefault(emptyMap())
            fetched.forEach { (symbol, quote) ->
                ok++
                runCatching {
                    db.quoteQueries.upsert(
                        symbol = symbol,
                        price = quote.price,
                        prev_close = quote.prevClose,
                        source = quote.source,
                        updated_at = quote.updatedAtEpochMs,
                    )
                }
            }
        }
        RefreshReport(quoteOk = ok, quoteTotal = symbols.size, fx = fx, fxFailed = fxFailed)
    }
    // ⚠️ 2026-09-29 拆分（第 5 批）：历史收盘价 2 个方法的实现 -> QuoteRepository。
    // 原类里的两处调用（资产曲线写/读）已改为就地写库 —— 本类不能反向依赖 QuoteRepository。

    // ------------------------------------------------- 资产曲线（M4，REQ-VIEW-05 / REQ-ANA-01）

    data class EquityCurveData(
        val points: List<com.stocknote.core.calc.EquityCurve.Point>,
        val maxDrawdown: com.stocknote.core.calc.EquityCurve.Drawdown,
        /**
         * 收盘覆盖的交易日数：正常 = 本次拉取到的条数；
         * 因「当天已拉过」跳过网络时 = 库里实际覆盖的交易日数（见 equityCurve 末尾兜底）。
         */
        val dataDays: Int,
        /**
         * **逐日出入金净额**（date -> 折本位币金额，存入为正）。
         * 盈亏日历要用它把入金从「当日盈亏」里剔除（老周 2026-09-29：入金不是赚的钱）。
         */
        val dailyCashFlows: Map<String, Double> = emptyMap(),
        /**
         * securityId -> 标的名称（老周 2026-10-01）。
         *
         * 盈亏日历「点某天看明细」要按标的列出盈亏，而 `Point.pnlBySecurity` 里只有 id；
         * 名称在这里一并带给 UI —— 否则 UI 还得自己再查一次库、且未必拿得到同样的映射。
         */
        val securityNames: Map<String, String> = emptyMap(),
    )

    /**
     * 资产曲线结果。data == null 时 reason 给出**具体**原因，供 UI 区分展示。
     */
    data class EquityCurveResult(
        val data: EquityCurveData?,
        val reason: String? = null,
    )

    /**
     * **某一天的明细流水**（盈亏日历「点某天看明细」用，老周 2026-10-01）。
     *
     * ⚠️ 这里**只装流水** —— 各标的的当日盈亏在 `EquityCurve.Point.pnlBySecurity` 里，
     * 曲线算好后已经有缓存（`equityCurveCached`），本方法**不重算**，避免两处口径漂移。
     */
    data class DayDetail(
        val date: String,
        /** 当日买卖流水（已剔除账外备忘标的，与日历总数同口径） */
        val trades: List<Transaction>,
        /** 当日出入金 */
        val cashFlows: List<DayCashFlow>,
        /** 当日分红 / 送股 / 配股事件 */
        val dividends: List<DayDividend>,
    ) {
        /** 当日是否有任何流水 —— UI 用来决定要不要显示「这天没有现金变动」。 */
        val isEmpty: Boolean get() = trades.isEmpty() && cashFlows.isEmpty() && dividends.isEmpty()
    }

    /**
     * 一笔出入金（[DayDetail] 展示用）。
     * @param amountOrig 原币金额（正 = 存入、负 = 取出）
     * @param amountBase 本位币金额（固定 CNY 口径，与 XIRR 一致）
     * @param type 出入金类型（UI 映射成中文）
     */
    data class DayCashFlow(
        val amountOrig: Double,
        val currencyCode: String,
        val amountBase: Double,
        val type: String,
        val note: String?,
    )

    /**
     * 一笔分红事件（[DayDetail] 展示用）。
     * @param amountBase 现金分红金额（CNY）；送股/配股为 0
     * @param bonusShares 送股数（BONUS）
     * @param rightsShares 配股数（RIGHTS）
     */
    data class DayDividend(
        val securityId: String,
        val type: String,
        val amountBase: Double,
        val bonusShares: Double,
        val rightsShares: Double,
    )

    /**
     * 曲线结果缓存（M4 收尾）：统计页最大回撤、热力图、收益分析页共用同一条曲线，
     * 避免每个页面各拉一次 K 线（7 只标的 × 每次请求，既慢又浪费流量）。
     * 账本变动（记一笔/出入金/分红）后由调用方通过 refresh 强制重算。
     */
    // M4 修复（2026-09-27）：曲线缓存在 Dispatchers.Default 里写、主线程读，
    // 必须 @Volatile 保证跨线程可见性（此前是裸 var，改动可能不被另一线程看到）。
    @kotlin.concurrent.Volatile
    private var cachedCurve: EquityCurveResult? = null

    /**
     * 曲线**拉取窗口**（天）：每次重算时要向行情接口要多少天日线。
     *
     * 老周 2026-09-23 起曲线区间是**最近一年**，所以拉取窗口必须 ≥ 365，
     * 这里取 370 留出节假日余量。⚠️ 两者是一对，改动要一起看：
     * 曲线画一年、却只拉 120 天的话，库里不足一年时前段没有收盘价，
     * 持仓市值按 0 计 → 曲线前半段退化成一条「纯现金」线（数值偏低、形状失真）。
     *
     * 另注意：缓存只有一个槽位、**不区分 days**，所以所有调用方必须用同一窗口。
     */
    // ⚠️ 2026-09-28 拆分改动：去掉 private，供 QuoteRepository 委托时复用同一默认值。
    internal val CURVE_FETCH_DAYS = 370

    /** 加密 KV 里记「资产曲线上次**成功**拉取的日期」（yyyy-MM-dd）——方案 A 的跳过依据 */
    private val CURVE_FETCH_DAY_KEY = "curve_fetch_day"

    /**
     * 取曲线：refresh=false 时优先返回缓存。
     *
     * refresh=true（手动「刷新数据」/ 账本变动后）**同时**表示「这次真的要打网络」：
     * 否则用户点了刷新，却因为「今天已拉过」而静默跳过，体感像点了没用。
     */
    suspend fun equityCurveCached(refresh: Boolean = false, days: Int = CURVE_FETCH_DAYS): EquityCurveResult {
        if (!refresh) cachedCurve?.let { return it }
        val result = equityCurve(days, forceFetch = refresh)
        // M4 修复（2026-09-27）：**失败结果不缓存** —— 此前无条件写入，
        // 一次网络抖动就能让曲线永久显示"算不出来"（要等某个写操作才失效）。失败就让它下次重拉。
        if (result.data != null) cachedCurve = result
        return result
    }

    /** 账本变动后调用：让下次取曲线重算。 */
    fun invalidateCurveCache() {
        cachedCurve = null
    }

    /**
     * **某一天的明细流水**（老周 2026-10-01：盈亏日历点某天，在下面看这天的明细）。
     *
     * 口径与资产曲线**刻意保持一致**：
     *  - 只取**计入统计**的标的（`exclude_from_stats = 0`）的流水 —— 否则日历上根本没算这些标的的
     *    盈亏，明细里却冒出它们的买卖，用户会以为对不上账；
     *  - 出入金/分红给的是**本位币金额**，与曲线用的折算口径同源。
     *
     * ⚠️ 各标的的当日盈亏**不在这里返回** —— 它在 `EquityCurve.Point.pnlBySecurity`
     * （曲线上已经算好并缓存，重算只会带来两处口径漂移的风险）。
     */
    suspend fun dayDetail(date: String): DayDetail = withContext(Dispatchers.Default) {
        val curveSecIds = db.securityQueries.selectAll().executeAsList()
            .filter { it.exclude_from_stats == 0L }
            .map { it.id }
            .toSet()

        val trades = db.tradeQueries.selectAll().executeAsList()
            .map { it.toDomain() }
            .filter { it.tradeDate == date && it.securityId in curveSecIds }

        // 出入金：读**原始行**（保留原币金额/币种/备注，UI 才能显示「入金 19,000 HKD」）
        val cashFlows = db.cashFlowQueries.selectAll().executeAsList()
            .filter { it.flow_date == date }
            .map {
                DayCashFlow(
                    amountOrig = it.amount_orig,
                    currencyCode = it.currency,
                    amountBase = it.amount_base,
                    type = it.type,
                    note = it.note,
                )
            }

        // 分红：CASH 才折算金额；BONUS/RIGHTS 是股数变动（金额记 0，靠股数文案表达）
        val dividends = db.dividendQueries.selectAll().executeAsList()
            .filter { it.ex_date == date && it.security_id in curveSecIds }
            .map {
                DayDividend(
                    securityId = it.security_id,
                    type = it.type,
                    amountBase = if (it.type == "CASH") {
                        it.quantity * it.per_share * it.fx_rate
                    } else {
                        0.0
                    },
                    bonusShares = it.bonus_shares,
                    rightsShares = if (it.type == "RIGHTS") it.quantity else 0.0,
                )
            }

        DayDetail(date = date, trades = trades, cashFlows = cashFlows, dividends = dividends)
    }

    // ------------------------------------------------- 加密备份恢复（REQ-SEC-03）
    // ⚠️ 2026-09-29 第 9 批：整块（backupColumns / backupTables / buildEncryptedBackup /
    // sqlLiteral / restoreFromEncoded）**已搬到 BackupRepository**。
    // 它自成体系（dump/restore + checksum + 加密），与原类无内在耦合。


    // ------------------------------------------------- 现金联动（老周 2026-09-16）

    /** 某标的的当前持仓数量（重放口径，含分红送股影响）。用于卖出时的「最多可卖」。 */
    /**
     * 某标的当前持仓数量（按全量重放）。
     *
     * ⚠️ 2026-09-20 重写（老周 2026-09-20）：原实现走 `loadSnapshot().positions`，
     * 而 snapshot 已经**剔除了账外备忘标的**（场外基金「不计入统计」）——
     * 于是卖出校验会认为「已清仓的场外基金」无持仓，直接拒绝登记卖出。
     * 现改为**只重放这一个标的**，与统计开关无关（备忘账也要能卖出），顺带更快。
     */
    /**
     * 某标的持仓数量（重放口径）。
     *
     * @param excludeTxId 需**排除**的交易 id（H7 修复 2026-09-27）：编辑卖出时要按「**剔除本笔后**」的持仓
     *   去截断，否则会拿"本笔自己参与算出来的持仓"当上限，截断值偏大。
     * @param asOfDate 只重放**该日期（含）之前**的交易（M16 修复 2026-09-27）：
     *   补录一笔历史卖出时，上限应是「**那一天的持仓**」，而不是今天的持仓 ——
     *   否则补录去年的卖出会拿现在的股数当上限（现在可能已经加仓到很多，于是历史超卖被放行）。
     *   null = 不限日期（即"当前持仓"，大多数调用方用这个）。
     */
    suspend fun positionQuantityOf(
        securityId: String,
        excludeTxId: String? = null,
        asOfDate: String? = null,
    ): Double =
        withContext(Dispatchers.Default) {
            val sec = db.securityQueries.selectById(securityId).executeAsOneOrNull()?.toDomain()
                ?: return@withContext 0.0
            val txs = db.tradeQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
                .filter { excludeTxId == null || it.id != excludeTxId }
                .filter { asOfDate == null || it.tradeDate <= asOfDate }
            val divs = db.dividendQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
            com.stocknote.core.calc.PositionCalculator
                .replay(security = sec, transactions = txs, dividends = divs).quantity
        }

    /**
     * 卖出**超卖截断**：返回 `实际成交量 → 按比例折算后的手续费`（H7 修复，2026-09-27）。
     *
     * ⚠️ `addTransaction` 与 `updateTransaction` **共用这一份实现** —— 此前只有新增路径做了截断，
     * 编辑路径完全没有：把卖出从 100 股改成 1000 股（实际持仓 200）时，现金按 1000 股回款、
     * 重放只卖 200 股，差额**永久留在现金里**，且没有任何提示。
     */
    // ⚠️ 2026-09-29 第 8 批：truncateSell（超卖截断）已随交易主线搬到 TradeRepository。
    // 它只被 addTransaction / updateTransaction / insertTradeForCsv 使用，三者一并搬走，
    // 故本类无需保留副本。

    /**
     * **可用现金（唯一入口，本位币口径）** —— M1 / M2 修复（2026-09-27）。
     *
     * 口径：`Σ(账户现金，按币种折本位币) + 出入金净额(折本位币) + 现金分红(折本位币)`。
     *
     * ⚠️ 此前**有两个互不相同的实现**，且一个用于买入校验、另一个用于页面显示：
     *  - `loadSnapshot`：只累加 **base 币种**的账户（外币账户被忽略 → M2）+ 分红取 **positions 重放**；
     *  - `availableCashNow`：累加**全部**账户 + 分红取 **dividend 表**。
     *  两者在「有外币账户」或「登记分红后清仓」时会算出**不同的数**（M1）。
     *
     * 现统一到本函数，取两个口径中**更贴近事实**的那个：
     *  - 账户：**全部账户**折本位币（外币账户也是钱）；
     *  - 分红：**dividend 表**口径（钱已实际到账，不该因后来清仓就消失）。
     */
    // ⚠️ 2026-09-29 第 10 批：availableCash / availableCashNow 的转发已删除，
    // 调用方直接用 CashRepository（容器里的 cash）。本类内部唯一用处（loadSnapshot）已改为 cash.availableCash(base)。

    /**
     * **旧版货币 ETF 标的并存自检**（M10 修复，2026-09-28）。
     *
     * 背景：`5.sqm`（v5→v6）把示例标的 `sh511880` 改名为 `sh511660`，
     * 而 `security.symbol` 上有 **UNIQUE 索引** —— 若某用户的库里**同时存在**这两个代码，
     * 迁移会抛 `SQLiteConstraintException`，**升级后 App 直接打不开**，且不可逆。
     *
     * ⚠️ 为什么不改 `5.sqm`：迁移**已随版本发布**，改它对已升级用户无效（他们早跑过了），
     * 反会引入"同一版本号不同迁移内容"的隐患。所以只能做**运行时自检**。
     * ⚠️ 注意：本方法查的是「**当前库里**两者并存」——那意味着用户手动新建了 `sh511660`
     * （迁移早已成功跑完，不会崩），表现为"两个货币 ETF 标的"，需要提示用户去重。
     *
     * @return 冲突的 symbol 列表；空 = 无冲突。
     */
    suspend fun legacyCashEquivalentConflicts(): List<String> = withContext(Dispatchers.Default) {
        val symbols = db.securityQueries.selectAll().executeAsList().map { it.symbol }.toSet()
        if ("sh511880" in symbols && "sh511660" in symbols) listOf("sh511880", "sh511660") else emptyList()
    }
    // ⚠️ 2026-09-28 拆分（阶段 2）：设置域 6 个方法（fontScale / saveFontScale /
    // feeRateAShare / feeRateHk / saveFeeRates / lotCheckEnabled / setLotCheckEnabled）
    // 的实现**已搬到 SettingsRepository**，原实现一并删除。
    // 搬它的理由：本类内部零自用（叶子域），搬走不牵动任何其它逻辑。
    // ⚠️ 2026-09-29 第 10 批：cashFlowsSince 的实现与 KDoc 已搬到 CashRepository
    //（那段"严格大于而非 >=、否则重复扣减"的真机教训也一并搬走了，不要在本类另留副本）。

    /**
     * 某个标的的「原币 → CNY」折算率（CNY 标的 = 1.0）。
     *
     * ⚠️ 与 `TradeRepository.rateToBaseOf` **同源**（老周 2026-09-29 一起改）：
     * 本笔成交日汇率 [fxRate] > 最新汇率 > [FxTable] 固定兜底。
     */
    private suspend fun rateToBaseOf(securityId: String, fxRate: Double? = null): Double {
        val code = runCatching {
            db.securityQueries.selectById(securityId).executeAsOneOrNull()?.currency
        }.getOrNull() ?: Currency.CNY.code
        if (code == Currency.CNY.code) return 1.0
        if (fxRate != null && fxRate > 0.0) return FxTable.roundRate(fxRate)
        val cur = Currency.entries.firstOrNull { it.code == code } ?: return 1.0
        return latestFxRates()[code] ?: FxTable.rate(cur, Currency.CNY)
    }

    /**
     * 一笔交易对**现金账户（CNY）**的影响 —— 已按标的币种折算本位币。
     *
     * ⚠️ 2026-09-16 修复：此前直接用原币数值增减 CNY 现金（买港股按港元数字当人民币扣，
     * 少扣约 15%）。现统一走 [com.stocknote.core.calc.CashImpact.cashDeltaOf]。
     */
    private suspend fun cashDeltaInCny(
        securityId: String,
        side: TradeSide,
        quantity: Double,
        price: Double,
        fee: Double,
        /** 本笔交易的成交日汇率（`trade.fx_rate`）；非空且 > 0 时优先。 */
        fxRate: Double? = null,
        /** 印花税（原币金额，REQ-ACC-17）：与手续费同等计入现金，缺省 0（历史数据）。 */
        stampDuty: Double = 0.0,
    ): Double = com.stocknote.core.calc.CashImpact.cashDeltaOf(
        side = side.name,
        quantity = quantity,
        price = price,
        fee = fee,
        rateToBase = rateToBaseOf(securityId, fxRate),
        stampDuty = stampDuty,
    )

    // ⚠️ 2026-09-29 第 8 批：isOffBook（单数）已随交易主线搬到 TradeRepository，本类不再使用。
    // 注意 `offBookSecurityIds()`（复数、返回集合）是**另一个**方法，仍在本类（被统计口径使用）。
    // ⚠️ 2026-09-29 第 11 批：deleteSecurity（含 M15 级联清理说明）已搬到 SecurityRepository。
    // ⚠️ 2026-09-29 第 7 批：applyCashDelta（private 同步函数）已改为 CashRepository.applyDelta
    //（同步 —— 调用点在 db.transaction{} 内）。本类 4 个调用点改为 cash.applyDelta(...)。

    // ⚠️ 2026-09-17 修：Format.money 已自带「¥」，此前又拼了一个 → 文案出现「¥¥36,011」
    // 2026-09-17 B 方案：金额统一 2 位小数（错误文案里的金额也一视同仁）
    // ⚠️ 2026-09-29 第 8 批：moneyCny / isOffBook / truncateSell 的实现已随交易主线
    // 搬到 TradeRepository（本类内部不再使用它们）。

    // ------------------------------------------------- 收益率分析（M4 收尾，REQ-VIEW-07）

    data class ReturnStats(
        /** 累计净投入成本 = 买入(含费) − 卖出回款 − 现金分红；≤0 表示本金已收回 */
        val netInvested: Double,
        /** 总盈亏 = 期末持仓市值 − 净投入 */
        val totalPnl: Double,
        /** 累计收益率 = 总盈亏 / 累计净投入成本（REQ-VIEW-07 定义）；净投入≤0 时 null */
        val cumulativeReturn: Double?,
        /** 年化 = 资金加权 XIRR（真实收益率）；算不出时 null */
        val xirr: Double?,
        /** 当前持仓市值（折本位币，不含现金） */
        val marketValue: Double,
    )

    /**
     * 收益率三口径的数据（REQ-VIEW-07）：
     *  - 累计收益率 = 总盈亏 / 累计净投入成本（需求原文定义，简单·非年化）；
     *  - 年化 = 资金加权 XIRR：买入负、卖出正、分红正、期末持仓市值正；
     *  - 基准由调用方拉 sh000300 区间涨跌，与本方法解耦。
     * 口径注释写进 UI，避免三口径混用（需求 5.12）。
     */
    suspend fun returnStats(): ReturnStats = withContext(Dispatchers.Default) {
        val fxRates = latestFxRates()
        // H3 修复（2026-09-27）：**折算必须在这一层做**。
        // 此前 buyCost / sellProceeds / capitalRaise / flows 全是**原币**累加（HKD+USD+CNY 直接相加），
        // 而 marketValue 折了本位币 → `totalPnl = marketValue - netInvested` 是**混币种相减**。
        // 顺带消掉 N+1（M10）：security 表一次性读成 map，别在循环里逐笔 selectById。
        val securitiesById = db.securityQueries.selectAll().executeAsList().associateBy { it.id }

        /** 该笔交易的原币→CNY 折算率：成交日汇率 → 库内最新 → 1.0（本位币恒 1.0） */
        fun rateOf(tx: com.stocknote.core.model.Transaction): Double {
            val cur = securitiesById[tx.securityId]?.currency
                ?.let { code -> Currency.entries.firstOrNull { it.code == code } } ?: Currency.CNY
            if (cur == Currency.CNY) return 1.0
            return tx.fxRate ?: fxRates[cur.code] ?: 1.0
        }

        val trades = db.tradeQueries.selectAll().executeAsList().map { it.toDomain() }
            .filter { tx ->
                // 现金等价物（511660）是"现金换现金"，不计入投入与盈亏（REQ-ACC-07 口径）；
                // 账外备忘标的（场外基金「不计入统计」）同样不计入 —— 它本来就不在总资产里（老周 2026-09-20）
                val sec = securitiesById[tx.securityId]
                sec == null || (sec.is_cash_equivalent == 0L && sec.exclude_from_stats == 0L)
            }

        var buyCost = 0.0
        var sellProceeds = 0.0
        // 利润转增资本累计（REQ-ACC-16）：**计入本金**，但不产生现金流。
        // 与统计页口径保持一致 —— 那边「总盈亏」由重放派生，转增后同样下降 X；
        // 若这里不动 netInvested，两个页面的总盈亏会差一个 X（老周对账会立刻发现）。
        var capitalRaise = 0.0
        val flows = mutableListOf<com.stocknote.core.calc.CashFlow>()
        trades.forEach { tx ->
            // H3 修复：金额与手续费都折本位币后再累加（口径与 marketValue 一致）
            val rate = rateOf(tx)
            val gross = tx.amountOf * rate
            val fee = tx.fee * rate
            // 印花税（REQ-ACC-17）：与手续费同等进"净投入"与 XIRR 现金流 —— 它是实际付出的钱，
            // 漏掉会让收益率虚高（A 股卖出、港股双向都有）
            val duty = tx.stampDuty * rate
            when (tx.side) {
                TradeSide.BUY -> {
                    buyCost += gross + fee + duty
                    flows += com.stocknote.core.calc.CashFlow(tx.tradeDate, -(gross + fee + duty))
                }
                TradeSide.SELL -> {
                    sellProceeds += gross - fee - duty
                    flows += com.stocknote.core.calc.CashFlow(tx.tradeDate, gross - fee - duty)
                }
                // 利润转增资本：本金 +X，盈亏相应 −X；**不加现金流**（→ XIRR 完全不变）
                TradeSide.CAPITALIZE -> capitalRaise += gross
            }
        }
        // 现金分红：既是收益、也是正现金流（账外备忘标的的分红同样剔除）
        val offBookIds = offBookSecurityIds()
        db.dividendQueries.selectAll().executeAsList()
            .filter { it.type == "CASH" && it.security_id !in offBookIds }
            .forEach {
                val amt = it.quantity * it.per_share * it.fx_rate
                flows += com.stocknote.core.calc.CashFlow(it.ex_date, amt)
            }

        // 期末持仓市值（折本位币）：loadSnapshot 的重放口径
        val snapshot = loadSnapshot(refreshQuotes = false)
        val marketValue = snapshot.positions.sumOf {
            FxTable.convertWith(it.marketValue, it.currency, Currency.CNY, fxRates)
        }
        // 期末市值作为最后一笔正现金流（XIRR 收敛用）
        if (marketValue > 1e-9) {
            flows += com.stocknote.core.calc.CashFlow(com.stocknote.data.platform.todayIso(), marketValue)
        }

        // 本金 = 买入成本 + 转增资本 − 卖出回款（转增的部分也是"我的本金"，见 REQ-ACC-16）
        val netInvested = buyCost + capitalRaise - sellProceeds
        val totalPnl = marketValue - netInvested
        ReturnStats(
            netInvested = netInvested,
            totalPnl = totalPnl,
            cumulativeReturn = com.stocknote.core.calc.ReturnCalculator.cumulativeReturn(totalPnl, netInvested),
            xirr = runCatching { com.stocknote.core.calc.ReturnCalculator.xirr(flows) }.getOrNull(),
            marketValue = marketValue,
        )
    }

    /**
     * 组合资产曲线：对全部持仓标的拉历史收盘（腾讯 K 线）落库，
     * 再用 EquityCurve.build 计算「现金 + 持仓市值」的逐日总资产。
     *
     * 口径说明（⚠️ **2026-10-01 更正**）：外币持仓市值按**逐日汇率**折算 CNY ——
     * 数据源是**腾讯外汇日 K**（`whHKDCNY` / `whUSDCNY`，见 [FxTable.fxQuoteOf]）。
     *
     * 这里此前写着「**历史逐日汇率无免费数据源**，用最新汇率近似历史」——
     * **那句话是错的**（腾讯外汇日 K 一直可取，与股票 K 线同一个接口）。
     * 代价是港股当日盈亏**整整少了汇率波动那一块**，与券商差约 1%
     *（老周 2026-10-01 真机对账发现：腾讯 9-01 本应用 −15,853.95 vs 券商 −15,997.91）。
     *
     * 现按 [FxTable.mergeRateSeries] 合并「日 K（主）+ fx_rate 表（备）」，
     * 两者都没有时才回退到最新汇率近似。
     * 曲线覆盖范围 = 收盘数据可得窗口（近期 N 个交易日）。
     */
    /**
     * @param forceFetch true = 无视「今天已拉过就跳过」的优化，强制打网络（手动刷新用）。
     */
    suspend fun equityCurve(days: Int = CURVE_FETCH_DAYS, forceFetch: Boolean = false): EquityCurveResult =
        withContext(Dispatchers.Default) {
            // ⚠️ 账外备忘标的（场外基金「不计入统计」）**不进资产曲线**（老周 2026-09-20）：
            // 曲线画的是「现金 + 持仓市值」，账外标的既不占现金也不是持仓，进来会凭空抬高曲线。
            val securities = db.securityQueries.selectAll().executeAsList()
                .filter { it.exclude_from_stats == 0L }
                .map { it.toDomain() }
            if (securities.isEmpty()) {
                return@withContext EquityCurveResult(null, "还没有任何标的，先记一笔交易吧")
            }

            // 1) 拉收盘并落库（失败静默：离线时用库里已有缓存）
            //    场外基金没有 K 线，改拉**历史单位净值**，同样落 daily_close（按 symbol 存），
            //    这样它一旦被打开「计入统计」也能正常参与曲线（老周 2026-09-20）
            //
            //    老周 2026-09-23（方案 A）：**当天已成功拉过就跳过网络**，冷启动曲线秒出、零流量。
            //    - 跳过依据用加密 KV 里的「上次成功拉取日」，**不是**「库里最新收盘日」——
            //      后者在周末/节假日永远不等于今天，会变成天天拉；
            //    - 只有真拉到数据（fetchedOk > 0）才记账：整轮离线不记，下次启动仍然重试；
            //    - 手动「刷新数据」/账本变动走 forceFetch=true，直接绕过该优化，点了就真拉。
            val today = com.stocknote.data.platform.todayIso()
            // ⚠️ 2026-10-01（老周对账修复配套）：**逐日汇率是本轮新增的缓存，老库里一条都没有**。
            // 若仍按「今天已拉过就跳过网络」，刚装上新版的人**当天什么变化都看不到**，
            // 汇率得等到明天才补上 —— 会被当成"改了没用"。
            // 所以只要「有外币标的 + 该币种的汇率缓存为空」，就**强制走一次网络**（一次性的补数据，
            // 补完即落库，之后自然回到「今天已拉过就跳过」的省流量逻辑）。
            val fxCacheMissing = securities.any { sec ->
                sec.currency != Currency.CNY && FxTable.fxQuoteOf(sec.currency)?.let { (sym, _) ->
                    db.fxRateQueries.selectClosesBySymbol(sym).executeAsList().isEmpty()
                } == true
            }
            val skipNetwork = !forceFetch && !fxCacheMissing &&
                keyStore?.getString(CURVE_FETCH_DAY_KEY) == today
            var dataDays = 0
            var fetchedOk = 0
            if (!skipNetwork) {
                securities.forEach { sec ->
                    val candles = runCatching {
                        if (sec.market == Market.FUND) {
                            quoteClient.fetchFundNav(sec.symbol, days)
                                .map { QuoteClient.Candle(it.date, it.nav) }
                                .reversed()   // fetchFundNav 是降序，曲线要求升序
                        } else {
                            quoteClient.fetchClosesWithDates(sec.symbol, days)
                        }
                    }
                        // ⚠️ 2026-10-01：原来这里的失败是**静默**的（getOrDefault 直接吞），
                        // 用户只看到一句「请检查网络后重试」却无从判断是哪只标的、什么原因。
                        .onFailure { err ->
                            com.stocknote.data.log.SnLog.e(
                                "CURVE",
                                "拉取历史收盘失败 ${sec.symbol}（${sec.market}）",
                                err,
                            )
                        }
                        .getOrDefault(emptyList())
                    if (candles.isEmpty()) {
                        com.stocknote.data.log.SnLog.w(
                            "CURVE",
                            "历史收盘为空 ${sec.symbol}（${sec.market}）",
                        )
                    }
                    if (candles.isNotEmpty()) {
                        // ⚠️ 2026-09-29 拆分（第 5 批）：历史收盘价实现已搬到 QuoteRepository，
                        // 这里改为**就地写库**（本类不能反向依赖 QuoteRepository，会循环）。
                        candles.forEach { db.fxRateQueries.upsertClose(sec.symbol, it.date, it.close) }
                        dataDays = maxOf(dataDays, candles.size)
                        fetchedOk++
                    }
                }
                // ⚠️ 2026-10-01（老周对账修复）：**顺带拉逐日汇率**。
                // 港股 / 美股的市值要按「**当日**汇率」折算才和券商口径对得上 —— 否则
                // 「昨天」与「今天」取到同一个汇率，汇率波动被整段抹掉（差的正是那 ~1%）。
                // 与收盘价**同库同机制**：落 daily_close，symbol 直接用腾讯外汇代码
                // （whHKDCNY / whUSDCNY），所以**不需要新表、也不需要新接口** ——
                // 走的就是 [QuoteClient.fetchClosesWithDates] 那条路。
                // ⚠️ 单独处理、**不混进 dataDays / fetchedOk** —— 那两个是「标的收盘」的口径，
                //    掺进汇率会让「近 N 个交易日」的显示失真。
                val fxTargets = securities.map { it.currency }.distinct()
                    .mapNotNull { FxTable.fxQuoteOf(it) }
                fxTargets.forEach { (fxSymbol, center) ->
                    val candles = runCatching { quoteClient.fetchClosesWithDates(fxSymbol, days) }
                        .onFailure { err ->
                            com.stocknote.data.log.SnLog.e("CURVE", "拉取逐日汇率失败 $fxSymbol", err)
                        }
                        .getOrDefault(emptyList())
                        // ⚠️ 汇率值域与股价差 3 个数量级，残数据必须挡掉（宁可回退手工汇率）
                        .filter { FxTable.isSaneRate(it.close, center) }
                    if (candles.isNotEmpty()) {
                        candles.forEach { db.fxRateQueries.upsertClose(fxSymbol, it.date, it.close) }
                        com.stocknote.data.log.SnLog.i(
                            "CURVE",
                            "逐日汇率已缓存 $fxSymbol ${candles.size} 条" +
                                "（${candles.first().date} ~ ${candles.last().date}）",
                        )
                    } else {
                        com.stocknote.data.log.SnLog.w(
                            "CURVE",
                            "逐日汇率取不到 $fxSymbol，本次回退 fx_rate 表的手工汇率",
                        )
                    }
                }
                if (fetchedOk > 0) keyStore?.putString(CURVE_FETCH_DAY_KEY, today)
            }

            // 2) 收盘表按 **securityId** 组织 —— 与持仓时间线的 key 一致；
            //    daily_close 表本身仍按 symbol 存，这里做一次映射。
            // ⚠️ 同上：就地读库（原 closesBySymbol 已搬到 QuoteRepository）
            val closesBySecurityId = securities.associate { sec ->
                sec.id to db.fxRateQueries.selectClosesBySymbol(sec.symbol).executeAsList()
                    .map { it.date to it.close }
            }
            if (closesBySecurityId.values.all { it.isEmpty() }) {
                val reason = when {
                    // 今天已拉过却还是空 → 是本地没有，别再甩锅给网络
                    skipNetwork -> "本地暂无历史收盘数据，点「刷新数据」可重新拉取"
                    fetchedOk == 0 -> "行情接口未取到任何历史收盘价，请检查网络后重试"
                    else -> "暂无历史收盘数据"
                }
                // 汇总一行，配上面每只标的的明细，足以判定是网络问题还是别的原因
                com.stocknote.data.log.SnLog.e(
                    "CURVE",
                    "资产曲线无数据：标的数=${securities.size} 成功=$fetchedOk " +
                        "跳过网络=$skipNetwork days=$days → $reason",
                )
                return@withContext EquityCurveResult(null, reason)
            }

            // 3) 交易流水（含等价物）+ 出入金（折本位币）
            //    账外标的的流水一并剔除 —— 否则曲线会有一条「无市值却有买入支出」的幽灵现金流（2026-09-20）
            val curveSecIds = securities.map { it.id }.toSet()
            val trades = db.tradeQueries.selectAll().executeAsList().map { it.toDomain() }
                .filter { it.securityId in curveSecIds }
            val fxRates = latestFxRates()
            val cashFlows = db.cashFlowQueries.selectAll().executeAsList().map {
                it.flow_date to FxTable.convertWith(it.amount_orig, Currency.entries
                    .firstOrNull { c -> c.code == it.currency } ?: Currency.CNY, Currency.CNY, fxRates)
            }
            // 现金分红（REQ-ACC-04）：作为曲线现金流事件纳入。
            // 现金分红 = 账户收到钱 → 现金流入；除权除息日股价下跌已体现在收盘价里，
            // 若再扣一遍会重复计算。送股/转股不改变现金（只摊薄成本与增加份数），不入此列。
            val dividendCashEvents = db.dividendQueries.selectAll().executeAsList()
                .filter { it.type == "CASH" && it.security_id in curveSecIds }
                .map {
                    it.ex_date to (it.quantity * it.per_share * it.fx_rate)
                }
            val dividendCashTotal = dividendCashEvents.sumOf { it.second }

            // ⚠️ H3 修复（2026-09-28）：**送股 / 配股会改变持仓份数**，必须进曲线的持仓时间线 ——
            // 此前只把 CASH 分红当现金流入，BONUS/RIGHTS 完全没进 → 10 送 10 后曲线仍按原股数估值，
            // 叠加除权日股价下调，表现为「总资产莫名掉一半」，用户会当成数据丢失。
            // 股数规则与 PositionCalculator.applyDividend 完全一致（0 持仓时不产生送股/配股）。
            val shareEvents = db.dividendQueries.selectAll().executeAsList()
                .filter { it.security_id in curveSecIds }
                .flatMap { d ->
                    when (d.type) {
                        "BONUS" -> if (d.bonus_shares > 0) {
                            listOf(
                                com.stocknote.core.calc.EquityCurve.ShareEvent(
                                    d.ex_date, d.security_id, d.bonus_shares,
                                ),
                            )
                        } else {
                            emptyList()
                        }

                        "RIGHTS" -> if (d.quantity > 0 && d.rights_price > 0) {
                            listOf(
                                com.stocknote.core.calc.EquityCurve.ShareEvent(
                                    d.ex_date, d.security_id, d.quantity,
                                ),
                            )
                        } else {
                            emptyList()
                        }

                        else -> emptyList()
                    }
                }

            // 现金锚点 = **当前**可用现金（账户现金现值 + 出入金净额 + 现金分红），与统计页
            // availableCash 口径完全一致，用于反推历史各时点现金；终点必回当前现金。
            // 此前误把它当"期初现金"再逐笔扣买入 → 现金被扣成负数、曲线总资产腰斩。
            // ⚠️ **2026-09-27 修复（自查发现的回归）**：此前这里写的是
            //   `openingCash(CNY) + cashFlows.sumOf{ it.second } + dividendCashTotal`
            // 但 `openingCash()` 在 H5 修复后语义已变为「**期初**本金」（= account.cash − Σ交易现金变动），
            // **不再等于"当前可用现金"** —— 于是现金锚被**高估了一个净买入额**
            // （真机实测：曲线末点 ¥2,096,560 vs 统计页总资产 ¥1,347,083，差约 75 万）。
            // 曲线要的正是「当前可用现金」，现在直接走唯一入口 CashRepository.availableCash（与统计页同源）。
            // ⚠️ 2026-09-29 第 10 批：原类同名转发已删除，改走 cash。
            val currentCash = cash.availableCash(Currency.CNY)

            // 各标的「原币→CNY」折算率（本位币口径）。港股/美股持仓按此折算后再计入市值，
            // 修正此前把 HK$/US$ 按面值当人民币相加的问题（与图注"外币按最新汇率折算"对齐）。
            val rateToBase = securities.associate { sec ->
                sec.id to FxTable.convertWith(1.0, sec.currency, Currency.CNY, fxRates)
            }

            // ⚠️ 2026-10-01（老周对账修复）：逐日汇率有**两路来源，必须合并** ——
            //   ① **主**：腾讯外汇日 K（已缓存进 daily_close，symbol = whHKDCNY / whUSDCNY，逐日连续）；
            //   ② **备**：fx_rate 表（用户手工录入，实测只有最近 3 天）。
            // 此前**只读 ②**，于是 `rateOn(sec, 昨天)` 与 `rateOn(sec, 今天)` 都命中最早那一条
            // → **汇率波动被整段抵消**，港股当日盈亏少掉那一块：
            // 真机对账（腾讯 1600 股 9-01）本应用 −15,853.95 vs 券商 −15,997.91，差 143.96（≈1%）。
            // ⚠️ 2026-09-29 那次"改用当日汇率"的修复没生效，就是因为只换了读法、没接上数据源。
            // 合并口径见 [FxTable.mergeRateSeries]（同日以日 K 为准）。
            // fx_rate 表以 (currency, effective_date) 为主键，selectAll 已按 currency + date 升序。
            val fxSeriesByCurrency = db.fxRateQueries.selectAll().executeAsList()
                .groupBy { it.currency }
                .mapValues { (_, rows) -> rows.map { it.effective_date to it.rate } }
            val rateSeriesBySecurityId = securities.associate { sec ->
                val fromKline = FxTable.fxQuoteOf(sec.currency)?.let { (fxSymbol, _) ->
                    db.fxRateQueries.selectClosesBySymbol(fxSymbol).executeAsList()
                        .map { it.date to it.close }
                }.orEmpty()
                sec.id to FxTable.mergeRateSeries(
                    primary = fromKline,
                    fallback = fxSeriesByCurrency[sec.currency.code].orEmpty(),
                )
            }

            // 逐日出入金净额（盈亏日历剔除用，见 [EquityCurve.dailyPnl]）
            val dailyCashFlows = cashFlows
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, amounts) -> amounts.sum() }

            // 4) 曲线 + 回撤（老周 2026-09-23：**只算最近一年**，常量 365 天）
            val points = com.stocknote.core.calc.EquityCurve.build(
                transactions = trades,
                cashFlows = cashFlows,
                currentCash = currentCash,
                closesBySecurityId = closesBySecurityId,
                rateToBase = rateToBase,
                rateSeriesBySecurityId = rateSeriesBySecurityId,
                dividends = dividendCashEvents,
                // ⚠️ H3 修复（2026-09-28）：送股/配股进持仓时间线（详见上面 shareEvents 的说明）
                shareEvents = shareEvents,
                lookbackDays = 365,
            )
            if (points.isEmpty()) {
                return@withContext EquityCurveResult(null, "交易与收盘数据没有交集，暂无法绘制区间曲线")
            }
            // ⚠️ 跳过网络（当天已拉过）时 dataDays 会停留在 0 → 副标题显示「近 0 个交易日收盘」，
            // 看起来像没数据（老周 2026-09-23 真机实测发现）。用**库里实际覆盖的最多交易日数**兜底。
            val coveredDays = closesBySecurityId.values.maxOfOrNull { it.size } ?: 0
            EquityCurveResult(
                data = EquityCurveData(
                    points = points,
                    maxDrawdown = com.stocknote.core.calc.EquityCurve.maxDrawdown(points),
                    dataDays = if (dataDays > 0) dataDays else coveredDays,
                    dailyCashFlows = dailyCashFlows,
                    // 名称一并带给 UI：盈亏日历点某天要按标的列盈亏，而 pnlBySecurity 只有 id
                    securityNames = securities.associate { it.id to it.name },
                ),
            )
        }

    // ⚠️ 2026-09-29 第 11 批：标的域 8 个方法的实现（securities / searchSecurities /
    // findBySymbol / searchOnline / findOrCreateSecurity / setSecurityExcludeFromStats /
    // deleteSecurity / listSecurities）**已搬到 SecurityRepository**。
    // ⚠️ loadSecurityDetail 留在本类：它要拼持仓（全量重放），是聚合方法。
    // 接线：feature 层调用方已切 container.security。

    /**
     * 标的历史交易页（REQ-VIEW-10）的数据源：合并概览 + 逐笔，一次取齐。
     * 列表层读聚合、详情层读逐笔 —— 两条粒度来自同一张 trade 表。
     */
    suspend fun loadSecurityDetail(securityId: String): SecurityDetail? =
        withContext(Dispatchers.Default) {
            val row = db.securityQueries.selectById(securityId).executeAsOneOrNull() ?: return@withContext null
            val security = row.toDomain()
            val txs = db.tradeQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
            val divs = db.dividendQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }

            val cached = db.quoteQueries.selectBySymbol(security.symbol).executeAsOneOrNull()
            val price = cached?.price ?: run {
                // 缓存没有就试一次联网，再拿不到就用成本价，保证页面永远有数
                runCatching { quoteClient.fetch(security.symbol) }.getOrNull()?.price
                    ?: PositionCalculator.replay(security, txs, dividends = divs).avgCost
            }

            SecurityDetail(
                security = security,
                position = PositionCalculator.replay(security, txs, price, divs),
                transactions = txs,
                quote = cached?.let {
                    Quote(
                        symbol = it.symbol,
                        price = it.price,
                        prevClose = it.prev_close,
                        source = it.source,
                        updatedAtEpochMs = it.updated_at,
                    )
                },
                dividends = divs,
            )
        }

    suspend fun dividendCount(): Long = withContext(Dispatchers.Default) {
        db.dividendQueries.countAll().executeAsOne()
    }

    /** 分红送股流水（全部，倒序展示用）。 */
    suspend fun allDividends(): List<DividendRecord> = withContext(Dispatchers.Default) {
        db.dividendQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    suspend fun dividendsOf(securityId: String): List<DividendRecord> =
        withContext(Dispatchers.Default) {
            db.dividendQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
        }

    /**
     * 登记分红送股（REQ-ACC-04）。登记本身不改成本 —— 成本调整发生在**下一次全量重放**，
     * 这是「交易/分红为事实源」口径的直接推论。
     */
    suspend fun addDividend(
        securityId: String,
        exDate: String,
        type: String,
        quantity: Double,
        perShare: Double,
        bonusShares: Double,
        rightsPrice: Double,
        note: String? = null,
        /** 原币→CNY 汇率；null = 按 [currency] 取库内最新汇率兜底（老周 2026-09-16：港股分红用设置里的实际汇率） */
        fxRate: Double? = null,
        /**
         * 标的币种（**H2 修复**，2026-09-27）：兜底折算必须按标的**实际币种**走。
         * **null = 自动按 securityId 查标的币种**（默认路径，调用方无需关心）。
         * ⚠️ 此前兜底硬编码 `FxTable.rate(HKD, CNY)=0.90`，而调用方只对 HKD 传汇率，
         * 于是 **A股 / 美股 / ETF / 场外基金的分红一律按 0.90 折算**（1000 元分红只算 900）。
         */
        currency: Currency? = null,
    ): String = withContext(Dispatchers.Default) {
        // 币种解析：显式传入优先；否则查标的（查不到按本位币兜底）
        val cur = currency ?: runCatching {
            db.securityQueries.selectById(securityId).executeAsOneOrNull()?.currency
                ?.let { code -> Currency.entries.firstOrNull { it.code == code } }
        }.getOrNull() ?: Currency.CNY
        val id = Ids.next("div")
        db.dividendQueries.upsert(
            id = id,
            security_id = securityId,
            ex_date = exDate,
            type = type,
            quantity = quantity,
            per_share = perShare,
            bonus_shares = bonusShares,
            rights_price = rightsPrice,
            // H2 修复：本位币恒 1.0；外币按"显式传入 → 库内最新 → FxTable 兜底"三级取值，并统一收敛 4 位小数
            fx_rate = if (cur == Currency.CNY) {
                1.0
            } else {
                FxTable.roundRate(fxRate ?: latestFxRates()[cur.code] ?: FxTable.rate(cur, Currency.CNY))
            },
        )
        // M5 修复（2026-09-27）：登记分红会改变现金锚 → 曲线缓存必须失效
        invalidateCurveCache()
        id
    }

    // ⚠️ 2026-09-29 第 10 批：cashFlows 的转发已删除 —— 调用方直接用 CashRepository。

    /**
     * **账户期初现金（本位币）** —— XIRR 的期初锚（技术说明书 5.12 / REQ-VIEW-07）。
     *
     * ⚠️ **H5 修复（2026-09-27）：不能直接返回 `account.cash`**。`addCashFlow()` 从不改 `account.cash`
     * （只插 `cash_flow` 表），它只被买卖的 `applyCashDelta` 增减 —— 所以 `account.cash` 是
     * 「期初 − 累计买入 + 累计卖出」的**残值**，不是期初本金：
     *   - 盈利兑现后 `account.cash > 0`，旧逻辑会把它当成"期初投入"加一条负现金流 → IRR 严重扭曲；
     *   - 为负时 `ReturnCalculator` 会静默跳过该分支 → 期初本金直接缺失。
     *   （演示数据的 `ACCOUNT_CASH` 恰好是负数，所以历次测试都绕过了这个分支。）
     *
     * 正解（**反推**，与 `EquityCurve` 的"现金锚"同源）：
     * `期初本金 = account.cash − Σ(各笔交易的现金变动)` —— 买入 delta 为负、卖出为正，
     * 减掉它们正好还原到"还没开始买卖时"的现金。
     */
    suspend fun openingCash(base: Currency = Currency.CNY): Double = withContext(Dispatchers.Default) {
        // ⚠️ M13 修复（2026-09-28）：此前只累加 `currency == 本位币` 的账户，而下面的 `tradeDelta`
        // 是**全部交易折本位币** —— 两边口径不一致。多币种账户上线后，期初本金会**少算**
        //（外币账户的现金被整块忽略）→ XIRR 的期初现金流缺失。
        // 现在：**所有账户按各自币种折本位币后求和**（单账户 CNY 时结果与原先完全一致）。
        val fxNow = latestFxRates()
        val accountCash = db.accountQueries.selectAll().executeAsList().sumOf { acc ->
            val cur = Currency.entries.firstOrNull { it.code == acc.currency } ?: Currency.CNY
            FxTable.convertWith(acc.cash, cur, base, fxNow)
        }
        // 预取标的（避免逐笔 selectById 的 N+1）
        val offBookIds = db.securityQueries.selectAll().executeAsList()
            .filter { it.exclude_from_stats != 0L }
            .map { it.id }
            .toSet()
        // Σ 各笔交易的现金变动（本位币）：买入 −(价×量+费)、卖出 +(价×量−费)、CAPITALIZE 0
        // ⚠️ 老周 2026-09-29：按**各笔自己的成交日汇率**重算（`trade.fx_rate`）——
        // 与写入路径（TradeRepository.cashDeltaInCny）同一口径，否则期初锚与账户现金会错开。
        val tradeDelta = db.tradeQueries.selectAll().executeAsList().sumOf { t ->
            if (t.security_id in offBookIds) {
                0.0   // 账外备忘标的从不影响现金 → 跳过（与 applyCashDelta 的调用点同口径）
            } else {
                cashDeltaInCny(
                    t.security_id, TradeSide.valueOf(t.side), t.quantity, t.price, t.fee,
                    fxRate = t.fx_rate,
                    // 印花税与手续费同等参与「期初本金」反推（REQ-ACC-17）
                    stampDuty = t.stamp_duty,
                )
            }
        }
        accountCash - tradeDelta
    }

    /**
     * **清空整个账本流水**（老周 2026-09-30；设置页红色按钮「清空所有标的」用）。
     *
     * 清掉：**全部交易**（连附属的笔记 / 截图）+ **全部分红送股** + **全部出入金**，
     * 并把每个账户的现金归零 —— 即"把账本推倒重来"，便于重新导入 CSV。
     *
     * **保留**：标的（名称 / 市场 / 每手 / 账外标记）、自选、交易计划、标签、汇率、复盘。
     * 刻意保留标的是因为重新导入 CSV 时 `SecurityRepository.findOrCreateSecurity` 会按 symbol
     * **复用**它们 —— 若连标的一起删，导入后会凭空多出一批"看起来一样"的新标的。
     *
     * ⚠️ 账户现金**直接归零**，而不是逐笔回滚：交易与出入金都清空后，
     * `account.cash`（买卖净额）与出入金净额必然都是 0，逐笔回滚只是绕远路且更容易漏。
     *
     * @return 被清掉的交易笔数（供界面提示）
     */
    suspend fun clearAllLedger(): Int = withContext(Dispatchers.Default) {
        val txIds = db.tradeQueries.selectAll().executeAsList().map { it.id }
        db.transaction {
            // ① 交易附属：笔记 / 截图（与 deleteTransaction 的级联口径一致）
            txIds.forEach { id ->
                db.noteQueries.deleteByTransaction(id)
                db.tradePhotoQueries.deleteByTx(id)
            }
            // ② 流水本体：交易 / 分红送股 / 出入金
            db.tradeQueries.deleteAll()
            db.dividendQueries.deleteAll()
            db.cashFlowQueries.deleteAll()
            // ③ 账户现金归零（账户本身与名称 / 币种保留）
            db.accountQueries.selectAll().executeAsList().forEach { a ->
                db.accountQueries.upsert(a.id, a.name, a.currency, 0.0)
            }
        }
        invalidateCurveCache()
        txIds.size
    }

    // ⚠️ 2026-09-29 第 11 批：searchOnline / findOrCreateSecurity / setSecurityExcludeFromStats
    // 已搬到 SecurityRepository（上面的 KDoc 描述仍适用）。

    /** 账外标的（「不计入统计与分析」）的 securityId 集合。 */
    private fun offBookSecurityIds(): Set<String> =
        db.securityQueries.selectAll().executeAsList()
            .filter { it.exclude_from_stats == 1L }
            .map { it.id }
            .toSet()

    /**
     * 统计 / 分析专用数据域：**只含账内标的与其流水**。
     *
     * 与 [securities] / [allTransactions] 的区别：那两个是**全量**（记录型页面用，
     * 如日记、自选、标的详情都要看得到账外备忘的基金）；本方法是**统计口径**
     * （收益率 / 胜率 / 策略分组等一律用它），账外标的在这里被剔除。
     */
    suspend fun statsScope(): StatsScope = withContext(Dispatchers.Default) {
        val securities = db.securityQueries.selectAll().executeAsList()
            // M9 修复（2026-09-27）：**现金等价物也要剔除** —— 货币 ETF（511660）本质是"现金换现金"，
            // 它的买卖不该进 胜率 / 盈亏比 / 策略分组 / 连胜连亏。此前只有 exclude_from_stats 一道过滤，
            // 与 returnStats 的口径不一致（那边明确排除现金等价物，REQ-ACC-07）。
            .filter { it.exclude_from_stats == 0L && it.is_cash_equivalent == 0L }
            .map { it.toDomain() }
        val ids = securities.map { it.id }.toSet()
        StatsScope(
            securities = securities,
            transactions = db.tradeQueries.selectAll().executeAsList().map { it.toDomain() }
                .filter { it.securityId in ids },
        )
    }

    /** 统计口径的数据域（见 [statsScope]）。 */
    data class StatsScope(
        val securities: List<Security>,
        val transactions: List<Transaction>,
    )


    /**
     * 利润转增资本（REQ-ACC-16，老周 2026-09-20）。
     *
     * 把该标的盈利中的 [amount] 折入本金（抬高成本价），该股总盈亏下降同等金额。
     * 落库为一条 `side = CAPITALIZE` 的 trade 记录：**金额存 price 列**、quantity = 0、fee = 0
     * （见 [com.stocknote.core.model.TradeSide.CAPITALIZE] 的存储约定）。
     *
     * ⚠️ **不碰现金、不写 cash_flow**（`CashImpact` 对 CAPITALIZE 恒返回 0）——
     * 这是账内重分类而非注资：纸面盈利没有现金来源，插一笔存款会凭空造钱。
     * 因此 totalAsset / 可用现金 / XIRR 都不受影响，只有成本与盈亏构成变化。
     *
     * 撤销方式：删除这条记录（[deleteTransaction]）即可，重放会回到转增前口径。
     *
     * @param amount   转增金额 X（原币，须 > 0）
     * @param tradeDate 记录日期；缺省今天
     */
    suspend fun addCapitalize(
        securityId: String,
        amount: Double,
        tradeDate: String = com.stocknote.data.platform.todayIso(),
    ): String = withContext(Dispatchers.Default) {
        require(amount > 0.0 && !amount.isNaN() && !amount.isInfinite()) { "转增金额必须大于 0" }

        // 仅对**仍持仓**的标的可转增（0 持仓没有本金可增）—— 与 UI 的按钮显隐同一口径，
        // 这里再拦一道，防止绕过 UI 直接调用（REQ-ACC-16 硬约束 2）。
        val holding = positionQuantityOf(securityId)
        if (holding <= 1e-9) error("该标的已无持仓，无法转增资本。")

        // 成交日汇率（REQ-ACC-15 口径，与记一笔同源）：本位币不写列，外币记市场汇率（拿不到留空回退最新）
        val currency = db.securityQueries.selectById(securityId).executeAsOneOrNull()?.currency
            ?.let { code -> Currency.entries.firstOrNull { it.code == code } }
            ?: Currency.CNY
        val fx: Double? = if (currency == Currency.CNY) null else fxRateOn(tradeDate, currency)

        invalidateCurveCache()
        val id = Ids.next("tx")
        val seq = db.tradeQueries.nextSeq().executeAsOne()
        db.tradeQueries.upsert(
            id = id,
            security_id = securityId,
            side = TradeSide.CAPITALIZE.name,
            quantity = 0.0,          // 转增资本不改股数
            price = amount,          // 金额承载在 price 列
            fee = 0.0,
            // 转增资本不是成交，不产生印花税（REQ-ACC-17）
            stamp_duty = 0.0,
            trade_date = tradeDate,
            seq = seq,
            note = null,
            emotion = null,
            score = null,
            tag_ids = null,
            quality = null,
            fx_rate = fx,
        )
        id
    }

    /**
     * 编辑。security_id **不可改**（改标的等于删除后另录，避免聚合口径混乱）；
     * 保存后不做任何增量修补，下次读取整标的重放。
     */

    // ⚠️ 2026-09-29 拆分（第 6 批）：交易截图 4 个方法的实现 -> TradeRepository。
    // 说明：**不参与加密备份**（备份是 SQL 文本 dump，BLOB 需额外编码，本期从简）。
    /**
     * 删除预告（REQ-ACC-09）：对「剔除这一笔」的集合做一次重放。
     * 与实际删除共用 [PositionCalculator.replay]，预告结果 = 实际结果。
     */

    /**
     * 修改出入金流水（老周 2026-09-16）。改后曲线缓存失效。
     *
     * ⚠️ **H4 修复（2026-09-28）**：此前 `amount_base` 被直接写成原币金额（`= signed`），
     * 且 UPDATE 语句里没有 `currency` / `fx_rate` —— 编辑一笔**外币流水**（如 CSV 导入的港币入金）
     * 会把本位币金额**改成原币数字**（1 万港元从 8555 变 10000），**可用现金虚增、收益率跟着偏**。
     * 现在：先读回原行，**币种与汇率原样保留**，`amount_base = 原币金额 × 汇率`（CNY 汇率恒 1）。
     * 注：改日期不重取新汇率（沿用该笔原汇率快照），避免编辑动作引入额外网络依赖。
     */
    // ⚠️ 2026-09-29 第 10 批：出入金 CRUD 的 4 个转发（addCashFlow / updateCashFlow /
    // deleteCashFlow / cashFlowCount）已删除，调用方直接用 CashRepository。
    // ⚠️ **注意**：原类的转发里曾负责 invalidateCurveCache()（写操作会改资产曲线）。
    //    现在改由**调用方**负责 —— 当前唯一写调用方是 TradeRepository.applyCashFlowsCsv，
    //    它在末尾已调 repo.invalidateCurveCache()。将来新增写调用方时别漏这一步。

    /** 表单「锁定标的取现价」拉取的 K 线根数。必须 >= QuoteClient.MIN_CANDLES(=5)，否则请求被判无效 */
    private val REFRESH_QUOTE_DAYS = 10

    /** 汇率记录保留天数（老周 2026-09-18：只留最近 3 天） */
    /**
     * 汇率表**每币种保留的最新条数**（L2 修复，2026-09-27；老周定：3 条）。
     *
     * 取值永远是"最新一条"（`latestPerCurrency`），多留几条只是**离线兜底**：
     * 拉不到新汇率时，至少还有最近几次成功拉取的值可用。
     */
    // ⚠️ 2026-09-28 拆分改动：去掉 private，供 FxRepository 委托时复用同一默认值
    //（避免两处各写一个 3，将来改了这边忘那边）。
    internal val FX_KEEP_PER_CURRENCY = 3

    /** 读行情缓存（离线可用；无缓存返回 null，UI 显示「—」）。 */
    suspend fun cachedQuote(symbol: String): Quote? = withContext(Dispatchers.Default) {
        db.quoteQueries.selectBySymbol(symbol).executeAsOneOrNull()?.let {
            Quote(
                symbol = it.symbol,
                price = it.price,
                prevClose = it.prev_close,
                source = it.source,
                updatedAtEpochMs = it.updated_at,
            )
        }
    }

    /**
     * 联网拉一次行情并落库，返回最新 Quote（拿不到返回 null）。供表单「锁定标的取现价」用。
     *
     * ⚠️ 2026-09-18 修复（老周报「选了标的不自动填成交价」）：
     * 此前传 `dayCount = 2`，但 [com.stocknote.data.net.QuoteClient.fetchRaw] 要求
     * 至少 `MIN_CANDLES = 5` 根 K 线才认为这次请求有效 —— **2 永远过不了门槛**，
     * 所以这个函数恒定返回 null：价格填不进表单，行情也永远写不进缓存表，
     * 连带「记一笔后持仓页显示成本总价、浮动盈亏为 0」。
     * 现改为 10（留足停牌 / 次新股 K 线偏少的余量）。
     */
    suspend fun refreshQuote(symbol: String): com.stocknote.core.model.Quote? =
        withContext(Dispatchers.Default) {
            val quote = runCatching { quoteClient.fetch(symbol, REFRESH_QUOTE_DAYS) }.getOrNull()
            if (quote != null) {
                runCatching {
                    db.quoteQueries.upsert(
                        symbol = symbol,
                        price = quote.price,
                        prev_close = quote.prevClose,
                        source = quote.source,
                        updated_at = quote.updatedAtEpochMs,
                    )
                }
            }
            quote
        }

    // ⚠️ 2026-09-29 拆分（第 5 批）：自选域 10 个方法的实现 -> WatchlistRepository。
    // 它的内部自用全在本域内互调（addWatch 先查 isWatched），跨域依赖为零。
    // ⚠️ 下面这个「分析师目标价」虽写在自选分区里，但属行情域（依赖 quoteClient），保留未动。

    // ---- 分析师目标价（老周 2026-09-21）----

    /**
     * 目标价取数（**两级**，老周 2026-09-21 定）：
     *  ① 逐条研报目标价 → 近 6 个月、去掉最大最小后取平均（口径准）；
     *  ② 机构评级聚合 → 评级家数 + 目标价区间；①没样本时用**区间中点**兜底（覆盖率高）。
     *
     * 只要标的是 A股，**两个源都查**（老周：「用①取到时把②的信息也查一下并做个提示」），
     * 让 UI 能把「N 份研报的均值」与「M 家机构给区间 a~b」摆在一起对照。
     * 非 A股 / 都取不到 → [AnalystTargetSource.Suggestion.None]，由 UI 如实说明。
     */
    suspend fun analystTargetSuggestion(
        symbol: String,
        months: Int = com.stocknote.data.net.AnalystTargetSource.DEFAULT_MONTHS,
    ): com.stocknote.data.net.AnalystTargetSource.Suggestion = withContext(Dispatchers.Default) {
        val from = com.stocknote.data.net.AnalystTargetSource.monthsAgoIso(todayIso(), months)
        val reports = runCatching { quoteClient.fetchAnalystTargets(symbol, from) }
            .getOrDefault(emptyList())
        val consensus = com.stocknote.data.net.AnalystTargetSource.consensus(reports, from)
        // ② 即使 ① 拿到了也照查（对照提示用）；失败就当没有，不影响 ① 的结果
        val profile = runCatching { quoteClient.fetchAnalystRatingProfile(symbol) }.getOrNull()
        com.stocknote.data.net.AnalystTargetSource.suggest(consensus, profile)
    }

    // ⚠️ 2026-09-29 拆分（第 6 批）：交易质量评级的实现 -> TradeRepository。

    // ------------------------------------------------- 分红送配自动检测（老周 2026-09-16 定稿）
    // 口径：A股 = 税前（票面）；港股 = 税后（已扣 20% 港股通红利税）
    // 策略：**半自动** —— 只检测与提示，用户确认后才写入账本（避免数据源出错污染账本）

    /**
     * 一条「待登记」的分红送配（已按登记日持股数算好金额）。
     *
     * @param quantity 股权登记日（港股用除权日前一日）的持股数
     * @param estimatedCash 预计现金分红（元/港元，标的币种）
     * @param estimatedShares 预计新增股数（送股+转增；港股通常为 0）
     */
    data class PendingDividend(
        val securityId: String,
        val securityName: String,
        val symbol: String,
        val currency: String,
        val exDate: String,
        val payDate: String?,
        val quantity: Double,
        val cashPerShare: Double,
        val bonusPerShare: Double,
        val rightsPerShare: Double,
        val estimatedCash: Double,
        val estimatedShares: Double,
        val taxNote: String,
        val note: String,
    ) {
        val hasCash: Boolean get() = estimatedCash > 1e-9
        val hasShares: Boolean get() = estimatedShares > 1e-9
    }

    /**
     * 一次分红扫描的结果（老周 2026-09-30）。
     *
     * 为什么要带统计：此前只返回「未登记分红」列表，**取数失败与确实没分红都表现为空列表**，
     * 界面无从区分（老周真机反馈"看不到任何反应"）。带上 [ok]/[failed] 后，
     * 界面才能如实说"检测失败"，而不是假装"没有分红"。
     *
     * @param items 检测到的未登记分红送配
     * @param scanned 参与扫描的标的数（有交易记录、且市场受支持）
     * @param ok 成功取到数据的标的数（**不代表一定有分红**）
     * @param failed 取数失败的标的数（网络 / 接口异常）
     */
    data class DividendScan(
        val items: List<PendingDividend>,
        val scanned: Int,
        val ok: Int,
        val failed: Int,
    )

    /**
     * 检测所有持仓标的的**未登记**分红送配。
     *
     * 规则：
     *  1. 只查**当前持仓**的标的（已清仓的不再提示）
     *  2. **A股股票**走东财（税前）、**港股**走腾讯（税后）、**美股**走东财美股 F10（税前）、
     *     **ETF / 场外基金**走天天基金 F10（税前）—— 四源分开（老周 2026-09-20 / 09-30）：
     *     东财股票报表对 ETF 代码返回空，ETF 不能混入 A股 通道；腾讯美股 K 线又不带分红字段
     *  3. 只保留「除权除息日 ≥ 该标的首次买入日」且「除权日当天有持仓」的记录
     *  4. 持股数 = **权益登记日**的持仓（东财 `EQUITY_RECORD_DATE` / 天天基金「权益登记日」列；
     *     港股源无该字段 → 退回除权日前一日近似）
     *  5. 已登记过（同 securityId + exDate）的跳过
     *
     * ⚠️ 返回值带**扫描统计**（老周 2026-09-30）：调用方据此区分「取数失败」与「确实没分红」。
     */
    suspend fun detectPendingDividends(): DividendScan = withContext(Dispatchers.Default) {
        val securities = db.securityQueries.selectAll().executeAsList()
        val trades = db.tradeQueries.selectAll().executeAsList().map { it.toDomain() }
        val registered = db.dividendQueries.selectAll().executeAsList()
            .map { it.security_id to it.ex_date }.toSet()
        val out = mutableListOf<PendingDividend>()
        // 扫描统计：让上层能区分「取数失败」与「确实没分红」（老周 2026-09-30）
        var scanned = 0
        var okCount = 0
        var failedCount = 0

        securities.forEach { row ->
            val sec = row.toDomain()
            // 只查能取到分红的市场：A股股票 / 港股 / 美股 / ETF / 场外基金
            val isFund = sec.market == Market.ETF || sec.market == Market.FUND
            val isCnStock = sec.market == Market.A_SHARE
            val isHk = sec.market == Market.HK
            val isUs = sec.market == Market.US
            if (!isFund && !isCnStock && !isHk && !isUs) return@forEach

            val myTrades = trades.filter { it.securityId == sec.id }
            if (myTrades.isEmpty()) return@forEach
            val firstBuy = myTrades.minOf { it.tradeDate }

            // 多源分流：港股腾讯 / 美股（及 A股）东财 F10 / ETF+基金天天基金 F10
            scanned++
            val fetch = runCatching {
                when {
                    isHk -> quoteClient.fetchHkDividends(sec.symbol)
                    isUs -> quoteClient.fetchUsDividends(sec.symbol)
                    isFund -> quoteClient.fetchCnFundDividends(sec.symbol)
                    else -> quoteClient.fetchCnDividends(sec.symbol)
                }
            }.getOrElse { QuoteClient.DividendFetch.FAILED }
            if (fetch.ok) okCount++ else failedCount++
            val dividends = fetch.items
            if (dividends.isEmpty()) return@forEach

            dividends.forEach { d ->
                if (d.exDate < firstBuy) return@forEach
                if ((sec.id to d.exDate) in registered) return@forEach
                if (!d.hasCash && !d.hasBonus) return@forEach

                // 持股数：优先用**权益登记日**（东财/天天基金都有），无登记日的源退回除权日前一日
                val asOf = d.recordDate ?: com.stocknote.core.calc.CivilDate.prevIso(d.exDate)
                // ⚠️ H3 修复（2026-09-28）：把**该标的已登记的送股/配股**一并纳入持股数 ——
                // 送过股之后若不加上，此后每一次分红预估的股数都会偏小（金额随之偏小）。
                val registeredShareEvents = db.dividendQueries.selectAll().executeAsList()
                    .filter { it.security_id == sec.id }
                    .flatMap { r ->
                        when (r.type) {
                            "BONUS" -> if (r.bonus_shares > 0) {
                                listOf(
                                    com.stocknote.core.calc.EquityCurve.ShareEvent(
                                        r.ex_date, r.security_id, r.bonus_shares,
                                    ),
                                )
                            } else {
                                emptyList()
                            }

                            "RIGHTS" -> if (r.quantity > 0 && r.rights_price > 0) {
                                listOf(
                                    com.stocknote.core.calc.EquityCurve.ShareEvent(
                                        r.ex_date, r.security_id, r.quantity,
                                    ),
                                )
                            } else {
                                emptyList()
                            }

                            else -> emptyList()
                        }
                    }
                val qty = quantityAsOf(myTrades, asOf, registeredShareEvents)
                if (qty <= 1e-9) return@forEach

                val cash = qty * d.cashPerShare
                val shares = qty * (d.bonusPerShare + d.rightsPerShare)
                out += PendingDividend(
                    securityId = sec.id,
                    securityName = sec.name,
                    symbol = sec.symbol,
                    currency = d.currency,
                    exDate = d.exDate,
                    payDate = d.payDate,
                    quantity = qty,
                    cashPerShare = d.cashPerShare,
                    bonusPerShare = d.bonusPerShare,
                    rightsPerShare = d.rightsPerShare,
                    estimatedCash = cash,
                    estimatedShares = shares,
                    taxNote = d.taxNote,
                    note = d.note,
                )
            }
        }
        DividendScan(
            items = out.sortedBy { it.exDate },
            scanned = scanned,
            ok = okCount,
            failed = failedCount,
        )
    }

    /**
     * 登记一批分红送配（用户确认后调用）。
     * 现金分红写 CASH 一条；送股/转增写 BONUS 一条（同日两条，重放时按 ex_date+id 都生效）。
     * @return 实际写入的次数
     */
    suspend fun registerDividends(items: List<PendingDividend>): Int = withContext(Dispatchers.Default) {
        var n = 0
        items.forEach { p ->
            // H2 修复（2026-09-27）：**所有币种**都要决定汇率 —— 此前只对 HKD 取值、其余传 null，
            // 而 addDividend 的兜底写死港币 → A股/美股/ETF/基金分红一律落 0.90（算错 10%）。
            // 现在：本位币交 1.0（由 addDividend 内部处理），外币取"库内最新 → FxTable 兜底"。
            val cur = Currency.entries.firstOrNull { it.code == p.currency } ?: Currency.CNY
            val fx: Double? = if (cur == Currency.CNY) null
            else latestFxRates()[cur.code] ?: FxTable.rate(cur, Currency.CNY)
            if (p.hasCash) {
                addDividend(
                    securityId = p.securityId,
                    exDate = p.exDate,
                    type = "CASH",
                    quantity = p.quantity,
                    perShare = p.cashPerShare,
                    bonusShares = 0.0,
                    rightsPrice = 0.0,
                    note = p.taxNote,
                    fxRate = fx,
                    currency = cur,
                )
                n++
            }
            if (p.hasShares) {
                addDividend(
                    securityId = p.securityId,
                    exDate = p.exDate,
                    type = "BONUS",
                    quantity = p.quantity,
                    perShare = 0.0,
                    bonusShares = p.estimatedShares,
                    rightsPrice = 0.0,
                    note = p.taxNote,
                    currency = cur,
                )
                n++
            }
        }
        invalidateCurveCache()
        n
    }

    /**
     * 某日之前的持仓数量（as-of 口径；用于分红按登记日股数计算）。
     *
     * ⚠️ **H3 修复（2026-09-28）**：新增 [shareEvents] 参数 —— 此前这里只累加 BUY/SELL，
     * **送股(BONUS)/配股(RIGHTS) 完全没算** → 送过股之后，此后每一次分红预估的"持股数"都偏小，
     * 预填的分红金额随之偏小。事件规则与 `PositionCalculator.applyDividend` 一致。
     *
     * ⚠️ **2026-09-30 修复（老周：分红提示"从来没出现过"）**：H3 那版把**买入**也写成
     * 「已有持仓（qty > 1e-9）才加」，导致**首笔买入被吞掉**、之后只剩卖出 → 持股数恒为 0，
     * 所有候选分红都在 `qty <= 1e-9` 处被丢弃（检测结果永远是空）。
     * 现在交易（买卖）无条件生效，只有送股/配股才要求「仍持仓」。
     *
     * `internal` 而非 `private`：供契约测试直接钉住上面两条回归。
     */
    internal fun quantityAsOf(
        trades: List<com.stocknote.core.model.Transaction>,
        dateIso: String,
        /** 该标的的送股/配股事件（date + 股数增量），只取 `<= dateIso` 的生效。 */
        shareEvents: List<com.stocknote.core.calc.EquityCurve.ShareEvent> = emptyList(),
    ): Double {
        // 把交易与送股事件合并成同一条按日期排序的时间线（同日后跑送股，与曲线口径一致）
        // ⚠️ 必须**区分「交易」与「送股事件」**两类：交易无条件生效；送股只在仍有持仓时生效。
        data class Ev(val date: String, val seq: Long, val id: String, val qty: Double, val isTrade: Boolean)
        val events = buildList {
            trades.filter { it.tradeDate <= dateIso }.forEach { tx ->
                val delta = when (tx.side) {
                    TradeSide.BUY -> tx.quantity
                    TradeSide.SELL -> -tx.quantity
                    // 利润转增资本不改股数（REQ-ACC-16）
                    TradeSide.CAPITALIZE -> 0.0
                }
                add(Ev(tx.tradeDate, tx.seq, tx.id, delta, isTrade = true))
            }
            shareEvents.filter { it.date <= dateIso }.forEach { ev ->
                // 送股/配股排在当日交易之后：seq 用 Long.MAX_VALUE（同日靠后）
                add(Ev(ev.date, Long.MAX_VALUE, ev.securityId, ev.deltaQty, isTrade = false))
            }
        }.sortedWith(compareBy({ it.date }, { it.seq }, { it.id }))

        var qty = 0.0
        events.forEach { ev ->
            qty = if (ev.isTrade) {
                // 交易：买入无条件加、卖出直接减（超仓按 0 截断）—— 与 [PositionCalculator.replay] 一致。
                // ⚠️ 此前这里对**买入**也要求「已有持仓（qty > 1e-9）才加」，
                //    于是首笔买入被吞掉、之后只剩卖出 → 持股数恒为 0，
                //    所有分红都在 `qty <= 1e-9` 处被丢弃（老周真机"自动拉分红从没出现过"就是这个）。
                (qty + ev.qty).coerceAtLeast(0.0)
            } else {
                // 送股/配股：只在**仍有持仓**时生效（清仓后不再产生股本）
                if (ev.qty > 0 && qty <= 1e-9) qty else (qty + ev.qty).coerceAtLeast(0.0)
            }
        }
        return qty
    }

    // ⚠️ 2026-09-28：私有 `prevDayOf()` 已上提到 core —— 见 [com.stocknote.core.calc.CivilDate.prevIso]。

    // ------------------------------------------------- CSV 导入/导出（老周 2026-09-16 确认设计）

    /** 导入结果：成功/跳过明细（跳过原因逐行可读） */
    data class CsvImportResult(
        val okRows: Int,
        val skipped: List<String>,
    )
    /**
     * 导入交易 CSV。逐行校验，坏行跳过并给出「第 N 行：原因」；
     * 去重策略 = **跳过重复**（同 日期+标的代码+方向+数量+成交价 视为重复）；整批事务。
     */
    /** 解析并导入交易 CSV 文本（SAF 选文件用）—— 老周 2026-09-17 */
    /**
     * **CSV 导入专用的交易写入**（M8 修复，2026-09-27）。
     *
     * 与 [addTransaction] 的三点关键区别：
     *  1. **不做"现金充足"校验**：CSV 是整本账的搬运，**行序不代表资金先后** ——
     *     先导交易再导出入金（或反过来）都是正常用法，不该让买入行被跳过。
     *     此前每行都走 `addTransaction` 的校验，于是"先导交易"时**所有买入行直接失败**。
     *  2. **按「该笔交易日期的持仓」截断卖出**（而不是"当前持仓"）：调用方须**先把记录按日期排序**，
     *     否则乱序导入会把历史卖出截成 0 —— 成交被静默吞掉。
     *  3. **不做自动加自选**：批量导入时每行都去查一次自选纯属浪费，导入完成后界面统一刷新即可。
     *
     * ⚠️ **现金联动保留**：`account.cash` 是"买卖增减的账户现金"，必须同步维护，
     * 否则可用现金（= 账户现金 + 出入金 + 分红）会算错。
     */


    /** 导入出入金 CSV（去重：同 日期+类型+金额） */
    /** 解析并导入出入金 CSV 文本（SAF 选文件用）—— 老周 2026-09-17 */
    // ⚠️ 2026-09-29 拆分（第 6 批）：计划 CSV（planCsvHeader / buildPlansCsv / applyPlansCsv）
    // 的实现**已搬到 PlanRepository**（本来就属计划域）。
    // 连带删除：此前为让 applyPlansCsv 能用而补的 private upsertPlan —— 现在它用 PlanRepository.upsert。
    // ⚠️ 剩下三个（buildTradesCsv / buildCashFlowsCsv / buildTemplatesZip）仍在下方，待 CSV 域整批处理。

    /** 数字去掉多余小数（3.0 → 3，3.50 → 3.5）。M7 修复：改用手写格式化，避免依赖设备 Locale。 */
    // ⚠️ 2026-09-28：私有 `com.stocknote.core.csv.CsvCodec.num()` 已上提到 core —— 见 [com.stocknote.core.csv.CsvCodec.num]。
    // （老周指出上帝类里塞着 CSV 数字格式化私有函数，问题成立。上提后可在 core 的 jvmTest 单测。）

    /**
     * 按代码推断市场（与搜索候选项口径一致）。
     *
     * ⚠️ 2026-09-20 修复（老周报）：旧实现写在本文件里，条件为
     * `symbol.length == 6 && (startsWith("5") || startsWith("1"))`，有两个 bug：
     *   ① 传入的是 CSV 原值，而导出写的是带前缀的 `sh511660`（长度 9）→ ETF 条件**永不成立**，
     *      带前缀的 ETF 全被建成 A 股；
     *   ② 深市 `1` 开头过宽，把 123xxx 可转债 / 10xxxx 国债也误判成 ETF。
     * 现提取为可单测的纯函数 [com.stocknote.data.net.MarketGuess.of]。
     */
    private fun guessMarketOf(symbol: String): Market =
        com.stocknote.data.net.MarketGuess.of(symbol)

    // 注：`guessCurrencyOf(symbol)` 已于 2026-09-22 删除 —— CSV 导入改为**以文件里的币种列为准**
    // （老周：「币种按导入文件中的来，没有再猜」），不再按代码前缀推断币种。
    // 市场仍按前缀推断：CSV 栏位里没有「市场」列，且市场只影响展示标签，风险低。

    // ------------------------------------------------- 情绪自定义（A9，老周 2026-09-16 确认做）

    // ⚠️ 2026-09-29 第 9 批：情绪标签 4 个方法 + presetEmotions 属性 + 两个常量
    // **已搬到 EmotionRepository**（该域零依赖：只读写 app_setting 的一条 key）。

    // ⚠️ 2026-09-28 拆分（阶段 2）：巨潮风险 3 个 + 资讯收藏 4 个方法的实现
    // **已分别搬到 CninfoRepository 与 NewsFavoriteRepository**，原实现一并删除。
    // 两者都是叶子域（原类内部零自用），搬走不牵动任何逻辑。

    // ------------------------------------------- 现金等价物名单（REQ-ACC-07，老周 2026-09-20）

    /**
     * 现金等价物名单**原始文本**（设置页输入框里的样子，CSV 形式）；空串 = 未指定。
     * 用 [CashEquivalents.parse] 解析成条目列表。
     */
    suspend fun cashEquivalentRaw(): String = withContext(Dispatchers.Default) {
        db.settingQueries.selectByKey(CASH_EQUIV_KEY).executeAsOneOrNull()?.setting_value ?: ""
    }

    /** 现金等价物名单（已解析）。 */
    suspend fun cashEquivalentSymbols(): List<String> =
        CashEquivalents.parse(cashEquivalentRaw())

    /**
     * 保存现金等价物名单，并把结果**全量回写到 `security.is_cash_equivalent`**。
     *
     * 设计取舍（老周 2026-09-20 定「设置页填代码、逗号分隔」的方案）：
     * - 设置页名单是用户输入的**唯一入口**；但 `security.is_cash_equivalent` 仍是
     *   计算层的**唯一事实源**（`PositionCalculator` / 本类聚合都只读该字段），
     *   所以保存时把名单结果同步落库，计算层无需改动、也不引入「双事实源」。
     * - 之后新建的标的由 [findOrCreateSecurity] 查名单自动打标，
     *   避免「先填名单、后记账」时漏标。
     * - 移出名单的标的会被**清回 0**，口径即时纠正。
     *
     * @return 命中名单的标的数量（供 UI 回显提示）
     */
    suspend fun saveCashEquivalentSymbols(raw: String): Int = withContext(Dispatchers.Default) {
        val entries = CashEquivalents.parse(raw)
        db.settingQueries.upsert(CASH_EQUIV_KEY, CashEquivalents.format(entries))
    // ⚠️ 2026-09-29 拆分（第 6 批）：CSV 生成侧 4 个方法（buildTradesCsv / buildCashFlowsCsv /
    // buildCsvTemplate / buildTemplatesZip）的实现 -> CsvRepository；两个表头 val 一并搬走。
    // ⚠️ 导入侧（applyTradesCsv / applyCashFlowsCsv）仍在本类：依赖 private insertTradeForCsv
    // 与 invalidateCurveCache，待解开现金联动后再搬。CsvImportResult 保留（导入侧还要用）。

        var hit = 0
        db.securityQueries.selectAll().executeAsList().forEach { row ->
            val want = if (CashEquivalents.matches(row.symbol, entries)) 1L else 0L
            if (want == 1L) hit++
            if (row.is_cash_equivalent != want) {
                db.securityQueries.upsert(
                    id = row.id,
                    symbol = row.symbol,
                    name = row.name,
                    market = row.market,
                    currency = row.currency,
                    industry = row.industry,
                    is_cash_equivalent = want,
                    // 「不计入统计」开关与现金等价物名单互不相干，回写时**原样保留**
                    exclude_from_stats = row.exclude_from_stats,
                    // 每手股数同理：这里只改现金等价物标记，必须原样带回，否则会被置空
                    lot_size = row.lot_size,
                )
            }
        }
        // M5 修复（2026-09-27）：现金等价物名单变化会改变"现金口径" → 曲线缓存必须失效
        invalidateCurveCache()
        hit
    }

    // ⚠️ 2026-09-28 拆分（阶段 2）：本域 6 个方法（listPlans / planById / plansBySecurity /
    // upsertPlan / deletePlan / setPlanDone）的实现**已搬到 PlanRepository**，
    // 原实现与分区注释一并删除 —— 调用方（PlanHolder / PlanEditHolder）已全部改为依赖它。
    // 计划 CSV（buildPlansCsv / applyPlansCsv）**暂留本类**，待下一步迁移。


    // ⚠️ 2026-09-29 第 11 批：listSecurities 已搬到 SecurityRepository。

    companion object {
        private const val FONT_SCALE_KEY = "font_scale"
        private const val FEE_A_SHARE_KEY = "fee_rate_a_share"
        private const val FEE_HK_KEY = "fee_rate_hk"

        /** 现金等价物名单（REQ-ACC-07）：app_setting.cash_equivalent_symbols，CSV 保序 */
        private const val CASH_EQUIV_KEY = "cash_equivalent_symbols"
        // ⚠️ 情绪域相关常量（EMOTION_KEY / PRESET_EMOTIONS）已随该域搬到 EmotionRepository。
    }
}
