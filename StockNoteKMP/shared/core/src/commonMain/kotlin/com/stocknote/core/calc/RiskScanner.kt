package com.stocknote.core.calc

/**
 * 个股风险扫雷 —— 指标定义与评分（**纯函数 · 零网络依赖 · 100% 可单测**）。
 *
 * 移植自《东财F10个股风险扫雷技术方案 v1.1》配套脚本 `saolei.py`：
 * **4 大维度 · 24 个风险项 · 32 个量化指标**，每项输出 ✅正常 / ⚠️预警 / 🔴高危，
 * 维度得分 = 该维度得分和 ÷ (项数 × 2) × 100，总分按维度权重加权（消息面 .30 / 基本面 .35 /
 * 股东治理 .20 / 交易资金 .15），最后映射为五档风险等级。
 *
 * ⚠️ 阈值集中在 [Th] 里，与脚本一致，可按行业/板块调整；**改动阈值等于改口径，需同步文档**。
 * ⚠️ 缺失值一律视为"正常"（与脚本 `grade(null) -> NORMAL` 一致），**不猜、不编**。
 */
object RiskScanner {

    /** 单项灯色 */
    enum class Light { NORMAL, WARN, DANGER }

    /** 仪表标记：正常 ✅ / 预警 ⚠️ / 高危 🔴 */
    fun mark(light: Light): String = when (light) {
        Light.NORMAL -> "✅"
        Light.WARN -> "⚠️"
        Light.DANGER -> "🔴"
    }

    /**
     * 维度与**默认**权重（默认 = A 股权重）。顺序即展示顺序。
     * `VALUATION` / `FUNDS` 是**港股专用**维度（估值交易 / 资金面）——港股 4 维度与 A 股不同：
     * 港股无 ST / 涨跌停 / 面值退市 / 质押 / 解禁 / 龙虎榜 / 融资融券，故这些项全部屏蔽。
     */
    enum class Dim(val label: String, val weight: Double) {
        NEWS("消息面", 0.30),
        FUNDAMENTAL("基本面", 0.35),
        GOVERNANCE("股东治理", 0.20),
        CAPITAL("交易资金", 0.15),
        // —— 港股专用 ——
        VALUATION("估值交易", 0.20),
        FUNDS("资金面", 0.15),
    }

    /** A 股权重（4 维度） */
    val A_WEIGHTS: Map<Dim, Double> = mapOf(
        Dim.NEWS to 0.30, Dim.FUNDAMENTAL to 0.35, Dim.GOVERNANCE to 0.20, Dim.CAPITAL to 0.15,
    )

    /** 港股权重（4 维度；比 A 股更重基本面、更轻消息面） */
    val HK_WEIGHTS: Map<Dim, Double> = mapOf(
        Dim.NEWS to 0.25, Dim.FUNDAMENTAL to 0.40, Dim.VALUATION to 0.20, Dim.FUNDS to 0.15,
    )

    /** 一条风险项 */
    data class Item(
        val id: String,
        val dim: Dim,
        val name: String,
        /** 展示值（已格式化的文本，如 "16.75%" / "922.78亿" / "否"） */
        val value: String,
        val light: Light,
        /** 补充说明（如 "基于最新公告关键词" / "诉讼 2 次"） */
        val note: String = "",
    )

    /** 扫描结果 */
    data class Result(
        val items: List<Item>,
        val dimScores: Map<Dim, Double>,
        val score: Double,
        val level: String,
        /** 本次使用的维度权重（A 股 / 港股不同，界面据此标注，别用 [Dim.weight] 的默认值） */
        val weights: Map<Dim, Double> = A_WEIGHTS,
    ) {
        val dangers: Int get() = items.count { it.light == Light.DANGER }
        val warns: Int get() = items.count { it.light == Light.WARN }
    }

