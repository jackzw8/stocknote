package com.stocknote.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 东方财富 **F10 财务数据**源（依据老周 2026-09-24 提供的《东方财富F10接口技术文档.docx》）。
 *
 * ## 网关与数据集（文档 §3，实测 2026-09-24 均 HTTP 200）
 *  - **A股**（source=HSF10，SECUCODE 形如 `600519.SH`）：
 *    `/securities/api/data/get` + `type=RPT_F10_FINANCE_MAINFINADATA` + `sty=APP_F10_MAINFINADATA`
 *    （按报告期，**141 个字段**）
 *  - **港股**（source=F10，SECUCODE 形如 `00700.HK`）：
 *    `/securities/api/data/v1/get` + `reportName=RPT_HKF10_FN_MAININDICATOR`
 *    （按报告期，**89 个字段**）
 *
 * ⚠️ 两个市场的**字段名完全不同**（如 ROE：A股 `ROEJQ` / 港股 `ROE_AVG`）——
 * 所以这里用 [Metric] 做**一层归一化映射**，UI 只认 [Metric.key]，不碰原始字段名。
 *
 * ## 分组依据
 * 文档 §2「价值投资者关心的核心指标」的五个维度：盈利能力 / 成长性 / 财务健康 / 现金流 / 股东回报与估值。
 *
 * ⚠️ **A股这个数据集不含 PE/PB/股息率**（港股有 `PE_TTM`/`PB_TTM`/`DIVIDEND_RATE`）→
 * 用 null 表示"该市场没有该指标"，UI 显示「—」，**不编造**。
 *
 * 解析一律防御式：字段缺失/结构变化跳过该条，绝不抛异常。
 */
object FinanceSource {

    /** 财务数据支持的市场（**只有 A股与港股**，美股/场外基金无 F10 数据源）。 */
    enum class Kind { A, HK }

    /** 一个报告期的数据（[values] 的 key 是 [Metric.key]，不是原始字段名）。 */
    data class Report(
        /** 报告期 2026-06-30 */
        val date: String,
        /** 报告期中文名，如「2026中报」 */
        val dateName: String,
        /** 报告类型：一季报 / 中报 / 三季报 / 年报 */
        val type: String,
        val values: Map<String, Double?>,
    )

    /** 指标分组（文档 §2 的五个维度）。 */
    enum class Group(val label: String) {
        PROFIT("盈利能力"),
        GROWTH("成长性"),
        HEALTH("财务健康"),
        CASH("现金流"),
        RETURN("股东回报与估值"),
    }

    /**
     * 指标定义：**一次定义、两个市场复用**（A股/港股字段名各自映射）。
     *
     * @param aField A股字段名；null = A股没有这个指标
     * @param hkField 港股字段名；null = 港股没有这个指标
     * @param percent 原始值本身是百分数（如 16.75 表示 16.75%），UI 直接加 %
     * @param money 金额，按「亿元」展示（原始单位为元）
     */
    data class Metric(
        val key: String,
        val label: String,
        val group: Group,
        val aField: String? = null,
        val hkField: String? = null,
        val percent: Boolean = false,
        val money: Boolean = false,
    )

