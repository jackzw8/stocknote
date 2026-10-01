package com.stocknote.core.calc

import com.stocknote.core.model.DividendRecord
import com.stocknote.core.model.Position
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction

/**
 * 持仓计算器。
 *
 * 关键设计（见技术说明书 2.0 第 5.2 节）：
 *  - 交易编辑 / 删除后**不做事后增量修补**，而是对该标的的所有流水**全量重放**。
 *    原因是移动加权成本对顺序敏感，增量修补会把误差累积下去；全量重放保证
 *    「同一组流水 => 同一结果」，且是纯函数，可被单测完全覆盖。
 *  - 成本口径：买入时把手续费计入成本；卖出时按当时均价结转成本、扣减卖出费用后计入已实现盈亏。
 *  - 分红送股（REQ-ACC-04）按 ex_date 穿插进重放时间线，同日**先分红后流水**
 *    （除权日开盘即除权：当天买入不享受该次分红；实现为 exDate <= tradeDate 时先应用分红）：
 *      · 现金分红：成本均价下调 per_share，现金累计到 [Position.dividendCash]
 *      · 送股：股数 += bonus_shares，成本不变（摊薄均价）
 *      · 配股：按配股价买入 quantity 股，成本与股数同步增加
 *  - 利润转增资本（REQ-ACC-16）也是重放流里的一"笔"（[TradeSide.CAPITALIZE]）：
 *    金额先冲减已实现盈亏、余额抬高持仓成本 → 该股总盈亏恰好下降该金额，股数与市值不变，
 *    因此**总资产不变**；因为不产生现金流，现金 / 出入金 / XIRR 都不受影响。
 */
object PositionCalculator {

    private const val EPS = Position.EPS

    /** 交易日升序、同日按 seq、再按 id —— 保证重放结果稳定可复现。 */
    private val replayOrder = compareBy<Transaction>({ it.tradeDate }, { it.seq }, { it.id })

