package com.stocknote.feature.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.stocknote.core.format.Format
import com.stocknote.core.model.Currency
import com.stocknote.core.model.FxTable
import com.stocknote.core.model.Market
import com.stocknote.core.model.Position
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.nav.AppRoute
import com.stocknote.feature.state.AppUiState
import com.stocknote.feature.theme.StockNoteColors
import com.stocknote.feature.theme.marketAccent

/**
 * 持仓与现金页 —— 严格对照 `界面原型-新版/holdings.html`（REQ-VIEW-02/03）。
 * 现金等价物归「现金」子类，不计入持仓明细与占比。
 */
@Composable
private fun HoldingsScreenContent(
    state: AppUiState,
    onOpenSecurity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 待登记分红弹窗已上提为**全局宿主** [DividendAlertHost]（老周 2026-09-30）：
    // 它原本挂在本页，而 App 默认落在「探索」页、统计页刷新时也不在本页 ——
    // 检测到了却弹不出来。现由 App 根部渲染，任何页面都能弹。
    val snapshot = state.snapshot
    val positions = snapshot?.positions.orEmpty()

    var filterIndex by remember { mutableIntStateOf(0) }
    var sortIndex by remember { mutableIntStateOf(0) }
    // 持仓搜索（老周反馈第 8 条 A 方案，2026-09-14）：🔍 展开，按名称/代码过滤
    var searchText by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }

    // 「基金」筛选只对**账内**基金有意义（勾了「不计入统计」的基金在下面的「账外备忘」区，
    // 不在这份持仓明细里）—— 老周 2026-09-20
    val filters = listOf("全部", "A股", "港股", "美股", "ETF", "基金")
    // 「占比 ↓」排序已按老周 2026-09-24 要求替换为「股息率 ↓」（累计分红÷持仓成本）；
    // 同时新增「盈亏 ↑」（盈利从小到大），与「盈亏 ↓」并存
    val sorts = listOf("市值 ↓", "盈亏 ↓", "盈亏 ↑", "成本 ↑", "股息率 ↓")

    val visible = remember(positions, filterIndex, sortIndex, searchText, snapshot) {
        // 占比分母是本位币总资产，分子同样要折算本位币（原币直接除会错配）——与 HoldingRow 显示口径一致
        fun shareOf(p: Position): Double =
            snapshot?.let { if (it.totalAsset > 0) it.positionMarketValueInBase(p) / it.totalAsset else 0.0 } ?: 0.0
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

    val totalAsset = snapshot?.totalAsset ?: 0.0
    val mvTotal = snapshot?.marketValueTotal ?: 0.0
    val unrealTotal = snapshot?.unrealizedPnlTotal ?: 0.0

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // ---- 顶部栏：标题 + 头部按钮导航（记一笔 / 自选 / 日记）+ 搜索 ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的持仓", fontSize = pageSp(21f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                Spacer(Modifier.width(8.dp))
                Spacer(Modifier.width(10.dp))
                // ＋ 记一笔：底部 tabbar 由 4 项改 3 项后，记一笔入口迁到此处（老周 2026-09-15）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(StockNoteColors.Brand)
                        .clickable { AppNav.push(AppRoute.TradeForm(null, null)) }
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                        // 自动化测试锚点：全角「＋ 记一笔」用文字定位在 Compose 语义合并下不可靠
                        .semantics { contentDescription = "新增交易" },
                ) {
                    // 老周 2026-09-16：任何字体档都要能看到「记一笔」文字
                    //（fixedSp 视觉恒定 → 文字不随档位放大，所以不会挤爆按钮）
                    Text(
                        "＋ 记一笔",
                        fontSize = pageSp(12f),
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                // ⭐ 自选管理（REQ-TOOL-02，原在统计页头部；图标 2026-09-16 由 📌 改为 ⭐）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White)
                        .clickable { AppNav.push(AppRoute.Watchlist) },
                    contentAlignment = Alignment.Center,
                ) { Text("⭐", fontSize = pageSp(14f), modifier = Modifier.semantics { contentDescription = "自选股" }) }
                Spacer(Modifier.width(8.dp))
                // 📝 交易日记（REQ-NOTE-01~06，原在统计页头部）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White)
                        .clickable { AppNav.push(AppRoute.Diary) },
                    contentAlignment = Alignment.Center,
                ) { Text("📝", fontSize = pageSp(14f), modifier = Modifier.semantics { contentDescription = "交易日记" }) }
                Spacer(Modifier.width(8.dp))
                // 🔍 搜索（展开后过滤持仓行）
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

        // ---- 汇总：持仓市值 / 持仓浮动盈亏，**上下两行**各自占满整行（老周 2026-09-20）----
        // 注：老周 2026-09-16 起是左右并排两个半宽卡片；2026-09-20 要求改为上下分开，
        // 整行后金额与副标题都有充足横向空间（金额统一 2 位小数后变长过）。
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                MetricCell(
                    "持仓市值",
                    Format.money(mvTotal),
                    if (totalAsset > 0) "占总资产 ${Format.percent(mvTotal / totalAsset, signed = false)}" else "—",
                    modifier = Modifier.fillMaxWidth(),
                )
                MetricCell(
                    "持仓浮动盈亏",
                    Format.moneySigned(unrealTotal),
                    Format.percent(
                        if (mvTotal - unrealTotal > 0) unrealTotal / (mvTotal - unrealTotal) else null,
                    ),
                    valueColor = if (unrealTotal >= 0) StockNoteColors.Up else StockNoteColors.Down,
                    modifier = Modifier.fillMaxWidth(),
                )
                // ---- 今年收益（老周 2026-09-28，放在「现金」卡片之上）----
                // 口径（老周定「账户年内增值」）：今年收益 = 当前总资产 − 年初总资产 − 年内净入金；
                // 今年收益率 = 账户年内 XIRR（期初 = 年初资产、期内 = 年内出入金、期末 = 当前总资产）。
                // ⚠️ 年初资产取自**资产曲线的 1 月 1 日点** → 曲线未就绪时两个值都是 null，显示「—」。
                MetricCell(
                    "今年收益",
                    state.yearGain?.let { Format.moneySigned(it) } ?: "—",
                    // 副标题：收益率 + 起算日说明（账本当年才建时口径是「自入市以来」，如实标注）
                    when {
                        state.yearGain == null -> "今年收益率 —"
                        state.yearIsFullYear -> "今年收益率 ${Format.percent(state.yearRate)}"
                        else -> "今年收益率 ${Format.percent(state.yearRate)}（自 ${state.yearStartDate} 起算）"
                    },
                    valueColor = when {
                        state.yearGain == null -> StockNoteColors.TextPrimary
                        state.yearGain >= 0 -> StockNoteColors.Up
                        else -> StockNoteColors.Down
                    },
                    // 📅 盈亏日历入口（老周 2026-09-30：从分析页头部挪到「今年收益」卡上）
                    trailingGlyph = "📅",
                    onClick = { AppNav.push(AppRoute.PnlCalendar) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- 现金（不计入持仓）----
        // 老周 2026-09-19：由列表底部**上移到第二栏**（紧跟「持仓市值 / 浮动盈亏」汇总之后）
        item {
            Sec(
                title = "现金 · 不计入持仓",
                more = "出入金 ›",
                onMore = { AppNav.push(AppRoute.CashFlow) },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                CardBox {
                    CashRow("💵", "可用现金", "账户现金余额 · CNY", Format.money(snapshot?.availableCash ?: 0.0))
                    // 现金等价物明细（如 511660 货币 ETF）：归现金子类但可见（老周反馈 2026-09-14）
                    snapshot?.cashEquivalentPositions.orEmpty().forEach { p ->
                        CashRow(
                            glyph = "💰",
                            name = p.name,
                            sub = "${p.symbol} · ${Format.quantity(p.quantity)}${p.market.quantityUnit}",
                            // 与 PortfolioSnapshot.totalAsset 同口径（fx_rate 表最新汇率，老周 2026-09-18 对账修复）
                            value = Format.money(snapshot?.positionMarketValueInBase(p) ?: 0.0),
                            // 与持仓明细同一概念：点进去是该标的详情页（可改单笔流水、可删除）
                            onClick = { onOpenSecurity(p.securityId) },
                        )
                    }
                }
            }
            Text(
                text = "现金合计 ${Format.money(snapshot?.cashAmount ?: 0.0)} = 可用现金 + 现金等价物",
                fontSize = pageSp(11f),
                color = StockNoteColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
            )
        }

        // ---- 筛选 chips ----
        item { ChipStrip(filters, filterIndex) { filterIndex = it } }
        item { ChipStrip(sorts, sortIndex, topPad = 8) { sortIndex = it } }

        // ---- 持仓明细 ----
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
                        visible.forEach { p -> HoldingRow(p, snapshot, onClick = { onOpenSecurity(p.securityId) }) }
                    }
                }
            }
        }

        // ---- 配置占比（环形图）----
        if (snapshot != null && snapshot.positions.isNotEmpty()) {
            item {
                Sec(title = "配置占比", more = "按市场 ▾", modifier = Modifier.padding(top = 16.dp)) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            DonutChart(snapshot, Modifier.size(126.dp))
                            Spacer(Modifier.width(18.dp))
                            Column {
                                ALLOC_GROUPS.forEach { g ->
                                    val v = allocValue(snapshot, g)
                                    // 基金演示数据常态为 0，仅在有持仓时出现，避免一排恒 0.00% 的行
                                    if (v > 0.0 || g.market != Market.FUND) {
                                        LegendRow(g, v, snapshot.totalAsset)
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        // 汇率从「设置 → 汇率管理」取最新值（老周 2026-09-16：此前硬编码 0.92/7.2 与实际不符）
                        text = "分母 = 总资产 ${Format.money(totalAsset)}（含现金）· " + fxSummaryText(state.fxRates),
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
                    )
                }
            }
        }

        // ---- 账外备忘（场外基金「不计入统计与分析」，老周 2026-09-20）----
        // 与上面所有区块的区别：这里的市值**不属于**总资产、不进配置占比、不进资产曲线。
        // 它是一本平行的备忘账 —— 只看「我买的基金现在值多少、赚了多少」。
        val memo = snapshot?.memoPositions.orEmpty()
        if (memo.isNotEmpty()) {
            item {
                Sec(
                    title = "账外备忘（${memo.size}）",
                    more = "不计入统计",
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    CardBox {
                        memo.forEach { p -> MemoRow(p) { onOpenSecurity(p.securityId) } }
                    }
                }
            }
        }

        item {
            ProtoFoot(
                "现金等价物归「现金」子类，不计入持仓明细与持仓占比" +
                    if (memo.isNotEmpty()) "；账外备忘不计入总资产与统计" else "",
            )
        }
    }

}

