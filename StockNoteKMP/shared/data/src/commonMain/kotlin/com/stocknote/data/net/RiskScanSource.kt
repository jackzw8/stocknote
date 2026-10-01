package com.stocknote.data.net

import com.stocknote.core.calc.CivilDate
import com.stocknote.data.platform.todayIso
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 个股风险扫雷 —— **数据解析**（纯函数，无网络；网络抓取在 [QuoteClient.fetchRiskScan]）。
 *
 * 移植自《东财F10个股风险扫雷技术方案 v1.1》配套脚本 `saolei.py` 的取数逻辑：
 *  - **F10「操盘必读」**（一个请求 20 个模块，是核心）：`zxzb`（最新财务指标）、`zyzb`（主要指标明细）、
 *    `zxzbOther`（估值/总市值）、`zxgg`（最新公告标题）、`gdrs`（股东户数）、`dzjy`（大宗交易）、
 *    `lhbd`（龙虎榜）、`rzrq`（融资融券）；
 *  - **数据中心**（`datacenter-web`）：`RPTA_APP_ACCUMDETAILS`（股权质押）、`RPT_LIFT_STAGE`（限售解禁）、
 *    `RPT_EXECUTIVE_HOLD_DETAILS`（高管增减持）；
 *  - **资产负债表**（`zcfzbAjaxNew`）：`GOODWILL`（商誉）、`OPINION_TYPE`（审计意见）、
 *    `MONETARYFUNDS`、`SHORT_LOAN`、`TOTAL_PARENT_EQUITY`。
 *
 * ⚠️ **巨潮资讯（诉讼 / 担保）本期未接入**：它需要 `Accept-Enckey` 动态鉴权（AES-128-CBC），
 * 且接口返回**全市场**记录（体积大、每次全量拉取不经济）→ 这两项在界面上显示「无记录」，
 * 待产品方确认后再补（口径见方案 §2.4）。
 *
 * 解析一律**防御式**：结构变化/字段缺失 → 返回 null，**不抛异常、不编造**。
 */
object RiskScanSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 累积器：多来源分步填充，最后 `toInput()` 交给 [com.stocknote.core.calc.RiskScanner] */
    class Raw(var displayName: String) {
        // 消息面
        var announcements: List<String> = emptyList()
        var auditOpinion: String? = null
        var pledgeRatio: Double? = null
        // 巨潮（本地快照表 cninfo_risk，设置页手工同步）
        var sueCount: Int? = null
        var sueAmountWan: Double? = null
        var gteCount: Int? = null
        var gteRatio: Double? = null
        // 基本面
        var deductedProfit: Double? = null
        var deductedProfitLast: Double? = null
        var revenueYoy: Double? = null
        var ocfToRevenue: Double? = null
        var debtRatio: Double? = null
        var goodwill: Double? = null
        var netAssets: Double? = null
        var arToRevenue: Double? = null
        var arYoy: Double? = null
        var shortLoan: Double? = null
        var monetaryFunds: Double? = null
        var currentRatio: Double? = null
        var quickRatio: Double? = null
        var grossMargin: Double? = null
        var grossMarginLast: Double? = null
        var roe: Double? = null
        // 股东治理
        var netSellPct: Double? = null
        var holderChangeRatio: Double? = null
        var unlockMktCapRatio: Double? = null
        var unlockFreeRatio: Double? = null
        // 交易资金
        var price: Double? = null
        var marketCap: Double? = null
        var blockPremium: Double? = null
        var lhbNet: Double? = null
        var marginBalance: Double? = null

        fun toInput() = com.stocknote.core.calc.RiskScanner.Input(
            displayName = displayName,
            announcements = announcements,
            auditOpinion = auditOpinion,
            pledgeRatio = pledgeRatio,
            sueCount = sueCount,
            sueAmountWan = sueAmountWan,
            gteCount = gteCount,
            gteRatio = gteRatio,
            deductedProfit = deductedProfit,
            deductedProfitLast = deductedProfitLast,
            revenueYoy = revenueYoy,
            ocfToRevenue = ocfToRevenue,
            debtRatio = debtRatio,
            goodwill = goodwill,
            netAssets = netAssets,
            arToRevenue = arToRevenue,
            arYoy = arYoy,
            shortLoan = shortLoan,
            monetaryFunds = monetaryFunds,
            currentRatio = currentRatio,
            quickRatio = quickRatio,
            grossMargin = grossMargin,
            grossMarginLast = grossMarginLast,
            roe = roe,
            netSellPct = netSellPct,
            holderChangeRatio = holderChangeRatio,
            unlockMktCapRatio = unlockMktCapRatio,
            unlockFreeRatio = unlockFreeRatio,
            price = price,
            marketCap = marketCap,
            blockPremium = blockPremium,
            lhbNet = lhbNet,
            marginBalance = marginBalance,
        )
    }

    // ---------------------------------------------------------------- F10 操盘必读（核心）

    /** 解析 F10 操盘必读（`OperationsRequired/PageAjax`），填充大部分基础指标。 */
    fun fillFromF10(raw: Raw, text: String, fallbackName: String) {
        val root = obj(text) ?: return
        val zxzb = firstOf(root, "zxzb") ?: JsonObject(emptyMap())
        val zyzb = firstOf(root, "zyzb") ?: JsonObject(emptyMap())
        val other = firstOf(root, "zxzbOther") ?: JsonObject(emptyMap())

        // 名称（若数据层没给，用接口里的）
        val nm = zxzb.str("SECURITY_NAME_ABBR")
        if (nm.isNotEmpty()) raw.displayName = nm.ifEmpty { fallbackName }

        // —— 公告标题（关键词扫描用；content 实测为 null，只能扫标题）——
        raw.announcements = arr(root, "zxgg").mapNotNull { (it as? JsonObject)?.str("title") }
            .filter { it.isNotEmpty() }

        // —— 基本面：zxzb（最新期）——
        raw.deductedProfit = zxzb.num("KCFJCXSYJLR")
        raw.deductedProfitLast = zxzb.num("KCFJCXSYJLR_LAST")
        raw.revenueYoy = zxzb.num("TOTALOPERATEREVETZ")
        raw.debtRatio = zxzb.num("ZCFZL")
        raw.grossMargin = zxzb.num("XSMLL")
        raw.grossMarginLast = zxzb.num("XSMLL_LAST")
        raw.roe = zxzb.num("ROEJQ")

        // —— 基本面：zyzb（明细；接口给的是小数比，×100 转百分数）——
        raw.ocfToRevenue = zyzb.num("JYXJLYYSR")?.times(100)
        raw.arToRevenue = zyzb.num("YSZKYYSR")?.times(100)
        raw.currentRatio = zyzb.num("LD")
        raw.quickRatio = zyzb.num("SD")

        // —— 估值与市值：zxzbOther ——
        raw.marketCap = other.num("TOTAL_MARKET_CAP")

        // —— 股东户数环比：gdrs[0] ——
        raw.holderChangeRatio = firstOf(root, "gdrs")?.num("TOTAL_NUM_RATIO")

        // —— 大宗交易折价率：dzjy[0] ——
        raw.blockPremium = firstOf(root, "dzjy")?.num("PREMIUM_RATIO")

        // —— 龙虎榜净额：lhbd[0]（卖出 − 买入）；超过 180 天视为"近期无"——
        val lhb = firstOf(root, "lhbd")
        if (lhb != null) {
            val days = daysAgo(lhb.str("TRADE_DATE"))
            if (days <= 180) {
                val sell = lhb.num("TOTAL_SELL") ?: 0.0
                val buy = lhb.num("TOTAL_BUY") ?: 0.0
                raw.lhbNet = sell - buy
            }
        }

        // —— 融资余额：rzrq[0] ——
        raw.marginBalance = firstOf(root, "rzrq")?.num("FIN_BALANCE")
    }

    // ---------------------------------------------------------------- 资产负债表

    /** 解析资产负债表（`zcfzbAjaxNew`）：商誉 / 审计意见 / 货币资金 / 短期借款 / 归母净资产。 */
    fun fillFromBalance(raw: Raw, text: String) {
        val root = obj(text) ?: return
        val b0 = firstOf(root, "data") ?: return

        raw.goodwill = b0.num("GOODWILL")
        raw.shortLoan = b0.num("SHORT_LOAN")
        raw.monetaryFunds = b0.num("MONETARYFUNDS")
        raw.netAssets = b0.num("TOTAL_PARENT_EQUITY")
        // 审计意见：优先 OPINION_TYPE，回退 OSOPINION_TYPE（与脚本一致）
        val op = b0.str("OPINION_TYPE").ifEmpty { b0.str("OSOPINION_TYPE") }
        if (op.isNotEmpty()) raw.auditOpinion = op
        // 应收账款同比（脚本用 ACCOUNTS_RECE_YOY）
        raw.arYoy = b0.num("ACCOUNTS_RECE_YOY")
    }

    // ---------------------------------------------------------------- 数据中心（质押 / 解禁 / 高管减持）

    /** 解析数据中心通用响应 `result.data[]` */
    private fun dcRows(text: String): List<JsonObject> {
        val root = obj(text) ?: return emptyList()
        val result = root["result"] as? JsonObject ?: return emptyList()
        val data = result["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { it as? JsonObject }
    }

    /** 股权质押（`RPTA_APP_ACCUMDETAILS`）：取最新一条的累计质押比例。 */
    fun fillFromPledge(raw: Raw, text: String) {
        val rows = dcRows(text)
        if (rows.isEmpty()) return
        raw.pledgeRatio = rows[0].num("ACCUM_PLEDGE_TSR")
    }

    /**
     * 限售解禁（`RPT_LIFT_STAGE`）：
     * 解禁市值/总市值 = `LIFT_MARKET_CAP`（万元）× 10000 ÷ 总市值 × 100；
     * 解禁占流通比 = `FREE_RATIO` × 100（脚本口径）。
     */
    fun fillFromUnlock(raw: Raw, text: String) {
        val rows = dcRows(text)
        if (rows.isEmpty()) return
        val r = rows[0]
        val cap = raw.marketCap
        val liftWan = r.num("LIFT_MARKET_CAP")
        if (liftWan != null && cap != null && cap > 0) {
            raw.unlockMktCapRatio = liftWan * 10000 / cap * 100
        }
        raw.unlockFreeRatio = r.num("FREE_RATIO")?.times(100)
    }

    /**
     * 高管/股东净减持（`RPT_EXECUTIVE_HOLD_DETAILS`）：近 365 天 `CHANGE_SHARES` 求和 ÷ 总股本 × 100。
     * ⚠️ 总股本取自 F10 的 `TOTAL_SHARE`，若缺失则该项留空（不猜）。
     */
    fun fillFromHolderChange(raw: Raw, text: String, totalShare: Double?) {
        val rows = dcRows(text)
        if (rows.isEmpty() || totalShare == null || totalShare <= 0) return
        val net = rows.filter { daysAgo(it.str("CHANGE_DATE")) <= 365 }
            .sumOf { it.num("CHANGE_SHARES") ?: 0.0 }
        raw.netSellPct = net / totalShare * 100
    }

    /** F10 里的总股本（供高管减持换算） */
    fun totalShareOf(text: String): Double? = firstOf(obj(text) ?: JsonObject(emptyMap()), "zxzb")?.num("TOTAL_SHARE")

    // ---------------------------------------------------------------- 巨潮资讯（诉讼 / 担保）

    /**
     * 解析巨潮**专题统计**响应（诉讼 `p_sysapi1055` / 担保 `p_sysapi1054`，两者结构相同）。
     *
     * 返回 `{ 6 位代码 → (次数, 数值) }`：
     *  - 诉讼：`F001N` = 诉讼次数，`F002N` = **诉讼金额（万元）**；
     *  - 担保：`F001N` = 担保笔数，`F003N` = **对外担保/净资产（%）**。
     *  两个接口同构，故这里用 `F002N ?: F003N` 自适应取值。
     *
     * ⚠️ 接口返回**全市场**记录（这正是它不做成"每次扫雷都拉"的原因 —— 见 [CninfoRow] 说明）。
     */
    fun parseCninfoRecords(text: String): Map<String, Pair<Int, Double?>> {
        val root = obj(text) ?: return emptyMap()
        val records = root["records"] as? JsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, Pair<Int, Double?>>()
        records.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val code = o.str("SECCODE")
            if (code.isEmpty()) return@forEach
            out[code] = (o.num("F001N")?.toInt() ?: 0) to (o.num("F002N") ?: o.num("F003N"))
        }
        return out
    }

    /** 把本地快照（[cninfo_risk][com.stocknote.data.repo.PortfolioRepository] 表的一行）填入扫描输入 */
    fun fillFromCninfo(
        raw: Raw,
        sueCount: Int?,
        sueAmountWan: Double?,
        gteCount: Int?,
        gteRatio: Double?,
    ) {
        raw.sueCount = sueCount
        raw.sueAmountWan = sueAmountWan
        raw.gteCount = gteCount
        raw.gteRatio = gteRatio
    }

    // ---------------------------------------------------------------- 港股（老周 2026-09-24 补）

    /** 解析**港股 F10 主要指标**（`RPT_HKF10_FN_MAININDICATOR`）：data[] 按期倒序，[0] 当期、[1] 上期 */
    fun parseHkMain(text: String): List<JsonObject> = dcRows(text)

    /** 解析**港股资产负债表**（`RPT_HKF10_FN_BALANCE_PC`）：取最新报告期的「科目名 → 金额」 */
    fun parseHkBalanceItems(text: String): Map<String, Double> {
        val rows = dcRows(text)
        if (rows.isEmpty()) return emptyMap()
        val latest = rows.mapNotNull { it.str("REPORT_DATE").takeIf { s -> s.isNotEmpty() } }.maxOrNull()
            ?: return emptyMap()
        return rows.filter { it.str("REPORT_DATE") == latest }
            .mapNotNull { r ->
                val k = r.str("STD_ITEM_NAME")
                if (k.isEmpty()) null else k to (r.num("AMOUNT") ?: 0.0)
            }
            .toMap()
    }

    /**
     * 解析**南向资金持股**（`RPT_MUTUAL_HOLD_DET`）→ 近 20 日持股变化 %（下降为负）。
     * 只取「中国证券登记结算」的记录（与脚本一致），按日聚合后取最新与第 20 个交易日比较。
     */
    fun parseSouthboundChange(text: String): Double? {
        val rows = dcRows(text)
        if (rows.isEmpty()) return null
        val ser = LinkedHashMap<String, Double>()
        rows.forEach { r ->
            if (r.str("ORG_NAME").contains("中国证券登记结算")) {
                val d = r.str("HOLD_DATE").take(10)
                if (d.isNotEmpty()) r.num("HOLD_NUM")?.let { ser[d] = it }
            }
        }
        val dates = ser.keys.sortedDescending()
        if (dates.isEmpty()) return null
        val latest = ser[dates[0]] ?: return null
        val base = ser[dates[minOf(19, dates.size - 1)]] ?: return null
        if (base == 0.0) return null
        return (latest - base) / base * 100
    }

    /** 解析**港股回购**（`RPT_HK_BUYBACK`）→ 近 30 日 `(笔数, 回购股数)` */
    fun parseBuyback30d(text: String): Pair<Int, Double> {
        val rows = dcRows(text).filter { daysAgo(it.str("TRADE_DATE")) <= 30 }
        return rows.size to rows.sumOf { it.num("REPO_NUM") ?: 0.0 }
    }

    /** 组装港股扫描输入（任何一源取不到都只是对应项显示「—」，不影响其余项） */
    fun buildHkInput(
        symbol: String,
        mainText: String,
        balanceText: String,
        announcements: List<String>,
        southboundText: String,
        buybackText: String,
        price: Double?,
    ): com.stocknote.core.calc.RiskScanner.HkInput {
        val main = parseHkMain(mainText)
        val cur = main.getOrNull(0) ?: JsonObject(emptyMap())
        val prev = main.getOrNull(1) ?: JsonObject(emptyMap())
        val bal = parseHkBalanceItems(balanceText)
        val bb = parseBuyback30d(buybackText)
        return com.stocknote.core.calc.RiskScanner.HkInput(
            displayName = cur.str("SECURITY_NAME_ABBR").ifEmpty { symbol },
            announcements = announcements,
            holderProfit = cur.num("HOLDER_PROFIT"),
            holderProfitPrev = prev.num("HOLDER_PROFIT"),
            revenueYoy = cur.num("OPERATE_INCOME_YOY"),
            ocfToRevenue = cur.num("OCF_SALES"),
            debtRatio = cur.num("DEBT_ASSET_RATIO"),
            currentRatio = cur.num("CURRENT_RATIO"),
            grossMargin = cur.num("GROSS_PROFIT_RATIO"),
            grossMarginPrev = prev.num("GROSS_PROFIT_RATIO"),
            roe = cur.num("ROE_YEARLY"),
            revenue = cur.num("OPERATE_INCOME"),
            // 港股科目名可能是「应收帐款」或「应收账款」，两种都试
            accountsReceivable = bal["应收帐款"] ?: bal["应收账款"],
            pe = cur.num("PE_TTM"),
            pb = cur.num("PB_TTM"),
            marketCap = cur.num("TOTAL_MARKET_CAP"),
            price = price,
            southboundChangePct = parseSouthboundChange(southboundText),
            buybackCount = bb.first,
            buybackShares = bb.second,
        )
    }

    // ---------------------------------------------------------------- 工具

    /** 距今天数（解析失败返回 9999 = "很久以前"，与脚本口径一致） */
    private fun daysAgo(dateText: String): Int {
        if (dateText.length < 10) return 9999
        val target = runCatching { CivilDate.toEpochDay(dateText.take(10)) }.getOrNull() ?: return 9999
        val today = runCatching { CivilDate.toEpochDay(todayIso()) }.getOrNull() ?: return 9999
        return (today - target).toInt().coerceAtLeast(0)
    }

    private fun obj(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun arr(root: JsonObject, key: String): List<kotlinx.serialization.json.JsonElement> =
        (root[key] as? JsonArray)?.toList() ?: emptyList()

    private fun firstOf(root: JsonObject, key: String): JsonObject? =
        (root[key] as? JsonArray)?.firstOrNull() as? JsonObject

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
}
