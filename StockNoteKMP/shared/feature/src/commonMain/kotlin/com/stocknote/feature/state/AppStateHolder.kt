package com.stocknote.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.stocknote.core.calc.PerformanceCalculator
import com.stocknote.core.model.Market
import com.stocknote.core.model.PortfolioSnapshot
import com.stocknote.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 底部导航的三个一级页面。M0 用手写状态机而不是 Navigation 库，先把链路跑通。 */
enum class Screen(val label: String) {
    STATISTIC("统计"),
    HOLDINGS("持仓"),
    /** 探索（老周 2026-09-24）：底部导航 4 项 → 5 项，插在正中间；首个功能是「个股新闻」 */
    EXPLORE("探索"),
    /** 交易计划（REQ-PLAN-01，老周 2026-09-19）：底部导航 3 项 → 4 项，插在持仓与分析之间 */
    PLAN("计划"),
    ANALYSIS("分析"),
}

data class AppUiState(
    // 老周 2026-09-25：**打开 App 默认落在「探索」页**（此前是统计页）
    val screen: Screen = Screen.EXPLORE,
    val loading: Boolean = true,
    val snapshot: PortfolioSnapshot? = null,
    val priceSeries: List<Double> = emptyList(),
    val priceSeriesTitle: String = "",
    val storageInfo: String = "",
    /** FIFO 平仓胜率（M3 口径）。null = 尚无平仓样本 */
    val winRate: Double? = null,
    val closedCount: Int = 0,
    val error: String? = null,

    // ---- M4 数据真值（资产曲线，供统计页回撤 / 热力图 / 收益分析共用）----
    /** 逐日总资产点（升序） */
    val equityPoints: List<com.stocknote.core.calc.EquityCurve.Point> = emptyList(),
    /**
     * 逐日**出入金净额**（date -> 本位币，存入为正），与 [equityPoints] 同源、同一次刷新。
     * 盈亏日历要用它把入金从「当日盈亏」里剔除 —— 入金不是赚的钱（老周 2026-09-29）。
     */
    val equityDailyCashFlows: Map<String, Double> = emptyMap(),
    /**
     * securityId -> 标的名称（老周 2026-10-01）。
     *
     * 盈亏日历「点某天看明细」要按标的列盈亏，而 `EquityCurve.Point.pnlBySecurity` 里只有 id。
     * ⚠️ 必须放在这里、而不是让日历页去读 `equityCurveResult` —— 后者**只在分析页可见时才拉取**，
     * 从持仓页直接点「盈亏日历」进来时它是 null，明细里就只能显示一串 id。
     */
    val equitySecurityNames: Map<String, String> = emptyMap(),
    /** 最大回撤（0~1，如 0.1376 = 13.76%）；null = 尚未算出 */
    val maxDrawdown: Double? = null,
    /** 回撤区间文案，如「峰值 2026-03-02 → 谷值 2026-04-15」 */
    val drawdownRange: String = "",
    val equityLoading: Boolean = false,
    /** 曲线不可用时给**具体**原因（网络失败 / 无数据 / 无交集），不一律写"需要联网" */
    val equityError: String? = null,
    /** 汇率表（设置页维护 / 每日自动更新）—— 持仓页等展示"当前折算汇率"用（老周 2026-09-16） */
    val fxRates: List<com.stocknote.core.model.FxRateRecord> = emptyList(),
    /** 检测到的「未登记分红送配」（老周 2026-09-16：半自动，用户确认后才写账本） */
    val pendingDividends: List<com.stocknote.data.repo.PortfolioRepository.PendingDividend> = emptyList(),
    val dividendChecking: Boolean = false,
    val dividendMessage: String? = null,
    /** 分红检测**失败原因**（网络 / 接口不可用）；非 null 时界面必须**可见地**提示（老周 2026-09-30）。 */
    val dividendError: String? = null,
    /** 取数**失败**的标的数（部分失败时提示"可能漏检"）。 */
    val dividendFailedCount: Int = 0,
    /**
     * 每次分红检测**完成**自增（老周 2026-09-30）。
     *
     * UI 据此判断"这是一次新的检测结果"，从而**自动弹出登记页一次** ——
     * 而不是每次重组都弹、也不是像旧版那样把提示卡埋在持仓列表里（老周真机看不到）。
     */
    val dividendScanToken: Int = 0,
    /** 手动全量刷新进行中（统计页 🔄 转圈用）—— 老周 2026-09-18 */
    val refreshing: Boolean = false,
    /** 手动刷新的精简结果；非 null 时弹提示，点「知道了」清空 —— 老周 2026-09-18 */
    val refreshMessage: String? = null,

    // ---- 今年收益（老周 2026-09-28，持仓页卡片）----
    /**
     * 今年收益（本位币）= **当前总资产 − 年初总资产 − 年内净入金**（老周定的「账户年内增值」口径）。
     * null = 尚未算出（等资产曲线就绪 —— 年初资产取自曲线的 1 月 1 日点）。
     */
    val yearGain: Double? = null,
    /**
     * 今年收益率 = **账户年内 XIRR**（期初 = 年初资产、期内 = 年内出入金、期末 = 当前总资产）。
     * null = 不可计算（曲线未就绪 / 年内无现金流且期初期末同号 / 求根失败）。
     */
    val yearRate: Double? = null,
    /**
     * 今年收益的**起算日**（yyyy-MM-dd）。正常是 `YYYY-01-01`；
     * ⚠️ 若账本是当年才建的（曲线起点晚于 1/1），会降级为曲线最早点 —— 界面需据此标注。
     */
    val yearStartDate: String = "",
    /** 起算日是否真的是当年 1 月 1 日（false = 降级口径「自入市以来」）。 */
    val yearIsFullYear: Boolean = true,
)

