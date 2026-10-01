package com.stocknote.feature.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stocknote.data.net.FinanceSource
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.repo.WatchlistRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.PortfolioRepository

/**
 * 个股财务数据页状态（老周 2026-09-24，依据《东方财富F10接口技术文档》）。
 *
 * 职责：当前标的 · 各报告期的指标值 · 当前查看的报告期 · 加载与错误态。
 * 数据全部来自 [QuoteClient.fetchFinance]（东财 F10），**只有 A股/港股有**。
 */
class FinanceHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-29 拆分（第 5 批）：自选域（取"最近用过的标的"）。 */
    private val watch: WatchlistRepository,
    private val quotes: QuoteClient,
    /** **标的域**（第 11 批）。 */
    private val security: SecurityRepository,
) {
    var symbol by mutableStateOf("")
        private set
    var name by mutableStateOf("")
        private set

    /** 数据来源市场标签：A股 / 港股 / 不支持 */
    var marketLabel by mutableStateOf("")
        private set

    /** 报告期列表（倒序：最新在前） */
    var reports by mutableStateOf<List<FinanceSource.Report>>(emptyList())
        private set

    /** 当前查看的报告期下标 */
    var periodIndex by mutableStateOf(0)
        private set

    var loading by mutableStateOf(false)
        private set

    /** 提示文案（无数据/不支持/失败都用它，UI 不做判断） */
    var hint by mutableStateOf<String?>(null)
        private set

    /**
     * **当前估值**（PE / PB / 股息率），key 与 [FinanceSource.Metric.key] 一致。
     * 只有 **A股** 需要（F10 数据集里没有这三项，走东财行情接口补，见 QuoteClient.fetchValuation）；
     * 港股 F10 自带，这里为空表。
     */
    var valuation by mutableStateOf<Map<String, Double>>(emptyMap())
        private set

    private var bootstrapped = false

    /** 当前报告期 */
    val current: FinanceSource.Report? get() = reports.getOrNull(periodIndex)

    /** 当前标的可展示的指标（按维度分组，已过滤掉该市场没有的） */
    val groupedMetrics: List<Pair<FinanceSource.Group, List<FinanceSource.Metric>>>
        get() {
            val kind = FinanceSource.secuCode(symbol)?.second ?: return emptyList()
            val available = FinanceSource.metricsFor(kind)
            return FinanceSource.Group.entries.map { g -> g to available.filter { it.group == g } }
                .filter { it.second.isNotEmpty() }
        }

    /** 首次进入：默认自选里的**第一只 A股/港股**（美股与场外基金没有 F10 数据） */
    suspend fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        if (symbol.isEmpty()) {
            val watched = NewsHolder.watchedSecurities(repo, watch, security)
            val pick = watched.firstOrNull { FinanceSource.secuCode(it.symbol) != null }
                ?: watched.firstOrNull()
            if (pick != null) {
                symbol = pick.symbol
                name = pick.name
            }
        }
        reload()
    }

    suspend fun select(symbol: String, name: String) {
        bootstrapped = true
        this.symbol = symbol
        this.name = name
        reload()
    }

    fun selectPeriod(index: Int) {
        if (index in reports.indices) periodIndex = index
    }

    suspend fun reload() {
        if (symbol.isEmpty()) {
            hint = "还没有自选标的，点上方「切换」选择一只"
            return
        }
        val mapped = FinanceSource.secuCode(symbol)
        if (mapped == null) {
            marketLabel = "—"
            reports = emptyList()
            hint = "该市场暂无财务数据（东方财富 F10 只覆盖 A股与港股）"
            return
        }
        marketLabel = if (mapped.second == FinanceSource.Kind.A) "A股" else "港股"
        loading = true
        hint = null
        // A股：F10 数据集没有 PE/PB/股息率 → 另外从东财行情接口补（老周 2026-09-24）
        valuation = if (mapped.second == FinanceSource.Kind.A) {
            runCatching { quotes.fetchValuation(symbol) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val list = runCatching { quotes.fetchFinance(symbol, periods = 6) }.getOrDefault(emptyList())
        reports = list
        periodIndex = 0
        hint = if (list.isEmpty()) "没取到财务数据，稍后可下拉重试" else null
        loading = false
    }
}