/**
 * 账外备忘行（场外基金「不计入统计与分析」）。
 *
 * 与 [HoldingRow] 的差别都来自「这是基金、且不进统计」：
 *  - 份额单位是**份**（不是股），价格是**单位净值**；
 *  - **不显示占比**（分母是总资产，账外标的放进去会把百分比算错）；
 *  - 徽标写「账外」，一眼能看出它不在总资产里。
 */
@Composable
private fun MemoRow(p: Position, onClick: () -> Unit) {
    val ratio = p.marketPrice?.let { price -> if (p.avgCost > 0) (price - p.avgCost) / p.avgCost else null }
    StockRow(
        avatar = p.name.take(1),
        name = p.name,
        marketTag = "账外",
        // 明细拆两行（老周 2026-09-21）：第 1 行「数量 · 成本」，第 2 行「净值」
        detail = "${Format.quantity(p.quantity)}${p.market.quantityUnit} · 成本 ${Format.money(p.avgCost, "")}",
        detail2 = "净值 ${p.marketPrice?.let { Format.money(it, "") } ?: "—"}",
        priceText = Format.money(p.marketValue, p.currency.symbol),
        pnlText = Format.moneySigned(p.unrealizedPnl, p.currency.symbol) +
            " · " + Format.percent(ratio, decimals = 1),
        pnlPositive = p.unrealizedPnl >= 0,
        market = p.market,
        avatarTinted = p.unrealizedPnl < 0,
        onClick = onClick,
    )
}

