package com.stocknote.data.seed

import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import com.stocknote.data.db.StockNoteDb

/**
 * 演示数据种子（老周 2026-09-20 重新设计）。
 *
 * **设计原则**
 *  1. **现金必须自洽**：`account.cash`（买卖净额）+ 出入金 + 现金分红 = 可用现金，
 *     且**资产曲线反推的起点现金恰好为 0**。此前的老种子数据没算过这笔账，
 *     直接拍了个 `cash = 401_920.0`，导致曲线上出现「无锚点」的诡异段。
 *  2. **日期单调**：所有交易日期 **晚于** 最早入金日期（2026-02-02）。
 *     此前 UI 测试种子把交易写在入金之前 → 曲线反推出 ¥0.90 的起点（看着像 bug，其实不是）。
 *  3. **成本价贴近现价**：成本价按 2026-09-18 实际收盘价往前让利 3%~8% 设计，
 *     所以整体是**小幅盈利**、五粮液一只亏损 —— 演示时既有红也有绿。
 *     老种子把成本写成 1600/1700/1800（现价 1257），一打开就是 −30 万，很吓人。
 *  4. **覆盖全市场**：A股 / 场内 ETF / 港股 / 美股 / 场外基金（含**账外备忘**）/ 现金等价物。
 *  5. **不写死用户隐私**，全部是公开标的的虚构持仓。
 *
 * **保留的对账锚**：贵州茅台净持仓恒为 **100 股**（60+60−40+20），与界面原型同源；
 * 价格改为贴近现价，故成本均价不再是 1680.00 —— 对账时请用「100 股」这个锚。
 *
 * ⚠️ **改数据必须同步改 [ACCOUNT_CASH]**：`DemoDataConsistencyTest` 会逐笔重算校验，
 * 数字对不上直接构建失败（这是故意的 —— 现金错了，整条资产曲线就错了）。
 *
 * 重新生成流程：`-PseedDemo=true` 构建 → 清空应用数据 → 首次启动自动播种。
 */
object DemoData {

    // ------------------------------------------------------------------ 数据表
    // 下面这些 internal 常量同时被 seed* 和 DemoDataConsistencyTest 使用，
    // 保证「播种的数字」与「被校验的数字」是同一份，不会各写一遍而漂移。

    /** 2026-09-18 收盘 / 净值快照（兜底行情用；联网后会被真实行情覆盖）。 */
    internal object Price {
        const val MAOTAI = 1257.12
        const val CATL = 301.95
        const val WULIANGYE = 70.00
        const val HS300 = 4.582
        const val MONEY_ETF = 100.010
        const val TENCENT = 419.00
        const val APPLE = 336.13
        const val FUND_519066 = 3.3010
        const val FUND_000001 = 1.3330
    }

    /** 汇率（原币 → CNY）。写进 fx_rate 表，外币持仓与出入金折算都靠它。 */
    internal const val HKD_TO_CNY = 0.86
    internal const val USD_TO_CNY = 6.89

    internal data class SecurityRow(
        val id: String,
        val symbol: String,
        val name: String,
        val market: Market,
        val currency: Currency,
        val industry: String?,
        val cashEq: Boolean,
        /** 场外基金「不计入统计与分析」= 账外备忘（REQ-ACC-17）。 */
        val offBook: Boolean = false,
    )

    internal data class TradeRow(
        val seq: Long,
        val date: String,
        val securityId: String,
        val side: TradeSide,
        val qty: Double,
        val price: Double,
        val fee: Double,
        val fxRate: Double,
        val tags: String? = null,
        val emotion: String? = null,
        val score: Long? = null,
        val note: String? = null,
        /**
         * 费率（**万分之**，与 [com.stocknote.core.calc.TradeForms] 同单位）。
         * 只用于让测试能反推校验 [fee] 有没有抄错；不入库（trade 表只存金额）。
         */
        val feeRate: Double = 2.5,
    )

    internal data class FlowRow(val id: String, val date: String, val amount: Double, val note: String)

    internal data class DivRow(
        val id: String,
        val securityId: String,
        val exDate: String,
        val qty: Double,
        val perShare: Double,
        val fxRate: Double = 1.0,
    )

