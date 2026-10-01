package com.stocknote.feature.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.stocknote.core.calc.PerfFormat
import com.stocknote.core.calc.PerformanceCalculator
import com.stocknote.core.format.Format
import com.stocknote.feature.chart.NetValueChart
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.nav.AppRoute
import com.stocknote.feature.state.AnalysisHolder
import com.stocknote.feature.theme.StockNoteColors

/**
 * 分析复盘页 —— 严格对照 `界面原型-新版/analysis.html`（REQ-ANA-01~03/05）。
 * 绩效口径 = FIFO 配平仓；归因口径 = 移动加权重放（两者并存，见 AnalysisHolder 注释）。
 */
@Composable
fun AnalysisScreen(
    holder: AnalysisHolder,
    refreshKey: Int,
    /** 资产曲线（组合总资产）—— 原「图表中心」内容，老周 2026-09-19 迁到本页第一栏 */
    equityCurve: com.stocknote.data.repo.PortfolioRepository.EquityCurveResult? = null,
    /** 基准（沪深300）逐日收盘 (date, close)；null = 不叠基准线 */
    benchmark: List<Pair<String, Double>>? = null,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()

    LaunchedEffect(refreshKey) { holder.load() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // ---- 顶部栏：标题 + 📈 图表中心 ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("分析图表", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                }
                Spacer(Modifier.weight(1f))
                // 头部导航（老周 2026-09-15：盈亏日历/收益率分析自图表中心迁来，靠右只放图标）
                // 注：📈「图表中心」入口已随页面一起删除（老周 2026-09-19：资产曲线直接迁入本页第一栏）
                // 📅 盈亏日历入口**已移走**（老周 2026-09-30）：改挂到「持仓页 → 今年收益」卡片右上角
                // 💹 收益率分析（REQ-VIEW-07）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White)
                        .clickable { AppNav.push(AppRoute.ReturnAnalysis) },
                    contentAlignment = Alignment.Center,
                ) { Text("💹", fontSize = 14.sp, modifier = Modifier.semantics { contentDescription = "分红登记" }) }
            }
        }

        // ---- 第一栏：资产曲线（组合总资产）----
        // 老周 2026-09-19：原独立「图表中心」整页作废，资产曲线直接作为本页第一个栏目。
        item { EquityCurveCard(equityCurve, benchmark) }

        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(26.dp))
                }
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.load() }) } }

        // ---- 绩效总览（stat3 × 2）----
        state.stats?.let { st ->
            val wr = st.winRate
            item {
                Sec(title = "绩效总览", more = "FIFO 口径", modifier = Modifier.padding(top = 0.dp)) {
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                        Stat3Row(
                            Triple("胜率", PerfFormat.pct(wr), wr != null && wr > 0),
                            Triple("盈亏比", PerfFormat.ratio(st.profitFactor), false),
                            Triple(
                                "平均持仓",
                                PerfFormat.days(st.avgHoldingDays),
                                false,
                            ),
                        )
                        Divider()
                        Stat3Row(
                            Triple("平均盈利", PerfFormat.money(st.avgWin), (st.avgWin ?: 0.0) > 0),
                            Triple("平均亏损", PerfFormat.money(st.avgLose), false),
                            Triple("平仓笔数", "${st.closedCount}", false),
                        )
                    }
                }
            }

            // ---- 连胜 / 连亏 ----
            item {
                Sec(title = "连胜 / 连亏", modifier = Modifier.padding(top = 16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StreakBox(
                            title = "当前连胜",
                            count = st.winCount,
                            current = state.streaks?.currentWin ?: 0,
                            max = state.streaks?.maxWin ?: 0,
                            positive = true,
                            modifier = Modifier.weight(1f),
                        )
                        StreakBox(
                            title = "当前连亏",
                            count = st.loseCount,
                            current = state.streaks?.currentLose ?: 0,
                            max = state.streaks?.maxLose ?: 0,
                            positive = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- 按策略分析（无标签数据时回退到按市场）----
            item {
                val groups = state.tagGroups.ifEmpty { state.marketGroups }
                val fallback = state.tagGroups.isEmpty()
                Sec(
                    title = "按策略分析",
                    more = if (fallback) "按市场 ▾" else "按标签分组",
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    CardBox {
                        if (groups.isEmpty()) {
                            EmptyHint("还没有平仓记录。记几笔买卖并卖出后，这里会出现绩效分析。")
                        } else {
                            groups.forEachIndexed { i, g ->
                                StratCard(g, winRateMax = 100.0)
                                if (i != groups.lastIndex) Divider()
                            }
                        }
                    }
                    Text(
                        text = if (fallback) {
                            "还没有打标签的平仓样本，先按市场展示；记一笔时可选策略标签。"
                        } else {
                            "按标签分组，仅统计已打标交易；以卖出时点的标签为准。"
                        },
                        fontSize = 11.sp,
                        color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
                    )
                }
            }
        }

        // ---- 盈亏归因（REQ-ANA-03）----
        item {
            Sec(title = "盈亏归因", modifier = Modifier.padding(top = 16.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    Column(Modifier.padding(16.dp)) {
                        val price = state.pricePnl
                        val div = state.dividendTotal
                        val total = state.totalPnl
                        val priceW = if (total != 0.0) (price / total * 100).coerceIn(0.0, 100.0) else 0.0
                        val divW = if (total != 0.0) (div / total * 100).coerceIn(0.0, 100.0) else 0.0

                        Text("股价涨跌收益", fontSize = 12.sp, color = StockNoteColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                PerfFormat.money(price),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (price >= 0) StockNoteColors.Up else StockNoteColors.Down,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Bar(priceW, if (price >= 0) Color(0xFFE23B3B) else Color(0xFF17A567))

                        Spacer(Modifier.height(16.dp))
                        Text("分红累计收益", fontSize = 12.sp, color = StockNoteColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (state.dividendCount == 0L) "暂无分红记录" else PerfFormat.money(div),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = StockNoteColors.TextTertiary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Bar(divW, Color(0xFFF5A623))

                        Spacer(Modifier.height(16.dp))
                        Divider()
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("合计总盈亏", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                PerfFormat.money(total),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (total >= 0) StockNoteColors.Up else StockNoteColors.Down,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Divider()
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val x = state.xirr
                            Column {
                                // 改名为「账户年化」（老周 2026-09-24，方案 A）：
                                // 与收益率分析页的「交易年化」区分 —— 本站口径含期初现金与出入金、期末用总资产
                                Text("账户年化 XIRR", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                                Text(
                                    if (x != null) "资金加权 · 年化 · 含期初现金" else "样本时间跨度不足 · 暂无法年化",
                                    fontSize = 10.sp,
                                    color = StockNoteColors.TextTertiary,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (x != null) PerfFormat.pct(x) else "—",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = when {
                                    x == null -> StockNoteColors.TextTertiary
                                    x >= 0 -> StockNoteColors.Up
                                    else -> StockNoteColors.Down
                                },
                            )
                        }
                    }
                }
                Text(
                    text = "股价涨跌 = 已实现（移动加权）+ 浮动盈亏；分红来自分红送股登记（标的地页 › 分红送股）。",
                    fontSize = 11.sp,
                    color = StockNoteColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
                )
            }
        }

        // 交易质量（REQ-ANA-04）入口：标签管理已移入设置页（老周反馈 2026-09-14）
        item {
            Sec(title = "交易质量", modifier = Modifier.padding(top = 16.dp)) {
                CardBox {
                    Row(
                        Modifier.fillMaxWidth().clickable { AppNav.push(AppRoute.Diary) }.padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEAF7F1)),
                            contentAlignment = Alignment.Center,
                        ) { Text("🎯", fontSize = 17.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("神操作 / 昏招回顾", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                            Text(
                                "在交易日记里筛选查看两类评级交易",
                                fontSize = 11.sp, color = StockNoteColors.TextTertiary,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Text("›", fontSize = 14.sp, color = StockNoteColors.TextTertiary)
                    }
                }
            }
        }

    }
}

@Composable
private fun Stat3Row(a: Triple<String, String, Boolean>, b: Triple<String, String, Boolean>, c: Triple<String, String, Boolean>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        listOf(a, b, c).forEachIndexed { i, (k, v, good) ->
            if (i > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(38.dp)
                        .background(StockNoteColors.Divider)
                        .align(Alignment.CenterVertically),
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(k, fontSize = 11.sp, color = StockNoteColors.TextSecondary)
                Spacer(Modifier.height(7.dp))
                // 金额类数值可能偏长（如 +¥3,880.40），19sp 在三列格里会折成多行
                //（老周 2026-09-23 真机实测：「+¥3,880.40」被折成三行）。按长度缩字号 + 强制单行。
                Text(
                    v,
                    fontSize = when {
                        v.length >= 11 -> 13.sp
                        v.length >= 9 -> 15.sp
                        else -> 19.sp
                    },
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    softWrap = false,
                    color = when {
                        v.startsWith("-") -> StockNoteColors.Down
                        good -> StockNoteColors.Up
                        else -> StockNoteColors.TextPrimary
                    },
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(StockNoteColors.Divider))
}

@Composable
private fun StreakBox(
    title: String,
    count: Int,
    current: Int,
    max: Int,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            // ⚠️ 配色按 A 股习惯：红 = 赚（连胜）、绿 = 亏（连亏）（老周 2026-09-24 要求红绿对调；
            //    原实现连胜给绿、连亏给红，正好反了）
            .background(if (positive) Color(0xFFFDF0EE) else Color(0xFFE8F6EF))
            .padding(13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (positive) Color(0xFFB02B2B) else Color(0xFF0F7A4C),
        )
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$current",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (positive) StockNoteColors.Up else StockNoteColors.Down,
            )
            Text(" 笔", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StockNoteColors.TextSecondary)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "样本 $count 笔 · 历史最大 $max 笔",
            fontSize = 10.sp,
            color = StockNoteColors.TextTertiary,
        )
    }
}

/** 策略卡：对应 .strat（名称/笔数/盈亏 + 胜率/盈亏比/均持 + 进度条） */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun StratCard(g: PerformanceCalculator.GroupStat, winRateMax: Double) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.label, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
            Spacer(Modifier.width(7.dp))
            Text("${g.count} 笔", fontSize = 11.sp, color = StockNoteColors.TextTertiary)
            Spacer(Modifier.weight(1f))
            Text(
                PerfFormat.money(g.pnl),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (g.pnl >= 0) StockNoteColors.Up else StockNoteColors.Down,
            )
        }
        Spacer(Modifier.height(9.dp))
        // FlowRow：一行放不下时**按「标签+值」对整体换行**（老周 2026-09-16：
        // 「均持 98天」作为整体落到第二行，不再拆碎）
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("胜率 ", fontSize = 11.sp, color = StockNoteColors.TextTertiary)
                Text(PerfFormat.pct(g.winRate), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("盈亏比 ", fontSize = 11.sp, color = StockNoteColors.TextTertiary)
                Text(PerfFormat.ratio(g.profitFactor), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("均持 ", fontSize = 11.sp, color = StockNoteColors.TextTertiary)
                Text(PerfFormat.days(g.avgHoldingDays), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(9.dp))
        val wr = ((g.winRate ?: 0.0) * 100).coerceIn(0.0, 100.0)
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFEFF2F7)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((wr / 100.0).toFloat())
                    .height(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (g.pnl >= 0) Color(0xFFE23B3B) else Color(0xFF17A567)),
            )
        }
    }
}

@Composable
private fun Bar(percentWidth: Double, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFEFF2F7)),
    ) {
        Box(
            Modifier
                .fillMaxWidth((percentWidth / 100.0).toFloat())
                .height(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
        )
    }
}

