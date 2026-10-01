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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stocknote.core.calc.RiskScanner
import com.stocknote.feature.state.RiskScanHolder
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.launch

/**
 * 个股扫雷页（老周 2026-09-24）。
 *
 * 规则移植自《东财F10个股风险扫雷技术方案 v1.1》：**4 维度 · 24 项 · 32 指标**，
 * 每项 ✅正常 / ⚠️预警 / 🔴高危，维度得分加权合成 0–100 风险总分与五档等级。
 *
 * 布局：顶部选标的 → 风险总分卡（大数字 + 等级）→ 四维度进度条 → 分组明细（名称 / 值 / 灯色）。
 * ⚠️ 配色是**风险语义**（绿=正常 / 橙=预警 / 红=高危），与行情涨跌色无关，别混用。
 */
@Composable
fun RiskScanScreen(
    holder: RiskScanHolder,
    repo: com.stocknote.data.repo.PortfolioRepository,
    watch: com.stocknote.data.repo.WatchlistRepository,
    quotes: com.stocknote.data.net.QuoteClient,
    security: com.stocknote.data.repo.SecurityRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
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
                ) { TopBar(title = "个股扫雷", onBack = onBack) }
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

            val res = holder.result
            when {
                holder.loading -> item { ScanHint("正在扫雷（4 维度 24 项…）") }
                res == null -> item { ScanHint(holder.hint ?: "暂无数据") }
                else -> {
                    item { ScoreCard(res) }
                    item { DimBars(res) }
                    RiskScanner.Dim.entries.forEach { dim ->
                        val items = res.items.filter { it.dim == dim }
                        if (items.isNotEmpty()) {
                            // 权重取**本次**使用的（A 股 / 港股不同），不能用 Dim.weight 的默认值
                            item { DimSection(dim, items, res.weights[dim] ?: dim.weight) }
                        }
                    }
                    item {
                        ProtoFoot(
                            "数据来源：东方财富 F10 / 数据中心 · 规则化判断，仅供研究参考，不构成投资建议",
                        )
                    }
                }
            }
        }
    }
}

/** 风险总分卡：大数字 + 等级徽章 + 危险/预警计数 */
@Composable
private fun ScoreCard(res: RiskScanner.Result) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(StockNoteColors.Surface)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("风险总分", fontSize = pageSp(12f), color = StockNoteColors.TextTertiary)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatScore(res.score),
                fontSize = pageSp(40f),
                fontWeight = FontWeight.Bold,
                color = levelColor(res.level),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(levelColor(res.level))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(res.level, fontSize = pageSp(13f), fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "🔴 高危 ${res.dangers} 项   ⚠️ 预警 ${res.warns} 项   共 ${res.items.size} 项",
            fontSize = pageSp(11.5f),
            color = StockNoteColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 四维度得分条（0–100，越高风险越大） */
@Composable
private fun DimBars(res: RiskScanner.Result) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(StockNoteColors.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("维度得分", fontSize = pageSp(12f), color = StockNoteColors.TextTertiary)
        // 只展示**本次扫描用到的**维度：A 股 = 消息面/基本面/股东治理/交易资金，
        // 港股 = 消息面/基本面/估值交易/资金面 —— 两者维度不同，不能写死遍历全部枚举
        res.dimScores.keys.forEach { d ->
            val v = res.dimScores[d] ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary,
                    maxLines = 1, modifier = Modifier.width(80.dp))
                Box(
                    modifier = Modifier.weight(1f).height(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(StockNoteColors.Divider),
                ) {
                    if (v > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((v / 100.0).toFloat().coerceIn(0f, 1f))
                                .height(8.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(levelColor(res.level)),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(formatScore(v), fontSize = pageSp(12f), fontWeight = FontWeight.Bold,
                    color = StockNoteColors.TextPrimary, modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.End)
            }
        }
    }
}

/** 一个维度的小节：标题（含该市场权重）+ 明细行 */
@Composable
private fun DimSection(dim: RiskScanner.Dim, items: List<RiskScanner.Item>, weight: Double) {
    val weightPct = kotlin.math.round(weight * 100).toInt()
    Sec(title = "${dim.label}（权重 $weightPct%）", modifier = Modifier.padding(top = 10.dp)) {
        CardBox {
            items.forEachIndexed { i, it -> RiskRow(it, i < items.lastIndex) }
        }
    }
}

/** 一行风险项：名称（+说明） ｜ 灯色 + 值 */
@Composable
private fun RiskRow(item: RiskScanner.Item, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = pageSp(13f), color = StockNoteColors.TextPrimary)
                if (item.note.isNotEmpty()) {
                    Text(item.note, fontSize = pageSp(10.5f), color = StockNoteColors.TextTertiary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = RiskScanner.mark(item.light) + " " + item.value,
                fontSize = pageSp(13f),
                fontWeight = FontWeight.Bold,
                color = lightColor(item.light),
            )
        }
        if (showDivider) {
            Box(Modifier.padding(horizontal = 15.dp).height(0.6.dp).background(StockNoteColors.Divider))
        }
    }
}

@Composable
private fun ScanHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 30.dp))
    }
}

/** 风险等级配色（绿→橙→红→深红；**风险语义，与行情涨跌色无关**） */
private fun levelColor(level: String): Color = when (level) {
    "低风险" -> Color(0xFF2E7D32)
    "中低风险" -> Color(0xFF827717)
    "中高风险" -> Color(0xFFEF6C00)
    "高风险" -> Color(0xFFC62828)
    else -> Color(0xFF8E0000)
}

/** 单项灯色（✅绿 / ⚠️橙 / 🔴红） */
private fun lightColor(light: RiskScanner.Light): Color = when (light) {
    RiskScanner.Light.NORMAL -> Color(0xFF2E7D32)
    RiskScanner.Light.WARN -> Color(0xFFEF6C00)
    RiskScanner.Light.DANGER -> Color(0xFFC62828)
}

/** 分数展示：整数则不显示小数（与脚本一致，避免 32.0 这种冗余） */
private fun formatScore(v: Double): String {
    val r = kotlin.math.round(v * 10) / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