    /** 阈值表（与 saolei.py 的 `TH` 一一对应；括号内为 (预警阈值, 高危阈值)） */
    object Th {
        val debtRatio = 70.0 to 85.0            // 资产负债率 %
        val goodwillRatio = 30.0 to 50.0        // 商誉/净资产 %
        val pledgeRatio = 50.0 to 70.0          // 股权质押比例 %
        val unlockMktCap = 10.0 to 20.0         // 解禁市值/总市值 %
        val holderChange = 20.0 to 50.0         // 股东户数环比 %
        val pricePar = 1.5 to 1.0               // 最新价（**反向**：越低越危险）
        val mktcapYi = 10.0 to 5.0              // 总市值 亿（**反向**）
        val discount = 10.0 to 20.0             // 大宗交易折价 %
        val netSellPct = 1.0 to 3.0             // 高管净减持 %
        val lawsuitRatio = 10.0 to 30.0         // 涉诉金额/净资产 %
        val guaranteeRatio = 50.0 to 100.0      // 对外担保/净资产 %
        // —— 港股专用（见方案 v1.2 §6 / saolei.py 的 TH）——
        val hkPe = 60.0 to 100.0                // 港股市盈率 PE(TTM)
        val hkPb = 10.0 to 20.0                 // 港股市净率 PB(TTM)
        val hkMktcapYi = 50.0 to 10.0           // 总市值（亿港元，**反向**）
        val hkPrice = 1.0 to 0.5                // 最新价（港元，**反向**；仙股风险）
        val southChg = -5.0 to -10.0            // 南向持股近 20 日变化 %（**下降为负**，取负后按"越大越危险"判）
    }

    /** 越大越危险：≥ high 高危、≥ warn 预警 */
    fun grade(v: Double?, warn: Double, high: Double): Light = when {
        v == null -> Light.NORMAL
        v >= high -> Light.DANGER
        v >= warn -> Light.WARN
        else -> Light.NORMAL
    }

    /** 越小越危险（价格、市值）：≤ high 高危、≤ warn 预警 */
    fun gradeLow(v: Double?, warn: Double, high: Double): Light = when {
        v == null -> Light.NORMAL
        v <= high -> Light.DANGER
        v <= warn -> Light.WARN
        else -> Light.NORMAL
    }

    /** 风险等级（五档） */
    fun levelOf(score: Double): String = when {
        score < 20 -> "低风险"
        score < 40 -> "中低风险"
        score < 60 -> "中高风险"
        score < 80 -> "高风险"
        else -> "极高风险"
    }

    /**
     * 维度得分 + 加权总分。
     * @param weights 按市场传入（A 股 [A_WEIGHTS] / 港股 [HK_WEIGHTS]）；
     *   只统计**本市场实际用到的**维度（没出现的维度不计分，避免被 0 分拉低总分）
     */
    fun aggregate(items: List<Item>, weights: Map<Dim, Double> = A_WEIGHTS): Result {
        val dimScores = LinkedHashMap<Dim, Double>()
        Dim.entries.filter { d -> items.any { it.dim == d } }.forEach { d ->
            val group = items.filter { it.dim == d }
            val sum = group.sumOf { lightScore(it.light) }
            dimScores[d] = round1(sum.toDouble() / (group.size * 2) * 100)
        }
        val total = round1(dimScores.entries.sumOf { (d, s) -> s * (weights[d] ?: 0.0) })
        return Result(items, dimScores, total, levelOf(total), weights)
    }

    private fun lightScore(l: Light): Int = when (l) {
        Light.NORMAL -> 0
        Light.WARN -> 1
        Light.DANGER -> 2
    }

    private fun round1(v: Double): Double = kotlin.math.round(v * 10) / 10.0

    // ---------------------------------------------------------------- 扫描用原始数据

