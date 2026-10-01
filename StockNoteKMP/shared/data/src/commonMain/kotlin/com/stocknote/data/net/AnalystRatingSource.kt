package com.stocknote.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * **机构评级聚合**数据源（老周 2026-09-21 第二轮）：东方财富数据中心 `RPT_WEB_RESPREDICT`。
 *
 * 与 [AnalystTargetSource]（逐条研报目标价）是**互补**关系：
 *  - 逐条：能拿到"每一份研报的目标价 + 日期"，口径准，但**覆盖稀疏**（多数研报不填目标价）；
 *  - 聚合（本文件）：给出**评级机构家数**（买入/增持/中性…）与**机构目标价区间**（最低~最高），
 *    实测覆盖率高得多 —— 连逐条目标价为 0 的平安银行(13.21~15.10)、招商银行(49.22~58.35) 都有。
 *
 * ⚠️ 这里**没有"平均目标价"字段**，只有区间。所以：
 *  - 想在图上/文案里对照时，用 [Profile.rangeText]；
 *  - 逐条没样本需要兜底时，用 [Profile.midOfRange]（**区间中点**，UI 必须写明不是均值）。
 *
 * 只有 A股（东财该报表按 A股代码索引），解析是纯函数、可单测。
 */
object AnalystRatingSource {

    /**
     * @param orgCount 评级机构家数（东财 `RATING_ORG_NUM`）
     * @param buy/add/neutral/reduce/sell 各评级档位家数（东财 `RATING_BUY_NUM` 等，可能为空）
     * @param aimMin/aimMax 机构目标价区间（东财 `DEC_AIMPRICEMIN/MAX`），可能为空
     */
    data class Profile(
        val orgCount: Int,
        val buy: Int? = null,
        val add: Int? = null,
        val neutral: Int? = null,
        val reduce: Int? = null,
        val sell: Int? = null,
        val aimMin: Double? = null,
        val aimMax: Double? = null,
    ) {
        /** 有可用的目标价区间（两端都在、且为正） */
        val hasRange: Boolean get() = aimMin != null && aimMax != null && aimMin > 0 && aimMax > 0

        /** 区间中点 —— **不是均价**，只用于"逐条没样本"时的兜底填值 */
        val midOfRange: Double?
            get() {
                val min = aimMin ?: return null
                val max = aimMax ?: return null
                if (min <= 0.0 || max <= 0.0) return null
                return (min + max) / 2.0
            }

        /** 区间文案：`13.21 ~ 15.10` */
        val rangeText: String?
            get() = if (hasRange) "$aimMin ~ $aimMax" else null

        /** 评级家数文案：`21 家（买入 10 / 增持 10）` */
        val countText: String
            get() = buildString {
                append(orgCount).append(" 家")
                val parts = buildList {
                    buy?.takeIf { it > 0 }?.let { add("买入 $it") }
                    add?.takeIf { it > 0 }?.let { add("增持 $it") }
                    neutral?.takeIf { it > 0 }?.let { add("中性 $it") }
                    reduce?.takeIf { it > 0 }?.let { add("减持 $it") }
                    sell?.takeIf { it > 0 }?.let { add("卖出 $it") }
                }
                if (parts.isNotEmpty()) append("（").append(parts.joinToString(" / ")).append("）")
            }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 东财返回 → [Profile]；没有有效记录时返回 null。 */
    fun parse(text: String): Profile? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val arr = (root["result"] as? JsonObject)?.get("data") as? JsonArray ?: return null
        val o = arr.firstOrNull() as? JsonObject ?: return null
        val orgCount = o.int("RATING_ORG_NUM") ?: 0
        val profile = Profile(
            orgCount = orgCount,
            buy = o.int("RATING_BUY_NUM"),
            add = o.int("RATING_ADD_NUM"),
            neutral = o.int("RATING_NEUTRAL_NUM"),
            reduce = o.int("RATING_REDUCE_NUM"),
            sell = o.int("RATING_SALE_NUM"),
            aimMin = o.double("DEC_AIMPRICEMIN"),
            aimMax = o.double("DEC_AIMPRICEMAX"),
        )
        // 既没有评级家数也没有目标价区间 → 视为无数据（接口会回一行全空）
        return if (profile.orgCount > 0 || profile.hasRange) profile else null
    }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.toInt()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
}