    fun replay(
        security: Security,
        transactions: List<Transaction>,
        marketPrice: Double? = null,
        dividends: List<DividendRecord> = emptyList(),
        /**
         * 该标的币种 → 本位币的**最新**汇率（老周 2026-09-19，REQ-ACC-15 路径 1）。
         * 用于两类笔：① 未记录成交日汇率的（A 股 / 本列上线前的历史数据）；
         * ② 分红、配股（它们本身不带汇率）。默认 1.0 = 本位币交易。
         *
         * ⚠️ 只影响新增字段 [Position.costInBase]，原有**原币口径**（avgCost / costAmount /
         * realizedPnl）完全不变 —— 因此即使某个调用点漏传，也不会污染既有账本数字。
         */
        latestFxRate: Double = 1.0,
    ): Position {
        val ordered = transactions
            .filter { it.securityId == security.id }
            .sortedWith(replayOrder)
        val divs = dividends
            .filter { it.securityId == security.id }
            .sortedWith(compareBy({ it.exDate }, { it.id }))

        var quantity = 0.0
        var costAmount = 0.0
        var realized = 0.0
        var dividendCash = 0.0

        // 本位币成本（REQ-ACC-15）：买入按**各笔成交日汇率**累加，卖出等比例扣减。
        // 由它与「最新汇率折的市值」相减，即得**含汇兑损益**的折算盈亏（更贴近券商对账）。
        var costInBase = 0.0
        // 已实现盈亏的本位币口径（同上）：卖出净额按卖出日汇率折算 − 成本侧本位币扣减
        var realizedInBase = 0.0

        fun applyDividend(d: DividendRecord) {
            when (d.type) {
                "CASH" -> if (quantity > EPS && d.perShare > 0) {
                    val cash = d.perShare * quantity
                    dividendCash += cash
                    // 成本均价下调，但不把成本打成负数
                    val costBefore = costAmount
                    costAmount = (costAmount - cash).coerceAtLeast(0.0)
                    // 本位币成本按**同一比例**下调：分红不带汇率，不该改变成本侧的"平均汇率"
                    if (costBefore > EPS) costInBase *= costAmount / costBefore
                }

                "BONUS" -> if (quantity > EPS && d.bonusShares > 0) {
                    quantity += d.bonusShares
                    // 成本总额不变 → 均价被摊薄（重放末尾统一由 cost/qty 得出）；本位币成本同理不变
                }

                // 与 CASH/BONUS 同样要求有持仓：0 持仓不该产生配股（老周 2026-09-18 审查）
                "RIGHTS" -> if (quantity > EPS && d.quantity > 0 && d.rightsPrice > 0) {
                    val added = d.rightsPrice * d.quantity
                    costAmount += added
                    // 配股额同样不带汇率 → 用最新汇率近似（与「未记录汇率」的兜底同口径）
                    costInBase += added * latestFxRate
                    quantity += d.quantity
                }
            }
        }

        var di = 0
        for (tx in ordered) {
            while (di < divs.size && divs[di].exDate <= tx.tradeDate) {
                applyDividend(divs[di])
                di++
            }
            when (tx.side) {
                TradeSide.BUY -> {
                    // 成本含**手续费 + 印花税**（REQ-ACC-17）：A 股买入无印花税、港股买入有，
                    // 漏掉会让成本价偏低、浮盈虚高
                    val bought = tx.price * tx.quantity + tx.fee + tx.stampDuty
                    costAmount += bought
                    // 成交日汇率优先；没记录（A股 / 老数据）才用最新汇率兜底
                    costInBase += bought * (tx.fxRate ?: latestFxRate)
                    quantity += tx.quantity
                }

                TradeSide.SELL -> {
                    // M11 修复（2026-09-27）：**零持仓卖出**（历史脏数据、或 H7 修复前经编辑路径造出的记录）
                    // 此前因 sellQty 被截断成 0，只记了 `-fee`、**回款被整体丢弃** ——
                    // 而现金侧（CashImpact）照加 `price×qty−fee`，于是"现金与账本对不上且无任何告警"。
                    // 现在：**只有确实有持仓时才截断**；一分都没有时按**全额**记账
                    //（成本侧为 0 → 等价于"零成本卖出"，全部回款进已实现盈亏，与现金侧一致）。
                    val sellQty = if (quantity > EPS && tx.quantity > quantity) quantity else tx.quantity
                    val avg = if (quantity > EPS) costAmount / quantity else 0.0
                    // ⚠️ **已知口径差异（L6，2026-09-27 记录，本次不改行为）**：
                    // 超额卖出被截断（`sellQty < tx.quantity`）时，这里用的是**全额** `tx.fee`，
                    // 没有按成交比例摊薄；而 `addTransaction` 的截断会折算手续费（`fee * 实际/录入`），
                    // `Performance.fifoClose` 也按比例摊 —— 三处口径不一致。
                    // 影响面：只波及「历史脏数据 / 备份恢复 / CSV 导入」这类**没经过 addTransaction 截断**的记录，
                    // 正常录入路径不会出现。改成 `tx.fee * sellQty / tx.quantity` 会**改变超卖场景的已实现盈亏**，
                    // 老周 2026-09-27 决定：本次只记录，不动数值。
                    realized += (tx.price - avg) * sellQty - tx.fee - tx.stampDuty
                    costAmount -= avg * sellQty
                    // 本位币口径（REQ-ACC-15）：卖出净额按**卖出日汇率**折本位币，
                    // 减去等比例扣减的本位币成本 → 已实现盈亏同样含汇兑损益
                    val costPart = if (quantity > EPS) costInBase * (sellQty / quantity) else 0.0
                    // 印花税同样从卖出净额里扣（A 股卖出、港股双向，REQ-ACC-17）
                    realizedInBase += (tx.price * sellQty - tx.fee - tx.stampDuty) * (tx.fxRate ?: latestFxRate) - costPart
                    costInBase -= costPart
                    // 持仓不允许变负（零持仓卖出时 sellQty 可能大于 0，这里兜底）
                    quantity = (quantity - sellQty).coerceAtLeast(0.0)
                }

                TradeSide.CAPITALIZE -> {
                    // 利润转增资本（REQ-ACC-16）：把 X 折入本金（抬成本价），使该股总盈亏下降 X。
                    //   ① 先冲减**正的**已实现盈亏（亏损不该被"倒冲"回来，故下限取 0）；
                    //   ② 余额抬高持仓成本 → 均价随之上升、浮动盈亏下降。
                    // 金额取 tx.price（CAPITALIZE 的 quantity 恒为 0，不能用 price×quantity）。
                    // 仅对**仍持仓**的标的生效：0 持仓没有"本金"可增（清仓后这条记录自然失效）。
                    if (quantity > EPS) {
                        val amount = tx.amountOf
                        val reduced = if (realized > 0.0) minOf(amount, realized) else 0.0
                        realized -= reduced
                        val raise = amount - reduced
                        costAmount += raise
                        // 本位币口径同步：与买卖一样按**本笔汇率**折算（分红不带汇率，故沿用同一兜底）
                        val rate = tx.fxRate ?: latestFxRate
                        costInBase += raise * rate
                        realizedInBase -= reduced * rate
                    }
                }
            }
            if (quantity <= EPS) {
                quantity = 0.0
                costAmount = 0.0
                costInBase = 0.0
            }
        }
        while (di < divs.size) {
            applyDividend(divs[di])
            di++
        }

        val avgCost = if (quantity > EPS) costAmount / quantity else 0.0
        return Position(
            securityId = security.id,
            symbol = security.symbol,
            name = security.name,
            market = security.market,
            currency = security.currency,
            quantity = quantity,
            avgCost = avgCost,
            costAmount = costAmount,
            realizedPnl = realized,
            marketPrice = marketPrice,
            tradeCount = ordered.size,
            dividendCash = dividendCash,
            // 本位币成本（REQ-ACC-15）：无持仓时为 0；调用方可据此算含汇兑损益的折算盈亏
            costInBase = costInBase,
            realizedPnlInBase = realizedInBase,
        )
    }