@Composable
private fun IconBox(glyph: String) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = pageSp(15f), color = StockNoteColors.TextSecondary) }
}

@Composable
private fun ChipStrip(options: List<String>, selected: Int, topPad: Int = 13, onSelect: (Int) -> Unit) {
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
private fun HoldingRow(p: Position, snap: com.stocknote.core.model.PortfolioSnapshot?, onClick: () -> Unit) {
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
        // 图标按市场上色（老周 2026-09-21）：与下面「配置占比」环形图同一套色
        market = p.market,
        avatarTinted = p.unrealizedPnl < 0,
        onClick = onClick,
    )
}

@Composable
private fun CashRow(
    glyph: String,
    name: String,
    sub: String,
    value: String,
    /** 非 null 时整行可点（现金等价物：点击进标的详情，与持仓明细同一概念 —— 老周 2026-09-16） */
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE8F6EF)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, fontSize = pageSp(15f)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
            Text(sub, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
        }
        Text(value, fontSize = pageSp(14f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
    }
}

/**
 * 配置占比的分组定义 —— 环形图与图例**共用同一份**，按 [Market] 枚举匹配。
 * 教训一：此前图例拿展示文案「港股(通)」去比对 market.label（"港股"），永远匹配不上 → 0.00%。
 * 教训二：Position.marketValue 是**原币**（苹果 USD / 腾讯 HKD），直接除以本位币总资产
 *         会得到错配百分比（图例合计 < 100%）；必须先经 [FxTable.convert] 折算本位币。
 * market = null 表示「现金（含等价物）」，取 snapshot.cashAmount。
 */
