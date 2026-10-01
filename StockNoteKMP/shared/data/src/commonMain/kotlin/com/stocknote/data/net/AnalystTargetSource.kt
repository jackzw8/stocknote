package com.stocknote.data.net

import com.stocknote.core.calc.CivilDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 分析师目标价数据源（老周 2026-09-21）。
 *
 * **来源 = 东方财富研报列表** `reportapi.eastmoney.com/report/list`：
 * 逐份研报给出 `publishDate`（发布日期）与 `indvAimPriceT/L`（分析师目标价上限/下限），
 * 正好满足老周要的口径：**最近 6 个月的分析师目标价，去掉最大最小后取平均**。
 *
 * ⚠️ 覆盖范围与取舍（实测 2026-09-21）：
 *  - **只有 A股**有数据：港股 / 美股 / 基金传代码过去返回空（该接口是 A股研报库）。
 *  - 目标价是**可选字段**，并非每份研报都填：实测茅台近 6 个月 40 份研报里 5 份带目标价，
 *    宁德时代 17 份里 7 份。所以 UI 必须**如实显示样本数**，别让"1 份研报的均值"看着像共识。
 *  - 东财另有汇总字段 `DEC_AIMPRICEMAX/MIN`（全部评级口径的最高/最低），
 *    但那是**聚合值、没有日期**，做不了"去极值"，所以不用它算均值。
 *
 * 关于「芝士网」：老周提过。实测 `stock.cheesefortune.com` 是登录制的前端 SPA
 * （接口需鉴权，且页面只给「近 90 天目标均价」这一个聚合值，拿不到逐条明细），
 * 无法支撑"最近 6 个月去极值平均"的口径，故**不接入**。
 *
 * 解析与统计都是纯函数，便于单测（见 AnalystTargetSourceTest）。
 */
object AnalystTargetSource {

    /** 默认时间窗：最近 6 个月（老周 2026-09-21 定的口径）。 */
    const val DEFAULT_MONTHS = 6

    /** 一条分析师目标价（一份研报一条）。 */
    data class Report(
        /** 研报发布日期 yyyy-MM-dd */
        val dateIso: String,
        /** 目标价（标的币种）。研报给区间时取**区间中点**（见 [priceOf]） */
        val price: Double,
        val org: String,
        val rating: String,
    )

    /**
     * 一致预期结果。
     *
     * @param mean 去极值后的均值（老周要的口径）；样本 < 3 时退化为全部样本的均值
     * @param plainMean 原始均值（不去极值），仅用于对照展示
     * @param droppedLow/droppedHigh 被去掉的最低/最高样本（样本 < 3 时为 null）
     */
    data class Consensus(
        val mean: Double,
        val plainMean: Double,
        val samples: List<Report>,
        val droppedLow: Report?,
        val droppedHigh: Report?,
    ) {
        val count: Int get() = samples.size
        val min: Double get() = samples.minOf { it.price }
        val max: Double get() = samples.maxOf { it.price }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 研报列表 JSON → 目标价样本（纯函数）。
     *
     * 目标价取值规则：`indvAimPriceT`（上限）与 `indvAimPriceL`（下限）
     *  - 两个都有且不同 → 取**中点**（研报给的是价格区间，取一端会系统性偏高/偏低）
     *  - 只有一个 → 用它
     *  - 都没有 / ≤ 0 → 该研报不计入（绝大多数研报其实不填目标价）
     */
    fun parse(text: String): List<Report> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val arr = root["data"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val date = o.str("publishDate").take(10)
            if (date.length != 10) return@mapNotNull null
            val price = priceOf(o.str("indvAimPriceT"), o.str("indvAimPriceL")) ?: return@mapNotNull null
            Report(
                dateIso = date,
                price = price,
                org = o.str("orgSName"),
                rating = o.str("sRatingName").ifEmpty { o.str("emRatingName") },
            )
        }
    }

    /** 见 [parse] 注释：区间取中点。 */
    internal fun priceOf(highText: String, lowText: String): Double? {
        val high = highText.trim().toDoubleOrNull()?.takeIf { it > 0 }
        val low = lowText.trim().toDoubleOrNull()?.takeIf { it > 0 }
        return when {
            high != null && low != null -> (high + low) / 2.0
            else -> high ?: low
        }
    }