    /**
     * 扫描所需的**原始数据**（由数据层抓取、组装；任何一项取不到就给 null，
     * 对应风险项会按"正常"处理并在值里显示「—」，**不猜不编**）。
     */
    data class Input(
        val displayName: String,
        // —— 消息面 ——
        /** 最新公告标题（用于关键词扫描：立案/处罚/问询函/事务所变更/高管变动/资本运作失败） */
        val announcements: List<String> = emptyList(),
        /** 最新审计意见类型（如「标准无保留意见」） */
        val auditOpinion: String? = null,
        // —— 巨潮「诉讼 / 担保」：来自**本地快照表**（cninfo_risk），
        //    由设置页「诉讼担保查询」手工同步一次，扫雷只读本地（快、离线可用）
        /** 诉讼金额（万元，巨潮原始值）；占比需 ÷ 归母净资产，由 [build] 计算 */
        val sueAmountWan: Double? = null,
        val sueCount: Int? = null,
        /** 对外担保/净资产（%，巨潮直接给，无需再算） */
        val gteRatio: Double? = null,
        val gteCount: Int? = null,
        /** 股权质押/冻结比例 % */
        val pledgeRatio: Double? = null,
        // —— 基本面 ——
        val deductedProfit: Double? = null,
        val deductedProfitLast: Double? = null,
        val revenueYoy: Double? = null,
        /** 经营现金流/营收 %（接口给的是小数比，数据层已 ×100） */
        val ocfToRevenue: Double? = null,
        val debtRatio: Double? = null,
        val goodwill: Double? = null,
        val netAssets: Double? = null,
        /** 应收账款/营收 %（已 ×100） */
        val arToRevenue: Double? = null,
        val arYoy: Double? = null,
        val shortLoan: Double? = null,
        val monetaryFunds: Double? = null,
        val currentRatio: Double? = null,
        val quickRatio: Double? = null,
        val grossMargin: Double? = null,
        val grossMarginLast: Double? = null,
        val roe: Double? = null,
        // —— 股东治理 ——
        val netSellPct: Double? = null,
        val holderChangeRatio: Double? = null,
        val unlockMktCapRatio: Double? = null,
        val unlockFreeRatio: Double? = null,
        // —— 交易资金 ——
        val price: Double? = null,
        val marketCap: Double? = null,
        val blockPremium: Double? = null,
        val lhbNet: Double? = null,
        val marginBalance: Double? = null,
    )

    // ---------------------------------------------------------------- 港股（20 项 / 4 维度）

    /**
     * 港股扫描所需的原始数据（来源见《东财F10个股风险扫雷技术方案 v1.2》§2.5）：
     *  - 东财**港股 F10**：`RPT_HKF10_FN_MAININDICATOR`（主要指标）/ `RPT_HKF10_FN_BALANCE_PC`（资产科目）；
     *  - **腾讯自选股港股公告**（`type=0`，关键词扫标题）；
     *  - **南向资金** `RPT_MUTUAL_HOLD_DET`、**回购** `RPT_HK_BUYBACK`（港股特有）。
     *
     * 港股**没有** ST / 涨跌停 / 面值退市 / 质押 / 解禁 / 龙虎榜 / 融资融券等口径 → 这些项全部屏蔽。
     * 取不到的字段一律 null → 该项按"正常"处理并显示「—」，**不猜不编**。
     */
    data class HkInput(
        val displayName: String,
        // —— 消息面（公告标题关键词）——
        val announcements: List<String> = emptyList(),
        // —— 基本面（净利润与毛利率需要**上期**做对比）——
        val holderProfit: Double? = null,
        val holderProfitPrev: Double? = null,
        val revenueYoy: Double? = null,
        /** 经营现金流/营收 %（`OCF_SALES`，接口直接给百分数） */
        val ocfToRevenue: Double? = null,
        val debtRatio: Double? = null,
        val currentRatio: Double? = null,
        val grossMargin: Double? = null,
        val grossMarginPrev: Double? = null,
        val roe: Double? = null,
        val revenue: Double? = null,
        val accountsReceivable: Double? = null,
        // —— 估值交易 ——
        val pe: Double? = null,
        val pb: Double? = null,
        val marketCap: Double? = null,
        val price: Double? = null,
        // —— 资金面 ——
        /** 南向资金持股（近 20 日变化 %；下降为负 → 资金撤离） */
        val southboundChangePct: Double? = null,
        /** 近 30 日回购笔数 / 股数（回购 = 管理层信心，展示项） */
        val buybackCount: Int? = null,
        val buybackShares: Double? = null,
    )

    // ---------------------------------------------------------------- 扫描主逻辑

