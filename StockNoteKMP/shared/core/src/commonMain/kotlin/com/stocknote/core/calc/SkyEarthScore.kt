package com.stocknote.core.calc

import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyFactor
import com.stocknote.core.model.SkyLevel

/**
 * 看天看地 · 总分合成纯函数（FR-SE-05 / 技术说明书 §4）。
 *
 * 口径（勿推翻）：
 *  - 权重 `w_i = 1/(i+2)`（位次 1-based，第 1 条最重），**只在已判断集合内归一化**；
 *  - `score = round(100 × Σw·s / Σw) ∈ [−100, 100]`；
 *  - 全未判断 → `null`（UI 显示「—」），**绝不返回 0**；
 *  - `nowMs` 由调用方注入 —— 纯函数里不取时间，否则没法测。
 *
 * 为什么归一化只在已判集合内做：避免「只判了 2 条」时被未判条目稀释成假中性
 * （技术说明书 §4.1 注）。
 */
object SkyEarthScore {

    /** 天组上限（FR-SE-01）。 */
    const val SKY_MAX = 10

    /** 地组上限（FR-SE-01）。 */
    const val EARTH_MAX = 5

    /** 过期阈值：距最后一次判断**超过** 30 天（FR-SE-07，老周定 30 不是 7）。 */
    const val STALE_DAYS = 30

    private const val DAY_MS = 86_400_000L

    /**
     * 位次原始权重 `raw = 1/(i+2)`，i 为**清单内位次**（1-based）。
     *
     * ⚠️ 归一化分母也按「清单内位次」求和，**不是**已判集合内的相对位次 ——
     * 例：只判了第 3、4 条时 raw 固定是 1/5、1/6（Σ=0.36667）；
     * 若误用相对位次（1、2 → 1/3、1/4），两条档位不同时结果会错（−9 会变成 −14）。
     * 技术说明书 §4.1 预计算表（天组 Σ=1.60321 / 地组 Σ=1.09286）就是「全部位次都判断」的特例。
     */
    fun rawWeight(rank: Int): Double = 1.0 / (rank + 2)

    /**
     * 合成总分。入参为**该组完整有序清单**（含未判断条目，按显示顺序）——
     * 传完整清单而不是「已判子集」，是为了让位次恒等于清单内位置，调用方无从传错。
     *
     * 内部过滤 [SkyFactor.score] != null 的条目：分母 = 已判条目的原始权重之和。
     *
     * @return 全未判断 → null；否则 `round(100 × Σw·s / Σw) ∈ [−100, 100]`。
     */
    fun scoreOf(factors: List<SkyFactor>): Int? {
        val judged: List<Pair<Int, Int>> = factors.withIndex().mapNotNull { (idx, f) ->
            f.score?.let { s -> (idx + 1) to s.value }
        }
        if (judged.isEmpty()) return null
        val den = judged.sumOf { (rank, _) -> rawWeight(rank) }
        val num = judged.sumOf { (rank, s) -> rawWeight(rank) * s }
        return roundHalfAway(100.0 * num / den)
    }

    /**
     * 分数 → 五档（FR-SE-05）。阈值对称、边界闭合：
     * ≤−60 很悲观 / −59~−20 偏悲观 / −19~19 中性 / 20~59 偏乐观 / ≥60 很乐观；null → 待判断。
     */
    fun levelOf(score: Int?): SkyLevel = when {
        score == null -> SkyLevel.UNJUDGED
        score <= -60 -> SkyLevel.VERY_BEARISH
        score <= -20 -> SkyLevel.BEARISH
        score <= 19 -> SkyLevel.NEUTRAL
        score <= 59 -> SkyLevel.BULLISH
        else -> SkyLevel.VERY_BULLISH
    }

    /**
     * 30 天未更新判定（FR-SE-07）。
     *
     * ⚠️ 从未判断（[judgedAtMs] == null）→ **false**：它没「变旧」，它只是还没开始。
     * ⚠️ 严格大于（恰好 30 天不算过期）。
     */
    fun isStale(judgedAtMs: Long?, nowMs: Long, days: Int = STALE_DAYS): Boolean =
        judgedAtMs != null && (nowMs - judgedAtMs) > days * DAY_MS

    /**
     * 四舍五入（half away from zero）。
     *
     * ⚠️ 不用 `kotlin.math.round`：它是 IEEE rint 语义（.5 向偶数），
     * 且三端（Android/iOS/桌面）的实现细节不值得赌 —— 本模块的分数要在三端显示成同一个整数。
     */
    private fun roundHalfAway(v: Double): Int =
        if (v >= 0) (v + 0.5).toInt() else -((-v + 0.5).toInt())
}