/**
 * 资产曲线卡片（组合总资产）—— 原独立「图表中心」页的主体内容。
 * 老周 2026-09-19：图表中心整页作废，资产曲线直接作为分析页**第一个栏位**。
 *
 * 口径：现金 + 持仓市值（外币按最新汇率折算）；粒度可切 日/周/月/年；
 * 可叠加沪深300基准（同区间归一化对比）。
 */
@Composable
private fun EquityCurveCard(
    equityCurve: com.stocknote.data.repo.PortfolioRepository.EquityCurveResult?,
    benchmark: List<Pair<String, Double>>?,
) {
    // ---- REQ-VIEW-05：粒度切换（日/周/月/年，原型默认"周"）----
    var granularity by remember {
        mutableStateOf(com.stocknote.core.calc.EquityCurve.Granularity.WEEK)
    }
    val granularities = com.stocknote.core.calc.EquityCurve.Granularity.entries

    SectionCard(title = "资产曲线（组合总资产）") {
        val result = equityCurve
        val data = result?.data
        when {
            // 仍在异步拉取（历史收盘价首次加载需数秒）—— 此前这一态与"失败"混在一起，
            // 导致加载中就直接显示"需要联网"，误导排查。
            result == null -> Text(
                text = "正在拉取持仓标的历史收盘价…（首次加载约需数秒，之后走本地缓存即时可用）",
                fontSize = 13.sp,
                color = StockNoteColors.TextTertiary,
            )

            data == null -> Text(
                text = "暂无资产曲线：${result.reason ?: "原因未知"}",
                fontSize = 13.sp,
                color = StockNoteColors.TextTertiary,
            )

            else -> {
                // ---- REQ-VIEW-05：粒度聚合（日/周/月/年）----
                val allPts = data.points
                val resampled = remember(allPts, granularity) {
                    com.stocknote.core.calc.EquityCurve.resample(allPts, granularity)
                }
                val pts = resampled
                val values = pts.map { it.totalAsset }
                val first = values.first()
                val last = values.last()
                val tone = if (last >= first) StockNoteColors.Up else StockNoteColors.Down

                // 粒度切换 chips（原型 seg2：日/周/月/年）
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    granularities.forEach { g ->
                        val on = g == granularity
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) StockNoteColors.Brand else Color(0xFFF3F6FB))
                                .clickable { granularity = g }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                g.label,
                                fontSize = 12.sp,
                                color = if (on) Color.White else StockNoteColors.TextSecondary,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                // ---- REQ-VIEW-07：叠加基准曲线（沪深300，同区间涨跌幅归一化）----
                val compareValues = remember(pts, benchmark) {
                    if (benchmark.isNullOrEmpty() || benchmark.size < 2) null
                    else {
                        val benchMap = benchmark.associate { it.first to it.second }
                        var lastClose: Double? = null
                        pts.mapNotNull { p ->
                            // 顺延取 <= 当日最近的基准收盘
                            var v: Double? = benchMap[p.date]
                            if (v == null) {
                                val earlier = benchmark.filter { it.first <= p.date }
                                v = earlier.lastOrNull()?.second
                            }
                            lastClose = v ?: lastClose
                            lastClose
                        }
                    }
                }
                // X 轴日期刻度：首 / 1/3 / 2/3 / 末（原型 .axis 四个刻度）
                val xLabels = remember(pts) {
                    if (pts.size < 4) null
                    else listOf(
                        pts.first().date.take(7),
                        pts[pts.size / 3].date.take(7),
                        pts[pts.size * 2 / 3].date.take(7),
                        pts.last().date.take(7),
                    )
                }

                NetValueChart(
                    values = values,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    lineColor = tone,
                    compareValues = compareValues,
                    xLabels = xLabels,
                    // 老周 2026-09-28：Y 轴以「万」为单位（原来是 ¥1,330,000 这类长标签，挤在左侧）。
                    // ⚠️ 只在这条**资产曲线**上覆盖：NetValueChart 是通用件，标的详情的净值/价格图
                    // 仍用默认的 Format.money（净值用「万」没有意义）。
                    labelFormatter = { com.stocknote.core.format.Format.wan(it, 1) },
                )
                Spacer(Modifier.height(8.dp))
                if (compareValues != null) {
                    Row(Modifier.padding(bottom = 4.dp)) {
                        Text("── 组合", fontSize = 11.sp, color = tone, modifier = Modifier.padding(end = 12.dp))
                        Text("- - 基准(沪深300)", fontSize = 11.sp, color = Color(0xFF9AA4B2))
                    }
                }
                Text(
                    text = "${pts.first().date} ~ ${pts.last().date} · ${granularity.label}粒度 · 近 ${data.dataDays} 个交易日收盘 · " +
                        "口径：现金 + 持仓市值（外币按最新汇率折算）",
                    fontSize = 11.sp,
                    color = StockNoteColors.TextTertiary,
                )
                Spacer(Modifier.height(12.dp))
                KeyValueGrid(
                    items = listOf(
                        Triple("最新总资产", Format.money(last), if (last >= first) StockNoteColors.Up else StockNoteColors.Down),
                        Triple("区间变化", Format.percent(if (first > 0) (last - first) / first else null), StockNoteColors.TextPrimary),
                        Triple("最高", Format.money(values.max()), StockNoteColors.TextPrimary),
                        Triple("最低", Format.money(values.min()), StockNoteColors.TextPrimary),
                    ),
                )
                Spacer(Modifier.height(12.dp))
                // 最大回撤：数值**紧跟在标签后面靠左**（老周 2026-09-24）。
                // 原来用 MetaRow —— 它把 label 设成 weight(1f)，会把数值一路顶到最右边，
                // 和上方的「最新总资产 / 区间变化」两列布局看起来对不齐。
                val dd = data.maxDrawdown
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "最大回撤",
                        fontSize = pageSp(13f),
                        color = StockNoteColors.TextSecondary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        Format.percent(-dd.maxDrawdown, signed = false),
                        fontSize = pageSp(13f),
                        fontWeight = FontWeight.Medium,
                        color = StockNoteColors.Down,
                    )
                }
                Text(
                    // 无回撤（或点数不足）时不能照抄「峰值  → 谷值」：那时 peakDate/troughDate
                    // 是空串或同一个首日，会显示成「峰值  → 谷值」这种空壳文案（老周 2026-09-24 核对时发现）
                    text = if (dd.maxDrawdown > 0 && dd.peakDate.isNotBlank() && dd.troughDate.isNotBlank()) {
                        "峰值 ${dd.peakDate} → 谷值 ${dd.troughDate}"
                    } else {
                        "区间内没有回撤"
                    },
                    fontSize = 11.sp,
                    color = StockNoteColors.TextTertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
