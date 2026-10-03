package com.stocknote.core.model

/** 市场类型。港股通按 A 股账户管理，仅币种不同。 */
enum class Market(val label: String) {
    A_SHARE("A股"),
    HK("港股"),
    US("美股"),
    ETF("ETF"),
    FUND("基金"),
    ;

    /**
     * 数量单位（老周 2026-09-20 统一口径）。
     *
     * 股票与场内 ETF 按**股**，**场外基金按份**（申购赎回都是按份额）。
     * 此前各展示处各写一份字面量，结果「持仓明细」行硬编码「股」、
     * 「账外备忘」行硬编码「份」、现金等价物那行又写成「份」（货币 ETF 应该是股）
     * —— 同一页面三处口径打架。所有展示处一律走这里，别再写字面量。
     */
    val quantityUnit: String
        get() = if (this == FUND) "份" else "股"

    /**
     * 该市场的缺省计价币种（老周 2026-09-21 统一口径）：港股 HKD / 美股 USD，其余人民币。
     * 用途：CSV 导入时文件没给币种、或新建标的时按市场取缺省。
     */
    val defaultCurrency: Currency
        get() = when (this) {
            HK -> Currency.HKD
            US -> Currency.USD
            else -> Currency.CNY
        }
}

enum class Currency(val code: String, val symbol: String) {
    CNY("CNY", "¥"),
    HKD("HKD", "HK$"),
    USD("USD", "$"),
}

enum class TradeSide(val label: String) {
    BUY("买入"),
    SELL("卖出"),

    /**
     * **利润转增资本**（REQ-ACC-16，老周 2026-09-20）：把该标的盈利中的一笔金额折入本金
     * （抬高成本价），使该股总盈亏下降同等金额。这是**账内重分类**，不是成交：
     *
     *  - 不产生现金流动，因此**不进现金、不进出入金、不影响 XIRR**（见 [com.stocknote.core.calc.CashImpact]）；
     *  - 存储约定：金额 X 存在 [Transaction.price] 列（原币），`quantity` 恒为 0、`fee` 恒为 0；
     *    ⚠️ 因此**不能**用 `price × quantity` 取金额，凡涉金额处都要显式按 side 分支；
     *  - 与买卖共用同一条全量重放通道 → **删除该记录即完全撤销**，无需任何迁移。
     */
    CAPITALIZE("转增资本"),
    ;

    /** 是否是真正的买卖成交（[CAPITALIZE] 只是账务调整，不是交易）。 */
    val isTrading: Boolean get() = this == BUY || this == SELL
}

/**
 * 标的。symbol 为交易所原始代码（如 sh600519 / 00700 / AAPL）。
 * 现金等价物（如 511660 货币ETF）也建为标的，但在账务口径上归入「现金」子类、不计入持仓。
 */
data class Security(
    val id: String,
    val symbol: String,
    val name: String,
    val market: Market,
    val currency: Currency,
    val industry: String? = null,
    val isCashEquivalent: Boolean = false,
    /**
     * **不计入统计与分析**（老周 2026-09-20，为「场外基金」而加）。
     *
     * 口径 = **完全账外（纯备忘）**：
     *  - 买入**不扣现金**、卖出**不加现金**（不碰现金账户、不参与「现金不足」校验）；
     *  - 该标的**市值不进总资产**、不进资产曲线、不进收益率 / 胜率 / 配置环；
     *  - 但它自己的**份额 / 成本 / 净值 / 浮盈**照常按 [com.stocknote.core.calc.PositionCalculator]
     *    重放算出来，在「账外备忘」区单独展示 —— 也就是说它是一本**平行的备忘账**。
     *
     * 场外基金（[Market.FUND]）缺省为 true（老周：默认不计入），用户可在记一笔时打开开关。
     * 与 [isCashEquivalent] 的区别：现金等价物是**账内**的现金形态（进总资产、算现金），
     * 而本标记是**账外**（不进总资产、不算现金）。
     */
    val excludeFromStats: Boolean = false,
    /**
     * **每手股数**（老周 2026-09-28）。
     *
     * 用途：数量录入的**整手校验**（见 `LotRule`）。取值来源：
     *  - A股 / ETF：恒 `100`；
     *  - **港股：真实值**，自腾讯行情 `qt.gtimg.cn/q=hkXXXXX` 的**第 61 个 `~` 分隔字段**取得
     *    （不固定：腾讯控股 100、小米 200、中国移动 500、建设银行 1000）；
     *  - 美股 / 场外基金：`1`（没有「手」的概念）。
     *
     * `null` = **尚未取到**（旧数据、拉取失败）→ 由 `LotRule` 按市场规则兜底，不影响功能。
     */
    val lotSize: Double? = null,
)

