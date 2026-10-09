package com.stocknote.feature.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.ReviewPeriod
import com.stocknote.core.format.Format
import com.stocknote.core.model.Review
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeQuality
import com.stocknote.core.model.Transaction
import com.stocknote.data.platform.todayIso
import com.stocknote.data.repo.ReviewRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.TradeRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 交易日记（原型 07，REQ-NOTE-01~06 的展示层）+ 月度复盘（REQ-NOTE-06）。
 *
 * 入口：统计页「📝 写复盘」（原型 dashboard.html）；「持仓」页头部 📝。
 * （原「分析页 → 交易质量 ›」入口已于 2026-10-08 去掉。）
 * 筛选：策略标签 / 神操作 / 昏招（REQ-ANA-04 验收「可筛选查看两类」）。
 */

// ==================================================================================
// 状态
// ==================================================================================

class DiaryHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-29 拆分（第 4 批）：复盘域。 */
    private val review: ReviewRepository,
    /** ⚠️ 2026-09-29 拆分（第 6 批）：交易域（全量流水查询）。 */
    private val trade: TradeRepository,
    /** **标的域**（第 11 批）。 */
    private val security: SecurityRepository,
    private val scope: CoroutineScope,
) {
    data class UiState(
        val securitiesById: Map<String, Security> = emptyMap(),
        /** 交易日期倒序（新在前），只列写了备注的（M2 起必填，即全部） */
        val txs: List<Transaction> = emptyList(),
        val reviews: List<Review> = emptyList(),
        /** null=全部；值为标签名或 "GREAT"/"BLUNDER" */
        val filter: String? = null,
        // ---- 搜索（老周 2026-09-16）：关键字（股票名/代码/备注）+ 起止日期 ----
        val showSearch: Boolean = false,
        val keyword: String = "",
        val fromDate: String = "",
        val toDate: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            try {
                val secs = security.securities()
                val txs = trade.allTransactions()
                    // ⚠️ 轻微-6（2026-09-28）：加 id 兜底（nextSeq 复用 seq 时同日多笔顺序不稳定）
                    .sortedWith(
                        compareByDescending<Transaction> { it.tradeDate }
                            .thenByDescending { it.seq }
                            .thenByDescending { it.id },
                    )
                val reviews = review.all()
                _state.update {
                    it.copy(
                        securitiesById = secs.associateBy { s -> s.id },
                        txs = txs,
                        reviews = reviews,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = "加载失败：${t.message ?: t::class.simpleName}") }
            }
        }
    }

    fun setFilter(v: String?) = _state.update { it.copy(filter = v) }

    // ---- 搜索（老周 2026-09-16）----

    fun toggleSearch() = _state.update {
        it.copy(showSearch = !it.showSearch, keyword = if (it.showSearch) "" else it.keyword,
            fromDate = if (it.showSearch) "" else it.fromDate, toDate = if (it.showSearch) "" else it.toDate)
    }
    fun setKeyword(v: String) = _state.update { it.copy(keyword = v) }
    fun setFromDate(v: String) = _state.update { it.copy(fromDate = v) }
    fun setToDate(v: String) = _state.update { it.copy(toDate = v) }
    fun clearSearch() = _state.update {
        it.copy(keyword = "", fromDate = "", toDate = "")
    }

    /**
     * 应用搜索条件（关键字匹配 股票名/代码/备注；日期闭区间，空表示不限）。
     * 纯函数，便于单测与复用。
     */
    fun applySearch(
        txs: List<Transaction>,
        securitiesById: Map<String, Security>,
        keyword: String,
        fromDate: String,
        toDate: String,
    ): List<Transaction> {
        val kw = keyword.trim()
        return txs.filter { tx ->
            val sec = securitiesById[tx.securityId]
            val kwOk = kw.isEmpty() ||
                (sec?.name?.contains(kw, ignoreCase = true) == true) ||
                (sec?.symbol?.contains(kw, ignoreCase = true) == true) ||
                (tx.note?.contains(kw, ignoreCase = true) == true)
            // 日期为 yyyy-MM-dd 字符串，字典序即时间序，可直接比较
            val fromOk = fromDate.isBlank() || tx.tradeDate >= fromDate
            val toOk = toDate.isBlank() || tx.tradeDate <= toDate
            kwOk && fromOk && toOk
        }
    }

    /** 标记/取消质量评级：点已选中的标记 = 取消。 */
    fun setQuality(txId: String, q: TradeQuality) {
        val current = _state.value.txs.firstOrNull { it.id == txId }?.quality
        val next = if (current == q) null else q
        scope.launch {
            trade.setTradeQuality(txId, next)
            load()
        }
    }

    fun deleteReview(id: String) {
        scope.launch {
            review.delete(id)
            load()
        }
    }
}

