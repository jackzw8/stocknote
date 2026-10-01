package com.stocknote.feature.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.PerfFormat
import com.stocknote.core.format.Format
import com.stocknote.core.model.PortfolioSnapshot
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.nav.AppRoute
import com.stocknote.feature.state.AppDisplaySettings
import com.stocknote.feature.state.AppUiState
import com.stocknote.feature.theme.StockNoteColors


/**
 * 统计页 —— 严格对照 `界面原型-新版/dashboard.html`（REQ-VIEW-01）逐块实现。
 * 改样式请先看原型，不要凭感觉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatisticScreenContent(
    state: AppUiState,
    onRefresh: () -> Unit,
    /** 手动全量刷新（统计页头部 🔄）—— 老周 2026-09-18 */
    onRefreshAll: () -> Unit = {},
    /** 关闭「刷新完成」提示 */
    onDismissRefreshMessage: () -> Unit = {},
    onOpenSecurity: (String) -> Unit = {},
    /** 切换底部 tab（「全部›」/「📊 分析」入口）：直接 select，不走导航栈（修复 tab 卡死） */
    onSwitchTab: (com.stocknote.feature.state.Screen) -> Unit = {},
    /** 切换**全局**「隐藏盈亏」模式（REQ-VIEW-09，老周 2026-09-30）：App 层接到设置 holder。 */
    onTogglePrivacy: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    // 隐藏盈亏（REQ-VIEW-09，老周 2026-09-30 升级为**全局**模式）：
    // 此前这里是「只罩 Hero 卡」的局部开关（2026-09-17 临时版），
    // 现统一由设置里的全局开关驱动 —— 点 👁 等于全局切换（所有页面一起遮罩），
    // 不会再出现"统计页遮了、别的页面还露着真数字"。
    val privacy by AppDisplaySettings.privacyMode.collectAsState()

    // 下拉刷新（老周 2026-09-23）：列表滑到顶部继续下滑即触发刷新——复用页内
    // 「🔄 刷新数据」的同一动作（onRefreshAll），完成后照旧弹「刷新完成」结果提示。
    // isRefreshing 直接用 state.refreshing：按钮点、手势拉，同一份状态，不会双重触发。
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefreshAll,
        modifier = modifier.fillMaxSize(),
    ) {
        // 说明：包一层后内层缩进保持原样，避免整段 250 行重排产生巨型 diff。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ---- 顶部栏：标题 + 日期副标题 + ℹ️ + 头像（.top）----
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("交易统计", fontSize = pageSp(21f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                    // 原型 privacy.html：标题下方标注当前处于隐私态，一眼知道"数字被藏起来了"
                    if (privacy) {
                        Text(
                            "已开启「隐藏盈亏」模式",
                            fontSize = pageSp(11f),
                            color = StockNoteColors.TextTertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 注：手动刷新的入口已于 2026-09-21 挪到「持仓 TOP5」上方的页内按钮（🔄 刷新数据）
                // ℹ️ 关于本应用（老周 2026-09-16：从设置页移回统计页头部右侧）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White)
                        .clickable { AppNav.push(AppRoute.About) },
                    contentAlignment = Alignment.Center,
                ) { Text("ℹ️", fontSize = pageSp(14f), modifier = Modifier.semantics { contentDescription = "关于本应用" }) }
                Spacer(Modifier.width(8.dp))
                // ⚙️ 设置（原型 09）
                // 注：自选管理(📌)与交易日记(📝)入口已按老周要求迁到持仓页头部（2026-09-15）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White)
                        .clickable { AppNav.push(AppRoute.Settings) },
                    contentAlignment = Alignment.Center,
                ) { Text("⚙️", fontSize = pageSp(14f), modifier = Modifier.semantics { contentDescription = "打开设置" }) }
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = onRefresh) } }

        // ---- Hero：总资产 + badge + 三列（.hero）----
        // 👁 切换**全局**隐藏盈亏（老周 2026-09-30）：回调由 App 层接到设置 holder（落库 + 即时生效）
        item { HeroCard(snapshot, state.loading, privacy, onTogglePrivacy = onTogglePrivacy) }

        // ---- 指标网格（.mgrid）----
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp)) {
                // 老周 2026-09-20：持仓市值 / 现金合计 改为**上下两行**、各自占满整行
                //（此前是左右并排两个半宽卡片）。整行后金额与副标题都有充足横向空间。
                // 老周 2026-09-27：数值后加**持仓占比**（持仓市值 ÷ 总资产，不带正号）
                val mvTotal = snapshot?.marketValueTotal ?: 0.0
                val assetTotal = snapshot?.totalAsset ?: 0.0
                MetricCell(
                    "📈 持仓市值",
                    Format.money(mvTotal),
                    "${snapshot?.positions?.size ?: 0} 只标的",
                    modifier = Modifier.fillMaxWidth(),
                    valueSuffix = if (assetTotal > 0.0) {
                        Format.percent(mvTotal / assetTotal, decimals = 1, signed = false)
                    } else null,
                )
                Spacer(Modifier.height(11.dp))
                MetricCell(
                    "💰 现金合计",
                    Format.money(snapshot?.cashAmount ?: 0.0),
                    "含现金等价物",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(11.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    // 最大回撤（REQ-ANA-01 真值）：由资产曲线产出，后台异步算好后自动填上
                    val dd = state.maxDrawdown
                    MetricCell(
                        "📉 最大回撤",
                        when {
                            state.equityLoading && dd == null -> "…"
                            dd != null -> Format.percent(-dd, signed = false, decimals = 2)
                            else -> "—"
                        },
                        when {
                            dd != null -> state.drawdownRange.ifEmpty { "基于资产曲线" }
                            state.equityLoading -> "正在计算曲线…"
                            else -> state.equityError ?: "逐日估值 · 待数据"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    // M3 已交付 FIFO 平仓统计，与分析页同源；无平仓样本时如实显示 —
                    MetricCell(
                        "🎯 胜率",
                        PerfFormat.pct(state.winRate),
                        if (state.closedCount > 0) "已平仓 ${state.closedCount} 笔 · FIFO" else "平仓后出数 · FIFO",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- 页内「刷新数据」（老周 2026-09-21）----
        // 原是顶部栏的一个 🔄 图标：图标太小、也没写清它刷的是什么。
        // 现挪进页内成整行按钮，名字就叫「刷新数据」，压在「持仓 TOP5」区块上面。
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    // 蓝底白字（老周 2026-09-21）：与「＋ 记一笔」同一视觉，一眼认出是主操作
                    .background(StockNoteColors.Brand)
                    .clickable(enabled = !state.refreshing) { onRefreshAll() }
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = "刷新数据" },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("🔄", fontSize = pageSp(15f))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.refreshing) "正在刷新数据…" else "刷新数据",
                        fontSize = pageSp(14f),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }

        // ---- 持仓 TOP ----
        if (snapshot != null && snapshot.positions.isNotEmpty()) {
            item {
                Sec(
                    title = "持仓 TOP5",
                    more = "全部 ›",
                    onMore = { onSwitchTab(com.stocknote.feature.state.Screen.HOLDINGS) },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    CardBox {
                        // 老周 2026-09-19：按**市值**降序取前 5（此前固定前 3 条）。
                        // 市值口径 = 折算本位币（与「持仓市值 / 占总资产」一致；
                        // 港股/美股直接用原币市值比较会失真）。
                        snapshot.positions
                            .sortedByDescending { snapshot.positionMarketValueInBase(it) }
                            .take(5)
                            .forEach { p ->
                            val ratio = p.marketPrice?.let { price ->
                                if (p.avgCost > 0) (price - p.avgCost) / p.avgCost else null
                            }
                            StockRow(
                                avatar = p.name.take(1),
                                name = p.name,
                                marketTag = p.market.label,
                                // 明细拆两行（老周 2026-09-21）：第 1 行「数量 · 成本」，第 2 行「现价」
                                detail = "${Format.quantity(p.quantity)}${p.market.quantityUnit} · 成本 ${Format.money(p.avgCost, "")}",
                                // 当日涨跌%（老周 2026-09-23）：现价后追加；缺昨收（手工价/场外基金）则不加
                                detail2 = "现 ${p.marketPrice?.let { Format.money(it, "") } ?: "—"}" +
                                    (p.dayChangeRatio?.let { " · ${Format.percent(it)}" } ?: ""),
                                // 2026-09-17 B 方案：金额统一 2 位小数（StockRow 已纵向四行，不再挤压名称列）
                                priceText = Format.money(p.marketValue, p.currency.symbol),
                                pnlText = Format.moneySigned(p.unrealizedPnl, p.currency.symbol) +
                                    " · " + Format.percent(ratio, decimals = 1),
                                pnlPositive = p.unrealizedPnl >= 0,
                                // 图标按市场上色（老周 2026-09-21）：与持仓明细、配置占比环形图同一套色
                                market = p.market,
                                avatarTinted = p.unrealizedPnl < 0,
                                onClick = { onOpenSecurity(p.securityId) },
                            )
                        }
                    }
                }
            }
        }

        // ---- 隐私说明（.note.blue）----
        item {
            Spacer(Modifier.height(16.dp))
            NoteBar("🔒 数据本地加密存储，无账号、无云端；联网仅查询公开行情。")
        }

        item {
            Text(
                "总资产 = 现金（可用现金 + 现金等价物）+ 各持仓市值\n涨红跌绿为中国习惯",
                fontSize = pageSp(10f),
                color = StockNoteColors.TextTertiary,
                lineHeight = pageSp(16f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            )
        }
    }
    }

    // 刷新结果提示（老周 2026-09-18）：精简列出行情 / 汇率结果
    InfoDialog(
        message = state.refreshMessage,
        title = "刷新完成",
        onDismiss = onDismissRefreshMessage,
    )
}

/** Hero 卡：对应 .hero（渐变底 + 右上/右下装饰圆 + 34px 金额 + badge + 分隔线 + 三列指标） */
@Composable
private fun HeroCard(
    snapshot: PortfolioSnapshot?,
    loading: Boolean,
    privacy: Boolean,
    onTogglePrivacy: () -> Unit,
) {
    // ⚠️ 金额**不再在本卡内局部遮罩**（老周 2026-09-30：升级为全局模式）：
    // 全局开关已把 `Format.money` 遮成 `•••`，这里再包一层就成了"遮罩叠遮罩"。
    // 本卡的 `privacy` 现在只用于右上角 👁 / 🙈 图标。
    fun amt(v: Double) = Format.money(v)
    fun signed(v: Double) = Format.moneySigned(v)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3C6FE8), Color(0xFF2E6BE6), Color(0xFF2559C9)))),
    ) {
        // 右上 / 右下 半透明装饰圆（.hero::after / ::before）
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .align(Alignment.TopEnd),
        )
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.07f))
                .align(Alignment.BottomEnd),
        )

        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("总资产 · 本位币 CNY", fontSize = pageSp(12f), color = Color.White.copy(alpha = 0.86f))
                Spacer(Modifier.weight(1f))
                // 👁 隐藏盈亏（.hero .eye）
                Text(
                    text = if (privacy) "🙈" else "👁",
                    fontSize = pageSp(15f),
                    modifier = Modifier.clickable { onTogglePrivacy() },
                )
                if (loading) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.size(13.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.6f)),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                // 隐私态由**全局遮罩**负责（Format.money → ¥•,•••,•••.••）；
                // 这里不再做局部占位，避免"遮罩叠遮罩"两份口径并存（老周 2026-09-30）。
                text = Format.money(snapshot?.totalAsset ?: 0.0),
                fontSize = pageSp(30f),
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Text(
                    // 同上：遮罩交给全局（Format.percent → ••.••%），本卡不再局部占位
                    text = "累计收益率 ${Format.percent(snapshot?.cumulativeReturn)} · 非年化",
                    fontSize = pageSp(11f),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(15.dp))
            Box(
                Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.20f)),
            )
            Spacer(Modifier.height(13.dp))
            Column {
                // 2026-09-17 B 方案：金额统一 2 位小数。
                // ⚠️ 原「三列等宽」在这些数字变长后装不下 —— 实测 15sp 截成「¥52,43…」、
                // 降到 13sp 仍把「-¥50,601.25」（11 字符）截成「-¥50,601…」
                //（真机 Redmi K30 + 1.1 字体缩放）。三列各仅约 294px，装不下 11 字符的金额。
                // → 改为 **label 左 / 数值右 的整行布局**：每行可用宽度约 880px，余量充足，
                //    与卡片内外其它 MetaRow 风格一致。
                // 可用现金 = 持仓页同口径（不含现金等价物；等价物单独列示）——老周 2026-09-16
                HeroMetric("可用现金", amt(snapshot?.availableCash ?: 0.0))
                HeroMetric("总盈亏", signed(snapshot?.totalPnl ?: 0.0))
                // 持仓浮动盈亏（老周 2026-09-20）：与持仓页「持仓浮动盈亏」同口径
                // （snapshot.unrealizedPnlTotal = Σ 各持仓浮动盈亏折算本位币），
                // 插在「当日盈亏」上方 —— 总盈亏（累计）与当日盈亏（单日）之间给一个"当前持仓浮盈"的量级参考。
                // 蓝色渐变底上沿用白色文字，不套红绿（与同卡片其它指标一致）。
                HeroMetric("持仓浮动盈亏", signed(snapshot?.unrealizedPnlTotal ?: 0.0))
                HeroMetric("当日盈亏", signed(snapshot?.dayPnl ?: 0.0))
            }
        }
    }
}

/** Hero 指标行：label 左 / 数值右（金额 2 位小数后有足够横向空间，不再截断） */
@Composable
private fun HeroMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = pageSp(12f), color = Color.White.copy(alpha = 0.78f))
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = pageSp(15f),
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与统计页一致；未包的页面默认 1f 不受影响）。 */
@Composable
fun StatisticScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    /** 手动全量刷新（统计页头部 🔄）—— 老周 2026-09-18 */
    onRefreshAll: () -> Unit = {},
    /** 关闭「刷新完成」提示 */
    onDismissRefreshMessage: () -> Unit = {},
    onOpenSecurity: (String) -> Unit = {},
    /** 切换底部 tab（「全部›」/「📊 分析」入口）：直接 select，不走导航栈（修复 tab 卡死） */
    onSwitchTab: (com.stocknote.feature.state.Screen) -> Unit = {},
    /** 切换**全局**「隐藏盈亏」模式（REQ-VIEW-09，老周 2026-09-30）：App 层接到设置 holder。 */
    onTogglePrivacy: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        StatisticScreenContent(
            state, onRefresh, onRefreshAll, onDismissRefreshMessage, onOpenSecurity, onSwitchTab,
            onTogglePrivacy, modifier,
        )
    }
}