/**
 * 交易流水 —— 账本唯一事实源。
 * Position 一律由 Transaction 全量重放推导，不允许直接写库（见技术说明书 2.0 第 5.2 节）。
 */
data class Transaction(
    val id: String,
    val securityId: String,
    val side: TradeSide,
    val quantity: Double,
    /**
     * 成交价（原币）。**例外**：`side = CAPITALIZE` 时本列承载**转增金额 X**（原币），
     * 此时 [quantity] 恒为 0 —— 所以取金额要用 [amountOf] 而不是 `price * quantity`。
     */
    val price: Double,
    val fee: Double = 0.0,
    /** ISO-8601 日期，形如 2026-09-13 */
    val tradeDate: String,
    /** 同一交易日内多笔的稳定排序键 */
    val seq: Long,
    val note: String? = null,
    /** 交易时情绪（REQ-NOTE-02）：恐惧/贪婪/冷静/犹豫/自信 */
    val emotion: String? = null,
    /** 执行评分 1-5（REQ-NOTE-05），null = 未评 */
    val score: Int? = null,
    /** 策略标签名列表（REQ-NOTE-04），CSV 存于 trade.tag_ids */
    val tags: List<String> = emptyList(),
    /** 交易质量评级（REQ-ANA-04）：神操作/昏招，null = 未评 */
    val quality: TradeQuality? = null,
    /**
     * 成交日汇率（**原币 → CNY**，REQ-ACC-15 老周 2026-09-19）。
     *
     * null 的两种含义：① A 股等本位币交易（无需折算）；② 本列上线前的历史数据。
     * null 时折算回退「最新汇率」（**不追溯**），所以老账本数字不会被静默改写。
     */
    val fxRate: Double? = null,
    /**
     * **印花税**（原币金额，REQ-ACC-17 老周 2026-09-30）。
     *
     * 与 [fee] 同口径存**金额**（不是税率）；由 `StampDuty.dutyOf` 按市场 + 方向算出，
     * 界面**只展示不可改**。它**计入现金**（买入多付、卖出少收），也**计入成本/盈亏**
     * （与手续费同等对待），否则账本与券商账单对不上。
     *
     * ⚠️ 刻意排在**最后一个参数**：本列上线前的历史数据默认 0，
     * 也避免给既有的**位置参数**构造（测试里较多）造成错位。
     */
    val stampDuty: Double = 0.0,
) {
    /**
     * 本笔的**金额**（原币）——统一入口，避免各处再写 `price * quantity`
     * 而在 `CAPITALIZE`（quantity = 0）上算出 0：
     *  - BUY / SELL：价 × 量（不含手续费，手续费另计）
     *  - CAPITALIZE：金额即 [price] 本身
     */
    val amountOf: Double
        get() = if (side == TradeSide.CAPITALIZE) price else price * quantity
}

/**
 * 交易质量评级（REQ-ANA-04）。存库用 name（GREAT/BLUNDER），展示用 label。
 */
enum class TradeQuality(val label: String) {
    /** 神操作：事后看特别好的决策 */
    GREAT("神操作"),

    /** 昏招：违背自己规则、情绪上头的决策 */
    BLUNDER("昏招"),
}

/**
 * 自选项（REQ-TOOL-02）。移除仅取消关注，不级联交易/持仓/笔记。
 * groupName 仅影响归类展示；pinned 置顶固定最前。
 */
data class WatchItem(
    val id: String,
    val securityId: String,
    val groupName: String,
    val sortOrder: Long,
    val pinned: Boolean,
    val createdAt: String,
    /**
     * 我给这只票定的**目标价**（标的币种）；null = 还没设（老周 2026-09-21）。
     *
     * 与交易计划共用：计划表单可取用/回写（见 PlanEditHolder）。
     */
    val targetPrice: Double? = null,
)

/**
 * 月/季复盘总结（REQ-NOTE-06）。同一周期一篇。
 * @param periodType "MONTH" | "QUARTER"
 * @param period     "2026-08" | "2026Q3"
 */
data class Review(
    val id: String,
    val periodType: String,
    val period: String,
    val content: String,
    val updatedAt: String,
)

