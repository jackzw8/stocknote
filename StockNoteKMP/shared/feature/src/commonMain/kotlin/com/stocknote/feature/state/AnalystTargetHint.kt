package com.stocknote.feature.state

import com.stocknote.data.net.AnalystTargetSource

/**
 * 目标价取数结果的**文案**（老周 2026-09-21）。
 *
 * 两个入口共用（交易计划的目标价、自选股的目标价弹窗），保证同一件事只有一种说法。
 * 单独放一个文件是因为：这类"取到了什么、依据是什么、能不能信"的说明，
 * 最容易两处各写一句、越改越不一致。
 *
 * ⚠️ 文案必须如实：用**区间中点**兜底时不能说成"均价"（那会让人以为有均价数据）。
 */
fun analystTargetHint(suggestion: AnalystTargetSource.Suggestion, isAShare: Boolean): String =
    when (suggestion) {
        is AnalystTargetSource.Suggestion.FromConsensus -> buildString {
            val c = suggestion.consensus
            append("已填入近 6 个月 ${c.count} 份研报的均值 ${fmt(suggestion.price)}")
            val low = c.droppedLow
            val high = c.droppedHigh
            if (low != null && high != null) {
                append("（去掉最低 ${fmt(low.price)} / 最高 ${fmt(high.price)}）")
            } else {
                append("（样本不足 3 份，未去极值）")
            }
            // ② 的对照信息：老周要求「用 ① 取到时把 ② 也查一下并提示」
            suggestion.profile?.let { p ->
                append("\n对照：机构评级 ${p.countText}")
                p.rangeText?.let { append("，目标价区间 $it") }
            }
        }

        is AnalystTargetSource.Suggestion.FromRangeMid -> buildString {
            val p = suggestion.profile
            append("近 6 个月没有逐条研报目标价，已用机构目标价区间")
            p.rangeText?.let { append(" $it ") }
            append("的中点 ${fmt(suggestion.price)} 填入（${p.countText}评级，中点不是均价）")
        }

        AnalystTargetSource.Suggestion.None ->
            if (isAShare) "近 6 个月没有找到分析师目标价，也没有机构评级数据"
            else "该市场暂无分析师数据（数据源只覆盖 A股）"
    }

/** 去尾零（14.1600 → "14.16"） */
private fun fmt(v: Double): String {
    // ⚠️ M3 修复（2026-09-28）：改走 Format.fixedPlain —— `"%.4f".format` 受设备 Locale 影响，
    // 在逗号小数分隔符的地区会输出 `168092,01`，与 M7 的修复自相矛盾且回填后无法解析。
    val s = com.stocknote.core.format.Format.fixedPlain(v, 4).trimEnd('0').trimEnd('.')
    return s.ifEmpty { "0" }
}