    /**
     * 一致预期：只看 [fromIso] 之后的研报，**去掉最大最小**再平均（老周 2026-09-21 定的口径）。
     *
     * 边界（别自己发明）：
     *  - 样本 ≥ 3：去掉一个最低、一个最高，余下求算术平均；
     *  - 样本 = 2：去掉就没样本了 → 直接平均；
     *  - 样本 = 1：直接用它（UI 会写明"仅 1 份"）；
     *  - 样本 = 0：返回 null，调用方退回机构区间中点或如实显示「没找到」。
     */
    fun consensus(reports: List<Report>, fromIso: String): Consensus? {
        val samples = reports.filter { it.dateIso >= fromIso }.sortedBy { it.price }
        if (samples.isEmpty()) return null
        val plain = samples.map { it.price }.average()
        return if (samples.size >= 3) {
            val trimmed = samples.subList(1, samples.size - 1)
            Consensus(
                mean = trimmed.map { it.price }.average(),
                plainMean = plain,
                samples = samples,
                droppedLow = samples.first(),
                droppedHigh = samples.last(),
            )
        } else {
            Consensus(mean = plain, plainMean = plain, samples = samples, droppedLow = null, droppedHigh = null)
        }
    }

    /**
     * **两级取数**：先用逐条研报均值（口径准，但覆盖稀疏），没有再用机构评级的**目标价区间中点**兜底
     * （覆盖率高：实测连逐条目标价为 0 的平安银行/招商银行都有区间）。
     *
     * 老周 2026-09-21 定：「用 1 取到时把 2 的信息也查一下并做个提示」——所以只要 A股，
     * 两个源都查，[Suggestion.FromConsensus] 也会带上 [AnalystRatingSource.Profile] 供 UI 做对照提示。
     */
    sealed interface Suggestion {
        /** 用逐条研报均值（主口径）。[profile] 非空 = 顺带查到的机构评级，仅供对照展示。 */
        data class FromConsensus(
            val price: Double,
            val consensus: Consensus,
            val profile: AnalystRatingSource.Profile?,
        ) : Suggestion

        /** 逐条没样本，退回**机构目标价区间中点**（不是"均价"，UI 必须写明）。 */
        data class FromRangeMid(val price: Double, val profile: AnalystRatingSource.Profile) : Suggestion

        /** 两个源都没有。 */
        data object None : Suggestion
    }

    fun suggest(consensus: Consensus?, profile: AnalystRatingSource.Profile?): Suggestion {
        val mid = profile?.midOfRange
        return when {
            consensus != null -> Suggestion.FromConsensus(consensus.mean, consensus, profile)
            mid != null -> Suggestion.FromRangeMid(mid, profile!!)
            else -> Suggestion.None
        }
    }

    /**
     * A股代码（`sh600519` / `sz000001` / `bj430047`）→ 纯数字代码；**其它市场返回 null**。
     *
     * 为什么要这么严（老周 2026-09-21）：这个接口是 A股研报库，而 App 的代码里
     * 场外基金是 `of000001`、港股是 `hk00700`。若用「取数字部分」的宽松判断，
     * `of000001`（华夏成长混合）会被拿去查 **000001 平安银行**的研报目标价 —— 静默串标的。
     */
    fun aShareCodeOf(symbol: String): String? =
        if (A_SHARE_SYMBOL.matches(symbol)) symbol.drop(2) else null

    private val A_SHARE_SYMBOL = Regex("""(sh|sz|bj)\d{6}""")

    /** 「最近 N 个月」的起点（含当天）。按自然月回退，日不足时收到当月最后一天（2/31 → 2/28）。 */
    fun monthsAgoIso(todayIso: String, months: Int = DEFAULT_MONTHS): String {
        val p = CivilDate.parseIso(todayIso)
        val total = p.year * 12 + (p.month - 1) - months
        val y = total / 12
        val m = total % 12 + 1
        val d = minOf(p.day, CivilDate.daysInMonth(y, m))
        return com.stocknote.core.calc.CivilDate.isoOf(y, m, d)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
}