/**
 * 策略标签定义（REQ-NOTE-04 / 标签管理页）。
 * 刻意让 **id = 标签名**：trade.tag_ids CSV 里存的就是名字，
 * 重命名 = 换主键 + 批量改 CSV，删除 = 清 CSV，无需历史数据迁移。
 */
data class TagDef(
    val id: String,
    val name: String,
    val category: String = "STRATEGY",
)

/**
 * 汇率记录（REQ-ACC-05 / 5.7，M4 数据真值）。
 * rate = 1 单位 [currency] 兑 CNY；同一币种可有多条不同生效日，计算取最新。
 */
data class FxRateRecord(
    val currency: String,
    val effectiveDate: String,
    val rate: Double,
    val updatedAt: String,
)

/**
 * 持仓 —— 由 [Transaction] 按 securityId 聚合而来的派生视图，不是事实源。
 * avgCost 为移动加权成本均价（含买入费用）。
 */
data class Position(
    val securityId: String,
    val symbol: String,
    val name: String,
    val market: Market,
    val currency: Currency,
    val quantity: Double,
    val avgCost: Double,
    val costAmount: Double,
    val realizedPnl: Double,
    val marketPrice: Double? = null,
    /**
     * 昨收（由行情快照带出，老周 2026-09-23）：统计页持仓 TOP5 要显示**当日涨跌%**。
     * 仅取自行情源；手工价 / 无昨收（停牌、场外基金净值）= null → 不显示涨跌，不编数。
     */
    val prevClose: Double? = null,
    val tradeCount: Int = 0,
    /** 现金分红累计（原币）。分红送股会在重放时调整成本，现金部分在此累计（REQ-ACC-04） */
    val dividendCash: Double = 0.0,
    /**
     * 本位币成本（REQ-ACC-15 路径 1，老周 2026-09-19）：
     * 买入按**各笔成交日汇率**累加、卖出等比例扣减；0.0 = 未计算（调用方按「最新汇率 × costAmount」兜底）。
     *
     * 与 [marketValue]（原币）× 最新汇率 相减，得到**含汇兑损益**的折算盈亏。
     */
    val costInBase: Double = 0.0,
    /**
     * **已实现盈亏（本位币）**（REQ-ACC-15 路径 1）：卖出净额按**卖出日汇率**折本位币，
     * 减去等比例扣减的本位币成本 → 同样**含汇兑损益**。
     * 0.0 = 未计算（调用方按「最新汇率 × realizedPnl」兜底）。
     *
     * 与 [costInBase] 一起，保证同一只票的"持仓浮盈"与"已实现盈亏"用同一套汇率口径。
     */
    val realizedPnlInBase: Double = 0.0,
) {
    /** 无行情时退化为成本价，避免 UI 出现 0 市值 */
    val marketValue: Double
        get() = marketPrice?.let { it * quantity } ?: costAmount

    val unrealizedPnl: Double
        get() = marketPrice?.let { (it - avgCost) * quantity } ?: 0.0

    /**
     * 当日涨跌幅（老周 2026-09-23）：(现价 − 昨收) / 昨收。
     * 缺现价或昨收（或昨收为 0）→ null，UI 不显示——不拿成本价冒充昨收。
     */
    val dayChangeRatio: Double?
        get() = prevClose?.takeIf { it != 0.0 }?.let { prev ->
            marketPrice?.let { (it - prev) / prev }
        }

    val totalPnl: Double
        get() = realizedPnl + unrealizedPnl

    val isOpen: Boolean
        get() = quantity > EPS

    companion object {
        const val EPS = 1e-9
    }
}

/** 行情快照。source 记录来源（tencent / sina / manual），便于排查与降级展示。 */
data class Quote(
    val symbol: String,
    val price: Double,
    val prevClose: Double? = null,
    val source: String,
    val updatedAtEpochMs: Long? = null,
    /**
     * 报价日期（P3-36，2026-10-02）：这条行情所属的**交易日**（交易所本地，yyyy-MM-dd）。
     *
     * 来源：腾讯 `qt[30]`（报价时间 yyyyMMddHHmmss，实测 2026-10-02 请求 A 股返回 09-30 ——
     * 休市时行情快照停在最后一个交易日）；基金用净值日期；K 线兜底时用最后一根 K 线日期。
     *
     * 用途：`loadSnapshot` 把「报价日期 ≠ 今天」的标的从**当日盈亏**里剔除（市值照算），
     * 否则休市日会把「最近交易日的涨跌」当成"当日"（实测 10-02 统计页比日历多出
     * 3,807 ≈ 09-30 A 股的涨跌）。null = 源没给（手工价 / 旧缓存 / 解析失败）→ 不设防。
     */
    val quoteDate: String? = null,
) {
    val changeRatio: Double?
        get() = prevClose?.takeIf { it != 0.0 }?.let { (price - it) / it }
}