    /** 指标表（顺序即界面展示顺序）。 */
    val METRICS: List<Metric> = listOf(
        // ---- 盈利能力 ----
        Metric("roe", "ROE（加权）", Group.PROFIT, aField = "ROEJQ", hkField = "ROE_AVG", percent = true),
        Metric("roe_kf", "ROE（扣非）", Group.PROFIT, aField = "ROEKCJQ", percent = true),
        Metric("roa", "总资产净利率 ROA", Group.PROFIT, aField = "ZZCJLL", hkField = "ROA", percent = true),
        Metric("roic", "投入资本回报率 ROIC", Group.PROFIT, aField = "ROIC", hkField = "ROIC_YEARLY", percent = true),
        Metric("gross_margin", "毛利率", Group.PROFIT, aField = "XSMLL", hkField = "GROSS_PROFIT_RATIO", percent = true),
        Metric("net_margin", "净利率", Group.PROFIT, aField = "XSJLL", hkField = "NET_PROFIT_RATIO", percent = true),

        // ---- 成长性 ----
        Metric("rev_yoy", "营业总收入同比", Group.GROWTH, aField = "TOTALOPERATEREVETZ", hkField = "OPERATE_INCOME_YOY", percent = true),
        Metric("profit_yoy", "归母净利润同比", Group.GROWTH, aField = "PARENTNETPROFITTZ", hkField = "HOLDER_PROFIT_YOY", percent = true),
        Metric("eps_yoy", "每股收益同比", Group.GROWTH, aField = "EPSJBTZ", percent = true),
        Metric("bps_yoy", "每股净资产同比", Group.GROWTH, aField = "BPSTZ", percent = true),

        // ---- 财务健康 ----
        Metric("debt_ratio", "资产负债率", Group.HEALTH, aField = "ZCFZL", hkField = "DEBT_ASSET_RATIO", percent = true),
        Metric("current_ratio", "流动比率", Group.HEALTH, aField = "LD", hkField = "CURRENT_RATIO"),
        Metric("quick_ratio", "速动比率", Group.HEALTH, aField = "SD"),
        Metric("interest_debt", "有息负债率", Group.HEALTH, aField = "INTEREST_DEBT_RATIO", percent = true),

        // ---- 现金流 ----
        Metric("ocf_ps", "每股经营现金流", Group.CASH, aField = "MGJYXJJE", hkField = "PER_NETCASH_OPERATE"),
        Metric("ocf_sales", "经营现金流 / 营收", Group.CASH, aField = "JYXJLYYSR", hkField = "OCF_SALES"),
        Metric("ocf", "经营活动现金流净额", Group.CASH, hkField = "NETCASH_OPERATE", money = true),
        Metric("fcf", "自由现金流", Group.CASH, aField = "FCFF_FORWARD", money = true),

        // ---- 股东回报与估值 ----
        Metric("eps", "每股收益 EPS", Group.RETURN, aField = "EPSJB", hkField = "BASIC_EPS"),
        Metric("bps", "每股净资产 BPS", Group.RETURN, aField = "BPS", hkField = "BPS"),
        Metric("revenue", "营业总收入", Group.RETURN, aField = "TOTALOPERATEREVE", hkField = "OPERATE_INCOME", money = true),
        Metric("net_profit", "归母净利润", Group.RETURN, aField = "PARENTNETPROFIT", hkField = "HOLDER_PROFIT", money = true),
        Metric("pe", "市盈率 PE(TTM)", Group.RETURN, hkField = "PE_TTM"),
        Metric("pb", "市净率 PB", Group.RETURN, hkField = "PB_TTM"),
        Metric("dividend_rate", "股息率", Group.RETURN, hkField = "DIVIDEND_RATE", percent = true),
        Metric("divi_ratio", "分红率", Group.RETURN, hkField = "DIVI_RATIO", percent = true),
    )

    /** 某市场下可用的指标（该市场有字段的）。 */
    fun metricsFor(kind: Kind): List<Metric> =
        METRICS.filter { (if (kind == Kind.A) it.aField else it.hkField) != null }

    /**
     * App 侧代码 → 东财 SECUCODE + 市场。
     *
     * - `sh600519` → `600519.SH`（A股）
     * - `sz000001` → `000001.SZ`（A股）
     * - `bj430047` → `430047.BJ`（北交所，A股口径）
     * - `hk00700` → `00700.HK`（港股）
     * - 其它（美股 `usAAPL`、场外基金 `of…`）→ null，UI 如实提示「暂无财务数据」
     */
    fun secuCode(symbol: String): Pair<String, Kind>? {
        val s = symbol.trim().lowercase()
        return when {
            s.startsWith("sh") && s.length >= 8 -> s.substring(2) + ".SH" to Kind.A
            s.startsWith("sz") && s.length >= 8 -> s.substring(2) + ".SZ" to Kind.A
            s.startsWith("bj") && s.length >= 8 -> s.substring(2) + ".BJ" to Kind.A
            s.startsWith("hk") && s.length >= 6 -> s.substring(2).padStart(5, '0') + ".HK" to Kind.HK
            else -> null
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析接口返回。两市场**结构相同**：`result.data[]`，每项一个报告期（倒序）。
     * 纯函数，便于单测。
     */
    fun parse(text: String, kind: Kind): List<Report> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        val result = root["result"] as? JsonObject ?: return emptyList()
        val arr = result["data"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val metrics = METRICS
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val date = o.rawStr("REPORT_DATE").take(10)
            if (date.isEmpty()) return@mapNotNull null
            val values = HashMap<String, Double?>(metrics.size)
            metrics.forEach { m ->
                val field = if (kind == Kind.A) m.aField else m.hkField
                values[m.key] = field?.let { o.rawNum(it) }
            }
            Report(
                date = date,
                dateName = o.rawStr("REPORT_DATE_NAME").ifEmpty { reportNameOf(date) },
                type = o.rawStr("REPORT_TYPE"),
                values = values,
            )
        }
    }

    private fun JsonObject.rawStr(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    /**
     * 报告期日期 → 人读名（**港股数据集没有 REPORT_DATE_NAME 字段**，按日期推断；
     * 推不出来就原样返回日期，不编造）。A股接口自带该字段，不会走到这里。
     */
    internal fun reportNameOf(date: String): String {
        if (date.length < 10) return date
        val year = date.take(4)
        return when (date.substring(5)) {
            "03-31" -> year + "一季报"
            "06-30" -> year + "中报"
            "09-30" -> year + "三季报"
            "12-31" -> year + "年报"
            else -> date
        }
    }

    private fun JsonObject.rawNum(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
}
