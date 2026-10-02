package com.stocknote.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.stocknote.core.calc.PlanPricing
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradePlan
import com.stocknote.core.model.TradeSide
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.todayIso
import com.stocknote.data.repo.PlanRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.TagRepository
import com.stocknote.data.repo.WatchlistRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.data.repo.SettingsRepository
import com.stocknote.data.util.Ids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 新建 / 编辑交易计划的表单状态（REQ-PLAN-01，老周 2026-09-19 定稿）。
 *
 * 与记一笔 [TradeFormHolder] 的差异：
 *  - **不写账本**：只 upsert / delete `trade_plan` 这一行，不碰 transaction、不参与重放；
 *  - 字段少了：交易日期、情绪、执行评分、交易截图（归入复盘要素，计划未执行所以没有）；
 *  - 字段多了：**目标价 + 折扣**（核心：目标价 × 折扣 → 计划价，见 [PlanPricing]）；
 *  - 备注**非必填**（记一笔是必填拦截）；
 *  - 编辑态多一个**删除**动作（列表不做左滑删除，删除统一走本页底部按钮）。
 */
class PlanEditHolder(
    /** 其它域（标签 / 标的 / 自选等）仍走原仓储 —— 拆分是渐进的 */
    private val repo: PortfolioRepository,
    /** **计划域**：读取 / 保存 / 删除 */
    private val plan: PlanRepository,
    /** **设置域**：缺省费率、整手校验开关 */
    private val settings: SettingsRepository,
    /** **标签域**（第 4 批）：标签选项与新增。 */
    private val tag: TagRepository,
    /** **自选域**（第 5 批）：最近用过的标的。 */
    private val watch: WatchlistRepository,
    /** **标的域**（第 11 批）。 */
    private val security: SecurityRepository,
    private val scope: CoroutineScope,
) {

    data class UiState(
        /** 非空 = 编辑态 */
        val editPlanId: String? = null,
        val securityId: String? = null,
        val securityLabel: String = "",
        /** 标的代码（sh600519 / hk00700…）—— 拉分析师目标价要用它 */
        val securitySymbol: String? = null,
        /** 该标的是否在自选里：决定「目标价」能不能取自选 / 要不要反写回去 */
        val watched: Boolean = false,
        /** 自选里已有的目标价（null = 自选里还没设） */
        val watchTarget: Double? = null,
        /** 正在联网取分析师目标价 */
        val targetFetching: Boolean = false,
        /** 取目标价的结果提示（成功写明样本数与区间；失败/无数据如实说明） */
        val targetFetchHint: String? = null,
        val locked: Boolean = false,
        val side: TradeSide = TradeSide.BUY,
        /** 目标价（必填，> 0）：我给这只标的的估值 / 目标位 */
        val targetPrice: String = "",
        /** 折扣：null = 未选（原型默认态显示「折扣 ▾」） */
        val discount: Double? = null,
        /** 计划价 = 目标价 × 折扣（可手动覆盖；改目标价/折扣会重算覆盖） */
        val plannedPrice: String = "",
        val quantity: String = "",
        /**
         * 「整手校验」开关（老周 2026-09-28，设置里配、**缺省开**）。
         * 由界面进入时调 [refreshLotCheck] 从加密 KV 读入。
         */
        val lotCheckEnabled: Boolean = true,
        /** 手续费率，单位**万分位**（2.5 = 万分之 2.5），缺省取设置值 */
        val feeRate: String = "2.5",
        /** 计划理由 / 备注：**非必填** */
        val note: String = "",
        val tags: List<String> = emptyList(),
        val tagOptions: List<String> = emptyList(),
        val showTagCreate: Boolean = false,
        // ---- 标的搜索（与记一笔同一套：本地先出 + 联网后置）----
        val keyword: String = "",
        val candidates: List<Security> = emptyList(),
        val onlineHits: List<QuoteClient.SymbolHit> = emptyList(),
        val searchingOnline: Boolean = false,
        val searching: Boolean = false,
        /** 候选列表是「最近使用」而不是搜索结果（老周 2026-09-21：空关键词点搜索） */
        val recentMode: Boolean = false,
        /** 选中标的后的现价（用于「距达成」提示；计划表单也可参考） */
        val marketPrice: Double? = null,
        /** 表单展示币种：跟**标的所属市场**走（港股 HKD / 美股 USD / A股·ETF CNY）——老周 2026-09-21 */
        val formCurrency: Currency = Currency.CNY,
        /** 表单展示市场：只用来取数量单位（股票/场内 ETF=股，场外基金=份） */
        val formMarket: Market? = null,
        /** 已选标的的**每手股数**（老周 2026-09-28）：港股为真实值；null = 按市场兜底。 */
        val formLotSize: Double? = null,
        val issues: List<PlanPricing.Issue> = emptyList(),
        val saving: Boolean = false,
        val saved: Boolean = false,
        val deleted: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        /** 保存失败计数：每次校验失败 / 异常 +1，UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 编辑态保留原创建时间，否则 upsert 会把 createdAt 覆盖成新值（列表倒序会跳） */
    private var originCreatedAt: String? = null

    /**
     * 编辑态透传完成标记（老周 2026-09-22）：「已完成」的唯一入口在列表行尾圆点，
     * 编辑页**不改它**，但保存（INSERT OR REPLACE 整行覆盖）时必须原样带回，否则会被清掉。
     */
    private var originDoneAt: String? = null

    fun start(planId: String?) {
        scope.launch {
            _state.update { it.copy(loading = planId != null) }
            // 同记一笔（老周 2026-09-19）：标签库里为空时用**预置 6 个**兜底，
            // 否则新建计划时标签区只剩「＋ 新建」，没有可选项。
            val tagOptions = runCatching { tag.all().map { t -> t.name } }.getOrDefault(DEFAULT_TAGS)
            if (planId == null) {
                _state.update {
                    it.copy(
                        editPlanId = null,
                        loading = false,
                        tagOptions = tagOptions,
                        feeRate = it.feeRate,
                    )
                }
                // 新建态也按设置页缺省费率（A股/其它用 A 股值）
                val default = runCatching { settings.feeRateAShare() }.getOrDefault(2.5)
                _state.update { it.copy(feeRate = default.toString()) }
                return@launch
            }
            // ⚠️ 2026-09-28 拆分：改走 PlanRepository。这里用 `this@PlanEditHolder.plan`
            // 是因为本作用域有同名局部变量 `plan`（编辑中的计划），直接写 `plan` 会被遮蔽。
            val plan = runCatching { this@PlanEditHolder.plan.byId(planId) }.getOrNull()
            if (plan == null) {
                _state.update { it.copy(loading = false, error = "找不到要编辑的计划") }
                return@launch
            }
            originCreatedAt = plan.createdAt
            originDoneAt = plan.doneAt
            val security = runCatching { security.listSecurities() }.getOrDefault(emptyList())
                .firstOrNull { it.id == plan.securityId }
            _state.update {
                it.copy(
                    editPlanId = plan.id,
                    loading = false,
                    securityId = plan.securityId,
                    securityLabel = security?.let { s -> "${s.name} (${s.symbol})" }
                        ?: plan.securityId,
                    securitySymbol = security?.symbol,
                    locked = true,
                    side = plan.side,
                    targetPrice = trimNum(plan.targetPrice),
                    discount = plan.discount,
                    plannedPrice = trimNum(plan.plannedPrice),
                    quantity = trimNum(plan.quantity),
                    feeRate = trimNum(plan.feeRate),
                    note = plan.note,
                    tags = plan.tags,
                    tagOptions = tagOptions,
                    formCurrency = security?.currency ?: Currency.CNY,
                    formMarket = security?.market,
                    formLotSize = security?.lotSize,
                    marketPrice = runCatching { cachedPrice(security) }.getOrNull(),
                )
            }
            // 自选侧的目标价（「取自选 / 反写」的参照值）
            refreshWatchTarget(plan.securityId)
        }
    }

    /**
     * 读一次自选侧的目标价（老周 2026-09-21）。
     * ⚠️ 已经在自选里才谈「取用/反写」；不在自选就什么都不做 —— 不会顺手替用户加自选。
     */
    private fun refreshWatchTarget(securityId: String?) {
        if (securityId == null) return
        scope.launch {
            val watched = runCatching { watch.isWatched(securityId) }.getOrDefault(false)
            val target = if (watched) runCatching { watch.targetPriceOf(securityId) }.getOrNull() else null
            _state.update { it.copy(watched = watched, watchTarget = target) }
        }
    }

    // ------------------------------------------------------------ 字段

    fun setSide(side: TradeSide) = _state.update { it.copy(side = side) }

    /** 改目标价：与折扣都有值时**重算并覆盖**计划价（老周 Q3 拍板） */
    fun setTargetPrice(v: String) {
        _state.update { it.copy(targetPrice = v) }
        recalcPlannedPrice()
    }

    /** 选折扣：同样触发重算覆盖 */
    fun setDiscount(d: Double?) {
        _state.update { it.copy(discount = d) }
        recalcPlannedPrice()
    }

    /** 手动改计划价：保留，直到下次改目标价/折扣才被覆盖 */
    fun setPlannedPrice(v: String) = _state.update { it.copy(plannedPrice = v) }

    fun setQuantity(v: String) = _state.update { it.copy(quantity = v) }

    /** 从设置（加密 KV）读入「整手校验」开关（老周 2026-09-28）；界面进入时调一次。 */
    fun refreshLotCheck() {
        scope.launch {
            val enabled = runCatching { settings.lotCheckEnabled() }.getOrDefault(true)
            _state.update { it.copy(lotCheckEnabled = enabled) }
        }
    }
    fun setFeeRate(v: String) = _state.update { it.copy(feeRate = v) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }

    /** 计划价 = round(目标价 × 折扣, 2)；两者都有值才覆盖（Q3：覆盖，不锁） */
    private fun recalcPlannedPrice() {
        val s = _state.value
        val planned = PlanPricing.plannedPriceOf(s.targetPrice, s.discount) ?: return
        _state.update { it.copy(plannedPrice = trimNum(planned)) }
    }

    // ------------------------------------------------------------ 策略标签

    fun toggleTagCreate() = _state.update { it.copy(showTagCreate = !it.showTagCreate) }

    fun toggleTag(name: String) = _state.update {
        it.copy(tags = if (name in it.tags) it.tags - name else it.tags + name)
    }

    fun createTagAndSelect(nameRaw: String) {
        val name = nameRaw.trim()
        if (name.isEmpty()) return
        scope.launch {
            runCatching { tag.add(name) }
            _state.update {
                it.copy(
                    tagOptions = (it.tagOptions + name).distinct(),
                    tags = if (name in it.tags) it.tags else it.tags + name,
                    showTagCreate = false,
                )
            }
        }
    }

    // ------------------------------------------------------------ 标的搜索（与记一笔同款）

    /** 改关键词：一开始打字就退出「最近用过」展示态（老周 2026-09-21），免得列表标题对不上 */
    fun setKeyword(v: String) = _state.update { it.copy(keyword = v, recentMode = false) }

    fun search() {
        val kw = _state.value.keyword
        // 关键词为空时点「搜索」= 看**最近用过的标的**（老周 2026-09-21）：
        // 常用标的不必每次手打代码，点一下就出来了。
        if (kw.isBlank()) {
            scope.launch {
                _state.update { it.copy(searching = true, searchingOnline = false, recentMode = true) }
                val recent = runCatching { watch.recentSecurities() }.getOrDefault(emptyList())
                _state.update { it.copy(searching = false, candidates = recent, onlineHits = emptyList()) }
            }
            return
        }
        scope.launch {
            _state.update { it.copy(searching = true, searchingOnline = true, recentMode = false) }
            val local = runCatching { security.searchSecurities(kw) }.getOrDefault(emptyList())
            _state.update { it.copy(searching = false, candidates = local) }
            val online = runCatching { security.searchOnline(kw) }.getOrDefault(emptyList())
            _state.update { it.copy(searchingOnline = false, onlineHits = online) }
        }
    }

    /** 选中联网候选：先建标的（按 symbol 去重复用），再带入表单 */
    fun pickOnline(hit: QuoteClient.SymbolHit) {
        scope.launch {
            val market = when (hit.marketLabel) {
                "ETF" -> Market.ETF
                "港股" -> Market.HK
                "美股" -> Market.US
                else -> Market.A_SHARE
            }
            val currency = Currency.entries.firstOrNull { it.code == hit.currencyCode } ?: Currency.CNY
            // ⚠️ P1-35（2026-10-02）：原来是**裸调** —— 建标的一抛异常协程体就结束，
            // 表现为「点了候选没反应」（选不中、无提示、无日志）。与 TradeFormHolder 同型，一起补上。
            val created = runCatching {
                security.findOrCreateSecurity(
                    symbol = hit.symbol,
                    name = hit.name,
                    market = market,
                    currency = currency,
                )
            }.onFailure { e ->
                com.stocknote.data.log.SnLog.e("PLAN_EDIT", "选中联网候选失败：建标的 ${hit.symbol}", e)
                _state.update {
                    it.copy(
                        error = "选中「${hit.name}」失败：" +
                            (e.message ?: e::class.simpleName ?: "未知错误"),
                    )
                }
            }.getOrNull()
            if (created == null) return@launch
            pick(created)
        }
    }

    fun pick(security: Security) {
        scope.launch {
            val feeDefault = defaultFeeRateOf(security.market)
            _state.update {
                it.copy(
                    securityId = security.id,
                    securityLabel = "${security.name} (${security.symbol})",
                    securitySymbol = security.symbol,
                    locked = true,
                    feeRate = trimNum(feeDefault),
                    formCurrency = security.currency,
                    formMarket = security.market,
                    formLotSize = security.lotSize,
                )
            }
            refreshWatchTarget(security.id)
            // 拉一次现价（用于「距达成空间」提示；拿不到就算了）
            val price = runCatching { cachedPrice(security) }.getOrNull()
                ?: runCatching { repo.refreshQuote(security.symbol) }.getOrNull()?.price
            if (price != null && price > 0) {
                _state.update { it.copy(marketPrice = price) }
            }
        }
    }

    // ------------------------------------------------------------ 目标价：取自选 / 联网取

    /** A股（sh/sz/bj）—— 分析师数据只覆盖这个市场，提示文案据此区分「没数据」与「不支持」 */
    private fun isAShare(symbol: String): Boolean =
        symbol.startsWith("sh") || symbol.startsWith("sz") || symbol.startsWith("bj")

    /**
     * 「取自选」：把自选里已有的目标价填进表单（老周 2026-09-21）。
     * 随后照常触发「目标价 × 折扣 → 计划价」的重算。
     */
    fun applyWatchTarget() {
        val v = _state.value.watchTarget ?: return
        setTargetPrice(trimNum(v))
        _state.update { it.copy(targetFetchHint = "已取自选目标价 ${trimNum(v)}") }
    }

    /**
     * 「获取」：联网取目标价 —— **两级口径**（老周 2026-09-21 定）：
     *  ① 近 6 个月研报目标价 → 去掉最大最小后平均（主口径）；
     *  ② 机构评级聚合 → 评级家数 + 目标价区间；①没样本时用**区间中点**兜底。
     *
     * ① 取到时也会把 ② 查出来写进提示做对照（老周：「用①取到时把②的信息也查一下并做个提示」）。
     * 两个源都没有时**不动用户已填的值**，只如实说明。
     */
    fun fetchAnalystTarget() {
        val s = _state.value
        val symbol = s.securitySymbol ?: run {
            _state.update { it.copy(targetFetchHint = "先选标的，再取目标价") }
            return
        }
        if (s.targetFetching) return
        _state.update { it.copy(targetFetching = true, targetFetchHint = null) }
        scope.launch {
            val suggestion = runCatching { repo.analystTargetSuggestion(symbol) }
                .getOrDefault(com.stocknote.data.net.AnalystTargetSource.Suggestion.None)
            val fill = when (suggestion) {
                is com.stocknote.data.net.AnalystTargetSource.Suggestion.FromConsensus -> suggestion.price
                is com.stocknote.data.net.AnalystTargetSource.Suggestion.FromRangeMid -> suggestion.price
                com.stocknote.data.net.AnalystTargetSource.Suggestion.None -> null
            }
            // 取到了才覆盖：取不到时保留用户手填的值，别把人家写的清掉
            if (fill != null) setTargetPrice(trimNum(kotlin.math.round(fill * 100) / 100.0))
            _state.update {
                it.copy(
                    targetFetching = false,
                    targetFetchHint = analystTargetHint(suggestion, isAShare(symbol)),
                )
            }
        }
    }

    private suspend fun cachedPrice(security: Security?): Double? {
        val s = security ?: return null
        return runCatching { repo.cachedQuote(s.symbol) }.getOrNull()?.price
    }

    private suspend fun defaultFeeRateOf(market: Market): Double =
        when (market) {
            Market.HK -> runCatching { settings.feeRateHk() }.getOrDefault(5.0)
            else -> runCatching { settings.feeRateAShare() }.getOrDefault(2.5)
        }

    // ------------------------------------------------------------ 保存 / 删除

    fun save() {
        val s = _state.value
        val issues = PlanPricing.validate(
            securityId = s.securityId,
            targetPriceText = s.targetPrice,
            plannedPriceText = s.plannedPrice,
            quantityText = s.quantity,
            feeRateText = s.feeRate,
        ).toMutableList()
        // 整手校验（老周 2026-09-28）：与「记一笔」同规则 —— A股/ETF/港股必须 100 的整数倍。
        val qtyValue = s.quantity.trim().toDoubleOrNull()
        val planMarket = s.formMarket
        if (qtyValue != null && planMarket != null) {
            com.stocknote.core.calc.LotRule.check(qtyValue, planMarket, s.formLotSize, s.lotCheckEnabled)
                ?.let { issues += PlanPricing.Issue("quantity", it) }
        }
        if (issues.isNotEmpty()) {
            _state.update { it.copy(issues = issues, saveFailTick = it.saveFailTick + 1) }
            return
        }
        scope.launch {
            _state.update { it.copy(saving = true, error = null) }
            // 时间字段与交易日期同格式（yyyy-MM-dd）—— 老周 2026-09-21。
            // 原因：纪元毫秒字符串与 trade_date 混排会串（"2026-09-21" > "1758…"），
            // 而且 CSV 导出「创建日期」列回填时会被 `CivilDate.parseIso` 判为非法。
            val today = com.stocknote.data.platform.todayIso()
            val plan = TradePlan(
                id = s.editPlanId ?: Ids.next("plan"),
                securityId = s.securityId!!,
                side = s.side,
                targetPrice = s.targetPrice.trim().toDouble(),
                discount = s.discount,
                plannedPrice = s.plannedPrice.trim().toDouble(),
                quantity = s.quantity.trim().toDouble(),
                feeRate = s.feeRate.trim().toDoubleOrNull() ?: 0.0,
                note = s.note.trim(),
                tags = s.tags,
                createdAt = originCreatedAt ?: today,
                updatedAt = today,
                doneAt = originDoneAt,
            )
            // ⚠️ 2026-09-28 拆分：保存改走 PlanRepository（同样用 this@ 避免与局部变量同名）
            val err = runCatching { this@PlanEditHolder.plan.upsert(plan) }.exceptionOrNull()
            // 「反写自选目标价」（老周 2026-09-21）：计划里改过的目标价写回自选。
            // ⚠️ 只对**已在自选**的标的写：写不到就 0 行，不会凭空创建自选项。
            //    失败不报错打断保存 —— 计划本身已经存好了，反写只是顺手同步。
            if (err == null && s.watched) {
                runCatching { watch.setTargetPriceBySecurity(plan.securityId, plan.targetPrice) }
                refreshWatchTarget(plan.securityId)
            }
            _state.update {
                if (err != null) {
                    it.copy(
                        saving = false,
                        error = "保存失败：${err.message}",
                        saveFailTick = it.saveFailTick + 1,
                    )
                } else {
                    it.copy(saving = false, saved = true, issues = emptyList())
                }
            }
        }
    }

    fun delete() {
        val id = _state.value.editPlanId ?: return
        scope.launch {
            // ⚠️ 2026-09-28 拆分：删除改走 PlanRepository
            val err = runCatching { plan.delete(id) }.exceptionOrNull()
            _state.update {
                if (err != null) {
                    it.copy(error = "删除失败：${err.message}", saveFailTick = it.saveFailTick + 1)
                } else {
                    it.copy(deleted = true)
                }
            }
        }
    }

    fun dismissIssues() = _state.update { it.copy(issues = emptyList()) }

    private fun trimNum(v: Double): String {
        // ⚠️ M3 修复（2026-09-28）：改用 Locale 无关的 Format.fixedPlain
        val s = com.stocknote.core.format.Format.fixedPlain(v, 3).trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }
    }

    private companion object {
        /** 内置策略标签（与记一笔 `TradeFormHolder.DEFAULT_TAGS` 同源，标签库为空时兜底） */
        val DEFAULT_TAGS = listOf("价值投资", "突破", "网格", "趋势", "波段", "打板")
    }
}

@Composable
/**
 * ⚠️ 2026-09-28 拆分：改为**双依赖** —— `repo` 供标签/费率/标的/自选等其它域，`plan` 供计划域。
 */
fun rememberPlanEditHolder(
    repo: PortfolioRepository,
    plan: PlanRepository,
    settings: SettingsRepository,
    tag: TagRepository,
    watch: WatchlistRepository,
    security: SecurityRepository,
): PlanEditHolder {
    val scope = rememberCoroutineScope()
    return remember(repo, plan, settings, tag, watch) {
        PlanEditHolder(repo, plan, settings, tag, watch, security, scope)
    }
}
