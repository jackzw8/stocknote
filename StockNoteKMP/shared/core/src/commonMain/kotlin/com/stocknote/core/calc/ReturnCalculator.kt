package com.stocknote.core.calc

import kotlin.math.abs
import kotlin.math.pow

/**
 * 一条现金流。金额符号约定：
 *  - 投入资金（买入、入金）为**负**
 *  - 收回资金（卖出、出金、期末市值）为**正**
 */
data class CashFlow(val dateIso: String, val amount: Double)

/**
 * 收益率计算。三种口径（见技术说明书 2.0 第 5.4 节）：
 *  - 持仓收益率：浮盈 / 成本，在 [com.stocknote.core.model.Position] 上直接算
 *  - 累计收益率：简单口径、**非年化**，总盈亏 / 累计投入
 *  - 真实收益率：资金加权 IRR（XIRR），**年化**，考虑资金占用时长
 *
 * XIRR 求解用二分法而非牛顿法：牛顿法在现金流符号变化多次时容易发散，
 * 二分法只要区间内存在符号变化就必定收敛，代价是迭代次数多一些——对本场景完全可接受。
 */
object ReturnCalculator {

    private const val MAX_ITERATIONS = 300
    private const val TOLERANCE = 1e-9
    private const val LOWER_BOUND = -0.9999
    /**
     * 求解区间**上界**。M19 修复（2026-09-27）：100 → **1000**。
     *
     * 持有期极短而收益极高时（年化 > 10000%），原上界会让 `fLow * fHigh > 0` 而被判"无解"，
     * UI 只能显示「无法年化」。放宽后这类极端场景也能算出；**正常场景的结果不受影响**
     * （二分法在连续单调区间上收敛到同一个根）。
     */
    private const val UPPER_BOUND = 1000.0

    /**
     * 现金流是否**多次变号**（M19，2026-09-27）。
     *
     * XIRR 的二分法只返回区间内的**一个**根；当现金流多次变号时可能存在**多个 IRR 解**
     * （「买入 → 加仓 → 减仓 → 再买入」这类很常见），此时那个根未必是唯一/正确的那一个。
     * 本函数让界面能在结果旁给出「结果可能不唯一」的提示 —— **不改变 [xirr] 的返回值**，
     * 所以不会让任何现有场景从"能算"变成"算不出"。
     */
    fun hasMultipleSignChanges(flows: List<CashFlow>): Boolean {
        var changes = 0
        var lastSign = 0
        flows.sortedBy { it.dateIso }.forEach { f ->
            val sign = when {
                f.amount > 0.0 -> 1
                f.amount < 0.0 -> -1
                else -> 0
            }
            if (sign != 0) {
                if (lastSign != 0 && sign != lastSign) changes++
                lastSign = sign
            }
        }
        return changes > 1
    }

    /** 以首笔现金流日期为起点的净现值：Σ amount_i / (1+r)^(t_i/365) */
    fun npv(flows: List<CashFlow>, rate: Double, originIso: String? = null): Double {
        if (flows.isEmpty()) return 0.0
        val origin = originIso ?: flows.minOf { it.dateIso }
        val base = 1.0 + rate
        if (base <= 0.0) return Double.NaN
        var acc = 0.0
        for (f in flows) {
            val years = CivilDate.daysBetween(origin, f.dateIso) / CivilDate.DAYS_PER_YEAR
            acc += f.amount / base.pow(years)
        }
        return acc
    }

    /**
     * XIRR（年化资金加权收益率）。无解时返回 null（例如全部现金流同号、或区间内无符号变化）。
     */
    fun xirr(flows: List<CashFlow>): Double? {
        if (flows.size < 2) return null
        val hasPositive = flows.any { it.amount > 0 }
        val hasNegative = flows.any { it.amount < 0 }
        if (!hasPositive || !hasNegative) return null

        var low = LOWER_BOUND
        var high = UPPER_BOUND
        var fLow = npv(flows, low)
        var fHigh = npv(flows, high)

        if (fLow.isNaN() || fHigh.isNaN()) return null
        if (fLow * fHigh > 0) return null

        var mid = 0.0
        repeat(MAX_ITERATIONS) {
            mid = (low + high) / 2.0
            val fMid = npv(flows, mid)
            if (fMid.isNaN()) return null
            if (abs(fMid) < TOLERANCE) return mid
            if (fLow * fMid < 0) {
                high = mid
                fHigh = fMid
            } else {
                low = mid
                fLow = fMid
            }
        }
        return mid
    }

    /**
     * 累计收益率（简单、非年化）。
     * 口径：总盈亏 / 累计投入成本。累计投入 = 期间所有买入金额（含费用）之和。
     */
    fun cumulativeReturn(totalPnl: Double, totalInvested: Double): Double? =
        if (totalInvested > 1e-9) totalPnl / totalInvested else null

    /**
     * 最大回撤（正数表示回撤幅度百分比）。
     * 输入为按时间升序的净值 / 市值序列。
     */
    fun maxDrawdown(series: List<Double>): Double {
        if (series.isEmpty()) return 0.0
        var peak = series.first()
        var worst = 0.0
        for (v in series) {
            if (v > peak) peak = v
            if (peak > 1e-9) {
                val dd = (peak - v) / peak
                if (dd > worst) worst = dd
            }
        }
        return worst
    }

    /**
     * 账户口径的 XIRR 现金流（REQ-VIEW-07 / REQ-ACC-03）：
     *  - **期初现金也是投入资金**，必须以负现金流计入，否则 IRR 被严重高估甚至无解；
     *    其日期锚定到「最早的事实」（首笔交易或首笔出入金中较早者）——期初现金必然不晚于它。
     *  - 显式出入金：存入为负、取出为正。
     *  - 期末总资产作为最后一笔正流入（价值 ≤ 0 时不计）。
     *
     * 全部现金流落在同一天时 NPV 与利率无关，[xirr] 会自然返回 null（无法年化）。
     */
    fun accountFlows(
        openingCash: Double,
        openingAnchorIso: String?,
        explicit: List<CashFlow>,
        terminalValue: Double,
        terminalDateIso: String,
    ): List<CashFlow> {
        val flows = mutableListOf<CashFlow>()
        if (openingCash > 1e-9 && openingAnchorIso != null) {
            flows += CashFlow(openingAnchorIso, -openingCash)
        }
        flows += explicit
        if (terminalValue > 1e-9) flows += CashFlow(terminalDateIso, terminalValue)
        return flows
    }

    /**
     * 从交易流水生成 XIRR 所需的现金流：
     * 买入为负、卖出为正（已扣费用），期末持仓市值作为最后一笔正流入。
     */
    fun buildFlows(
        transactions: List<com.stocknote.core.model.Transaction>,
        terminalValue: Double,
        terminalDateIso: String,
    ): List<CashFlow> {
        val flows = transactions.mapNotNull { tx ->
            val gross = tx.amountOf
            when (tx.side) {
                com.stocknote.core.model.TradeSide.BUY -> CashFlow(tx.tradeDate, -(gross + tx.fee))
                com.stocknote.core.model.TradeSide.SELL -> CashFlow(tx.tradeDate, gross - tx.fee)
                // 利润转增资本（REQ-ACC-16）：账内重分类，没有现金进出 → 不参与 XIRR
                com.stocknote.core.model.TradeSide.CAPITALIZE -> null
            }
        }.toMutableList()
        if (terminalValue > 1e-9) {
            flows += CashFlow(terminalDateIso, terminalValue)
        }
        return flows
    }
}