    internal val SECURITIES: List<SecurityRow> = listOf(
        SecurityRow("sec_sh600519", "sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY, "白酒", false),
        SecurityRow("sec_sz300750", "sz300750", "宁德时代", Market.A_SHARE, Currency.CNY, "电池", false),
        SecurityRow("sec_sz000858", "sz000858", "五粮液", Market.A_SHARE, Currency.CNY, "白酒", false),
        SecurityRow("sec_sh510300", "sh510300", "沪深300ETF", Market.ETF, Currency.CNY, "宽基指数", false),
        // 现金等价物：口径上归「现金」，不进持仓明细与占比（REQ-ACC-07）
        SecurityRow("sec_sh511660", "sh511660", "货币ETF建信", Market.ETF, Currency.CNY, "现金管理", true),
        SecurityRow("sec_hk00700", "hk00700", "腾讯控股", Market.HK, Currency.HKD, "互联网", false),
        SecurityRow("sec_usAAPL", "usAAPL", "苹果", Market.US, Currency.USD, "消费电子", false),
        // 场外基金：账内（进统计）
        SecurityRow("sec_of519066", "of519066", "汇添富蓝筹稳健混合A", Market.FUND, Currency.CNY, "混合型", false),
        // 场外基金：账外备忘（不进总资产 / 曲线 / 收益率，只在持仓页「账外备忘」区单列）
        SecurityRow("sec_of000001", "of000001", "华夏成长混合", Market.FUND, Currency.CNY, "混合型", false, true),
    )

    /**
     * 交易流水（唯一事实源）。13 笔，按日期与 seq 双序。
     *
     * `fee` 由「价 × 量 × 费率 / 10000，不足 5 元按 5 元」算出
     * （与 [com.stocknote.core.calc.TradeForms.feeAmountOf] 同口径）；
     * `fxRate` 写**成交日汇率**（REQ-ACC-15），港股 0.86 / 美股 6.89 / 人民币 1.0。
     */
    internal val TRADES: List<TradeRow> = listOf(
        // ---- 沪深300ETF：底仓，2 月一次性买入（30,000 × 4.32 + 32.40 = 129,632.40）----
        TradeRow(1, "2026-02-11", "sec_sh510300", TradeSide.BUY, 30_000.0, 4.32, 32.40, 1.0,
            "网格", "冷静", 5, "沪深300 估值处于近三年 30% 分位，网格底仓一次性买入。"),

        // ---- 贵州茅台：4 笔（净 100 股，与界面原型同源的对账锚）----
        TradeRow(2, "2026-03-02", "sec_sh600519", TradeSide.BUY, 60.0, 1150.0, 17.25, 1.0,
            "价值投资", "冷静", 4, "白酒需求见底信号出现，按计划建第一笔。"),
        TradeRow(5, "2026-04-15", "sec_sh600519", TradeSide.BUY, 60.0, 1210.0, 18.15, 1.0,
            "价值投资", "冷静", 4, "一季报超预期，加仓至目标仓位。"),
        TradeRow(7, "2026-06-08", "sec_sh600519", TradeSide.SELL, 40.0, 1320.0, 13.20, 1.0,
            "价值投资", "自信", 4, "到目标价区间上沿，减仓 1/3 锁定利润，剩余继续持有。"),
        TradeRow(12, "2026-08-20", "sec_sh600519", TradeSide.BUY, 20.0, 1180.0, 5.90, 1.0,
            "价值投资", "犹豫", 3, "中报后回落到估值中枢下沿，小幅补回。"),

        // ---- 腾讯控股（HK$，成交日汇率 0.86）----
        TradeRow(3, "2026-03-16", "sec_hk00700", TradeSide.BUY, 200.0, 385.0, 38.50, HKD_TO_CNY,
            "价值投资", "恐惧", 3, "市场担心 AI 投入拖累利润率，借回调建仓。", feeRate = 5.0),

        // ---- 宁德时代：买入后部分止盈 ----
        TradeRow(4, "2026-04-08", "sec_sz300750", TradeSide.BUY, 300.0, 285.0, 21.375, 1.0,
            "突破", "贪婪", 3, "海外储能订单超预期，突破颈线追一笔。"),
        TradeRow(11, "2026-08-05", "sec_sz300750", TradeSide.SELL, 100.0, 295.0, 7.375, 1.0,
            "突破", "冷静", 4, "短期涨幅到位，先减 1/3 落袋。"),

        // ---- 五粮液：唯一亏损标的（现价 70 < 成本 78）----
        TradeRow(6, "2026-05-19", "sec_sz000858", TradeSide.BUY, 400.0, 78.0, 7.80, 1.0,
            "波段", "犹豫", 2, "行业库存周期底部，先建仓观察；跌破 70 元再决策。"),

        // ---- 苹果（US$，成交日汇率 6.89；美股免佣）----
        TradeRow(8, "2026-06-22", "sec_usAAPL", TradeSide.BUY, 60.0, 305.0, 0.0, USD_TO_CNY,
            "趋势", "自信", 4, "服务业务毛利持续走高，按趋势跟随建仓。", feeRate = 0.0),

        // ---- 现金等价物：口径上归「现金」，不进持仓明细 ----
        TradeRow(9, "2026-07-03", "sec_sh511660", TradeSide.BUY, 2_000.0, 100.00, 50.00, 1.0),

        // ---- 场外基金（账内）：走天天基金净值 ----
        TradeRow(10, "2026-07-20", "sec_of519066", TradeSide.BUY, 10_000.0, 3.08, 7.70, 1.0,
            "价值投资", "冷静", 4, "主动权益卫星仓位，选长期夏普靠前的产品。"),

        // ---- 场外基金（账外备忘）：勾了「不计入统计」，买卖不动现金 ----
        TradeRow(13, "2026-09-08", "sec_of000001", TradeSide.BUY, 20_000.0, 1.25, 6.25, 1.0),
    )

    /**
     * 出入金（REQ-ACC-03）。合计 **1,300,000**。
     *
     * ⚠️ 入库时 `amount_orig` 存**带符号**值（存入为正、取出为负），
     * `amount_base = 原币 × 汇率` —— 与 `addCashFlow` 的口径一致（那边也是 `signed`）。
     */
    internal val FLOWS: List<FlowRow> = listOf(
        FlowRow("cf_seed_1", "2026-02-02", 500_000.0, "年初建仓资金"),
        FlowRow("cf_seed_2", "2026-03-10", 300_000.0, "一季报后追加"),
        FlowRow("cf_seed_3", "2026-05-06", 200_000.0, "回调加仓资金"),
        FlowRow("cf_seed_4", "2026-07-15", 300_000.0, "年中结余转入"),
    )

    /**
     * 分红送股（REQ-ACC-04）。三笔**现金分红**，正好各覆盖一条分红源通路：
     *  - 贵州茅台 → 东财股票分红接口（A股）
     *  - 沪深300ETF → **天天基金 F10 页**（ETF 走股票接口返回空，见 REQ-DIV-05）
     *  - 汇添富蓝筹（场外基金）→ 同上 F10 页
     *
     * ⚠️ `qty` 是**除权日当天的持仓**，不是买入数量：
     *   茅台 6/20 除权时 6/8 已卖出 40 股 → 持仓 80（不是 120）。
     * 现金分红会**降低持仓成本**（技术说明书 5.2），所以茅台的展示成本均价
     * （1,161.19）低于其加权买入均价（1,180.30）。
     */
    internal val DIVIDENDS: List<DivRow> = listOf(
        DivRow("div_seed_1", "sec_sh600519", "2026-06-20", 80.0, 23.882),
        DivRow("div_seed_2", "sec_sh510300", "2026-08-15", 30_000.0, 0.045),
        DivRow("div_seed_3", "sec_of519066", "2026-09-05", 10_000.0, 0.03),
    )

    /**
     * 演示账本的当前可用现金锚点。
     *
     * ⚠️ `account.cash` 的语义是**买卖净额**（出入金另计，见技术说明书 5.12）：
     *   account.cash = Σ卖出回款 − Σ买入支出（含手续费与**印花税**）= −752,628.63
     * 可用现金 = account.cash + 出入金 1,300,000 + 现金分红 3,560.56 = **550,931.93**
     * （2026-09-30 加印花税：A 股卖出 52,800×万5 = 26.40 + 29,500×万5 = 14.75；
     *   港股买入 77,000 HKD×千1 = 77.00 HKD × 0.86 汇率 = 66.22 → 合计 107.37）
     *
     * 负数不是笔误：买入花掉的钱远多于卖出收回的钱，差额由出入金补足。
     * 改上面任何一笔交易/出入金/分红，这个常量都要跟着重算
     * —— `DemoDataConsistencyTest` 会拦下来。
     */
    internal const val ACCOUNT_CASH = -752_628.63

    /** 期望的可用现金（= [ACCOUNT_CASH] + 出入金 + 账内现金分红）。测试用。 */
    internal const val EXPECTED_AVAILABLE_CASH = 550_931.93

    // ------------------------------------------------------------------ 播种

    fun seedIfEmpty(db: StockNoteDb) {
        val existing = db.securityQueries.countAll().executeAsOne()
        if (existing > 0L) return

        // ⚠️ M14 修复（2026-09-28）：**整体包一个事务**。
        // 演示数据实际有 **1000+ 条单条 INSERT**（此前注释还写着"数据量只有十几行"，早已过时），
        // 逐条自动提交会慢到数百 ms～数秒；更要命的是**中途失败会留下半份账本**
        //（比如只有标的没有交易）—— 用户看到的是"数据乱七八糟"，而不是明确的失败。
        // 包事务后：要么全成、要么全不成，且写入速度提升一个量级。
        //
        // 注：仍运行在启动主线程（`MainActivity` 构造 AppContainer 时）——
        // 这是**有意的**：container 是后续 UI 的前置依赖，挪到后台会引入"UI 先于数据就绪"的竞态。
        // 包事务已把耗时压到可接受范围；真要再优化应改为"先起 UI 再异步播种 + 完成后刷新"，
        // 那属于交互改造，不在本次范围。
        db.transaction {
            seedTags(db)
            seedSecurities(db)
            seedFxRates(db)
            seedAccounts(db)
            seedCashFlows(db)
            seedTrades(db)
            seedDividends(db)
            seedNotes(db)
            seedReviews(db)
            seedTradePlans(db)
            seedWatchlist(db)
            seedFallbackQuotes(db)
            seedDailyCloses(db)
        }
    }

    /**
     * 内置策略标签（名字即 id —— trade.tag_ids CSV 存的就是这些名字）。
     * 与迁移 3.sqm 的回填同源；管理页可增删改。
     */
    private fun seedTags(db: StockNoteDb) {
        val names = listOf("价值投资", "突破", "网格", "趋势", "波段", "打板")
        names.forEach { db.tagQueries.upsert(id = it, name = it, category = "STRATEGY") }
    }

    private fun seedSecurities(db: StockNoteDb) {
        for (r in SECURITIES) {
            db.securityQueries.upsert(
                id = r.id,
                symbol = r.symbol,
                name = r.name,
                market = r.market.name,
                currency = r.currency.code,
                industry = r.industry,
                is_cash_equivalent = if (r.cashEq) 1L else 0L,
                exclude_from_stats = if (r.offBook) 1L else 0L,
                // 每手股数（老周 2026-09-28）：演示数据直接按市场规则写好，便于立刻看到整手校验效果。
                // 真实港股每手由行情刷新从腾讯接口第 61 字段回写覆盖。
                lot_size = com.stocknote.core.calc.LotRule.lotSizeOf(r.market).toLong(),
            )
        }
    }

    /**
     * 汇率（REQ-ACC-05）。生效日取 2026-09-01：`latestPerCurrency` 按 effective_date
     * 取最大的一条，一条即可覆盖全部历史折算（演示数据不追求逐日精确）。
     */
    private fun seedFxRates(db: StockNoteDb) {
        listOf(Currency.HKD to HKD_TO_CNY, Currency.USD to USD_TO_CNY).forEach { (c, rate) ->
            db.fxRateQueries.upsert(
                currency = c.code,
                effective_date = "2026-09-01",
                rate = rate,
                updated_at = "2026-09-01T09:00:00",
            )
        }
    }

    private fun seedAccounts(db: StockNoteDb) {
        db.accountQueries.upsert(
            id = "acc_seed_a",
            name = "A股账户",
            currency = Currency.CNY.code,
            cash = ACCOUNT_CASH,
        )
    }

    private fun seedCashFlows(db: StockNoteDb) {
        FLOWS.forEach {
            db.cashFlowQueries.upsert(
                id = it.id,
                account_id = "acc_seed_a",
                flow_date = it.date,
                type = "DEPOSIT",
                amount_orig = it.amount,
                currency = Currency.CNY.code,
                fx_rate = 1.0,
                amount_base = it.amount,
                note = it.note,
            )
        }
    }

    private fun seedTrades(db: StockNoteDb) {
        // 印花税（REQ-ACC-17）：**按标的市场 + 方向用同一套规则现算**（`StampDuty.dutyOf`），
        // 不手工硬编码每笔 —— 否则规则调整后演示数据会悄悄漂移、与真实录入对不上。
        // securities 在 seedTrades 之前已播种，这里可以直接查市场。
        val marketById = db.securityQueries.selectAll().executeAsList().associate { row ->
            row.id to (Market.entries.firstOrNull { it.name == row.market } ?: Market.A_SHARE)
        }
        TRADES.forEach { r ->
            val stampDuty = com.stocknote.core.calc.StampDuty.dutyOf(
                market = marketById[r.securityId] ?: Market.A_SHARE,
                isBuy = r.side == TradeSide.BUY,
                price = r.price,
                quantity = r.qty,
            )
            db.tradeQueries.upsert(
                id = "seed_tx_${r.seq}",
                security_id = r.securityId,
                side = r.side.name,
                quantity = r.qty,
                price = r.price,
                fee = r.fee,
                stamp_duty = stampDuty,
                trade_date = r.date,
                seq = r.seq,
                note = null,          // 备注走 note 表（1:1），见 seedNotes
                emotion = r.emotion,
                score = r.score,
                tag_ids = r.tags,
                quality = null,
                fx_rate = r.fxRate,
            )
        }
    }

    private fun seedDividends(db: StockNoteDb) {
        DIVIDENDS.forEach {
            db.dividendQueries.upsert(
                id = it.id,
                security_id = it.securityId,
                ex_date = it.exDate,
                type = "CASH",
                quantity = it.qty,
                per_share = it.perShare,
                bonus_shares = 0.0,
                rights_price = 0.0,
                fx_rate = it.fxRate,
            )
        }
    }

    /** 交易笔记（REQ-NOTE-01，1:1 挂 transaction_id）。只给有复盘价值的几笔写。 */
    private fun seedNotes(db: StockNoteDb) {
        data class Row(val id: String, val txId: String, val content: String)

        val rows = listOf(
            Row(
                "note_seed_1", "seed_tx_1",
                "底仓一次买够，不做择时。理由：估值分位足够低时，分批的成本优势远小于踏空风险。",
            ),
            Row(
                "note_seed_2", "seed_tx_2",
                "首笔只建 1/3：白酒是长周期生意，需求验证需要几个季度，留足补仓空间。",
            ),
            Row(
                "note_seed_3", "seed_tx_7",
                "卖出纪律：到达目标区间上沿先减 1/3。事后回溯，若一次性清仓会错过后续 +7%。",
            ),
            Row(
                "note_seed_4", "seed_tx_6",
                "这一笔目前浮亏。复盘点：建仓时对渠道库存的判断偏乐观，止损线设在 70 元，不补仓。",
            ),
        )
        rows.forEach {
            db.noteQueries.upsert(
                id = it.id,
                transaction_id = it.txId,
                content = it.content,
                screenshot = null,
                // SQLDelight 给列名 annotation 生成的参数是 annotation_（避开 Kotlin 关键字）
                annotation_ = null,
            )
        }
    }

    /** 月度复盘（REQ-NOTE-06）。同一周期一篇。 */
    private fun seedReviews(db: StockNoteDb) {
        data class Row(val id: String, val period: String, val content: String)

        val rows = listOf(
            Row(
                "rev_seed_1", "2026-07",
                "本月建仓汇添富蓝筹稳健混合A，作为主动权益的卫星仓位，核心仓维持不动。\n" +
                    "反思：宁德时代在 4 月追突破时买点偏高，虽然目前仍浮盈，但过程不够从容 —— " +
                    "下次这类「消息驱动 + 突破」的买点，宁可等回踩确认。",
            ),
            Row(
                "rev_seed_2", "2026-08",
                "组合本月小幅上行，沪深300ETF 贡献最大。茅台中期分红到账，成本进一步摊薄。\n" +
                    "五粮液受行业需求疲软拖累，是唯一亏损持仓；维持不补仓的决定，等三季度库存数据。",
            ),
        )
        rows.forEach {
            db.reviewQueries.upsert(
                id = it.id,
                period_type = "MONTH",
                period = it.period,
                content = it.content,
                updated_at = "${it.period}-28T20:00:00",
            )
        }
    }

    /**
     * 交易计划（REQ-PLAN-01）。**不是交易**，不进重放、不动持仓与现金。
     * 计划价 = 目标价 × 折扣（[com.stocknote.core.calc.PlanPricing.plannedPriceOf]），已按 2 位小数算好。
     */
    private fun seedTradePlans(db: StockNoteDb) {
        data class Row(
            val id: String, val securityId: String, val side: String,
            val target: Double, val discount: Double?, val planned: Double,
            val qty: Double, val feeRate: Double, val note: String,
            val tags: String, val createdAt: String,
        )

        val rows = listOf(
            Row("plan_seed_1", "sec_sh600519", "SELL", 1350.0, 1.0, 1350.0, 40.0, 2.5,
                "涨到 1,350 再减 40 股，剩余的留给分红复利。", "价值投资", "2026-08-25"),
            Row("plan_seed_2", "sec_sz300750", "BUY", 280.0, 0.9, 252.0, 100.0, 2.5,
                "回踩到 252 附近接回 8 月减掉的那 100 股。", "突破", "2026-08-06"),
            Row("plan_seed_3", "sec_sh510300", "BUY", 4.70, 0.9, 4.23, 10_000.0, 2.5,
                "跌破 4.30 继续按网格加一档。", "网格", "2026-08-18"),
            Row("plan_seed_4", "sec_hk00700", "BUY", 430.0, 1.0, 430.0, 100.0, 5.0,
                "站上 430 确认趋势后加仓，不追高。", "趋势", "2026-09-01"),
        )
        rows.forEach {
            db.tradePlanQueries.insertItem(
                id = it.id,
                security_id = it.securityId,
                side = it.side,
                target_price = it.target,
                discount = it.discount,
                planned_price = it.planned,
                quantity = it.qty,
                fee_rate = it.feeRate,
                note = it.note,
                tag_ids = it.tags,
                // 计划的时间字段统一 yyyy-MM-dd（与 trade_date 同格式，老周 2026-09-21）
                created_at = it.createdAt,
                updated_at = it.createdAt,
                // 演示计划都是进行中（done_at 可空列，老周 2026-09-22）
                done_at = null,
            )
        }
    }

    /** 演示自选（REQ-TOOL-02）。三个分组 + 两张置顶，覆盖分组与置顶两种展示形态。 */
    private fun seedWatchlist(db: StockNoteDb) {
        data class Row(
            val id: String,
            val securityId: String,
            val group: String,
            val order: Long,
            val pinned: Boolean,
            /** 目标价（老周 2026-09-21）：只给一部分行，正好覆盖「已设 / 未设」两种形态 */
            val target: Double? = null,
        )

        val rows = listOf(
            Row("wl_seed_1", "sec_sh600519", "重点", 1, true, 1800.0),
            Row("wl_seed_2", "sec_hk00700", "重点", 2, true, 480.0),
            Row("wl_seed_3", "sec_sz300750", "观察", 3, false, 480.0),
            Row("wl_seed_4", "sec_sz000858", "观察", 4, false),
            Row("wl_seed_5", "sec_sh510300", "未分组", 5, false),
            Row("wl_seed_6", "sec_usAAPL", "未分组", 6, false, 320.0),
            Row("wl_seed_7", "sec_of519066", "未分组", 7, false),
        )
        rows.forEach { r ->
            db.watchlistQueries.insertItem(
                id = r.id,
                security_id = r.securityId,
                group_name = r.group,
                sort_order = r.order,
                pinned = if (r.pinned) 1L else 0L,
                created_at = "2026-02-11",
                // ⚠️ 轻微-7（2026-09-28）：insertItem 补 target_price 后这里同步传入
                target_price = r.target,
            )
            r.target?.let { db.watchlistQueries.updateTargetPrice(it, r.id) }
        }
    }

    /**
     * 兜底行情（source = "manual"）。离线首启时也能看到合理市值，联网后会被真实行情覆盖。
     * 取的是 2026-09-18 的真实收盘 / 净值，所以**首次打开就与真实行情一致**，不会跳变。
     */
    private fun seedFallbackQuotes(db: StockNoteDb) {
        data class Row(val symbol: String, val price: Double, val prevClose: Double)

        val rows = listOf(
            Row("sh600519", Price.MAOTAI, 1266.98),
            Row("sz300750", Price.CATL, 304.30),
            Row("sz000858", Price.WULIANGYE, 69.13),
            Row("sh510300", Price.HS300, 4.532),
            Row("sh511660", Price.MONEY_ETF, 100.006),
            Row("hk00700", Price.TENCENT, 426.00),
            Row("usAAPL", Price.APPLE, 337.00),
            Row("of519066", Price.FUND_519066, 3.2500),
            Row("of000001", Price.FUND_000001, 1.2980),
        )

        rows.forEach {
            db.quoteQueries.upsert(
                symbol = it.symbol,
                price = it.price,
                prev_close = it.prevClose,
                source = "manual",
                updated_at = null,
            )
        }
    }

    /**
     * 逐日收盘价种子（资产曲线的数据底座）。
     *
     * **为什么要播种**：资产曲线读的是 `daily_close` 表（历史逐日收盘），
     * 而不是 `quote` 表（当前价）。不播种的话，只要历史 K 线接口一时不可用
     * （实测腾讯 K 线会返回反爬跳转页），曲线就只剩「场外基金净值」那一条数据源，
     * 窗口被压到 ~20 天、市值只算得出一只标的 —— 演示效果直接垮掉。
     *
     * **数据怎么来的**：**合成**的（不是真实历史）。做法是「首个交易日 → 2026-09-18 真实收盘」
     * 线性插值，再叠一层确定性的正弦微扰，看起来像行情但完全可复现、不依赖网络。
     * 终点**严格等于真实收盘/净值**，所以曲线的最后一点与统计页总资产对得上。
     *
     * 一旦联网拉到真实 K 线，`saveDailyCloses` 会按 (symbol, date) UPSERT 覆盖掉合成值。
     */
    private fun seedDailyCloses(db: StockNoteDb) {
        data class Row(val symbol: String, val start: Double, val end: Double)

        // start = 建仓前后的价格水平（故意让组合整体呈上行走势，演示时好看且有代表性）
        val rows = listOf(
            Row("sh510300", 3.950, Price.HS300),
            Row("sh600519", 1_080.00, Price.MAOTAI),
            Row("hk00700", 340.00, Price.TENCENT),
            Row("sz300750", 240.00, Price.CATL),
            // 五粮液是唯一亏损标的 → 走势设计成下行，与浮亏一致
            Row("sz000858", 82.00, Price.WULIANGYE),
            Row("usAAPL", 250.00, Price.APPLE),
            // 现金等价物几乎不动
            Row("sh511660", 100.000, Price.MONEY_ETF),
            Row("of519066", 2.9500, Price.FUND_519066),
        )

        val startDay = com.stocknote.core.calc.CivilDate.toEpochDay(2026, 2, 11)
        val endDay = com.stocknote.core.calc.CivilDate.toEpochDay(2026, 9, 18)
        val span = (endDay - startDay).toDouble()

        rows.forEachIndexed { si, r ->
            var day = startDay
            while (day <= endDay) {
                val ymd = com.stocknote.core.calc.CivilDate.fromEpochDay(day)
                // 跳过周末（真实交易日还有节假日，演示数据不追求完全一致）
                val dow = ((day + 3) % 7).toInt()   // 0 = 周一
                if (dow < 5) {
                    val t = (day - startDay) / span
                    val base = r.start + (r.end - r.start) * t
                    // 确定性微扰：两个不同频率的正弦，振幅约 1.5%，相位按标的错开
                    val phase = si * 0.7
                    val wobble = 1.0 + 0.015 * kotlin.math.sin(t * 9.0 + phase) +
                        0.008 * kotlin.math.sin(t * 31.0 + phase * 2)
                    val price = if (day == endDay) r.end else base * wobble
                    db.fxRateQueries.upsertClose(
                        symbol = r.symbol,
                        date = com.stocknote.core.calc.CivilDate.isoOf(ymd.year, ymd.month, ymd.day),
                        close = kotlin.math.round(price * 10000.0) / 10000.0,
                    )
                }
                day++
            }
        }
    }
}
