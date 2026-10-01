package com.stocknote.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.stocknote.core.calc.CashFlow
import com.stocknote.core.calc.PerformanceCalculator
import com.stocknote.core.calc.ReturnCalculator
import com.stocknote.core.model.Security
import com.stocknote.data.platform.todayIso
import com.stocknote.data.repo.CashRepository
import com.stocknote.data.repo.PortfolioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 分析复盘页（REQ-ANA-01/02/03/05）的状态。
 *
 * 口径说明（给看代码的人）：
 *   胜率/盈亏比/持仓周期来自 FIFO 配平仓（PerformanceCalculator）；
 *   盈亏归因的「已实现/浮动」来自移动加权重放（PortfolioSnapshot）—— 两种口径并存、各有用途。
 */
class AnalysisHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 第 10 批：现金域（出入金流水）。 */
    private val cash: CashRepository,
    private val scope: CoroutineScope,
) {

    data class UiState(
        val loading: Boolean = true,
        /** 绩效总览（FIFO 口径） */
        val stats: PerformanceCalculator.Stats? = null,
        val streaks: PerformanceCalculator.Streaks? = null,
        val tagGroups: List<PerformanceCalculator.GroupStat> = emptyList(),
        val marketGroups: List<PerformanceCalculator.GroupStat> = emptyList(),
        /** 归因（移动加权口径，本位币） */
        val realizedTotal: Double = 0.0,
        val unrealizedTotal: Double = 0.0,
        val dividendTotal: Double = 0.0,
        val dividendCount: Long = 0,
        /** 真实收益率 XIRR（年化，REQ-VIEW-07 的核心口径）。null = 投入与收回同日/无样本，无法年化 */
        val xirr: Double? = null,
        /** 是否登记过显式出入金（期初现金不算） */
        val hasCashFlow: Boolean = false,
        val error: String? = null,
    ) {
        val pricePnl: Double get() = realizedTotal + unrealizedTotal
        val totalPnl: Double get() = pricePnl + dividendTotal
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // 统计/分析口径：**账外标的（场外基金「不计入统计」）不进胜率与策略分组**
                // （老周 2026-09-20）。日记 / 自选等记录型页面仍看全量（security.securities()）。
                val scope = repo.statsScope()
                val securities: List<Security> = scope.securities
                val txs = scope.transactions
                // ⚠️ H2 修复（2026-09-28）：传入**原币 → 本位币**折算率，让 FIFO 绩效的汇总
                //（stats / byTag / byMarket / realizedByDay）与同页归因区口径一致 ——
                // 归因区的 realizedTotal 取自 snapshot（**已折本位币**）；此前 FIFO 侧是原币直接相加，
                // 港股 1000 HKD 的盈利被当成 1000 CNY，**同一页面上下两块"已实现盈亏"数字对不上**。
                val fx = runCatching { repo.latestFxRates() }.getOrDefault(emptyMap())
                val rateToBase: (Security) -> Double = { sec ->
                    if (sec.currency == com.stocknote.core.model.Currency.CNY) 1.0
                    else runCatching {
                        com.stocknote.core.model.FxTable.convertWith(
                            1.0,
                            sec.currency,
                            com.stocknote.core.model.Currency.CNY,
                            fx,
                        )
                    }.getOrDefault(1.0)
                }
                val closed = PerformanceCalculator.fifoCloseAll(securities, txs, rateToBase)

                val snapshot = repo.loadSnapshot(refreshQuotes = false)
                val dividends = repo.dividendCount()

                // 真实收益率 XIRR（REQ-VIEW-07）：账户口径现金流 = 期初现金（投入）+
                // 存入（负）/取出（正）+ 期末总资产（收回）。
                // 期初现金锚定到「最早的事实」——否则只剩当日存入 + 当日资产，跨度为零必然无解；
                // 存入一笔钱不会抬高 IRR —— 这正是出入金必须单独记录的原因（REQ-ACC-03 的验收点）。
                // H6 修复（2026-09-27）：出入金必须用**本位币**口径 ——
                // 此前用 amountOrig（原币）：存 100,000 HKD 被当成投入 100,000 CNY（实际约 85,400），
                // 而期末总资产是本位币 → IRR 失真。
                // ⚠️ 同为出入金，loadSnapshot 与 equityCurve 都做过折算，只有这里漏了。
                val cfFlows = cash.cashFlows().map {
                    // amountBase 是写入时算好的本位币值；历史数据若为 0/缺失，用 原币 × 汇率 兜底
                    val base = if (it.amountBase != 0.0) it.amountBase
                    else it.amountOrig * (it.fxRate.takeIf { r -> r > 0.0 } ?: 1.0)
                    CashFlow(
                        dateIso = it.flowDate,
                        amount = if (it.type == "DEPOSIT") -kotlin.math.abs(base)
                        else kotlin.math.abs(base),
                    )
                }
                val hasFlow = cfFlows.isNotEmpty()
                val earliestFact = listOfNotNull(
                    txs.minOfOrNull { it.tradeDate },
                    cfFlows.minOfOrNull { it.dateIso },
                ).minOrNull()
                val allFlows = ReturnCalculator.accountFlows(
                    openingCash = repo.openingCash(),
                    openingAnchorIso = earliestFact,
                    explicit = cfFlows,
                    terminalValue = snapshot.totalAsset,
                    terminalDateIso = todayIso(),
                )

                _state.update {
                    it.copy(
                        loading = false,
                        stats = PerformanceCalculator.stats(closed),
                        streaks = PerformanceCalculator.streaks(closed),
                        tagGroups = PerformanceCalculator.byTag(closed),
                        marketGroups = PerformanceCalculator.byMarket(securities, closed),
                        realizedTotal = snapshot.realizedPnlTotal,
                        unrealizedTotal = snapshot.unrealizedPnlTotal,
                        // 分红累计来自「分红送股登记」，登记 UI 已在 M2 落地（标的地「分红送股 ›」）
                        // H4 修复（2026-09-27）：分红累计由 snapshot 给出**已折本位币**的口径
                        // （此前这里直接 sumOf { dividendCash }，那是原币 —— 与本页其它指标不同币种）
                        dividendTotal = snapshot.dividendCashTotal,
                        dividendCount = dividends,
                        hasCashFlow = hasFlow,
                        xirr = ReturnCalculator.xirr(allFlows),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(loading = false, error = "加载失败：${t.message ?: t::class.simpleName}")
                }
            }
        }
    }
}

@Composable
fun rememberAnalysisHolder(
    repo: PortfolioRepository,
    cash: CashRepository,
): AnalysisHolder {
    val scope = rememberCoroutineScope()
    return remember(repo, cash) { AnalysisHolder(repo, cash, scope) }
}