    /**
     * 批量重放并聚合。
     * @param securities 全部标的；现金等价物会被剔除，不计入持仓（归「现金」口径）。
     * @param fxRates    **币种 code → 本位币**的最新汇率（如 "HKD" to 0.8534），
     *                   用于不具备成交日汇率的笔（A股/老数据/分红配股）兜底折算本位币成本。
     */
    fun replayAll(
        securities: List<Security>,
        transactions: List<Transaction>,
        prices: Map<String, Double> = emptyMap(),
        dividends: List<DividendRecord> = emptyList(),
        fxRates: Map<String, Double> = emptyMap(),
    ): List<Position> {
        val bySecurity = transactions.groupBy { it.securityId }
        val divBySecurity = dividends.groupBy { it.securityId }
        return securities
            // 现金等价物归「现金」子类、账外备忘标的（场外基金「不计入统计」）归平行备忘账 ——
            // 两者都不属于「持仓」，因此都由调用方另行单独重放（老周 2026-09-20）。
            .filterNot { it.isCashEquivalent || it.excludeFromStats }
            .map { sec ->
                replay(
                    security = sec,
                    transactions = bySecurity[sec.id].orEmpty(),
                    marketPrice = prices[sec.symbol],
                    dividends = divBySecurity[sec.id].orEmpty(),
                    latestFxRate = fxRates[sec.currency.code] ?: 1.0,
                )
            }
            // 有交易就保留（含恰好保本平仓的 —— 老周 2026-09-18 审查：
            // 原条件 abs(realizedPnl)>EPS 会把「恰好盈亏为 0」的清仓标的隐藏掉）
            .filter { it.isOpen || it.tradeCount > 0 }
    }

    /** 现金等价物市值（如货币 ETF），在口径上归「现金」而非「持仓」。 */
    fun cashEquivalentValue(
        securities: List<Security>,
        transactions: List<Transaction>,
        prices: Map<String, Double> = emptyMap(),
    ): Double {
        val bySecurity = transactions.groupBy { it.securityId }
        return securities
            .filter { it.isCashEquivalent }
            .sumOf { sec ->
                val pos = replay(sec, bySecurity[sec.id].orEmpty(), prices[sec.symbol])
                pos.marketValue
            }
    }

    fun emptyPosition(security: Security) = Position(
        securityId = security.id,
        symbol = security.symbol,
        name = security.name,
        market = security.market,
        currency = security.currency,
        quantity = 0.0,
        avgCost = 0.0,
        costAmount = 0.0,
        realizedPnl = 0.0,
        marketPrice = null,
    )
}
