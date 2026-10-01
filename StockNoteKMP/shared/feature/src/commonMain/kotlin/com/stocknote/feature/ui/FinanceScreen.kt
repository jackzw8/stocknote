package com.stocknote.feature.ui

import com.stocknote.data.repo.SecurityRepository
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocknote.core.format.Format
import com.stocknote.data.net.FinanceSource
import com.stocknote.feature.state.FinanceHolder
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.launch

/**
 * 个股财务数据页（老周 2026-09-24）。
 *
 * 数据来自**东方财富 F10**（依据《东方财富F10接口技术文档》），按文档 §2 的五个维度分组：
 * 盈利能力 / 成长性 / 财务健康 / 现金流 / 股东回报与估值。
 *
 * 布局：顶部选标的 → 报告期切换（最近 6 期）→ 各维度分组，每行「指标名 + 该期数值」。
 * 数值格式化交给 [FinanceSource.Metric] 的 percent / money 标记；**取不到就显示「—」，不编造**。
 */
@Composable
fun FinanceScreen(
    holder: FinanceHolder,
    repo: com.stocknote.data.repo.PortfolioRepository,
    watch: com.stocknote.data.repo.WatchlistRepository,
    quotes: com.stocknote.data.net.QuoteClient,
    security: com.stocknote.data.repo.SecurityRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 选标的复用资讯页那套（自选 + 在线搜索），页内切换视图
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        NewsSymbolPickerScreen(
            repo = repo,
            quotes = quotes,
            watch = watch,
            security = security,
            onBack = { picking = false },
            onPick = { symbol, name ->
                picking = false
                scope.launch { holder.select(symbol, name) }
            },
        )
        return
    }

    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        LaunchedEffect(Unit) { holder.bootstrap() }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { TopBar(title = "个股财务", onBack = onBack) }
            }

            // ---- 当前标的 ----
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(StockNoteColors.Surface)
                        .clickable { picking = true }
                        .padding(horizontal = 15.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("标的", fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (holder.name.isNotEmpty()) holder.name else "请选择标的",
                        fontSize = pageSp(15f),
                        fontWeight = FontWeight.Bold,
                        color = StockNoteColors.TextPrimary,
                    )
                    if (holder.marketLabel.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(holder.marketLabel, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("切换 ›", fontSize = pageSp(12.5f), color = StockNoteColors.Brand)
                }
            }

            val current = holder.current
            if (holder.loading) {
                item { HintLine("正在获取财务数据…") }
            } else if (current == null) {
                item { HintLine(holder.hint ?: "暂无财务数据") }
            } else {
                // ---- 报告期切换 ----
                item {
                    LazyRowOfPeriods(holder)
                }
                // ---- 各维度 ----
                holder.groupedMetrics.forEach { (group, metrics) ->
                    item {
                        Sec(title = group.label, modifier = Modifier.padding(top = 10.dp)) {
                            CardBox {
                                metrics.forEachIndexed { i, m ->
                                    MetricRow(
                                        metric = m,
                                        value = current.values[m.key],
                                        showDivider = i < metrics.lastIndex,
                                    )
                                }
                            }
                        }
                    }
                }
                // ---- 当前估值（老周 2026-09-24）----
                // A股的 F10 数据集不含 PE/PB/股息率，这两项是从**东财行情接口**单独补的（实时值），
                // 与"某报告期"无关 → 所以**单列一组**并写明「当前」，不混进历史报告期里。
                if (holder.valuation.isNotEmpty()) {
                    item {
                        Sec(title = "当前估值", modifier = Modifier.padding(top = 10.dp)) {
                            CardBox {
                                val rows = listOf(
                                    Triple("pe", "市盈率 PE(TTM)", false),
                                    Triple("pb", "市净率 PB", false),
                                    Triple("dividend_rate", "股息率", true),
                                ).filter { holder.valuation.containsKey(it.first) }
                                rows.forEachIndexed { i, (key, label, pct) ->
                                    MetricRow(
                                        metric = FinanceSource.Metric(key, label, FinanceSource.Group.RETURN, percent = pct),
                                        value = holder.valuation[key],
                                        showDivider = i < rows.lastIndex,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    ProtoFoot(
                        "${current.dateName}（${current.type}） · 数据来自东方财富 F10 · 仅供参考",
                    )
                }
            }
        }
    }
}

/** 报告期切换（横向可滑） */
@Composable
private fun LazyRowOfPeriods(holder: FinanceHolder) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(holder.reports.size) { i ->
            val r = holder.reports[i]
            val on = i == holder.periodIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) StockNoteColors.Brand else StockNoteColors.Surface)
                    .clickable { holder.selectPeriod(i) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(
                    text = r.dateName.ifEmpty { r.date },
                    fontSize = pageSp(12f),
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) Color.White else StockNoteColors.TextSecondary,
                )
            }
        }
    }
}

/** 一行指标：名称 + 数值 */
@Composable
private fun MetricRow(
    metric: FinanceSource.Metric,
    value: Double?,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                metric.label,
                fontSize = pageSp(13f),
                color = StockNoteColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatMetric(metric, value),
                fontSize = pageSp(14f),
                fontWeight = FontWeight.Bold,
                color = if (value == null) StockNoteColors.TextTertiary else StockNoteColors.TextPrimary,
            )
        }
        if (showDivider) {
            Box(Modifier.padding(horizontal = 15.dp).height(0.6.dp).background(StockNoteColors.Divider))
        }
    }
}

/**
 * 数值格式化：
 *  - percent 指标：接口给的就是百分数（如 16.75 表示 16.75%）→ 直接加 `%`；
 *  - money 指标：原始单位为元 → 折算**亿元**（接口量级大，元位数太多没法读）；
 *  - 其余保留 2 位小数；**null → 「—」**（该市场没有这个指标，绝不编造）。
 */
private fun formatMetric(metric: FinanceSource.Metric, value: Double?): String {
    if (value == null || value.isNaN()) return "—"
    return when {
        metric.percent -> Format.percent(value / 100.0, decimals = 2).removePrefix("+")
        metric.money -> {
            val yi = value / 100_000_000.0
            // ⚠️ M3 修复（2026-09-28）：改用 Locale 无关的 Format.fixedPlain（原 "%.Nf".format 会在
            // 逗号小数符地区输出 `1,23 亿`）。保留原有位数与「亿」后缀，展示效果不变。
            com.stocknote.core.format.Format.fixedPlain(yi, if (kotlin.math.abs(yi) >= 100) 1 else 2) + " 亿"
        }
        // 普通数值：≥100 保留 2 位（如 200.99 够读），小额保留 4 位去尾零（如 0.0774 别被截成 0.08）
        else -> if (kotlin.math.abs(value) >= 100) {
            com.stocknote.core.format.Format.fixedPlain(value, 2)
        } else {
            com.stocknote.core.format.Format.fixedPlain(value, 4).trimEnd('0').trimEnd('.')
        }
    }
}

@Composable
private fun HintLine(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary)
    }
}