@Composable
fun rememberDiaryHolder(
    repo: PortfolioRepository,
    review: ReviewRepository,
    trade: TradeRepository,
    security: SecurityRepository,
): DiaryHolder {
    val scope = rememberCoroutineScope()
    return androidx.compose.runtime.remember(repo, review, trade) {
        DiaryHolder(repo, review, trade, security, scope)
    }
}

// ==================================================================================
// 页面
// ==================================================================================

@Composable
fun DiaryScreen(
    holder: DiaryHolder,
    onOpenSecurity: (String) -> Unit,
    onWriteReview: () -> Unit,
    onEditReview: (Review) -> Unit,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(Unit) { holder.load() }

    val tagOptions = listOf("价值投资", "突破", "网格", "趋势", "波段", "打板")
    val byFilter = state.txs.filter { tx ->
        when (state.filter) {
            null -> true
            "GREAT" -> tx.quality == TradeQuality.GREAT
            "BLUNDER" -> tx.quality == TradeQuality.BLUNDER
            else -> state.filter in tx.tags
        }
    }
    // 叠加搜索条件（股票名/代码/备注 + 起止日期）—— 老周 2026-09-16
    val shown = holder.applySearch(
        byFilter, state.securitiesById, state.keyword, state.fromDate, state.toDate,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // 回退按钮放左侧（与其他二级页 TopBar 一致，老周反馈 2026-09-14）
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton { AppNav.pop() }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("交易日记", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                    Text("每笔都有理由 · 长期沉淀", fontSize = 11.sp, color = StockNoteColors.TextTertiary)
                }
                Spacer(Modifier.weight(1f))
                // 🔍 搜索（展开后可按股票名/代码/备注 + 时间段过滤）—— 老周 2026-09-16
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (state.showSearch) StockNoteColors.Brand else Color.White)
                        .clickable { holder.toggleSearch() },
                    contentAlignment = Alignment.Center,
                ) { Text("🔍", fontSize = 14.sp, modifier = Modifier.semantics { contentDescription = "搜索日记" }) }
            }
        }

        // ---- 搜索区（关键字 + 起止日期）----
        if (state.showSearch) {
            item {
                SectionCard(title = "搜索") {
                    LabeledField(
                        label = "股票名 / 代码 / 备注关键字",
                        value = state.keyword,
                        onValueChange = holder::setKeyword,
                        placeholder = "如 茅台 或 sh600519",
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            DateField(
                                label = "起始日期（含）",
                                value = state.fromDate,
                                onValueChange = holder::setFromDate,
                                maxIso = com.stocknote.data.platform.todayIso(),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            DateField(
                                label = "结束日期（含）",
                                value = state.toDate,
                                onValueChange = holder::setToDate,
                                maxIso = com.stocknote.data.platform.todayIso(),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "命中 ${shown.size} 笔",
                            fontSize = 12.sp,
                            color = StockNoteColors.TextSecondary,
                        )
                        Spacer(Modifier.weight(1f))
                        GhostButton("清空条件", onClick = { holder.clearSearch() })
                    }
                }
            }
        }

        // 筛选 chips：全部 + 标签 + 神操作/昏招
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { DiaryChip("全部", state.filter == null) { holder.setFilter(null) } }
                items(tagOptions) { t -> DiaryChip(t, state.filter == t) { holder.setFilter(t) } }
                item { DiaryChip("👍 神操作", state.filter == "GREAT") { holder.setFilter("GREAT") } }
                item { DiaryChip("👎 昏招", state.filter == "BLUNDER") { holder.setFilter("BLUNDER") } }
            }
        }

        if (shown.isEmpty()) {
            item { EmptyHint(if (state.filter == null) "还没有交易记录。" else "该筛选下没有交易。") }
        }

        items(shown, key = { it.id }) { tx ->
            val sec = state.securitiesById[tx.securityId]
            DiaryCard(
                tx = tx,
                security = sec,
                onOpen = sec?.let { { onOpenSecurity(it.id) } },
                onQuality = { q -> holder.setQuality(tx.id, q) },
            )
        }

        // 月度复盘
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("月度复盘", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    "写复盘 ＋",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StockNoteColors.Brand,
                    modifier = Modifier.clickable { onWriteReview() }.padding(6.dp),
                )
            }
        }

        if (state.reviews.isEmpty()) {
            item {
                CardBox { EmptyHint("按月/季写下阶段总结，关联该期交易。") }
            }
        } else {
            item {
                CardBox {
                    state.reviews.forEachIndexed { i, r ->
                        ReviewRow(
                            review = r,
                            onClick = { onEditReview(r) },
                            onDelete = { holder.deleteReview(r.id) },
                            showDivider = i < state.reviews.lastIndex,
                        )
                    }
                }
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.load() }) } }

        item { ProtoFoot("备注为必填 · 评分参与绩效统计 · 复盘仅存本机") }
    }
}

