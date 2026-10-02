package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocknote.core.format.Format
import com.stocknote.core.model.Market
import com.stocknote.core.model.Position
import com.stocknote.feature.state.AppUiState
import com.stocknote.feature.theme.StockNoteColors

/**
 * 持仓明细页（老周 2026-10-02）。
 *
 * 由来：持仓页只保留「持仓 TOP5」概览，完整的持仓明细列表 + 筛选 / 排序 / 查询整体
 * 迁到本页，由「持仓 TOP5」右侧的「全部 ›」进入（二级页，不带底部导航）。
 *
 * 分页：每页 [PAGE_SIZE] 条。筛选 / 排序 / 搜索任一变化，自动回到第 1 页
 * （page 用 remember 的三个 key 承载，key 一变即重建为 0）。
 * 口径与原来完全一致：占比分母是本位币总资产、分子同经汇率折算。
 */
private const val PAGE_SIZE = 10

@Composable
private fun HoldingsDetailContent(
    state: AppUiState,
    onOpenSecurity: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val positions = snapshot?.positions.orEmpty()

    var filterIndex by remember { mutableIntStateOf(0) }
    var sortIndex by remember { mutableIntStateOf(0) }
    // 查询（原持仓页右上角 🔍 入口，2026-10-02 迁到本页）：按名称/代码过滤
    var searchText by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }

    // 「基金」筛选只对**账内**基金有意义（勾了「不计入统计」的基金在持仓页的「账外备忘」区）
    val filters = listOf("全部", "A股", "港股", "美股", "ETF", "基金")
    // 「股息率 ↓」= 累计分红 ÷ 持仓成本（与持仓明细同一口径）
    val sorts = listOf("市值 ↓", "盈亏 ↓", "盈亏 ↑", "成本 ↑", "股息率 ↓")

    val visible = remember(positions, filterIndex, sortIndex, searchText, snapshot) {
        // 股息率 = 累计分红 ÷ 持仓总成本（成本均价×数量，原币口径，同一标的内比较不失真）
        fun dividendYield(p: Position): Double {
            val cost = p.avgCost * p.quantity
            return if (cost > 1e-9) p.dividendCash / cost else 0.0
        }
        val filtered = when (filters.getOrNull(filterIndex)) {
            "A股" -> positions.filter { it.market == Market.A_SHARE }
            "港股" -> positions.filter { it.market == Market.HK }
            "美股" -> positions.filter { it.market == Market.US }
            "ETF" -> positions.filter { it.market == Market.ETF }
            "基金" -> positions.filter { it.market == Market.FUND }
            else -> positions
        }
        val searched = if (searchText.isBlank()) filtered
        else filtered.filter {
            it.name.contains(searchText, ignoreCase = true) || it.symbol.contains(searchText, ignoreCase = true)
        }
        when (sorts.getOrNull(sortIndex)) {
            "盈亏 ↓" -> searched.sortedByDescending { it.unrealizedPnl }
            "盈亏 ↑" -> searched.sortedBy { it.unrealizedPnl }
            "成本 ↑" -> searched.sortedBy { it.avgCost }
            "股息率 ↓" -> searched.sortedByDescending { dividendYield(it) }
            else -> searched.sortedByDescending { it.marketValue }
        }
    }

    val mvTotal = snapshot?.marketValueTotal ?: 0.0

    // ---- 分页：每页 10 条 ----
    val pageCount = maxOf(1, (visible.size + PAGE_SIZE - 1) / PAGE_SIZE)
    // key = 筛选/排序/搜索：任一变化 → page 重建为 0（回到第 1 页）
    var page by remember(filterIndex, sortIndex, searchText) { mutableIntStateOf(0) }
    // 数据变少（如删了一笔）导致当前页越界时就地夹紧，不必额外 effect
    val safePage = page.coerceIn(0, pageCount - 1)
    val pageItems = visible.drop(safePage * PAGE_SIZE).take(PAGE_SIZE)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // ---- 顶部栏：返回 + 标题 + 查询（🔍 展开过滤）----
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White)
                        .clickable { onBack() }
                        .padding(horizontal = 13.dp, vertical = 6.dp)
                        .semantics { contentDescription = "返回" },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", fontSize = pageSp(20f), fontWeight = FontWeight.Bold, color = StockNoteColors.Brand) }
                Spacer(Modifier.width(8.dp))
                Text("持仓明细", fontSize = pageSp(21f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                // 🔍 查询（原在持仓页右上角，2026-10-02 迁入本页）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (searchVisible) StockNoteColors.Brand else Color.White)
                        .clickable {
                            searchVisible = !searchVisible
                            if (!searchVisible) searchText = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔍", fontSize = pageSp(14f), modifier = Modifier.semantics { contentDescription = "搜索持仓" })
                }
            }
        }

        // ---- 搜索框（展开时）----
        if (searchVisible) {
            item {
                LabeledField(
                    label = "搜索持仓",
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = "名称或代码",
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { }) } }

        // ---- 筛选 / 排序 ----
        item { ChipStrip(filters, filterIndex) { filterIndex = it } }
        item { ChipStrip(sorts, sortIndex, topPad = 8) { sortIndex = it } }

        // ---- 持仓明细（当前页 10 条）----
        item {
            Sec(
                title = "持仓明细（${visible.size}）",
                more = "合计 ${Format.money(mvTotal)}",
                modifier = Modifier.padding(top = 16.dp),
            ) {
                CardBox {
                    if (visible.isEmpty()) {
                        EmptyHint("这个筛选下没有持仓。")
                    } else {
                        pageItems.forEach { p -> HoldingRow(p, snapshot, onClick = { onOpenSecurity(p.securityId) }) }
                    }
                }
                // 分页控件：只有一页时不显示，避免占地方
                if (pageCount > 1) {
                    PagerBar(
                        page = safePage,
                        pageCount = pageCount,
                        total = visible.size,
                        onPage = { page = it },
                    )
                }
            }
        }

        item {
            ProtoFoot("每页 $PAGE_SIZE 条 · 点击某行查看该标的的逐笔明细")
        }
    }
}