/**
 * 资产总览快照（统计页消费）。跨币种统一折算到 [baseCurrency] 后汇总。
 * @param dayPnl 当日盈亏（技术说明书 5.11）：Σ(现价 − 昨收) × 持仓，缺昨收的标的计 0
 */
data class PortfolioSnapshot(
    val positions: List<Position>,
    /** 可用现金（账户期初 + 出入金净额 + 现金分红，不含现金等价物） */
    val availableCash: Double,
    /** 现金合计 = 可用现金 + 现金等价物市值 */
    val cashAmount: Double,
    val baseCurrency: Currency,
    val dayPnl: Double = 0.0,
    /** 现金等价物明细（如 511660 货币 ETF）：归「现金」子类、不计持仓明细（老周反馈 2026-09-14） */
    val cashEquivalentPositions: List<Position> = emptyList(),
    /**
     * 最新汇率（fx_rate 表，币种 code -> 兑 CNY）—— 老周 2026-09-18 对账发现：
     * 此前折算走 FxTable **固定表**（HKD=0.90），而页面显示的折算汇率是 fx_rate 最新值（0.8534），
     * 两套来源导致港股持仓市值 / 总资产与显示汇率对不上（差 1,076.46）。
     * 现统一：折算优先 fx_rate 表；空表时 convertWith 自动退回固定表兜底。
     */
    val fxRates: Map<String, Double> = emptyMap(),
    /**
     * **账外备忘持仓**（老周 2026-09-20）：勾了「不计入统计与分析」的场外基金。
     *
     * 关键口径：这些持仓**不进** [positions]，因此**不进** [marketValueTotal] /
     * [totalAsset] / [totalPnl] / 配置环 —— 它们是与主账本平行的**备忘账**，
     * 只是各自能算出份额 / 成本 / 净值 / 浮盈供查阅。
     */
    val memoPositions: List<Position> = emptyList(),
    /**
     * **现金分红累计（本位币；H4 修复 2026-09-27）**：各持仓 [Position.dividendCash] 折算本位币后的合计。
     *
     * ⚠️ `Position.dividendCash` 是**原币**（= 每股分红 × 股数）。此前页面直接 `sumOf` 累加，
     * 港股 1000 HKD 分红被当成 1000 CNY（可用现金与总资产虚高）——现在统一在此折本位币，页面直接用本字段。
     */
    val dividendCashTotal: Double = 0.0,
) {
    private fun Position.inBase(): Double = FxTable.convertWith(marketValue, currency, baseCurrency, fxRates)

    /**
     * 成本（本位币）：**优先用重放结果 [costInBase]**（按各笔成交日汇率累计，REQ-ACC-15 路径 1）；
     * 为 0（无持仓 / 未计算）时退回「最新汇率 × 原币成本」——老数据因此**数字不变**。
     */
    private fun Position.costBaseAmount(): Double =
        if (costInBase > 0.0) costInBase
        else FxTable.convertWith(costAmount, currency, baseCurrency, fxRates)

    /**
     * 持仓浮盈（本位币）= 市值本位币 − 成本本位币，因此**含汇兑损益**（成本用成交日汇率、
     * 市值用最新汇率）。无行情时与 [unrealizedPnl] 同口径返回 0，不假装有盈亏。
     */
    private fun Position.unrealizedBaseAmount(): Double =
        if (marketPrice == null) 0.0 else inBase() - costBaseAmount()

    /**
     * 已实现盈亏（本位币）：优先用重放结果 [realizedPnlInBase]（含汇兑损益）；
     * 为 0（未卖过 / 未计算）时退回「最新汇率 × 原币已实现」。
     */
    private fun Position.realizedBaseAmount(): Double =
        if (realizedPnlInBase != 0.0) realizedPnlInBase
        else FxTable.convertWith(realizedPnl, currency, baseCurrency, fxRates)

    /** 供 UI（占比图 / 现金等价物行）使用的折算 —— 与 [marketValueTotal] 完全同口径 */
    fun positionMarketValueInBase(p: Position): Double =
        FxTable.convertWith(p.marketValue, p.currency, baseCurrency, fxRates)

    /** 持仓市值合计（已折算本位币） */
    val marketValueTotal: Double get() = positions.sumOf { it.inBase() }
    val costTotal: Double get() = positions.sumOf { it.costBaseAmount() }
    val unrealizedPnlTotal: Double get() = positions.sumOf { it.unrealizedBaseAmount() }
    val realizedPnlTotal: Double get() = positions.sumOf { it.realizedBaseAmount() }

    /** 总资产 = 可用现金（含现金等价物） + 各持仓市值，均为本位币 */
    val totalAsset: Double get() = cashAmount + marketValueTotal

    val totalPnl: Double get() = unrealizedPnlTotal + realizedPnlTotal

    /** 累计收益率（简单口径，非年化）= 总盈亏 / 累计投入成本 */
    val cumulativeReturn: Double?
        get() = if (costTotal > Position.EPS) totalPnl / costTotal else null

    // ---- 账外备忘（「不计入统计与分析」的场外基金）----
    // 这些数字**只用于备忘区展示**，绝不参与上面的总资产 / 总盈亏 / 配置环。

    /** 备忘持仓市值合计（本位币） */
    val memoMarketValueTotal: Double get() = memoPositions.sumOf { it.inBase() }

    /** 备忘持仓浮盈合计（本位币，含汇兑损益） */
    val memoUnrealizedPnlTotal: Double get() = memoPositions.sumOf { it.unrealizedBaseAmount() }

    val hasMemoPositions: Boolean get() = memoPositions.isNotEmpty()
}

