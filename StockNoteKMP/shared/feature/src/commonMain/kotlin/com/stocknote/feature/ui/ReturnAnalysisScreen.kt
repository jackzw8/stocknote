package com.stocknote.feature.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.EquityCurve
import com.stocknote.core.format.Format
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.theme.StockNoteColors
import kotlin.math.pow

/**
 * 收益率分析页（REQ-VIEW-07 P1）。
 *
 * 口径严格按需求 5.12/REQ-VIEW-07 原文（老周 2026-09-15 定稿采用真定义）：
 *  1. **累计收益率 = 总盈亏 / 累计净投入成本**（简单口径、非年化）；
 *     净投入 = 买入(含费) − 卖出回款 − 现金分红；总盈亏 = 期末持仓市值 − 净投入。
 *  2. **年化 = 资金加权 XIRR**（真实收益率）：买入负、卖出正、分红正、期末市值正。
 *  3. **基准对比（沪深300）**：基准取同期区间涨跌；可对比超额收益。
 * 三者口径在页内明确标注，避免混用（需求 5.12）。
 */
/**
 * 页面入口：**整页按 1.2 倍字号**（老周 2026-09-24，与统计页 / 持仓页同一做法）。
 *
 * 只在入口包一层 CompositionLocalProvider，页面内容整体挪到 [ReturnAnalysisScreenContent]，
 * 不改内容函数的缩进（避免整段重排改到行尾、把提交 diff 搞虚胖）。
 */
@Composable
fun ReturnAnalysisScreen(
    points: List<EquityCurve.Point>,
    /** 真口径数据（Repo.returnStats）；null = 尚未算出 */
    stats: com.stocknote.data.repo.PortfolioRepository.ReturnStats?,
    /** 基准（沪深300）区间涨跌幅；null = 未取到 */
    benchmarkChange: Double? = null,
    benchmarkName: String = "沪深300",
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        ReturnAnalysisScreenContent(
            points = points,
            stats = stats,
            benchmarkChange = benchmarkChange,
            benchmarkName = benchmarkName,
            modifier = modifier,
        )
    }
}