private data class AllocGroup(val color: Color, val label: String, val market: Market?)

private val ALLOC_GROUPS = listOf(
    AllocGroup(Color(0xFF17A567), "现金", null),
    AllocGroup(marketAccent(Market.A_SHARE), "A股", Market.A_SHARE),
    AllocGroup(marketAccent(Market.HK), "港股(通)", Market.HK),
    AllocGroup(marketAccent(Market.US), "美股", Market.US),
    AllocGroup(marketAccent(Market.ETF), "ETF", Market.ETF),
    AllocGroup(marketAccent(Market.FUND), "基金", Market.FUND),
)

private fun allocValue(snap: com.stocknote.core.model.PortfolioSnapshot, g: AllocGroup): Double =
    if (g.market == null) snap.cashAmount
    else snap.positions.filter { it.market == g.market }.sumOf { snap.positionMarketValueInBase(it) }

/** 配置占比环形图：按市场折算本位币后画弧 */
@Composable
private fun DonutChart(snapshot: com.stocknote.core.model.PortfolioSnapshot, modifier: Modifier = Modifier) {
    val snap = snapshot
    val segments = remember(snap) {
        ALLOC_GROUPS.mapNotNull { g ->
            val value = allocValue(snap, g)
            if (value > 0) Triple(g.color, value.toFloat(), g.label) else null
        }
    }
    val total = segments.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.001f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 30f
            val diameter = minOf(size.width, size.height) - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            var startAngle = -90f
            segments.forEach { (color, value, _) ->
                val sweep = (value / total) * 360f
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(diameter, diameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }
        Column(
            modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(Color.White),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("总资产", fontSize = pageSp(9f), color = StockNoteColors.TextTertiary)
            // 2026-09-17 B 方案：总资产带 2 位小数后变长（11 字符），
            // 66dp 的圆里 10sp 会溢出，降到 8sp
            Text(
                Format.money(snap.totalAsset),
                fontSize = pageSp(8f),
                fontWeight = FontWeight.ExtraBold,
                color = StockNoteColors.TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LegendRow(group: AllocGroup, value: Double, totalAsset: Double) {
    val pct = if (totalAsset > 0) value / totalAsset else null
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(group.color))
        Spacer(Modifier.width(8.dp))
        Text(group.label, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            Format.percent(pct, signed = false),
            fontSize = pageSp(12f),
            fontWeight = FontWeight.Bold,
            color = StockNoteColors.TextPrimary,
        )
    }
}


/**
 * 用最新汇率拼一句折算说明（老周 2026-09-16）。
 * 取值口径与 FxTable 一致：每币种取 effective_date 最大的一条；没有该币种则省略。
 * 例：`折算汇率 HK$→¥0.85 · $→¥6.71`；一条都没有时给中性说明。
 */
fun fxSummaryText(rates: List<com.stocknote.core.model.FxRateRecord>): String {
    if (rates.isEmpty()) return "外币按最近录入的汇率折算"
    fun latest(code: String): Double? = rates
        .filter { it.currency == code }
        .maxByOrNull { it.effectiveDate }
        ?.rate
    val parts = buildList {
        // ⚠️ M3 修复（2026-09-28）：改用 Locale 无关的 Format.fixedPlain。
        // ⚠️ 不能写成 `val fx = Format.fixedPlain` 再调用 —— 它是 object 的**成员函数**，
        // 这种函数引用在这里取不到（前一版就因此编译失败）。
        latest("HKD")?.let {
            add("HK$→¥" + com.stocknote.core.format.Format.fixedPlain(it, 4).trimEnd('0').trimEnd('.'))
        }
        latest("USD")?.let {
            add("$→¥" + com.stocknote.core.format.Format.fixedPlain(it, 4).trimEnd('0').trimEnd('.'))
        }
    }
    return if (parts.isEmpty()) "外币按最近录入的汇率折算" else "折算汇率 " + parts.joinToString(" · ")
}

/**
 * 待登记分红送配 / 检测失败的**弹窗**（老周 2026-09-16 起；2026-09-30 改为独立弹窗 + 失败可见）。
 *
 * ⚠️ 与旧版的差别（老周真机反馈"重启和刷新都看不到提示卡"）：
 *  1. 旧版是 LazyColumn 里的一个 item —— 列表长了根本看不到 → 现在由调用方**自动弹出**；
 *  2. 旧版"取数失败"与"确实没分红"都表现为空列表、界面毫无反馈 → 现在失败会**红字明说**。
 *
 * 内容随 state 自动切换：有明细 → 勾选登记；登记完成（items 空 + [message]）→ 结果提示；
 * 检测失败（[error]）→ 失败原因 + 「知道了」。
 */
@Composable
private fun PendingDividendDialog(
    items: List<com.stocknote.data.repo.PortfolioRepository.PendingDividend>,
    selectedKeys: Set<String>,
    message: String?,
    error: String?,
    failedCount: Int,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val title = when {
        error != null && items.isEmpty() -> "⚠️ 分红检测失败"
        items.isEmpty() && message != null -> "✅ 分红登记完成"
        else -> "🎁 待登记的分红送配（${items.size}）"
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = StockNoteColors.Surface,
        title = {
            Text(title, fontSize = pageSp(16f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 失败原因：红底红字，**必须一眼看到**（老周 2026-09-30：此前静默失败无从察觉）
                if (error != null) {
                    Text(
                        error,
                        fontSize = pageSp(12.5f),
                        lineHeight = pageSp(19f),
                        color = StockNoteColors.Danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFEF3F2))
                            .border(1.dp, Color(0xFFFDA29B), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    )
                }
                if (items.isNotEmpty()) {
                    Text(
                        "A股/美股 = 税前票面；港股 = 税后（已扣 20% 港股通红利税）。核对后勾选登记。",
                        fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(top = if (error != null) 8.dp else 0.dp, bottom = 6.dp),
                    )
                }
                items.forEach { d ->
                    val key = d.securityId + "@" + d.exDate
                    val on = key in selectedKeys
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) Color(0xFFF3F7FF) else Color.Transparent)
                            .clickable { onToggle(key) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(if (on) "☑️" else "⬜", fontSize = pageSp(15f))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${d.securityName} · 除权 ${d.exDate}",
                                fontSize = pageSp(13f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary,
                            )
                            // 币种符号按分红币种取 —— ⚠️ 美股是 `$`，旧版一律 `¥` 会显示错
                            val sym = when (d.currency) {
                                "HKD" -> "HK$"
                                "USD" -> "$"
                                else -> "¥"
                            }
                            val parts = buildList {
                                if (d.hasCash) add("现金 ${Format.money(d.estimatedCash, sym, decimals = 2)}（每股 ${Format.money(d.cashPerShare, "", decimals = 4)}）")
                                if (d.hasShares) add("送股/转增 ${Format.quantity(d.estimatedShares)} 股")
                            }
                            Text(
                                "${Format.quantity(d.quantity)} 股 × " + parts.joinToString(" + "),
                                fontSize = pageSp(11f), color = StockNoteColors.TextSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Text(
                                (if (d.payDate != null) "派息日 ${d.payDate} · " else "") + d.taxNote +
                                    (if (d.note.isNotEmpty()) " · ${d.note}" else ""),
                                fontSize = pageSp(10.5f), color = StockNoteColors.TextTertiary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                // 部分标的取数失败 → 明确提示"可能漏检"（老周 2026-09-30）
                if (failedCount > 0 && items.isNotEmpty()) {
                    Text(
                        "另有 $failedCount 个标的取数失败，可能漏检。",
                        fontSize = pageSp(11f), color = StockNoteColors.Danger,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (message != null) {
                    Text(
                        message,
                        fontSize = pageSp(12f), fontWeight = FontWeight.Bold, color = StockNoteColors.Brand,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (items.isEmpty()) {
                TextButton(onClick = onClose) {
                    Text("知道了", fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.Brand)
                }
            } else {
                TextButton(onClick = onConfirm, enabled = selectedKeys.isNotEmpty()) {
                    Text(
                        if (selectedKeys.isEmpty()) "请勾选" else "登记所选（${selectedKeys.size}）",
                        fontSize = pageSp(14f), fontWeight = FontWeight.Bold,
                        color = if (selectedKeys.isEmpty()) StockNoteColors.TextTertiary else StockNoteColors.Brand,
                    )
                }
            }
        },
        dismissButton = {
            if (items.isNotEmpty()) {
                TextButton(onClick = onClose) {
                    Text("稍后", fontSize = pageSp(14f), color = StockNoteColors.TextSecondary)
                }
            }
        },
    )
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与统计页一致；未包的页面默认 1f 不受影响）。 */
@Composable
fun HoldingsScreen(
    state: AppUiState,
    onOpenSecurity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        HoldingsScreenContent(state, onOpenSecurity, modifier)
    }
}

/**
 * **全局「待登记分红」弹窗宿主**（老周 2026-09-30）。
 *
 * 为什么要独立成宿主并挂在 **App 根部**：分红检测在「启动 / 手动刷新」时完成，
 * 而用户当时可能停在**任意页面**（App 默认落「探索」页，统计页也能刷新）——
 * 原来把弹窗挂在持仓页，检测到了也弹不出来（老周真机"重启和刷新都看不到"）。
 *
 * 弹出时机：`dividendScanToken` 每次**检测完成**自增一次 → 只自动弹一次；
 * 且仅当「有未登记分红」或「检测失败」才弹，用户关掉后不再打扰（直到下一次检测）。
 */
@Composable
fun DividendAlertHost(
    state: AppUiState,
    onRegister: (List<com.stocknote.data.repo.PortfolioRepository.PendingDividend>) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var handledToken by remember { mutableIntStateOf(0) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(state.pendingDividends) {
        selectedKeys = state.pendingDividends.map { it.securityId + "@" + it.exDate }.toSet()
    }
    LaunchedEffect(state.dividendScanToken) {
        val token = state.dividendScanToken
        if (token > 0 && token != handledToken) {
            handledToken = token
            if (state.pendingDividends.isNotEmpty() || state.dividendError != null) visible = true
        }
    }

    if (!visible) return

    PendingDividendDialog(
        items = state.pendingDividends,
        selectedKeys = selectedKeys,
        message = state.dividendMessage,
        error = state.dividendError,
        failedCount = state.dividendFailedCount,
        onToggle = { key ->
            selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
        },
        onConfirm = {
            val chosen = state.pendingDividends.filter { (it.securityId + "@" + it.exDate) in selectedKeys }
            if (chosen.isNotEmpty()) onRegister(chosen)
            // ⚠️ 登记后**不关窗**：内容会切换成"已登记 N 条…"的结果提示（由 dividendMessage 驱动），
            //    用户点「知道了」再关闭 —— 否则登记结果一闪而过看不见。
        },
        onClose = { visible = false },
    )
}
