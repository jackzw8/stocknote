package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.format.Format
import com.stocknote.feature.chart.NetValueChart
import com.stocknote.feature.theme.StockNoteColors

/**
 * 资产曲线卡片（组合总资产）—— 原独立「图表中心」页的主体内容。
 *
 * 位置沿革（老周）：
 *  - 2026-09-19：图表中心整页作废，资产曲线作为**分析页第一个栏目**；
 *  - **2026-10-08：迁到「持仓」页顶部**（分析页改作「看天看地」，不再承载曲线）。
 *    本文件因此从分析页那个大文件里抽出来单独成件 —— 一个卡片不跟着页走，跟着"谁在用"走。
 *
 * 口径：现金 + 持仓市值（外币按最新汇率折算）；粒度可切 日/周/月/年；
 * 可叠加沪深300基准（同区间归一化对比）。
 *
 * ⚠️ 数据由 App 层按「持仓页是否可见」门控拉取（`equityCurveCached()`），
 *    `null` = 还没拉（显示"正在拉取…"），不是错误。
 */
@Composable
internal fun EquityCurveCard(
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
                    labelFormatter = { Format.wan(it, 1) },
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