@Composable
private fun DiaryCard(
    tx: Transaction,
    security: Security?,
    onOpen: (() -> Unit)?,
    onQuality: (TradeQuality) -> Unit,
) {
    CardBox {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            // 头行：pill 买卖 + 名称 代码 + 日期
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 第三态：转增资本（REQ-ACC-16）——既不是买也不是卖，标签与配色都要独立，
                // 否则会被渲染成一笔"卖出".
                val sideTag = tx.side
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (sideTag) {
                                com.stocknote.core.model.TradeSide.BUY -> Color(0xFFFDF0EE)
                                com.stocknote.core.model.TradeSide.SELL -> Color(0xFFEAF7F1)
                                com.stocknote.core.model.TradeSide.CAPITALIZE -> Color(0xFFEAF1FE)
                            }
                        ),
                ) {
                    Text(
                        sideTag.label,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = when (sideTag) {
                            com.stocknote.core.model.TradeSide.BUY -> StockNoteColors.Up
                            com.stocknote.core.model.TradeSide.SELL -> StockNoteColors.Down
                            com.stocknote.core.model.TradeSide.CAPITALIZE -> StockNoteColors.BrandDark
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    security?.name ?: tx.securityId,
                    fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text(security?.symbol ?: "", fontSize = 11.5.sp, color = StockNoteColors.TextTertiary)
                Spacer(Modifier.weight(1f))
                Text(tx.tradeDate, fontSize = 11.sp, color = StockNoteColors.TextTertiary)
            }

            // 数量价格
            Spacer(Modifier.height(6.dp))
            Text(
                if (tx.side == com.stocknote.core.model.TradeSide.CAPITALIZE) {
                    "转增 ${Format.money(tx.price, "")}"   // 金额在 price 列（quantity 恒 0）
                } else {
                    // 数量单位走 Market.quantityUnit（场外基金 = 份，其余 = 股）
                    "${Format.quantity(tx.quantity)} ${security?.market?.quantityUnit ?: "股"}" +
                        " @ ${Format.money(tx.price, "")}" +
                        if (tx.fee > 0) " · 费 ${Format.money(tx.fee, "")}" else ""
                },
                fontSize = 12.sp, color = StockNoteColors.TextSecondary,
            )

            // 备注正文
            if (!tx.note.isNullOrBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    tx.note!!,
                    fontSize = 12.5.sp, color = StockNoteColors.TextSecondary, lineHeight = 19.sp,
                )
            }

            // 复盘要素行：情绪 / 标签 / 星级 / 质量标记按钮
            Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tx.emotion?.let { e ->
                    MiniChip("😌 $e", bg = Color(0xFFF3F6FB), fg = StockNoteColors.TextSecondary)
                }
                tx.tags.forEach { t ->
                    MiniChip(t, bg = Color(0xFFEAF1FE), fg = StockNoteColors.BrandDark)
                }
                if (tx.score != null && tx.score!! > 0) {
                    Text(
                        "★".repeat(tx.score!!) + "☆".repeat((5 - tx.score!!).coerceAtLeast(0)),
                        fontSize = 11.sp, color = Color(0xFFF0A020), letterSpacing = 1.sp,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("质量评级：", fontSize = 11.sp, color = StockNoteColors.TextTertiary)
                QualityButton(
                    label = "👍 神操作",
                    active = tx.quality == TradeQuality.GREAT,
                    activeBg = Color(0xFFEAF7F1),
                    activeFg = StockNoteColors.Up,
                ) { onQuality(TradeQuality.GREAT) }
                QualityButton(
                    label = "👎 昏招",
                    active = tx.quality == TradeQuality.BLUNDER,
                    activeBg = Color(0xFFFDF0EE),
                    activeFg = StockNoteColors.Up,
                ) { onQuality(TradeQuality.BLUNDER) }
            }
        }
    }
}

