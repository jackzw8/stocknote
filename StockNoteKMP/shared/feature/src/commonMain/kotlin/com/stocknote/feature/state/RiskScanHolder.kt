package com.stocknote.feature.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stocknote.core.calc.RiskScanner
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.repo.CninfoRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.WatchlistRepository
import com.stocknote.data.repo.PortfolioRepository

/**
 * 个股扫雷页状态（老周 2026-09-24，规则移植自《东财F10个股风险扫雷技术方案》）。
 *
 * 职责：当前标的 · 扫描结果 · 加载与提示态。数据来自 [QuoteClient.fetchRiskScan]，
 * **只覆盖 A 股**（沪/深/北）—— 非 A 股如实提示，不硬编造。
 */
class RiskScanHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-29 拆分（第 5 批）：自选域（取"最近用过的标的"）。 */
    private val watch: WatchlistRepository,
    /** ⚠️ 2026-09-28 拆分：巨潮风险域（本地快照读取）。 */
    private val cninfo: CninfoRepository,
    private val quotes: QuoteClient,
    /** **标的域**（第 11 批）。 */
    private val security: SecurityRepository,
) {
    var symbol by mutableStateOf("")
        private set
    var name by mutableStateOf("")
        private set

    /** 市场标签（A股） */
    var marketLabel by mutableStateOf("")
        private set

    /** 扫描结果（null = 尚未成功扫描） */
    var result by mutableStateOf<RiskScanner.Result?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    /** 提示文案（不支持的市场 / 取数失败 / 未选标的） */
    var hint by mutableStateOf<String?>(null)
        private set

    private var bootstrapped = false

    /** 首次进入：默认选**自选里第一只 A股**（扫雷只覆盖 A 股） */
    suspend fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        if (symbol.isEmpty()) {
            val watched = NewsHolder.watchedSecurities(repo, watch, security)
            val pick = watched.firstOrNull {
                QuoteClient.riskSecOf(it.symbol) != null || QuoteClient.hkBareCode(it.symbol) != null
            } ?: watched.firstOrNull()
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

    /** 执行扫描（A 股 24 项 / 港股 20 项，市场自动识别） */
    suspend fun reload() {
        if (symbol.isEmpty()) {
            hint = "还没有自选标的，点上方「切换」选择一只"
            result = null
            return
        }
        val isA = QuoteClient.riskSecOf(symbol) != null
        val isHk = QuoteClient.hkBareCode(symbol) != null
        if (!isA && !isHk) {
            marketLabel = "—"
            result = null
            hint = "个股扫雷目前支持 A 股与港股 —— 美股 / 场外基金暂无数据源"
            return
        }
        marketLabel = if (isA) "A股" else "港股"
        loading = true
        hint = null

        val scanned: RiskScanner.Result? = if (isA) {
            var input = runCatching { quotes.fetchRiskScan(symbol) }.getOrNull()
            // 巨潮「诉讼 / 担保」：**只读本地快照**（cninfo_risk）—— 数据由「设置 → 诉讼担保查询」手工同步，
            // 扫雷这一侧不发任何巨潮请求（那两个接口需鉴权且返回全市场数据，体积大）。
            if (input != null) {
                val code = symbol.trim().lowercase()
                    .removePrefix("sh").removePrefix("sz").removePrefix("bj")
                val row = runCatching { cninfo.ofCode(code) }.getOrNull()
                if (row != null) {
                    input = input.copy(
                        sueCount = row.sue_count.toInt(),
                        sueAmountWan = row.sue_amount,
                        gteCount = row.gte_count.toInt(),
                        gteRatio = row.gte_ratio,
                    )
                }
            }
            input?.let { RiskScanner.build(it) }
        } else {
            runCatching { quotes.fetchHkRiskScan(symbol) }.getOrNull()?.let { RiskScanner.buildHk(it) }
        }

        result = scanned
        if (scanned == null) hint = "没取到数据（接口偶发失败），稍后可下拉重试"
        loading = false
    }
}