    /**
     * 执行 **24 项 / 32 指标** 扫描（纯函数）。
     * 逐项判据与阈值与 `saolei.py` 的 `scan()` **完全一致**（含每项灯色分支）。
     */
    fun build(input: Input): Result {
        val items = ArrayList<Item>(24)
        fun add(id: String, dim: Dim, name: String, value: String, light: Light, note: String = "") {
            items += Item(id, dim, name, value, light, note)
        }

        // ---- 工具 ----
        fun fmt2(v: Double) = (kotlin.math.round(v * 100) / 100.0).toString()
        fun pct(v: Double?) = if (v == null) "—" else fmt2(v) + "%"
        fun yi(v: Double?) = if (v == null) "—" else fmt2(kotlin.math.round(v / 1e6) / 100.0) + "亿"
        fun wan(v: Double?) = if (v == null) "—" else fmt2(kotlin.math.round(v / 1e4) / 1.0) + "万"

        val titles = input.announcements.joinToString(" ")
        fun kw(vararg ws: String) = ws.count { titles.contains(it) }

        // ===== 维度一：消息面（8 项）=====
        val isSt = input.displayName.uppercase().contains("ST")
        add("M1", Dim.NEWS, "退市风险警示(ST/*ST)", if (isSt) "是" else "否",
            if (isSt) Light.DANGER else Light.NORMAL)

        val n2 = kw("立案", "处罚", "警示函")
        add("M2", Dim.NEWS, "立案调查与行政处罚(近期公告)", "$n2 次",
            if (n2 >= 1) Light.DANGER else Light.NORMAL, "基于最新公告关键词")

        val n3 = kw("问询函", "关注函")
        add("M3", Dim.NEWS, "监管函件(问询/关注函)", "$n3 次",
            if (n3 >= 3) Light.DANGER else if (n3 >= 2) Light.WARN else Light.NORMAL)

        val op = input.auditOpinion?.trim().orEmpty()
        val nonStd = op.isNotEmpty() && !op.contains("标准") && !op.contains("无保留")
        add("M4", Dim.NEWS, "审计意见类型", op.ifEmpty { "标准无保留" },
            if (nonStd) Light.DANGER else Light.NORMAL)

        val n5 = kw("会计师事务所", "变更审计")
        add("M5", Dim.NEWS, "会计师事务所变更", "$n5 次",
            if (n5 >= 2) Light.DANGER else if (n5 == 1) Light.WARN else Light.NORMAL)

        val n6 = kw("辞职", "离任", "董事长", "总经理")
        add("M6", Dim.NEWS, "关键高管/实控人变动", "$n6 次",
            if (n6 >= 2) Light.DANGER else if (n6 == 1) Light.WARN else Light.NORMAL)

        val n7 = kw("终止", "撤回", "失败")
        add("M7", Dim.NEWS, "重大资本运作失败", "$n7 次",
            if (n7 >= 1) Light.WARN else Light.NORMAL)

        // 涉诉金额/净资产 = 诉讼金额(万元) × 10000 ÷ 归母净资产 × 100（净资产缺失则无法计算 → 显示「—」）
        val sueRatio = input.sueAmountWan?.let { wan ->
            input.netAssets?.takeIf { it > 0 }?.let { eq -> wan * 10000 / eq * 100 }
        }
        add("M8a", Dim.NEWS, "涉诉金额/净资产", pct(sueRatio),
            grade(sueRatio, Th.lawsuitRatio.first, Th.lawsuitRatio.second),
            input.sueCount?.let { "诉讼 $it 次" } ?: "无记录")
        val gteRatio = input.gteRatio
        add("M8b", Dim.NEWS, "对外担保/净资产", pct(gteRatio),
            grade(gteRatio, Th.guaranteeRatio.first, Th.guaranteeRatio.second),
            input.gteCount?.let { "担保 $it 笔" } ?: "无记录")
        val pledge = input.pledgeRatio
        add("M8c", Dim.NEWS, "股权质押/冻结比例", if (pledge == null) "无" else pct(pledge),
            grade(pledge, 30.0, 50.0))

        // ===== 维度二：基本面（9 项）=====
        val kcf = input.deductedProfit
        val kcfLast = input.deductedProfitLast
        val f1 = when {
            kcf == null -> Light.NORMAL
            kcf >= 0 -> Light.NORMAL
            kcfLast != null && kcfLast < 0 -> Light.DANGER
            else -> Light.WARN
        }
        add("F1", Dim.FUNDAMENTAL, "扣非净利润", yi(kcf), f1)

        val revTz = input.revenueYoy
        add("F2", Dim.FUNDAMENTAL, "营业收入同比", pct(revTz),
            if (revTz != null && revTz < 0) Light.WARN else Light.NORMAL)

        val ocf = input.ocfToRevenue
        add("F3", Dim.FUNDAMENTAL, "经营现金流/营收", pct(ocf),
            if (ocf != null && ocf < 0) Light.WARN else Light.NORMAL)

        val debt = input.debtRatio
        add("F4", Dim.FUNDAMENTAL, "资产负债率", pct(debt), grade(debt, Th.debtRatio.first, Th.debtRatio.second))

        val gw = input.goodwill
        val eq = input.netAssets
        val gwr = if (gw != null && eq != null && eq != 0.0) gw / eq * 100 else null
        add("F5", Dim.FUNDAMENTAL, "商誉/净资产", if (gwr == null) "无商誉" else pct(gwr),
            grade(gwr, Th.goodwillRatio.first, Th.goodwillRatio.second))

        val arr = input.arToRevenue
        add("F6a", Dim.FUNDAMENTAL, "应收账款/营收", pct(arr),
            if (arr != null && arr > 50) Light.WARN else Light.NORMAL)
        val arYoy = input.arYoy
        add("F6b", Dim.FUNDAMENTAL, "应收账款同比", pct(arYoy),
            if (arYoy != null && revTz != null && (arYoy - revTz) > 20) Light.WARN else Light.NORMAL)

        val sl = input.shortLoan
        val mf = input.monetaryFunds
        val slr = if (sl != null && mf != null && mf != 0.0) sl / mf else null
        add("F7a", Dim.FUNDAMENTAL, "短期借款/货币资金", if (slr == null) "—" else fmt2(slr),
            if (slr != null && slr > 2) Light.WARN else Light.NORMAL)
        val ld = input.currentRatio
        add("F7b", Dim.FUNDAMENTAL, "流动比率", if (ld == null) "—" else fmt2(ld),
            if (ld != null && ld < 1) Light.WARN else Light.NORMAL)
        val sd = input.quickRatio
        add("F7c", Dim.FUNDAMENTAL, "速动比率", if (sd == null) "—" else fmt2(sd),
            if (sd != null && sd < 0.5) Light.DANGER else Light.NORMAL)

        val gp = input.grossMargin
        val gpLast = input.grossMarginLast
        add("F8", Dim.FUNDAMENTAL, "毛利率", pct(gp),
            if (gp != null && gpLast != null && (gpLast - gp) > 5) Light.WARN else Light.NORMAL)

        val roe = input.roe
        add("F9", Dim.FUNDAMENTAL, "加权ROE", pct(roe),
            if (roe != null && roe < 0) Light.DANGER else if (roe != null && roe < 5) Light.WARN else Light.NORMAL)

        // ===== 维度三：股东治理（4 项）=====
        add("G1", Dim.GOVERNANCE, "股权质押比例", if (pledge == null) "无质押" else pct(pledge),
            grade(pledge, Th.pledgeRatio.first, Th.pledgeRatio.second))

        val netSell = input.netSellPct
        add("G2", Dim.GOVERNANCE, "高管/股东净减持(近1年)",
            if (netSell == null) "无" else pct(netSell),
            if (netSell == null) Light.NORMAL
            else grade(-netSell, Th.netSellPct.first, Th.netSellPct.second))

        val hr = input.holderChangeRatio
        add("G3", Dim.GOVERNANCE, "股东户数环比", pct(hr), grade(hr, Th.holderChange.first, Th.holderChange.second))

        val unl = input.unlockMktCapRatio
        add("G4a", Dim.GOVERNANCE, "解禁市值/总市值",
            if (unl == null) "近期无解禁" else pct(unl),
            grade(unl, Th.unlockMktCap.first, Th.unlockMktCap.second))

        val fr = input.unlockFreeRatio
        add("G4b", Dim.GOVERNANCE, "解禁占流通比", pct(fr),
            if (fr != null && fr > 20) Light.WARN else Light.NORMAL)

        // ===== 维度四：交易资金（3 项）=====
        val price = input.price
        add("T1", Dim.CAPITAL, "最新价", if (price == null) "—" else fmt2(price) + " 元",
            gradeLow(price, Th.pricePar.first, Th.pricePar.second))

        val capYi = input.marketCap?.let { it / 1e8 }
        add("T2", Dim.CAPITAL, "总市值", if (capYi == null) "—" else fmt2(kotlin.math.round(capYi)) + "亿",
            gradeLow(capYi, Th.mktcapYi.first, Th.mktcapYi.second))

        val prem = input.blockPremium
        add("T3a", Dim.CAPITAL, "大宗交易折价率", pct(prem),
            if (prem != null && prem < -Th.discount.first) Light.WARN else Light.NORMAL)

        val lhb = input.lhbNet
        add("T3b", Dim.CAPITAL, "龙虎榜净额", if (lhb == null) "近期无" else wan(lhb),
            if (lhb != null && lhb > 5000 * 1e4) Light.WARN else Light.NORMAL)

        val fin = input.marginBalance
        add("T3c", Dim.CAPITAL, "融资余额", yi(fin), Light.NORMAL)

        return aggregate(items)
    }