@Composable
private fun ReturnAnalysisScreenContent(
    points: List<EquityCurve.Point>,
    /** 真口径数据（Repo.returnStats）；null = 尚未算出 */
    stats: com.stocknote.data.repo.PortfolioRepository.ReturnStats?,
    /** 基准（沪深300）区间涨跌幅；null = 未取到 */
    benchmarkChange: Double? = null,
    benchmarkName: String = "沪深300",
    modifier: Modifier = Modifier,
) {
    
    LazyColumn(
        modifier = modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBar(title = "收益率分析") { AppNav.pop() }
            }
        }

        if (points.size < 2) {
            item {
                SectionCard(title = "暂无数据") {
                    Text(
                        "需要资产曲线（至少 2 个估值点）。请确认网络可用后先加载一次资产曲线。",
                        fontSize = pageSp(13f),
                        color = StockNoteColors.TextTertiary,
                    )
                }
            }
            return@LazyColumn
        }

        val lastPoint = points.last()
        val firstPoint = points.first()

        // ---- 三个口径（真定义，数据来自 Repo.returnStats）----
        item {
            Sec(title = "收益率三口径", modifier = Modifier.padding(top = 8.dp)) {
                if (stats == null) {
                    Text(
                        "正在计算收益率…（需要交易流水与期末市值）",
                        fontSize = pageSp(13f),
                        color = StockNoteColors.TextTertiary,
                    )
                } else {
                    CardBox {
                        Row(Modifier.fillMaxWidth().padding(15.dp)) {
                            // 注脚（"总盈亏 ÷ 净投入"、"资金加权 · 真实收益率"）按老周 2026-09-24 要求删除
                            MetricBlock(
                                "累计收益率",
                                Format.percent(stats.cumulativeReturn, decimals = 2),
                                color = if ((stats.cumulativeReturn ?: 0.0) >= 0) StockNoteColors.Up else StockNoteColors.Down,
                                modifier = Modifier.weight(1f),
                                centered = true,
                            )
                            MetricBlock(
                                // 改名为「交易年化」（老周 2026-09-24，方案 A）：与统计页的「账户年化」区分 ——
                                // 本站只看买卖/分红产生的收益 + 期末持仓市值，**不含期初现金与出入金**
                                "交易年化（XIRR）",
                                Format.percent(stats.xirr, decimals = 2),
                                color = if ((stats.xirr ?: 0.0) >= 0) StockNoteColors.Up else StockNoteColors.Down,
                                modifier = Modifier.weight(1f),
                                centered = true,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(StockNoteColors.Divider))
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            // 沪深300 的注脚「同期基准对比」删除；这段（含右侧超额收益）整体居中（老周 2026-09-24）
                            MetricBlock(
                                benchmarkName,
                                if (benchmarkChange != null) {
                                    Format.percent(benchmarkChange, decimals = 2)
                                } else {
                                    "—"
                                },
                                color = if ((benchmarkChange ?: 0.0) >= 0) StockNoteColors.Up else StockNoteColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                                centered = true,
                            )
                            val cum = stats.cumulativeReturn
                            val excess = if (benchmarkChange != null && cum != null) {
                                cum - benchmarkChange
                            } else null
                            MetricBlock(
                                "超额收益",
                                if (excess != null) Format.percent(excess, decimals = 2) else "—",
                                sub = if (excess != null) "组合 − 基准" else "需基准数据",
                                color = if ((excess ?: 0.0) >= 0) StockNoteColors.Up else StockNoteColors.Down,
                                modifier = Modifier.weight(1f),
                                centered = true,
                            )
                        }
                    }
                }
            }
        }

        // ---- 投入与市值 ----
        item {
            Sec(title = "投入与市值", modifier = Modifier.padding(top = 16.dp)) {
                if (stats != null) {
                    CardBox {
                        // 标签和数字**分行显示**（老周 2026-09-24）：原来是 MetaRow（标签左、数字右两端对齐），
                        // 长标签「累计净投入成本（买入−卖出−分红）」会换行、数字跟着跳，改成上下两行更清楚
                        LabelValueStack(
                            "累计净投入成本（买入−卖出−分红）",
                            Format.money(stats.netInvested),
                            StockNoteColors.TextPrimary,
                        )
                        Spacer(Modifier.height(10.dp))
                        LabelValueStack(
                            "当前持仓市值",
                            Format.money(stats.marketValue),
                            StockNoteColors.TextPrimary,
                        )
                        Spacer(Modifier.height(10.dp))
                        LabelValueStack(
                            "总盈亏（市值 − 净投入）",
                            Format.moneySigned(stats.totalPnl),
                            if (stats.totalPnl >= 0) StockNoteColors.Up else StockNoteColors.Down,
                        )
                    }
                }
            }
        }

        // ---- 区间资产变化 ----
        item {
            Sec(title = "区间资产", modifier = Modifier.padding(top = 16.dp)) {
                CardBox {
                    MetaRow(
                        label = "起点总资产（${firstPoint.date}）",
                        value = Format.money(firstPoint.totalAsset),
                        valueColor = StockNoteColors.TextPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    MetaRow(
                        label = "最新总资产（${lastPoint.date}）",
                        value = Format.money(lastPoint.totalAsset),
                        valueColor = StockNoteColors.TextPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    MetaRow(
                        label = "区间盈亏",
                        value = Format.moneySigned(lastPoint.totalAsset - firstPoint.totalAsset),
                        valueColor = if (lastPoint.totalAsset >= firstPoint.totalAsset) {
                            StockNoteColors.Up
                        } else {
                            StockNoteColors.Down
                        },
                    )
                }
            }
        }

        // ---- 口径说明（三口径各自标注，避免混淆）----
        item {
            Sec(title = "口径说明", modifier = Modifier.padding(top = 16.dp)) {
                Column {
                    CaliberNote(
                        "累计收益率",
                        "总盈亏 ÷ 累计净投入成本（简单口径、非年化）。净投入 = 买入(含费) − 卖出回款 − 现金分红。",
                    )
                    CaliberNote(
                        "年化（XIRR）",
                        "资金加权年化（真实收益率）：买入负、卖出正、分红正、期末市值正。",
                    )
                    CaliberNote(
                        "基准",
                        "$benchmarkName 同期区间涨跌幅；取不到时显示 —，不以估算值冒充实测。",
                    )
                    CaliberNote(
                        "区间口径提醒",
                        "曲线用 K 线收盘价、统计页用实时行情，两者允许 ~1% 价格源差异。",
                    )
                }
            }
        }

        item { ProtoFoot("数据来自资产曲线与交易流水重放") }
    }
}

/** 「投入与市值」用的上下两行：标签在上（小字灰），数字在下（加粗）（老周 2026-09-24） */
@Composable
private fun LabelValueStack(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = pageSp(15f), fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
private fun MetricBlock(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    /** 注脚小字（老周 2026-09-24：三口径卡的注脚删掉，只有「超额收益」保留"组合 − 基准"） */
    sub: String? = null,
    /** 内容在格内水平居中（老周 2026-09-24：沪深300/超额收益那一段要求居中显示） */
    centered: Boolean = false,
) {
    Column(
        modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(label, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontSize = pageSp(21f),
            fontWeight = FontWeight.ExtraBold,
            color = color,
            maxLines = 1,
        )
        if (sub != null) {
            Spacer(Modifier.height(3.dp))
            Text(sub, fontSize = pageSp(10.5f), color = StockNoteColors.TextTertiary, maxLines = 2)
        }
    }
}

@Composable
private fun CaliberNote(title: String, desc: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(title, fontSize = pageSp(12.5f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
        Text(desc, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary, lineHeight = pageSp(17f))
    }
}
