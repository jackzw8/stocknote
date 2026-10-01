package com.stocknote.core.calc

import com.stocknote.core.format.Format
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Security
import com.stocknote.core.model.Transaction

/**
 * 绩效统计（REQ-ANA-01~03、REQ-ANA-05）。
 *
 * 口径（技术说明书 5.8，勿混淆）：
 *   · 盈亏与成本核算 = 移动加权（PositionCalculator，口径 A）
 *   · 胜率 / 盈亏比 / 持仓周期 = **FIFO 配平仓**（本文件，口径 B）
 *   两种口径独立实现、独立单测，互不影响。
 *
 * 全部为纯函数：输入交易列表，输出统计结果，便于 100% 单测。
 */
object PerformanceCalculator {

    /** 一段 FIFO 配对结果：一笔卖出消化了一笔（或多次）买入 */
    data class ClosedLot(
        val securityId: String,
        val symbol: String,
        val name: String,
        val quantity: Double,
        val buyPrice: Double,
        val sellPrice: Double,
        val buyDate: String,
        val sellDate: String,
        /** (卖价-买价)×数量 − 卖出费分摊 − 买入费分摊（**原币**口径） */
        val pnl: Double,
        val holdingDays: Long,
        val sellTags: List<String>,
        /** 该标的的币种（H2，2026-09-28）：用于说明 [pnl] 是原币，避免与折本位币的数字混用 */
        val currency: Currency = Currency.CNY,
        /**
         * **折本位币后的盈亏**（H2，2026-09-28）= `pnl × 该标的的折算率`。
         *
         * ⚠️ **所有跨标的的汇总（stats / byTag / byMarket / realizedByDay）必须用它，不能用 [pnl]** ——
         * `pnl` 是**原币**，港股 1000 HKD 的盈利与 A 股 1000 CNY 直接相加会虚高约 17%，
         * 且与同页归因区（取 `snapshot.realizedPnlTotal`，**已折本位币**）对不上，
         * 出现"同一页面上下两块'已实现盈亏'数字不一致"。
         */
        val pnlBase: Double = pnl,
    ) {
        val isWin: Boolean get() = pnl > 0.0
    }

    data class FifoResult(
        val closed: List<ClosedLot>,
        /** 未配平的买入堆栈笔数（仍在持仓的来源） */
        val openLots: Int,
        /** 卖出数量超过历史买入的总量（负持仓警示，不参与统计） */
        /**
         * 超卖数量（重放中被截断掉的股数）。
         *
         * ⚠️ **L8（2026-09-27 记录）**：这个信号目前**没有任何生产代码消费**（只有单测在断言它），
         * 也就是"账本已不一致"这个事实**算出来了却没告诉用户**。将来若要做"账本自检"提示，
         * 直接消费它即可；本次不改行为。
         */
        val oversellQty: Double,
    )

    data class Stats(
        val closedCount: Int,
        val winCount: Int,
        val loseCount: Int,
        /**
         * 胜率 = 盈利笔数 ÷ **全部已平仓笔数**。
         *
         * ⚠️ 分母**包含 `pnl == 0` 的打平笔**（L7，2026-09-27 补注）：有打平时，
         * 「胜率 + 亏损率」会**小于 100%** —— 这是刻意的口径（打平既不算赢也不算输）。
         * 若要"只按输赢两分"，分母应改用 `winCount + loseCount`。
         * null = 无平仓样本。
         */
        val winRate: Double?,
        /** 盈亏比 = 平均盈利 / |平均亏损| */
        val profitFactor: Double?,
        val avgWin: Double?,
        val avgLose: Double?,
        val avgHoldingDays: Double?,
        val totalRealized: Double,
    )

    data class Streaks(
        val currentWin: Int,
        val currentLose: Int,
        val maxWin: Int,
        val maxLose: Int,
    )

    /** 单个分组（策略 / 市场）的绩效 */
    data class GroupStat(
        val label: String,
        val count: Int,
        val pnl: Double,
        val winRate: Double?,
        val profitFactor: Double?,
        val avgHoldingDays: Double?,
    )

    // ------------------------------------------------ FIFO 配平仓

