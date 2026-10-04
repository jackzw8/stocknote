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
import androidx.compose.runtime.remember
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
import com.stocknote.feature.state.HoldingsDetailFilterHolder
import com.stocknote.feature.theme.StockNoteColors

/**
 * 持仓明细页（老周 2026-10-02）。
 *
 * 由来：持仓页只保留「持仓 TOP5」概览，完整的持仓明细列表 + 筛选 / 排序 / 查询整体
 * 迁到本页，由「持仓 TOP5」右侧的「全部 ›」进入（二级页，不带底部导航）。
 *
 * 分页：每页 [PAGE_SIZE] 条。筛选 / 排序 / 搜索任一变化，自动回到第 1 页
 * （页码与三个条件一起存在 [HoldingsDetailFilterHolder] 里，由 holder 的 setter 归零）。
 * 口径与原来完全一致：占比分母是本位币总资产、分子同经汇率折算。
 *
 * 筛选口径（老周 2026-10-04）：**全部 / 各市场只列仍持仓的标的**，已清仓单列一个
 * 「已清仓」chip；ETF 与场外基金合并成一类「基金」；排序去掉了「成本 ↑」。
 */
private const val PAGE_SIZE = 10

@Composable
private fun HoldingsDetailContent(
    state: AppUiState,
    /** 筛选 / 排序 / 搜索 / 页码（老周 2026-10-04）：由 App 顶层持有，跨「看详情」路由保留 */
    holder: HoldingsDetailFilterHolder,
    onOpenSecurity: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val positions = snapshot?.positions.orEmpty()

    val filterIndex = holder.filterIndex
    val sortIndex = holder.sortIndex
    val searchText = holder.searchText
    val searchVisible = holder.searchVisible

    // 筛选（老周 2026-10-04）：
    //  ①「全部」只列**仍持仓**的标的 —— 已清仓（数量归零）单列一个「已清仓」chip；
    //  ② ETF 与场外基金**合并**成一类，就叫「基金」（原来拆成 ETF / 基金两个 chip）。
    // ⚠️ 各市场 chip 同样只列仍持仓的，已清仓只在「已清仓」里出现（两类不重叠）。
    val filters = listOf("全部", "A股", "港股", "美股", "基金", "已清仓")
    // 「股息率 ↓」= 累计分红 ÷ 持仓成本（与持仓明细同一口径）；「成本 ↑」已去掉（老周 2026-10-04）
    val sorts = listOf("市值 ↓", "盈亏 ↓", "盈亏 ↑", "股息率 ↓")

    val visible = remember(positions, filterIndex, sortIndex, searchText, snapshot) {
        // 股息率 = 累计分红 ÷ 持仓总成本（成本均价×数量，原币口径，同一标的内比较不失真）
        fun dividendYield(p: Position): Double {
            val cost = p.avgCost * p.quantity
            return if (cost > 1e-9) p.dividendCash / cost else 0.0
        }
        fun openByMarket(m: Market) = positions.filter { it.isOpen && it.market == m }
        val filtered = when (filters.getOrNull(filterIndex)) {
            "A股" -> openByMarket(Market.A_SHARE)
            "港股" -> openByMarket(Market.HK)
            "美股" -> openByMarket(Market.US)
            // ETF 与场外基金合并为「基金」
            "基金" -> positions.filter { it.isOpen && (it.market == Market.ETF || it.market == Market.FUND) }
            "已清仓" -> positions.filter { !it.isOpen }
            else -> positions.filter { it.isOpen }
        }
        val searched = if (searchText.isBlank()) filtered
        else filtered.filter {
            it.name.contains(searchText, ignoreCase = true) || it.symbol.contains(searchText, ignoreCase = true)
        }
        when (sorts.getOrNull(sortIndex)) {
            "盈亏 ↓" -> searched.sortedByDescending { it.unrealizedPnl }
            "盈亏 ↑" -> searched.sortedBy { it.unrealizedPnl }
            "股息率 ↓" -> searched.sortedByDescending { dividendYield(it) }
            else -> searched.sortedByDescending { it.marketValue }
        }
    }

    // 是否处于「已清仓」视图：标题 / 空提示 / 右侧说明都据此调整
    val closedView = filters.getOrNull(filterIndex) == "已清仓"

    val mvTotal = snapshot?.marketValueTotal ?: 0.0

    // ---- 分页：每页 10 条 ----
    val pageCount = maxOf(1, (visible.size + PAGE_SIZE - 1) / PAGE_SIZE)
    // page 由 holder 持有、跨路由保留；筛选/排序/搜索变化时 holder 的 setter 已把它归零（回第 1 页）。
    // 数据变少（如删了一笔）导致当前页越界时就地夹紧，不必额外 effect
    val safePage = holder.page.coerceIn(0, pageCount - 1)
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
                        .clickable { holder.toggleSearch() },
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
                    onValueChange = { holder.setSearch(it) },
                    placeholder = "名称或代码",
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { }) } }

        // ---- 筛选 / 排序 ----
        item { ChipStrip(filters, filterIndex) { holder.selectFilter(it) } }
        item { ChipStrip(sorts, sortIndex, topPad = 8) { holder.selectSort(it) } }

        // ---- 持仓明细（当前页 10 条）----
        item {
            Sec(
                title = if (closedView) "已清仓（${visible.size}）" else "持仓明细（${visible.size}）",
                // 已清仓视图里「合计市值」没有意义（已清仓市值恒为 0），不显示
                more = if (closedView) null else "合计 ${Format.money(mvTotal)}",
                modifier = Modifier.padding(top = 16.dp),
            ) {
                CardBox {
                    if (visible.isEmpty()) {
                        EmptyHint(if (closedView) "还没有已清仓的标的。" else "这个筛选下没有持仓。")
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
                        onPage = { holder.goToPage(it) },
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
    /** 筛选 / 排序 / 搜索 / 页码 holder（老周 2026-10-04）：由 App 顶层持有，跨路由保留 */
    holder: HoldingsDetailFilterHolder,
    onOpenSecurity: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        HoldingsDetailContent(state, holder, onOpenSecurity, onBack, modifier)
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
    if (!p.isOpen) {
        // 已清仓（老周 2026-10-04）：数量归零 → 市值 / 浮动盈亏 / 成本**全是 0**，
        // 照原样渲染会是一行「0 / ¥0 / +0.0%」的废行。改为显示**已实现盈亏**（该标的平仓累计）。
        val realized = p.realizedPnl
        StockRow(
            avatar = p.name.take(1),
            name = p.name,
            marketTag = p.market.label,
            detail = "已清仓 · 累计 ${p.tradeCount} 笔交易",
            priceText = "已清仓",
            pnlText = "已实现 " + Format.moneySigned(realized, p.currency.symbol),
            pnlPositive = realized >= 0,
            market = p.market,
            avatarTinted = realized < 0,
            onClick = onClick,
        )
        return
    }
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