/**
 * 交易计划（REQ-PLAN-01，老周 2026-09-19 定稿）。
 *
 * ⚠️ 关键边界：计划**不是交易** —— 不写入 `transaction`、不参与全量重放，
 * 不影响持仓市值 / 累计盈亏 / XIRR 现金流。这只是一张独立的「意向表」，仅供查阅与提醒。
 * 记录的是「我打算怎么做」，而 Transaction 记录的是「已经发生了什么」。
 */
data class TradePlan(
    val id: String,
    val securityId: String,
    /** 计划买入 / 计划卖出 */
    val side: TradeSide,
    /** 目标价（必填，须 > 0）：我给这只标的的估值 / 目标位 */
    val targetPrice: Double,
    /** 折扣：null = 未选（原型默认态）；取值 0.4 / 0.5 … 1.2 */
    val discount: Double?,
    /** 计划价 = 目标价 × 折扣（2 位小数），可手动覆盖 */
    val plannedPrice: Double,
    val quantity: Double,
    /** 手续费率，单位**万分位**（2.5 = 万分之 2.5，与设置页同口径） */
    val feeRate: Double = 0.0,
    /** 计划理由 / 备注：**非必填**（区别于记一笔的必填拦截） */
    val note: String = "",
    /** 策略标签（记忆一笔一样：存的是标签名 CSV） */
    val tags: List<String> = emptyList(),
    /**
     * 创建 / 更新时间，**统一 yyyy-MM-dd**（老周 2026-09-21，与 `trade_date` 同格式）。
     *
     * 此前是纪元毫秒字符串，与日期混排会串（`"2026-09-21" > "1758…"`），
     * 也会让 CSV 的「创建日期」列导出后再导入被判非法。历史值由迁移 11.sqm 统一转换。
     */
    val createdAt: String = "",
    val updatedAt: String = "",
    /**
     * 完成标记（老周 2026-09-22）：null = 进行中；yyyy-MM-dd = 标记完成那天。
     * 入口只有计划列表行尾的圆点（编辑页只透传不清掉）；完成的计划不进
     * 「进行中计划」条数与「计划金额合计」，列表里排最后并置灰。
     */
    val doneAt: String? = null,
) {
    /** 是否已完成（[doneAt] 非空）—— UI 与统计口径统一从这里判断。 */
    val isDone: Boolean get() = doneAt != null
    /**
     * 计划金额 = 价 × 量 ± 手续费：买入加、卖出减。
     * 手续费口径与 `TradeForms.feeAmountOf` **同源**（费率万分位 + **佣金下限：不足 5 元按 5 元**），
     * 避免"计划金额"与"记一笔总价"两处规则分叉。
     */
    val planAmount: Double
        get() {
            val base = plannedPrice * quantity
            val fee = com.stocknote.core.calc.TradeForms.applyMinFee(
                rawFee = base * feeRate / 10_000.0,
                rate = feeRate,
            )
            return if (side == TradeSide.SELL) base - fee else base + fee
        }
}