    /**
     * 对单个标的的交易做 FIFO 配对：每笔卖出按时间顺序消化最早的未平买入。
     * 手续费按数量比例分摊到各配对段。
     */
    /**
     * @param rateToBase 该标的**原币 → 本位币**的折算率（H2，2026-09-28）。
     *   同一标的的所有配平笔共用同一币种，所以只需要一个标量；默认 1.0 = 不折算（CNY 标的）。
     */
    fun fifoClose(security: Security, transactions: List<Transaction>, rateToBase: Double = 1.0): FifoResult {
        data class Lot(val qty: Double, val price: Double, val date: String, val feeShare: Double)

        val closed = mutableListOf<ClosedLot>()
        val lots = ArrayDeque<Lot>()
        var oversell = 0.0

        transactions.sortedWith(compareBy({ it.tradeDate }, { it.seq }, { it.id })).forEach { tx ->
            when (tx.side.name) {
                // 买入分摊含**手续费 + 印花税**（REQ-ACC-17）：港股买入有印花税、A股没有
                "BUY" -> lots.addLast(
                    Lot(
                        tx.quantity, tx.price, tx.tradeDate,
                        if (tx.quantity > 0) (tx.fee + tx.stampDuty) / tx.quantity else 0.0,
                    )
                )

                "SELL" -> {
                    var remain = tx.quantity
                    // 卖出侧同样把印花税摊进每单位成本（A 股卖出、港股双向）
                    val sellFeePerUnit =
                        if (tx.quantity > 0) (tx.fee + tx.stampDuty) / tx.quantity else 0.0
                    while (remain > EPS && lots.isNotEmpty()) {
                        val lot = lots.first()
                        val matched = minOf(lot.qty, remain)
                        val pnl = (tx.price - lot.price) * matched -
                            sellFeePerUnit * matched - lot.feeShare * matched
                        closed += ClosedLot(
                            securityId = security.id,
                            symbol = security.symbol,
                            name = security.name,
                            quantity = matched,
                            buyPrice = lot.price,
                            sellPrice = tx.price,
                            buyDate = lot.date,
                            sellDate = tx.tradeDate,
                            pnl = pnl,
                            // H2（2026-09-28）：折本位币的盈亏，供跨标的汇总使用（见 ClosedLot.pnlBase）
                            pnlBase = pnl * rateToBase,
                            currency = security.currency,
                            holdingDays = CivilDate.daysBetween(lot.date, tx.tradeDate).coerceAtLeast(0L),
                            sellTags = tx.tags,
                        )
                        remain -= matched
                        if (lot.qty - matched <= EPS) lots.removeFirst() else {
                            lots[0] = lot.copy(qty = lot.qty - matched)
                        }
                    }
                    if (remain > EPS) oversell += remain
                }
            }
        }
        return FifoResult(closed = closed, openLots = lots.size, oversellQty = oversell)
    }

    /** 全组合的配平仓：逐标的配对后合并（不同标的间不互相配对）。 */
    /**
     * @param rateOf 给定标的返回其**原币 → 本位币**折算率（H2，2026-09-28）。
     *   缺省恒 1.0（等价于全部按原币，仅用于不涉及多币种的场景/测试）。
     */
    fun fifoCloseAll(
        securities: List<Security>,
        transactions: List<Transaction>,
        rateOf: (Security) -> Double = { 1.0 },
    ): List<ClosedLot> {
        val bySecurity = transactions.groupBy { it.securityId }
        return securities.flatMap { sec ->
            fifoClose(sec, bySecurity[sec.id].orEmpty(), rateOf(sec)).closed
        }
    }

    // ------------------------------------------------ 统计

    fun stats(closed: List<ClosedLot>): Stats {
        if (closed.isEmpty()) {
            return Stats(0, 0, 0, null, null, null, null, null, 0.0)
        }
        val wins = closed.filter { it.pnlBase > 0 }
        val loses = closed.filter { it.pnlBase < 0 }
        // ⚠️ H2（2026-09-28）：一律用 pnlBase（折本位币）—— pnl 是原币，跨币种相加会虚高
        val avgWin = if (wins.isEmpty()) null else wins.sumOf { it.pnlBase } / wins.size
        val avgLose = if (loses.isEmpty()) null else loses.sumOf { it.pnlBase } / loses.size
        return Stats(
            closedCount = closed.size,
            winCount = wins.size,
            loseCount = loses.size,
            winRate = wins.size.toDouble() / closed.size,
            profitFactor = if (avgWin == null || avgLose == null || avgLose == 0.0) null else avgWin / -avgLose,
            avgWin = avgWin,
            avgLose = avgLose,
            avgHoldingDays = closed.sumOf { it.holdingDays }.toDouble() / closed.size,
            totalRealized = closed.sumOf { it.pnlBase },
        )
    }