@Composable
private fun MiniChip(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
    }
}

@Composable
private fun QualityButton(label: String, active: Boolean, activeBg: Color, activeFg: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeBg else Color(0xFFF3F6FB))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (active) activeFg else StockNoteColors.TextTertiary,
        )
    }
}

@Composable
private fun ReviewRow(review: Review, onClick: () -> Unit, onDelete: () -> Unit, showDivider: Boolean) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(40.dp).height(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF2F0FB)),
                contentAlignment = Alignment.Center,
            ) { Text("📅", fontSize = 17.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ReviewPeriod.display(review.period), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                Text(
                    (review.periodType == ReviewPeriod.TYPE_QUARTER).let { if (it) "季度复盘 · " else "月度复盘 · " } +
                        "更新于 ${review.updatedAt}",
                    fontSize = 11.sp, color = StockNoteColors.TextTertiary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text("🗑", fontSize = 13.sp, modifier = Modifier.clickable { onDelete() }.padding(6.dp)
                                            .semantics { contentDescription = "删除复盘" })
            Text("›", fontSize = 14.sp, color = StockNoteColors.TextTertiary)
        }
        if (showDivider) {
            Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
        }
    }
}

@Composable
private fun DiaryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) StockNoteColors.Brand else Color.White)
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else StockNoteColors.TextSecondary,
        )
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(10.dp))
            .clickable { onBack() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("‹", fontSize = 20.sp, color = StockNoteColors.Brand)
    }
}

// ==================================================================================
// 写复盘（REQ-NOTE-06 编辑页）
// ==================================================================================

class ReviewFormHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-29 拆分（第 4 批）：复盘域。 */
    private val review: ReviewRepository,
    /** ⚠️ 2026-09-29 拆分（第 6 批）：交易域。 */
    private val trade: TradeRepository,
    private val scope: CoroutineScope,
) {
    data class PeriodStats(
        val txCount: Int = 0,
        val buys: Int = 0,
        val sells: Int = 0,
        val securities: Int = 0,
        val greats: Int = 0,
        val blunders: Int = 0,
    )

    data class UiState(
        val periodType: String = ReviewPeriod.TYPE_MONTH,
        val period: String = "",
        val content: String = "",
        val stats: PeriodStats = PeriodStats(),
        val saving: Boolean = false,
        val error: String? = null,
        /** 保存失败次数：UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun start(preset: Review?) {
        scope.launch {
            val today = todayIso()
            val type = preset?.periodType ?: ReviewPeriod.TYPE_MONTH
            val period = preset?.period
                ?: if (type == ReviewPeriod.TYPE_QUARTER) ReviewPeriod.quarterOf(today) ?: ""
                else ReviewPeriod.monthOf(today) ?: ""
            _state.update {
                it.copy(
                    periodType = type,
                    period = period,
                    content = preset?.content ?: "",
                )
            }
            refreshStats()
        }
    }

    fun setType(type: String) {
        scope.launch {
            val today = todayIso()
            val period = if (type == ReviewPeriod.TYPE_QUARTER) ReviewPeriod.quarterOf(today) ?: ""
            else ReviewPeriod.monthOf(today) ?: ""
            _state.update { it.copy(periodType = type, period = period) }
            refreshStats()
        }
    }

    fun setPeriod(v: String) = _state.update { it.copy(period = v) }
    fun setContent(v: String) = _state.update { it.copy(content = v) }

    /** 期内交易统计：笔数 / 买卖分布 / 涉及标的数 / 神操作 / 昏招（REQ 验收「关联时间段绩效」）。 */
    private suspend fun refreshStats() {
        val s = _state.value
        val range = ReviewPeriod.range(s.period) ?: return
        val (start, end) = range
        val inRange = trade.allTransactions().filter { it.tradeDate >= start && it.tradeDate <= end }
        _state.update {
            it.copy(
                stats = PeriodStats(
                    txCount = inRange.size,
                    buys = inRange.count { t -> t.side == com.stocknote.core.model.TradeSide.BUY },
                    sells = inRange.count { t -> t.side == com.stocknote.core.model.TradeSide.SELL },
                    securities = inRange.map { t -> t.securityId }.distinct().size,
                    greats = inRange.count { t -> t.quality == TradeQuality.GREAT },
                    blunders = inRange.count { t -> t.quality == TradeQuality.BLUNDER },
                ),
            )
        }
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.period.isBlank() || ReviewPeriod.range(s.period) == null) {
            _state.update {
                it.copy(error = "周期格式应为 2026-08（月）或 2026Q3（季）", saveFailTick = it.saveFailTick + 1)
            }
            return
        }
        if (s.content.isBlank()) {
            _state.update { it.copy(error = "复盘内容不能为空", saveFailTick = it.saveFailTick + 1) }
            return
        }
        scope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                review.save(s.periodType, s.period, s.content)
                _state.update { it.copy(saving = false) }
                onDone()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "保存失败：${t.message ?: t::class.simpleName}",
                        saveFailTick = it.saveFailTick + 1,
                    )
                }
            }
        }
    }
}