    // ---------------------------------------------------------------- 港股扫描主逻辑

    /**
     * 港股扫描（**20 项 / 4 维度**）。判据与阈值与 `saolei.py` 的 `scan_hk()` 逐项一致。
     *
     * ⚠️ 与 A 股的三点不同：
     *  ① 维度不同 —— 用「估值交易 / 资金面」代替「股东治理 / 交易资金」（港股无质押/解禁/龙虎榜/两融）；
     *  ② 权重不同 —— 见 [HK_WEIGHTS]（基本面 40%、消息面 25%）；
     *  ③ 消息面靠**腾讯自选股港股公告**关键词（港股无独立"监管函件"字段）。
     */
    fun buildHk(input: HkInput): Result {
        val items = ArrayList<Item>(20)
        fun add(id: String, dim: Dim, name: String, value: String, light: Light, note: String = "") {
            items += Item(id, dim, name, value, light, note)
        }
        fun fmt2(v: Double) = (kotlin.math.round(v * 100) / 100.0).toString()
        fun round0(v: Double) = kotlin.math.round(v)
        fun pct(v: Double?) = if (v == null) "—" else fmt2(v) + "%"
        fun yi(v: Double?) = if (v == null) "—" else fmt2(round0(v / 1e8 * 100) / 100) + "亿"

        val titles = input.announcements.joinToString(" ")
        fun kw(vararg ws: String) = ws.count { titles.contains(it) }

        // ===== 消息面（6 项；公告关键词来自腾讯自选股港股公告）=====
        val n2 = kw("处罚", "违规", "谴责", "警示")
        add("M2", Dim.NEWS, "监管处罚/违规(近期公告)", "$n2 次",
            if (n2 >= 1) Light.DANGER else Light.NORMAL, "基于最新公告关键词")
        val n3 = kw("问询", "关注函")
        add("M3", Dim.NEWS, "监管函件", "$n3 次",
            if (n3 >= 3) Light.DANGER else if (n3 >= 2) Light.WARN else Light.NORMAL)
        val n5 = kw("核数师", "会计师")
        add("M5", Dim.NEWS, "核数师变更", "$n5 次",
            if (n5 >= 2) Light.DANGER else if (n5 == 1) Light.WARN else Light.NORMAL)
        val n4 = kw("保留意见", "无法表示意见", "不发表意见")
        add("M4", Dim.NEWS, "核数师意见非标", "$n4 次",
            if (n4 >= 1) Light.DANGER else Light.NORMAL)
        val n6 = kw("辞任", "退任", "主席", "行政总裁", "首席财务")
        add("M6", Dim.NEWS, "关键高管变动", "$n6 次",
            if (n6 >= 2) Light.DANGER else if (n6 == 1) Light.WARN else Light.NORMAL)
        val n7 = kw("终止", "撤回", "终止收购")
        add("M7", Dim.NEWS, "资本运作终止", "$n7 次",
            if (n7 >= 1) Light.WARN else Light.NORMAL)

        // ===== 基本面（8 项）=====
        val hp = input.holderProfit
        val hpPrev = input.holderProfitPrev
        val l1 = when {
            hp == null || hp >= 0 -> Light.NORMAL
            hpPrev != null && hpPrev < 0 -> Light.DANGER
            else -> Light.WARN
        }
        add("F1", Dim.FUNDAMENTAL, "归母净利润", yi(hp), l1)
        val revTz = input.revenueYoy
        add("F2", Dim.FUNDAMENTAL, "营业收入同比", pct(revTz),
            if (revTz != null && revTz < 0) Light.WARN else Light.NORMAL)
        val ocf = input.ocfToRevenue
        add("F3", Dim.FUNDAMENTAL, "经营现金流/营收", pct(ocf),
            if (ocf != null && ocf < 0) Light.WARN else Light.NORMAL)
        val debt = input.debtRatio
        add("F4", Dim.FUNDAMENTAL, "资产负债率", pct(debt), grade(debt, Th.debtRatio.first, Th.debtRatio.second))
        val cr = input.currentRatio
        add("F5", Dim.FUNDAMENTAL, "流动比率", if (cr == null) "—" else fmt2(cr),
            if (cr != null && cr < 1) Light.WARN else Light.NORMAL)
        val gp = input.grossMargin
        val gpPrev = input.grossMarginPrev
        add("F6", Dim.FUNDAMENTAL, "毛利率", pct(gp),
            if (gp != null && gpPrev != null && (gpPrev - gp) > 5) Light.WARN else Light.NORMAL)
        val roe = input.roe
        add("F7", Dim.FUNDAMENTAL, "ROE", pct(roe),
            if (roe != null && roe < 0) Light.DANGER else if (roe != null && roe < 5) Light.WARN else Light.NORMAL)
        val rev = input.revenue
        val ar = input.accountsReceivable
        val arr = if (ar != null && rev != null && rev != 0.0) ar / rev * 100 else null
        add("F8", Dim.FUNDAMENTAL, "应收账款/营收", pct(arr),
            if (arr != null && arr > 50) Light.WARN else Light.NORMAL)

        // ===== 估值交易（4 项）=====
        val pe = input.pe
        add("V1", Dim.VALUATION, "市盈率 PE(TTM)", if (pe == null) "—" else fmt2(pe),
            grade(pe, Th.hkPe.first, Th.hkPe.second))
        val pb = input.pb
        add("V2", Dim.VALUATION, "市净率 PB(TTM)", if (pb == null) "—" else fmt2(pb),
            grade(pb, Th.hkPb.first, Th.hkPb.second))
        val capYi = input.marketCap?.let { it / 1e8 }
        add("V3", Dim.VALUATION, "总市值(亿港元)", if (capYi == null) "—" else fmt2(round0(capYi)),
            gradeLow(capYi, Th.hkMktcapYi.first, Th.hkMktcapYi.second))
        val price = input.price
        add("V4", Dim.VALUATION, "最新价(港元)", if (price == null) "—" else fmt2(price) + " 元",
            gradeLow(price, Th.hkPrice.first, Th.hkPrice.second))

        // ===== 资金面（2 项，港股特有）=====
        val south = input.southboundChangePct
        add(
            "Z1", Dim.FUNDS, "南向资金持股(近20日变化)",
            if (south == null) "—" else (if (south >= 0) "+" else "") + fmt2(south) + "%",
            if (south == null) Light.NORMAL else grade(-south, -Th.southChg.first, -Th.southChg.second),
            if (south != null && south < 0) "南向减持=资金撤离" else "南向增持",
        )
        val bbCnt = input.buybackCount
        val bbNum = input.buybackShares ?: 0.0
        add(
            "Z2", Dim.FUNDS, "近期回购(近30日)",
            if (bbCnt == null || bbCnt == 0) "无回购" else "$bbCnt 笔 / " + fmt2(round0(bbNum / 1e4)) + "万股",
            Light.NORMAL, if (bbCnt != null && bbCnt > 0) "回购=管理层信心" else "",
        )

        return aggregate(items, HK_WEIGHTS)
    }
}

