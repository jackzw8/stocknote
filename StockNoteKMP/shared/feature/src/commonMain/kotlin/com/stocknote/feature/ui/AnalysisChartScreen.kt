package com.stocknote.feature.ui

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.PerfFormat
import com.stocknote.core.calc.PerformanceCalculator
import com.stocknote.feature.state.AnalysisHolder
import com.stocknote.feature.theme.StockNoteColors

/**
 * **分析图表**二级页（老周 2026-10-08）—— 由「统计分析」页的「📊 分析图表」按钮进入。
 *
 * 内容是原来挂在「分析」tab 上的那四块：**绩效总览 / 连胜·连亏 / 按策略分析 / 盈亏归因**。
 * 因为那个 tab 位置改成了「看天看地」空白页（[SkyEarthScreen]），这四块整体搬来这里降级成二级页。
 *
 * 绩效口径 = FIFO 配平仓；归因口径 = 移动加权重放（两者并存，见 [AnalysisHolder] 注释）。
 * （原型仍见 `界面原型-新版/analysis.html`，REQ-ANA-01~03/05。）
 */
@Composable
fun AnalysisChartScreen(
    holder: AnalysisHolder,
    refreshKey: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()

    LaunchedEffect(refreshKey) { holder.load() }

    LazyColumn(
        // ⚠️ 必须自带不透明底色：本页是**二级页**、不走 `MainTabs` 的 Scaffold
        //（以前它是 tab 页，靠 Scaffold 的 containerColor 兜底；改成二级页后不补这行，
        //  会直接透出窗口背景的启动图）。
        modifier = modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // ---- 顶部栏：与其它二级页一致（返回 + 标题）----
        // 本页的搬家史（老周 2026-10-08）：资产曲线 → 「持仓」页顶部（见 `EquityCurveCard.kt`）；
        // 💹 收益率分析按钮 → 「统计分析」页；「交易质量」跳转行 → 去掉。
        // 📅 盈亏日历入口早在 2026-09-30 就移到了「持仓页 → 今年收益」卡片右上角。
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBar(title = "分析图表", onBack = onBack)
            }
        }

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

        // 「交易质量」跳转行已去掉（老周 2026-10-08）：原本是进交易日记筛「神操作 / 昏招」的入口。
        // 交易日记本身仍有入口 —— 「持仓」页头部 📝。
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
