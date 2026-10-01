package com.stocknote.feature.state

import com.stocknote.core.calc.PlanPricing
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradePlan
import com.stocknote.core.model.TradeSide
import com.stocknote.data.platform.todayIso
import com.stocknote.data.repo.PlanRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.data.repo.QuoteRepository
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 交易计划列表页状态（REQ-PLAN-01）。
 *
 * 只做「读 + 筛选 + 排序 + 汇总」，写操作一律走 [PlanEditHolder]。
 * ⚠️ 这里的数据与账本**完全隔离**：不读 transaction、不算持仓，只读 trade_plan 与标的/行情。
 */
class PlanHolder(
    /**
     * 其它域（标的 / 行情等）仍走原仓储 —— 拆分是渐进的，等对应域 Repository 稳定后再逐个切过来。
     */
    /** 标的列表等尚未拆出独立域，仍走原仓储。 */
    private val repo: PortfolioRepository,
    /** **计划域**：列表读取与完成标记。 */
    private val plan: PlanRepository,
    /** **行情域**：缓存行情读取与联网刷新。 */
    private val quote: QuoteRepository,
    /** **标的域**（第 11 批）。 */
    private val security: SecurityRepository,
    private val scope: CoroutineScope,
) {

    /** 方向筛选（原型 chips：全部 / 计划买入 / 计划卖出） */
    enum class Filter(val label: String) { ALL("全部"), BUY("计划买入"), SELL("计划卖出") }

    /** 排序（原型 chips：最近创建 / 距达成最近 / 金额↓） */
    enum class Sort(val label: String) { RECENT("最近创建"), NEAREST("距达成最近"), AMOUNT("金额 ↓") }

    /** 列表一行：计划 + 所属标的 + 现价 + 距达成空间 */
    data class PlanRow(
        val plan: TradePlan,
        val security: Security?,
        val marketPrice: Double?,
        /** 距达成空间 = (计划价 − 现价)/现价；null = 无行情，UI 不展示 */
        val gap: Double?,
        /** 计划金额 = 价×量×(1±费率) */
        val amount: Double,
    )

    data class UiState(
        val loading: Boolean = false,
        /** 筛选 + 排序后的结果（已完成的排在最后） */
        val rows: List<PlanRow> = emptyList(),
        /** 全部（未筛选），用于统计条数与合计 */
        val allRows: List<PlanRow> = emptyList(),
        val filter: Filter = Filter.ALL,
        val sort: Sort = Sort.RECENT,
        /** 搜索词（老周 2026-09-19：与持仓页同款，🔍 展开，按名称/代码过滤） */
        val searchText: String = "",
        /**
         * 计划金额合计（当前筛选口径）——**按币种分组**（老周 2026-09-21）。
         * 港币与人民币直接相加没有意义，所以分币种列：`HK$3,000.00 · ¥8,000.00`。
         * 老周 2026-09-22：**只统计未完成的** —— 完成的计划钱已经按事实走账本了，
         * 这里合计的是「还没执行的意向」。
         */
        val totals: List<Pair<String, Double>> = emptyList(),
        /**
         * 「全部」筛选下的**买卖分列**合计（老周 2026-09-22）：币种 code → (买入合计, 卖出合计)。
         * 只在 Filter.ALL 时填充（BUY/SELL 筛选下列表本来就是单方向，totals 已够）；
         * 同样只算未完成、原币口径，不与 totals 混用。
         */
        val splitTotals: Map<String, Pair<Double, Double>> = emptyMap(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 最近一次加载的原始数据 —— 改筛选/排序/搜索词时直接用它在内存里重算，不必回库 */
    private var lastPlans: List<TradePlan> = emptyList()
    private var lastSecById: Map<String, Security> = emptyMap()
    private var lastPrices: Map<String, Double> = emptyMap()

    fun load() {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // ⚠️ 2026-09-28 拆分：计划列表改走 PlanRepository（委托式，行为不变）
            val plans = runCatching { plan.list() }.getOrDefault(emptyList())
            val securities = runCatching { security.listSecurities() }.getOrDefault(emptyList())
            val secById = securities.associateBy { it.id }

            // 现价先读缓存（离线可用、即时出结果），再后台补一次行情
            val prices = LinkedHashMap<String, Double>()
            securities.forEach { s ->
                // ⚠️ 2026-09-28 拆分：缓存行情改走 QuoteRepository
                val q = runCatching { quote.cached(s.symbol) }.getOrNull()
                if (q != null && q.price > 0) prices[s.symbol] = q.price
            }
            applyRows(plans, secById, prices)

            // 后台刷新行情（失败静默，保留缓存价）
            launch {
                plans.map { it.securityId }.distinct().forEach { id ->
                    val sec = secById[id] ?: return@forEach
                    // ⚠️ 2026-09-28 拆分：联网刷新行情改走 QuoteRepository
                    val q = runCatching { quote.refresh(sec.symbol) }.getOrNull()
                    if (q != null && q.price > 0) prices[sec.symbol] = q.price
                }
                applyRows(plans, secById, prices)
            }
        }
    }

    private fun applyRows(
        plans: List<TradePlan>,
        secById: Map<String, Security>,
        prices: Map<String, Double>,
    ) {
        lastPlans = plans
        lastSecById = secById
        lastPrices = prices

        val all = plans.map { p ->
            val sec = secById[p.securityId]
            val price = sec?.let { prices[it.symbol] }
            PlanRow(
                plan = p,
                security = sec,
                marketPrice = price,
                gap = PlanPricing.gapOf(price, p.plannedPrice),
                amount = p.planAmount,
            )
        }
        _state.update { st ->
            val kw = st.searchText.trim()
            val searched = if (kw.isEmpty()) all else all.filter { r ->
                val sec = r.security
                sec != null && (
                    sec.name.contains(kw, ignoreCase = true) ||
                        sec.symbol.contains(kw, ignoreCase = true)
                    )
            }
            val filtered = searched.filter { r ->
                when (st.filter) {
                    Filter.ALL -> true
                    Filter.BUY -> r.plan.side == TradeSide.BUY
                    Filter.SELL -> r.plan.side == TradeSide.SELL
                }
            }
            val sorted = sortRows(filtered, st.sort)
                // 完成的排最后（老周 2026-09-22）：stable 排序，组内仍按上面选的口径排
                .sortedBy { if (it.plan.isDone) 1 else 0 }
            // 合计按**币种**分组（老周 2026-09-21）：计划金额是原币口径，混币相加会得出假数。
            // 只算**未完成**（老周 2026-09-22）—— 完成的意向已落地，不该再占「待执行金额」。
            val pending = filtered.filter { !it.plan.isDone }
            val totals = pending
                .groupBy { it.security?.currency?.code ?: "CNY" }
                .map { (code, list) -> code to list.sumOf { it.amount } }
                .sortedBy { it.first }
            // 「全部」条件再按**买卖分列**（老周 2026-09-22）：混方向的合计没意义——
            // 买入是「要花的钱」、卖出是「能回的钱」，得分开看。BUY/SELL 筛选下本身就是单方向，不拆。
            val splitTotals = if (st.filter == Filter.ALL) {
                pending
                    .groupBy { it.security?.currency?.code ?: "CNY" }
                    .mapValues { (_, list) ->
                        list.filter { it.plan.side == TradeSide.BUY }.sumOf { it.amount } to
                            list.filter { it.plan.side == TradeSide.SELL }.sumOf { it.amount }
                    }
            } else {
                emptyMap()
            }
            st.copy(
                loading = false,
                allRows = all,
                rows = sorted,
                totals = totals,
                splitTotals = splitTotals,
            )
        }
    }

    /** 用最近一次的数据在内存里重算（筛选 / 排序 / 搜索词变化时调用） */
    private fun recompute() = applyRows(lastPlans, lastSecById, lastPrices)

    private fun sortRows(rows: List<PlanRow>, sort: Sort): List<PlanRow> = when (sort) {
        // 最近创建：created_at 倒序（数值越大越新）
        Sort.RECENT -> rows.sortedByDescending { it.plan.createdAt }
        // 距达成最近：|gap| 升序；无行情的排最后
        Sort.NEAREST -> rows.sortedWith(
            compareBy(nullsLast()) { r -> r.gap?.let { kotlin.math.abs(it) } },
        )
        // 金额降序
        Sort.AMOUNT -> rows.sortedByDescending { it.amount }
    }

    fun setFilter(f: Filter) {
        _state.update { it.copy(filter = f) }
        recompute()
    }

    fun setSort(s: Sort) {
        _state.update { it.copy(sort = s) }
        recompute()
    }

    /** 搜索词变化：只用内存数据重算，不回库也不重新拉行情（与持仓页体验一致） */
    fun setSearchText(v: String) {
        _state.update { it.copy(searchText = v) }
        recompute()
    }

    /**
     * 行上标记 / 取消完成（老周 2026-09-22）。
     * 写库成功后**就地改内存**再重算 —— 不走 load()：行情缓存不丢、页面不闪。
     */
    fun setDone(planId: String, done: Boolean) {
        scope.launch {
            // ⚠️ 2026-09-28 拆分：完成标记改走 PlanRepository
            val err = runCatching { plan.setDone(planId, done) }.exceptionOrNull()
            if (err != null) {
                _state.update { it.copy(error = "标记失败：${err.message}") }
                return@launch
            }
            lastPlans = lastPlans.map {
                if (it.id == planId) {
                    it.copy(doneAt = if (done) todayIso() else null)
                } else {
                    it
                }
            }
            recompute()
        }
    }

    /**
     * 从标的详情页「🎯 查看计划」跳进来时的预置筛选（老周 2026-09-19）：
     * **按该标的搜索 + 按「距达成最近」排序**（方向筛选取「全部」，避免把该标的的计划筛掉）。
     * 这里要回库 load：计划列表可能从未加载过（用户直接进的详情页）。
     */
    fun applyQuickFilter(keyword: String, sort: Sort = Sort.NEAREST) {
        _state.update {
            it.copy(searchText = keyword, sort = sort, filter = Filter.ALL)
        }
        load()
    }
}

/**
 * ⚠️ 2026-09-28 拆分：改为**双依赖** —— `repo` 供标的/行情等其它域，`plan` 供计划域。
 * 拆分是渐进的，等其它域 Repository 稳定后再把剩下的也切过去。
 */
@Composable
fun rememberPlanHolder(
    repo: PortfolioRepository,
    plan: PlanRepository,
    quote: QuoteRepository,
    security: SecurityRepository,
): PlanHolder {
    val scope = rememberCoroutineScope()
    return remember(repo, plan, quote) { PlanHolder(repo, plan, quote, security, scope) }
}