/**
 * 应用状态持有者。
 *
 * 用 StateFlow + UDF 单向数据流，与最终 MVVM 的形状一致；
 * 差别只是 M0 阶段由 `remember` 持有而不是挂到 ViewModel 上
 * （lifecycle-viewmodel 的 KMP 版要等骨架验证通过后再引入，避免同时引入两个不确定性）。
 */
class AppStateHolder(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    fun select(screen: Screen) = _state.update { it.copy(screen = screen) }

    /**
     * 重新载入。
     *
     * @param refreshQuotes 是否**等待**联网行情后再出结果（慢但准，启动/下拉刷新用）
     * @param backgroundQuotesAfter 先用缓存**快速出结果**，再联网补一次行情并二次刷新。
     *   账本变动（记一笔 / 出入金 / 分红登记）后用这个 —— 否则新数据没有行情，
     *   持仓页会退化成「成本总价」且浮动盈亏显示 0（老周 2026-09-18 报）。
     */
    fun load(refreshQuotes: Boolean = true, backgroundQuotesAfter: Boolean = false) {        scope.launch {
            // 每天首次打开 App：自动更新港币/美元汇率（没有则新增）—— 老周 2026-09-16
            runCatching { container.portfolio.autoUpdateFxRatesIfNeeded() }
            _state.update { it.copy(loading = true, error = null) }
            try {
                val snapshot = container.portfolio.loadSnapshot(refreshQuotes = refreshQuotes)

                // 图表中心已删除"标的行情走势"卡（老周 2026-09-15），
                // 这里不再为它拉 60 日收盘 —— 省一次无谓的行情请求（老周 2026-09-16）
                val tradeCount = container.trade.tradeCount()

                // 统计页胜率卡（M3 已交付，不再占位）：FIFO 配平仓口径，与分析页同源。
                // ⚠️ 走统计口径（statsScope）：账外标的（场外基金「不计入统计」）不进胜率（老周 2026-09-20）
                val statsScope = container.portfolio.statsScope()
                val closed = PerformanceCalculator.fifoCloseAll(statsScope.securities, statsScope.transactions)
                val perfStats = PerformanceCalculator.stats(closed)

                val fx = runCatching { container.portfolio.fxRates() }.getOrDefault(emptyList())

                _state.update { prev ->
                    prev.copy(
                        loading = false,
                        snapshot = snapshot,
                        fxRates = fx,
                        // priceSeries / priceSeriesTitle 随行情走势卡一并停更（图表中心已改版）
                        priceSeries = emptyList(),
                        priceSeriesTitle = "",
                        storageInfo = "加密库已就绪 · $tradeCount 笔流水 · ${snapshot.positions.size} 个持仓",
                        winRate = perfStats.winRate,
                        closedCount = perfStats.closedCount,
                        error = null,
                    )
                }

                // 今年收益（老周 2026-09-28）：snapshot 就绪后**补算一次** ——
                // loadEquity 与 load 是并行跑的，若曲线先算完、那时 snapshot 还是 null，
                // YTD 就会算不出来且**之后不再重算**（真机实测显示「—」即此因）。
                refreshYearToDate()

                // ⚠️ M10 自检（2026-09-28）：库里若同时存在 sh511880 与 sh511660 两个货币 ETF 标的，
                // 提示用户去重（`5.sqm` 迁移已声明该风险，但运行时从未检查过）。
                // 只在真出现时写日志，不打扰正常用户。
                runCatching {
                    val conflicts = container.portfolio.legacyCashEquivalentConflicts()
                    if (conflicts.isNotEmpty()) {
                        // 改用 SnLog：既能进 logcat，也能被「导出运行日志」带出去（老周 2026-10-01）
                        com.stocknote.data.log.SnLog.w(
                            "SELFCHECK",
                            "检测到货币 ETF 标的并存：$conflicts —— " +
                                "5.sqm 迁移声明过该冲突会导致升级失败；当前表现为两个重复标的，建议删除其中一个。",
                        )
                    }
                }

                // 二次刷新：联网补行情（不阻塞首屏，拿到后只覆盖 snapshot）
                if (backgroundQuotesAfter) {
                    val fresh = runCatching {
                        container.portfolio.loadSnapshot(refreshQuotes = true)
                    }.getOrNull()
                    if (fresh != null) _state.update { it.copy(snapshot = fresh) }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "加载失败：${t.message ?: t::class.simpleName ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /**
     * 计算「今年收益 / 今年收益率」（老周 2026-09-28）。
     *
     * 口径（老周定「账户年内增值」）：
     *   - 今年收益 = 当前总资产 − 年初总资产 − 年内净入金
     *   - 今年收益率 = 账户年内 XIRR（期初 = 年初资产、期内 = 年内出入金、期末 = 当前总资产）
     *
     * ⚠️ 两个前提：① snapshot 已加载；② 曲线里有 `<= 当年 1/1` 的点（曲线窗口 365 天，正常含 1/1）。
     * 任一缺失就**直接返回、保持原值**（不写 null）—— 免得一次时序竞争把已算好的数字清掉。
     */
    private suspend fun refreshYearToDate() {
        val s = _state.value
        val snap = s.snapshot ?: return
        val today = com.stocknote.data.platform.todayIso()
        val yearStart = today.take(4) + "-01-01"
        // 年初资产优先取「<= 当年 1/1 的最后一个点」；
        // ⚠️ 取不到时**降级用曲线最早点**（真实场景：当年才建账，曲线起点晚于 1/1，如演示数据 2026-02-08），
        //    此时口径实际是「自入市以来」，故把起算日记进 yearStartDate 供界面标注。
        val pointAtYearStart = s.equityPoints.lastOrNull { it.date <= yearStart }
        val startPoint = pointAtYearStart ?: s.equityPoints.firstOrNull() ?: return
        val startIsYearStart = pointAtYearStart != null
        val currentAsset = snap.totalAsset
        val yearFlows = runCatching { container.cash.cashFlowsSince(startPoint.date) }
            .getOrDefault(emptyList())
        val netInflow = -yearFlows.sumOf { it.amount }
        val gain = currentAsset - startPoint.totalAsset - netInflow
        // 收益率（老周 2026-09-28 定：**简单口径**，不用 XIRR）：
        //   今年收益率 = 今年收益 ÷ 年初资产；⚠️ 年初资产为 0（账本当年才建）时退回 ÷ 年内净投入。
        val denominator = if (startPoint.totalAsset > 0) startPoint.totalAsset else netInflow
        val rate = if (denominator > 0) gain / denominator else null
        _state.update {
            it.copy(
                yearGain = gain,
                yearRate = rate,
                yearStartDate = startPoint.date,
                yearIsFullYear = startIsYearStart,
            )
        }
    }

    /**
     * 手动全量刷新（统计页 🔄，老周 2026-09-18）。
     *
     * 与启动时自动更新的区别：**忽略缓存与幂等判断**，汇率、全部持仓行情重新联网取一遍，
     * 顺手清理 3 天前的汇率记录，然后重算快照 / 胜率 / 资产曲线 / 待登记分红。
     * 全 App 各页面读的是同一份 state，所以会一起跟着刷新。
     *
     * 完成后把精简结果写进 [AppUiState.refreshMessage] 供 UI 弹提示。
     */
    fun refreshAll() {
        if (_state.value.refreshing) return
        scope.launch {
            _state.update { it.copy(refreshing = true, refreshMessage = null, error = null) }
            val report = runCatching { container.portfolio.refreshRemoteData() }.getOrNull()

            try {
                // 行情/汇率已落库，这里用缓存重算（不再联网，快）
                val snapshot = container.portfolio.loadSnapshot(refreshQuotes = false)
                val tradeCount = container.trade.tradeCount()
                // 同上：统计口径（剔除账外备忘标的）—— 老周 2026-09-20
                val statsScope = container.portfolio.statsScope()
                val closed = PerformanceCalculator.fifoCloseAll(statsScope.securities, statsScope.transactions)
                val perfStats = PerformanceCalculator.stats(closed)
                val fx = runCatching { container.portfolio.fxRates() }.getOrDefault(emptyList())

                _state.update { prev ->
                    prev.copy(
                        refreshing = false,
                        snapshot = snapshot,
                        fxRates = fx,
                        storageInfo = "加密库已就绪 · $tradeCount 笔流水 · ${snapshot.positions.size} 个持仓",
                        winRate = perfStats.winRate,
                        closedCount = perfStats.closedCount,
                        refreshMessage = buildRefreshMessage(report),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        refreshing = false,
                        refreshMessage = "刷新完成，但重算失败：${t.message ?: t::class.simpleName}",
                    )
                }
            }

            // 曲线 / 分红异步重算，不拖着提示
            loadEquity(force = true)
            checkDividends()
        }
    }

    /** 关闭刷新结果提示 */
    fun dismissRefreshMessage() = _state.update { it.copy(refreshMessage = null) }

    /** 把刷新结果拼成**精简**提示（老周 2026-09-18：结果要精简） */
    private fun buildRefreshMessage(
        r: com.stocknote.data.repo.PortfolioRepository.RefreshReport?,
    ): String {
        if (r == null) return "刷新失败：网络不可用，请稍后重试"
        val quoteLine = if (r.quoteTotal == 0) "行情 · 暂无持仓标的"
        else "行情 · ${r.quoteOk}/${r.quoteTotal} 只已更新"
        val fxLine = if (r.fx.isEmpty()) {
            "汇率 · 未取到" + if (r.fxFailed.isEmpty()) "" else "（${r.fxFailed.joinToString("/")}）"
        } else {
            "汇率 · " + r.fx.entries.joinToString("，") { (k, v) ->
                "$k ${com.stocknote.core.format.Format.money(v, "", decimals = 4)}"
            }
        }
        return quoteLine + "\n" + fxLine + "\n汇率记录 · 已保留最近 3 天"
    }

    /**
     * 检测未登记的分红送配（老周 2026-09-16；失败可见 2026-09-30）。
     *
     * 只检测不写入（半自动），结果放 state → 持仓页**自动弹出**登记页。
     * ⚠️ 失败必须**如实告知**：此前"取数失败"与"确实没分红"都表现为空列表，界面毫无反馈，
     * 老周真机反馈"重启和刷新都看不到提示卡"（实为静默失败）。
     */
    fun checkDividends() {
        scope.launch {
            _state.update {
                it.copy(
                    dividendChecking = true,
                    dividendError = null,
                    dividendMessage = null,
                    dividendFailedCount = 0,
                )
            }
            runCatching { container.portfolio.detectPendingDividends() }
                .onSuccess { r ->
                    // 参与扫描的标的**全部**取数失败 → 明确报「检测失败」，而不是让用户以为"没有分红"
                    val err = if (r.scanned > 0 && r.ok == 0 && r.failed > 0) {
                        "分红检测失败：${r.failed} 个标的的数据源均不可用（网络或接口异常），本次没有取到任何分红数据"
                    } else {
                        null
                    }
                    _state.update {
                        it.copy(
                            dividendChecking = false,
                            pendingDividends = r.items,
                            dividendError = err,
                            dividendFailedCount = r.failed,
                            dividendScanToken = it.dividendScanToken + 1,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            dividendChecking = false,
                            pendingDividends = emptyList(),
                            dividendError = "分红检测失败：" + (e.message ?: e::class.simpleName ?: "未知错误"),
                            dividendScanToken = it.dividendScanToken + 1,
                        )
                    }
                }
        }
    }

    /** 用户确认后登记（全部或部分） */
    fun registerDividends(
        items: List<com.stocknote.data.repo.PortfolioRepository.PendingDividend>,
        onDone: () -> Unit,
    ) {
        scope.launch {
            val n = runCatching { container.portfolio.registerDividends(items) }.getOrDefault(0)
            _state.update {
                it.copy(
                    pendingDividends = it.pendingDividends.filterNot { p -> items.any { i -> i.exDate == p.exDate && i.securityId == p.securityId } },
                    dividendMessage = "已登记 $n 条（现金分红计入现金，送股计入持仓）",
                )
            }
            onDone()
        }
    }

    /**
     * 异步算资产曲线（M4 数据真值）：产出逐日总资产 + 最大回撤。
     *
     * 刻意与 [load] 分开：曲线要拉全部标的的历史 K 线（较慢），
     * 放进 load 会拖慢统计页首屏。这里后台算、算完只更新曲线相关字段，
     * 统计页回撤卡先显示占位、就绪后自动变成真值。
     *
     * @param force true = 忽略缓存重算（账本变动后）
     */
    fun loadEquity(force: Boolean = false) {
        scope.launch {
            _state.update { it.copy(equityLoading = true, equityError = null) }
            // ⚠️ P1-21 修复（2026-10-02）：**整段**都必须在兜底之内。
            // 原来只把 `equityCurveCached` 包了 runCatching，而下面「今年收益」段里的
            // `container.cash.cashFlowsSince(yearStartPoint.date)` 是**裸调** —— 它一抛异常，
            // 协程体直接结束，后面所有 `_state.update` 都不执行 → `equityLoading` 永久停在 true、
            // `equityError` 仍是 null。后果：① 统计页「最大回撤」/「盈亏日历」一直转圈且**没有任何文案**，
            // 导出的运行日志里也查不到；② 启动图放行条件依赖
            // `!equityLoading && (maxDrawdown != null || equityError != null)`（App.kt）→ 一直卡到 5 秒上限。
            // 同文件的 load() / refreshAll() / checkDividends() 都做了兜底，这里属**遗漏而非设计**。
            try {
                val result = runCatching {
                    container.portfolio.equityCurveCached(refresh = force)
                }.getOrNull()

                val data = result?.data
                if (data == null || data.points.isEmpty()) {
                    _state.update {
                        it.copy(
                            equityLoading = false,
                            equityError = result?.reason ?: "资产曲线暂时算不出来",
                        )
                    }
                    return@launch
                }
                val dd = data.maxDrawdown
                // ---- 今年收益（老周 2026-09-28）----
                // 挂在曲线之后算：**年初资产取自曲线的 1 月 1 日点**，所以曲线没就绪时只能显示"—"。
                val today = com.stocknote.data.platform.todayIso()
                val yearStart = today.take(4) + "-01-01"
                val snapForYear = _state.value.snapshot
                // 曲线里 <= 年初 的最后一个点（曲线窗口 365 天，正常情况下已含 1/1）
                val yearStartPoint = data.points.lastOrNull { it.date <= yearStart }
                    ?: data.points.firstOrNull()   // 降级：账本当年才建（曲线起点晚于 1/1）→ 用最早点并标注
                var yGain: Double? = null
                var yRate: Double? = null
                if (snapForYear != null && yearStartPoint != null) {
                    val currentAsset = snapForYear.totalAsset
                    // ⚠️ 2026-09-30 修复：这里必须用 **yearStartPoint.date**（真正当成期初的那一点），
                    // 不能用自然年 `yearStart`(= YYYY-01-01)：
                    // 账本当年才建时 yearStartPoint 会降级成**曲线首点**（如 2026-02-02），
                    // 而该点的资产快照**已经含了当天及之前的所有入金** —— 再用 1/1 去取流水，
                    // 会把那笔入金当成"年内净投入"再算一遍 → 今年收益凭空少一整笔。
                    // 真机实测（演示数据）：2-02 入金 50 万被重复计入，今年收益 +3.0 万错成 −46.98 万（−93.9%）。
                    // 与 CashRepository.cashFlowsSince 的 KDoc「必须严格大于起算日」是同一条规矩。
                    val yearFlows = container.cash.cashFlowsSince(yearStartPoint.date)
                    // 出入金带符号（存入为负）→ 取反得到「年内净入金」
                    val netInflow = -yearFlows.sumOf { it.amount }
                    yGain = currentAsset - yearStartPoint.totalAsset - netInflow
                    // 账户年内 XIRR：期初（负）→ 年内各笔出入金 → 期末（正，当前总资产）
                    // 收益率：简单口径（老周 2026-09-28）= 收益 ÷ 年初资产；年初为 0 → ÷ 年内净投入
                    yRate = run {
                        val denom = if (yearStartPoint.totalAsset > 0) yearStartPoint.totalAsset else netInflow
                        if (denom > 0) yGain / denom else null
                    }
                }
                _state.update {
                    it.copy(
                        equityLoading = false,
                        equityPoints = data.points,
                        equityDailyCashFlows = data.dailyCashFlows,
                        // 名称与曲线同源带过来，供盈亏日历明细按标的显示（老周 2026-10-01）
                        equitySecurityNames = data.securityNames,
                        yearGain = yGain,
                        yearRate = yRate,
                        yearStartDate = yearStartPoint?.date ?: "",
                        yearIsFullYear = yearStartPoint?.let { it.date <= yearStart } ?: true,
                        maxDrawdown = dd.maxDrawdown,
                        // 老周 2026-09-23 真机反馈：原「峰值 2026-04-16 → 谷值 2026-05-01」在半宽卡里
                        // 被省略号吃掉。改成只留区间「04-16↘05-01」——半宽卡一定放得下，
                        // 且不再重复百分比（上面的大字已经写着回撤幅度了）。
                        drawdownRange = if (dd.maxDrawdown > 0 && dd.peakDate.isNotBlank()) {
                            "${dd.peakDate.drop(5)}↘${dd.troughDate.drop(5)}"
                        } else {
                            "区间内无回撤"
                        },
                        equityError = null,
                    )
                }
            } catch (t: Throwable) {
                // 兜底（P1-21）：无论哪一步炸（曲线计算 / 取年内流水 / 状态写入），
                // 标志位都必须**收敛**，原因同时给用户（equityError）与运行日志（SnLog）。
                // ⚠️ 刻意**不清空** equityPoints / maxDrawdown：上一次算出的曲线仍然有效，
                //    清掉反而把界面从"有数据"打回"待数据"。
                com.stocknote.data.log.SnLog.e("EQUITY", "资产曲线 / 今年收益刷新失败", t)
                _state.update {
                    it.copy(
                        equityLoading = false,
                        equityError = "资产曲线刷新失败：" +
                            (t.message ?: t::class.simpleName ?: "未知错误"),
                    )
                }
            }
        }
    }
}

@Composable
fun rememberAppStateHolder(container: AppContainer): AppStateHolder {
    val scope = rememberCoroutineScope()
    return remember(container) { AppStateHolder(container, scope) }
}