@Composable
fun rememberReviewFormHolder(
    repo: PortfolioRepository,
    review: ReviewRepository,
    trade: TradeRepository,
): ReviewFormHolder {
    val scope = rememberCoroutineScope()
    return androidx.compose.runtime.remember(repo, review, trade) {
        ReviewFormHolder(repo, review, trade, scope)
    }
}

@Composable
fun ReviewFormScreen(
    holder: ReviewFormHolder,
    preset: Review?,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(preset?.id) { holder.start(preset) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBar(title = if (preset == null) "写复盘" else "编辑复盘") { AppNav.pop() }
            }
        }

        item {
            SectionCard(title = "周期") {
                ChipRow(
                    options = listOf("月度", "季度"),
                    selectedIndex = if (state.periodType == ReviewPeriod.TYPE_MONTH) 0 else 1,
                    onSelect = { holder.setType(if (it == 0) ReviewPeriod.TYPE_MONTH else ReviewPeriod.TYPE_QUARTER) },
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "周期（${ReviewPeriod.display(state.period)}）",
                    value = state.period,
                    onValueChange = {
                        holder.setPeriod(it)
                    },
                    placeholder = "2026-08 或 2026Q3",
                )
            }
        }

        item {
            val st = state.stats
            InfoCard(
                title = "该周期交易（${st.txCount} 笔 · ${st.securities} 只标的）",
                message = "买入 ${st.buys} 笔 · 卖出 ${st.sells} 笔 · 👍神操作 ${st.greats} · 👎昏招 ${st.blunders}。" +
                    "写复盘时对照这些数字回顾当时的判断。",
            )
        }

        item {
            SectionCard(title = "复盘内容") {
                OutlinedTextField(
                    value = state.content,
                    onValueChange = holder::setContent,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    placeholder = {
                        Text(
                            "本月做对了什么 / 错过什么 / 情绪与纪律执行如何 / 下阶段计划…",
                            fontSize = 13.sp, color = StockNoteColors.TextTertiary,
                        )
                    },
                    textStyle = TextStyle(fontSize = 13.5.sp, color = StockNoteColors.TextPrimary, lineHeight = 20.sp),
                )
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.save { AppNav.pop() } }) } }

        item {
            PrimaryButton(if (state.saving) "保存中…" else "保存复盘", onClick = { holder.save { AppNav.pop() } }, enabled = !state.saving)
        }

        item { ProtoFoot("同一周期只保留一篇 · 仅存本机") }
    }

    // 保存 / 修改失败弹窗（老周 2026-09-18）：失败时**明确弹出**，避免「点了按钮没反应」的错觉
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = listOfNotNull(state.error),
    )
}
