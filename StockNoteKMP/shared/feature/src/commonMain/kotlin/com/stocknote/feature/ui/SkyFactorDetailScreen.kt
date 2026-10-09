package com.stocknote.feature.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.stocknote.core.calc.CivilDate
import com.stocknote.core.model.SkyDomain
import com.stocknote.core.model.SkyFactor
import com.stocknote.core.model.SkyJudgement
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.platform.todayIso
import com.stocknote.data.repo.SkyEarthRepository
import com.stocknote.feature.state.SkyEarthHolder
import com.stocknote.feature.theme.SkyEarthPalette
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.launch

/**
 * **关注点详情**（FR-SE-08/09，SE-8）：上半「我的判断」阶梯线，下半「同期基准」收盘价，**同一时间轴**。
 *
 * 基准：地组 = 个股自身；天组 = 沪深300（`sh000300`，可在设置改）。
 * ⚠️ 价格是**唯一的联网点**：放在 runCatching 里，拉不到只显示上半 + 一行提示 + 「重试」，
 * **不弹窗、不空屏**（需求 FR-SE-09.4）。
 */
@Composable
fun SkyFactorDetailScreen(
    holder: SkyEarthHolder,
    factorId: String,
    repo: SkyEarthRepository,
    quoteClient: QuoteClient,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        val scope = rememberCoroutineScope()
        val factor: SkyFactor? = holder.sky.firstOrNull { it.id == factorId }
            ?: holder.earth.firstOrNull { it.id == factorId }

        var judgements by remember(factorId) { mutableStateOf<List<SkyJudgement>>(emptyList()) }
        var prices by remember(factorId) { mutableStateOf<List<Pair<String, Double>>?>(null) }
        var loading by remember(factorId) { mutableStateOf(true) }
        var failed by remember(factorId) { mutableStateOf(false) }

        val isSky = factor?.domain == SkyDomain.SKY
        val benchmarkSymbol = if (isSky) holder.skyBenchmark else holder.earthSymbol
        val benchmarkLabel = if (isSky) "沪深300 $benchmarkSymbol" else
            listOfNotNull(holder.earthName.ifBlank { null }, holder.earthSymbol).joinToString(" ")

        /**
         * 拉基准收盘价。
         *
         * ⚠️ 先吃**进程内缓存**（M-2，审核报告 2026-10-09）：同一只标的反复进出详情页
         * 不再重复拉同一份 400 天日线；点「重试」时 [force] = true 绕过缓存（用户就是要新数据）。
         */
        suspend fun loadPrices(force: Boolean = false) {
            if (!force) {
                SkyDetailPriceCache.get(benchmarkSymbol)?.let {
                    prices = it
                    failed = false
                    loading = false
                    return
                }
            }
            loading = true
            failed = false
            val rows = runCatching { quoteClient.fetchClosesWithDates(benchmarkSymbol, 400) }.getOrNull()
            if (rows.isNullOrEmpty()) {
                prices = null
                failed = true
            } else {
                val mapped = rows.map { it.date to it.close }
                SkyDetailPriceCache.put(benchmarkSymbol, mapped)
                prices = mapped
            }
            loading = false
        }

        LaunchedEffect(factorId) {
            judgements = runCatching { repo.judgements(factorId) }.getOrDefault(emptyList())
            loadPrices()
        }

        Column(modifier.fillMaxSize().background(StockNoteColors.Background)) {
            // ---- 顶栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    "关注点详情",
                    fontSize = pageSp(17f),
                    fontWeight = FontWeight.ExtraBold,
                    color = StockNoteColors.TextPrimary,
                )
            }

            if (factor == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这条关注点已不存在", fontSize = pageSp(14f), color = StockNoteColors.TextSecondary)
                }
                return@CompositionLocalProvider
            }

            val firstDay = judgements.firstOrNull()?.day
            val today = todayIso()

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 26.dp)) {
                // ---- 标题 + 当前状态 ----
                item(key = "head") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(StockNoteColors.Surface)
                            .padding(14.dp),
                    ) {
                        Text(
                            factor.title,
                            fontSize = pageSp(15f),
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = pageSp(21f),
                            color = StockNoteColors.TextPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val face = when (factor.score?.value) {
                                1 -> SkyFace.BULL
                                0 -> SkyFace.NEUTRAL
                                -1 -> SkyFace.BEAR
                                else -> SkyFace.NEUTRAL
                            }
                            SkyFaceIcon(
                                face = face,
                                selected = factor.score != null,
                                sizeDp = 28,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "当前 · ${factor.score?.label ?: "未判断"}",
                                fontSize = pageSp(12f),
                                fontWeight = FontWeight.Bold,
                                color = StockNoteColors.TextSecondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (firstDay != null) "首次判断 $firstDay" else "还没有判断记录",
                                fontSize = pageSp(11f),
                                color = StockNoteColors.TextTertiary,
                            )
                        }
                    }
                }

                // ---- 我的判断（阶梯线）----
                item(key = "ladder") {
                    DetailCard(
                        title = "我的判断",
                        trailing = if (judgements.isNotEmpty()) "共 ${judgements.size} 次" else null,
                    ) {
                        if (judgements.isEmpty()) {
                            Text(
                                "还没有判断记录。回主页点一下三个脸就会留档。",
                                fontSize = pageSp(11.5f),
                                color = StockNoteColors.TextTertiary,
                            )
                        } else {
                            LadderChart(
                                judgements = judgements,
                                firstDay = judgements.first().day,
                                lastDay = today,
                                modifier = Modifier.fillMaxWidth().height(118.dp),
                            )
                        }
                    }
                }

                // ---- 同期基准走势 ----
                item(key = "price") {
                    DetailCard(
                        title = "同期走势 · $benchmarkLabel",
                        trailingAction = if (failed) "重试" else null,
                        onAction = {
                            scope.launch { loadPrices(force = true) }
                        },
                    ) {
                        val list = prices
                        if (loading) {
                            Text("正在获取走势…", fontSize = pageSp(11.5f), color = StockNoteColors.TextTertiary)
                        } else if (failed || list == null) {
                            Text(
                                "暂无法获取同期走势（不影响上面的判断记录）",
                                fontSize = pageSp(11.5f),
                                color = StockNoteColors.TextSecondary,
                            )
                        } else {
                            val filtered = if (firstDay != null) {
                                list.filter { it.first >= firstDay }
                            } else {
                                list
                            }
                            if (filtered.size < 2) {
                                Text(
                                    "暂无法获取同期走势（区间内无报价数据）",
                                    fontSize = pageSp(11.5f),
                                    color = StockNoteColors.TextSecondary,
                                )
                            } else {
                                PriceChart(
                                    prices = filtered,
                                    firstDay = firstDay ?: filtered.first().first,
                                    lastDay = today,
                                    modifier = Modifier.fillMaxWidth().height(96.dp),
                                )
                            }
                        }
                    }
                }

                // ---- 依据 ----
                item(key = "note") {
                    DetailCard(title = "依据") {
                        Text(
                            factor.note?.takeIf { it.isNotBlank() } ?: "（没有写依据）",
                            fontSize = pageSp(12.5f),
                            lineHeight = pageSp(19f),
                            color = StockNoteColors.TextSecondary,
                        )
                    }
                }

                // ---- 变更记录（倒序）----
                if (judgements.isNotEmpty()) {
                    item(key = "history") {
                        DetailCard(title = "变更记录") {
                            val ordered = judgements.sortedByDescending { it.judgedAt }
                            val earliest = judgements.minByOrNull { it.judgedAt }
                            ordered.forEachIndexed { idx, j ->
                                val prev = ordered.getOrNull(
                                    // 倒序列表中"上一条更早的记录" = 下一个索引
                                    idx + 1,
                                )
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${j.day} · " + if (prev == null) {
                                            "首次判断 ${j.score.label}"
                                        } else {
                                            "由 ${prev.score.label} → ${j.score.label}"
                                        },
                                        fontSize = pageSp(12.5f),
                                        fontWeight = FontWeight.Bold,
                                        color = StockNoteColors.TextPrimary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (j == earliest) {
                                        Text("首次", fontSize = pageSp(10.5f), color = StockNoteColors.TextTertiary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 子组件 ====================

/** 详情页的卡片（标题 + 可选右侧动作 + 内容）。 */
@Composable
private fun DetailCard(
    title: String,
    trailing: String? = null,
    trailingAction: String? = null,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = pageSp(13f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            trailing?.let {
                Text(it, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
            }
            trailingAction?.let {
                Text(
                    it,
                    fontSize = pageSp(11.5f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.Brand,
                    modifier = Modifier
                        .clickable(onClick = onAction)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(StockNoteColors.Surface)
                .padding(14.dp),
        ) {
            content()
        }
    }
}

/** 档位 → 阶梯线颜色（与情景图同一套情绪轴，不复用行情色）。 */
private fun scoreColor(score: Int): Color = when (score) {
    1 -> SkyEarthPalette.VeryBullish
    0 -> SkyEarthPalette.Neutral
    else -> SkyEarthPalette.VeryBearish
}

/** 判断阶梯线：横轴时间（首次判断 → 今天），纵轴 −1 / 0 / +1，每段按当时的档位着色。 */
@Composable
private fun LadderChart(
    judgements: List<SkyJudgement>,
    firstDay: String,
    lastDay: String,
    modifier: Modifier = Modifier,
) {
    val labelSize = pageSp(9f)
    val textMeasurer = rememberTextMeasurer()
    val tertiary = StockNoteColors.TextTertiary
    val dividerColor = StockNoteColors.Divider

    androidx.compose.foundation.Canvas(modifier) {
        val leftPad = 34.dp.toPx()
        val rightPad = 8.dp.toPx()
        val topPad = 10.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
        val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

        val start = runCatching { CivilDate.toEpochDay(firstDay) }.getOrDefault(0L)
        val end = runCatching { CivilDate.toEpochDay(lastDay) }.getOrDefault(start + 1)
        val span = (end - start).coerceAtLeast(1L)

        fun xOf(day: Long): Float = leftPad + (day - start).toFloat() / span * plotW
        fun yOf(score: Int): Float = when (score) {
            1 -> topPad
            0 -> topPad + plotH / 2f
            else -> topPad + plotH
        }

        // 三条水平网格线 + 左标签
        listOf(-1, 0, 1).forEach { s ->
            val y = yOf(s)
            drawLine(dividerColor, Offset(leftPad, y), Offset(size.width - rightPad, y), strokeWidth = 1f)
            val label = when (s) {
                1 -> "乐观"
                0 -> "中性"
                else -> "悲观"
            }
            val layout = textMeasurer.measure(label, TextStyle(fontSize = labelSize, color = tertiary))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(0f, y - layout.size.height / 2f),
            )
        }

        // 阶梯线：每次判断一段水平线（按当时档位着色），变化处画竖线
        var prevY = yOf(judgements.first().score.value)
        var prevX = xOf(start)
        var prevColor = scoreColor(judgements.first().score.value)
        judgements.forEach { j ->
            val x = xOf(runCatching { CivilDate.toEpochDay(j.day) }.getOrDefault(start))
            val y = yOf(j.score.value)
            val color = scoreColor(j.score.value)
            if (x > prevX) {
                // 上一段横线（用上一段的档位色）
                drawLine(prevColor, Offset(prevX, prevY), Offset(x, prevY), strokeWidth = 2.4f, cap = StrokeCap.Round)
                // 变化处竖线
                if (y != prevY) {
                    drawLine(color, Offset(x, prevY), Offset(x, y), strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
                prevColor = color
            }
            drawCircle(color, radius = 3f, center = Offset(x, y))
            prevY = y
            prevX = x
        }
        // 末段延伸到今天 + 末端小点
        drawLine(prevColor, Offset(prevX, prevY), Offset(xOf(end), prevY), strokeWidth = 2.4f, cap = StrokeCap.Round)
        drawCircle(prevColor, radius = 3f, center = Offset(xOf(end), prevY))

        // 底部日期（首 / 今）
        val d1 = textMeasurer.measure(firstDay, TextStyle(fontSize = labelSize, color = tertiary))
        drawText(textLayoutResult = d1, topLeft = Offset(leftPad, size.height - bottomPad + 6.dp.toPx()))
        val d2 = textMeasurer.measure(lastDay, TextStyle(fontSize = labelSize, color = tertiary))
        drawText(
            textLayoutResult = d2,
            topLeft = Offset((size.width - rightPad - d2.size.width).coerceAtLeast(leftPad), size.height - bottomPad + 6.dp.toPx()),
        )
    }
}

/** 基准收盘价折线（同一时间轴；横轴与阶梯线一致）。 */
@Composable
private fun PriceChart(
    prices: List<Pair<String, Double>>,
    firstDay: String,
    lastDay: String,
    modifier: Modifier = Modifier,
) {
    val labelSize = pageSp(9f)
    val textMeasurer = rememberTextMeasurer()
    val tertiary = StockNoteColors.TextTertiary
    val lineColor = StockNoteColors.Brand

    androidx.compose.foundation.Canvas(modifier) {
        val leftPad = 8.dp.toPx()
        val rightPad = 8.dp.toPx()
        val topPad = 14.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
        val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

        val start = runCatching { CivilDate.toEpochDay(firstDay) }.getOrDefault(0L)
        val end = runCatching { CivilDate.toEpochDay(lastDay) }.getOrDefault(start + 1)
        val span = (end - start).coerceAtLeast(1L)

        val minClose = prices.minOf { it.second }
        val maxClose = prices.maxOf { it.second }
        val range = (maxClose - minClose).takeIf { it > 1e-9 } ?: 1.0

        fun xOf(day: Long): Float = leftPad + (day - start).toFloat() / span * plotW
        fun yOf(close: Double): Float = topPad + (1f - ((close - minClose) / range).toFloat()) * plotH

        // 底部基线
        drawLine(StockNoteColors.Divider, Offset(leftPad, size.height - bottomPad), Offset(size.width - rightPad, size.height - bottomPad), strokeWidth = 1f)

        val path = Path()
        prices.forEachIndexed { idx, (date, close) ->
            val d = runCatching { CivilDate.toEpochDay(date) }.getOrDefault(start)
            val x = xOf(d)
            val y = yOf(close)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.2f, cap = StrokeCap.Round))

        // 末点
        val last = prices.last()
        val lastX = xOf(runCatching { CivilDate.toEpochDay(last.first) }.getOrDefault(end))
        val lastY = yOf(last.second)
        drawCircle(lineColor, radius = 3.2f, center = Offset(lastX, lastY))

        // 右上：最新价（区间相对位置一眼可见）
        val priceText = formatPrice(last.second)
        val layout = textMeasurer.measure(priceText, TextStyle(fontSize = labelSize, color = tertiary))
        drawText(textLayoutResult = layout, topLeft = Offset(leftPad, 0f))

        // 底部日期
        val d1 = textMeasurer.measure(firstDay, TextStyle(fontSize = labelSize, color = tertiary))
        drawText(textLayoutResult = d1, topLeft = Offset(leftPad, size.height - bottomPad + 5.dp.toPx()))
        val d2 = textMeasurer.measure(lastDay, TextStyle(fontSize = labelSize, color = tertiary))
        drawText(
            textLayoutResult = d2,
            topLeft = Offset((size.width - rightPad - d2.size.width).coerceAtLeast(leftPad), size.height - bottomPad + 5.dp.toPx()),
        )
    }
}

/** 价格显示（原币，不做汇率折算；仅图上标注用）。 */
private fun formatPrice(v: Double): String {
    val text = ((v * 100).toLong() / 100.0).toString()
    return text
}

/**
 * 详情页基准 K 线的**进程内缓存**（M-2，审核报告 2026-10-09）。
 *
 * 为什么：此前每进一次详情页就拉一份 400 天日线，同一只标的反复进出 = 反复拉同一份数据，
 * 弱网与流量都不划算（与 P2-23「并发去重」同型，P2-23 落地后可再收拢到统一机制）。
 *
 * 口径：只缓进程内、**不落库** —— 日线按天更新，重启后再拉一次即可；
 * 不做 TTL / 失效策略，用户点「重试」即强制绕过（见 `loadPrices(force = true)`）。
 *
 * ⚠️ 线程安全：不可变 Map **整体替换**（commonMain 不许用锁与平台 API；业务上写少读多）。
 * `@Volatile` 必须写**全限定** `kotlin.concurrent.Volatile`（iOS 上裸 @Volatile 解析不到）。
 */
internal object SkyDetailPriceCache {

    @kotlin.concurrent.Volatile
    private var entries: Map<String, List<Pair<String, Double>>> = emptyMap()

    fun get(symbol: String): List<Pair<String, Double>>? = entries[symbol]

    fun put(symbol: String, rows: List<Pair<String, Double>>) {
        entries = entries + (symbol to rows)
    }
}