    /** 连胜/连亏：按卖出日期排序（同日按配对顺序）后的符号游程统计。 */
    fun streaks(closed: List<ClosedLot>): Streaks {
        if (closed.isEmpty()) return Streaks(0, 0, 0, 0)
        val ordered = closed.sortedWith(compareBy({ it.sellDate }, { it.buyDate }))
        var curWin = 0
        var curLose = 0
        var maxWin = 0
        var maxLose = 0
        ordered.forEach { lot ->
            if (lot.pnl > 0) {
                curWin += 1; curLose = 0
            } else if (lot.pnl < 0) {
                curLose += 1; curWin = 0
            } else {
                curWin = 0; curLose = 0   // 打平不中断计数语义上更接近"没亏"，此处按中性处理
            }
            if (curWin > maxWin) maxWin = curWin
            if (curLose > maxLose) maxLose = curLose
        }
        return Streaks(curWin, curLose, maxWin, maxLose)
    }

    /** 按标签分组（REQ-ANA-02）。以**卖出交易**的标签为准（决策以卖出时点计）。 */
    fun byTag(closed: List<ClosedLot>): List<GroupStat> =
        closed.flatMap { lot -> lot.sellTags.ifEmpty { listOf(null) }.map { it to lot } }
            .groupBy({ it.first }, { it.second })
            .map { (tag, lots) ->
                val s = stats(lots)
                GroupStat(
                    label = tag ?: UNTAGGED,
                    count = lots.size,
                    // ⚠️ H2（2026-09-28）：折本位币汇总（原币相加会让跨市场的策略组虚高）
                    pnl = lots.sumOf { it.pnlBase },
                    winRate = s.winRate,
                    profitFactor = s.profitFactor,
                    avgHoldingDays = s.avgHoldingDays,
                )
            }
            .sortedByDescending { it.pnl }

    /** 按市场分组：没有标签数据时也能看分布。 */
    fun byMarket(securities: List<Security>, closed: List<ClosedLot>): List<GroupStat> {
        val marketOf = securities.associate { it.id to it.market.label }
        return closed.groupBy { marketOf[it.securityId] ?: "其他" }
            .map { (label, lots) ->
                val s = stats(lots)
                GroupStat(
                    label = label,
                    count = lots.size,
                    // ⚠️ H2（2026-09-28）：折本位币汇总 —— 否则「港股」组的 HKD 盈亏会被当成 CNY 显示
                    pnl = lots.sumOf { it.pnlBase },
                    winRate = s.winRate,
                    profitFactor = s.profitFactor,
                    avgHoldingDays = s.avgHoldingDays,
                )
            }
            .sortedByDescending { it.pnl }
    }

    /** 按日的已实现盈亏（日历热力图用，REQ-VIEW-06 的可行口径）。 */
    fun realizedByDay(closed: List<ClosedLot>): Map<String, Double> =
        // ⚠️ H2（2026-09-28）：折本位币后再加总（原币相加会让热力图跨币种虚高）
        closed.groupBy { it.sellDate }.mapValues { (_, lots) -> lots.sumOf { it.pnlBase } }

    private const val EPS = 1e-9
    const val UNTAGGED = "未打标"
}

/** 给 UI 用的便捷格式化（性能口径的数字统一短格式） */
object PerfFormat {
    // 2026-09-17 B 方案（老周定）：金额统一 2 位小数（原取整口径已废）
    fun money(v: Double?): String =
        if (v == null) "—" else Format.moneySigned(v)

    fun pct(v: Double?): String = Format.percent(v, decimals = 1)

    fun days(v: Double?): String = if (v == null) "—" else "${Format.quantity(v)}天"

    fun ratio(v: Double?): String = if (v == null) "—" else Format.quantity(v)
}