/** 分页控件：上一页 / 页码 / 下一页（样式对齐盈亏日历的月份切换）。 */
@Composable
private fun PagerBar(page: Int, pageCount: Int, total: Int, onPage: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹ 上一页",
            fontSize = pageSp(13f),
            fontWeight = FontWeight.SemiBold,
            color = if (page > 0) StockNoteColors.Brand else StockNoteColors.TextTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .clickable(enabled = page > 0) { onPage(page - 1) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "第 ${page + 1} / $pageCount 页 · 共 $total 条",
            fontSize = pageSp(11.5f),
            color = StockNoteColors.TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "下一页 ›",
            fontSize = pageSp(13f),
            fontWeight = FontWeight.SemiBold,
            color = if (page < pageCount - 1) StockNoteColors.Brand else StockNoteColors.TextTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .clickable(enabled = page < pageCount - 1) { onPage(page + 1) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 整页字号放大 1.2 倍（与持仓页/统计页一致）。 */
@Composable
fun HoldingsDetailScreen(
    state: AppUiState,
    onOpenSecurity: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        HoldingsDetailContent(state, onOpenSecurity, onBack, modifier)
    }
}

@Composable
internal fun ChipStrip(options: List<String>, selected: Int, topPad: Int = 13, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = topPad.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) Color(0xFFEAF1FE) else Color.White)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = pageSp(12f),
                    fontWeight = FontWeight.Bold,
                    color = if (on) StockNoteColors.BrandDark else StockNoteColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
internal fun HoldingRow(p: Position, snap: com.stocknote.core.model.PortfolioSnapshot?, onClick: () -> Unit) {
    val ratio = p.marketPrice?.let { price -> if (p.avgCost > 0) (price - p.avgCost) / p.avgCost else null }
    // 占比分母是本位币总资产，分子同样要折算本位币（原币直接除会错配）
    val share = snap?.let { if (it.totalAsset > 0) snap.positionMarketValueInBase(p) / it.totalAsset else null }
    StockRow(
        avatar = p.name.take(1),
        name = p.name,
        marketTag = p.market.label,
        // 明细拆两行（老周 2026-09-21）：第 1 行「数量 · 成本」，第 2 行「现价 · 占比」
        detail = "${Format.quantity(p.quantity)}${p.market.quantityUnit} · 成本 ${Format.money(p.avgCost, "")}",
        detail2 = "现 ${p.marketPrice?.let { Format.money(it, "") } ?: "—"}" +
            (share?.let { " · 占比 ${Format.percent(it, signed = false)}" } ?: ""),
        // 2026-09-17 B 方案（老周定）：**金额统一 2 位小数**。
        // StockRow 已改纵向四行、价格独占一行，加宽不会再挤压名称列。
        // 百分比仍保留 1 位（列表行紧凑）
        priceText = Format.money(p.marketValue, p.currency.symbol),
        pnlText = Format.moneySigned(p.unrealizedPnl, p.currency.symbol) +
            " · " + Format.percent(ratio, decimals = 1),
        pnlPositive = p.unrealizedPnl >= 0,
        // 图标按市场上色（老周 2026-09-21）：与配置占比环形图同一套色
        market = p.market,
        avatarTinted = p.unrealizedPnl < 0,
        onClick = onClick,
    )
}
