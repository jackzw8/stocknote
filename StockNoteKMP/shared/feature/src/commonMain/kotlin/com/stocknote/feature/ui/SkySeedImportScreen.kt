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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocknote.core.calc.CivilDate
import com.stocknote.core.io.SkySeedItem
import com.stocknote.core.io.SkySeedParser
import com.stocknote.core.io.SkySeedResult
import com.stocknote.core.io.SkySeedSegment
import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain
import com.stocknote.data.platform.todayIso
import com.stocknote.feature.state.SkyEarthHolder
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * **种子清单导入页**（FR-SE-11，SE-7）—— 复制提示词 → 粘回文本 → 解析 → **预览确认** → 导入。
 *
 * ⚠️ **一份文本可含多段**（H-1，2026-10-09）：老周的真机文件就是「看天 10 条 + 看地 5 条」装一个 txt，
 * 预览页必须**按段分组**展示、各段受各自上限约束（天 10 / 地 5），一次点按把两段都导进去。
 * ⚠️ 预览页必须让用户确认后才写库，**不静默导入**；超上限的条目默认不勾选并写明原因。
 * ⚠️ 模板里强制 AI 输出 `# 信息截至 YYYY-MM-DD`；解析出来超 7 天 → 提示可能已过时。
 */
@Composable
fun SkySeedImportScreen(
    holder: SkyEarthHolder,
    initialDomain: SkyDomain,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current

        var rawText by remember { mutableStateOf("") }
        var invalidMsg by remember { mutableStateOf<String?>(null) }

        /** 解析产物（用户可在预览里改/删/移 —— 改的是这个副本，不碰原始文本）。 */
        var segments by remember { mutableStateOf<List<SkySeedSegment>>(emptyList()) }

        /** 每段解析时的可用名额（跨段累计；容量基于库内已有条数，用户编辑预览不影响它）。 */
        var caps by remember { mutableStateOf<List<Int>>(emptyList()) }

        /** 每段的勾选集合（索引与 [segments] 一一对应）。 */
        var checked by remember { mutableStateOf<List<Set<Int>>>(emptyList()) }

        /** 正在编辑的条目：(段索引, 条目索引)。 */
        var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var copyHint by remember { mutableStateOf<String?>(null) }

        // 复制提示 2 秒后自动消失（不弹窗打扰）
        LaunchedEffect(copyHint) {
            if (copyHint != null) {
                delay(2000)
                copyHint = null
            }
        }

        fun remainingCapacity(d: SkyDomain): Int {
            val used = if (d == SkyDomain.SKY) holder.sky.size else holder.earth.size
            return (d.maxCount - used).coerceAtLeast(0)
        }

        fun doParse() {
            when (val r = SkySeedParser.parse(rawText)) {
                is SkySeedResult.Invalid -> {
                    invalidMsg = r.reason
                    segments = emptyList()
                    caps = emptyList()
                    checked = emptyList()
                }
                is SkySeedResult.Ok -> {
                    invalidMsg = null
                    segments = r.segments
                    // 各段默认勾选：受本段可用名额限制；**同域多段时名额跨段累计**（第二段吃剩下的）
                    val remaining = mutableMapOf<SkyDomain, Int>()
                    caps = r.segments.map { seg ->
                        val cap = remaining.getOrPut(seg.domain) { remainingCapacity(seg.domain) }
                        remaining[seg.domain] = (cap - minOf(seg.items.size, cap)).coerceAtLeast(0)
                        cap
                    }
                    checked = r.segments.mapIndexed { si, seg ->
                        seg.items.indices.filter { it < caps[si] }.toSet()
                    }
                }
            }
        }

        fun copyPrompt(d: SkyDomain) {
            val date = todayIso()
            val text = if (d == SkyDomain.SKY) {
                SkySeedPrompts.sky(date)
            } else {
                SkySeedPrompts.earth(
                    date = date,
                    name = holder.earthName.ifBlank { holder.earthSymbol },
                    code = holder.earthSymbol,
                    market = SkySeedPrompts.marketOf(holder.earthSymbol),
                )
            }
            clipboard.setText(AnnotatedString(text))
            copyHint = "已复制「${d.label}」提示词，去问 AI 吧"
        }

        // ---- 预览项操作（都按 (段, 条目) 定位）----

        fun toggle(si: Int, i: Int) {
            checked = checked.toMutableList().also { list ->
                val set = list[si]
                list[si] = if (i in set) set - i else set + i
            }
        }

        fun pick(si: Int, i: Int, attitude: SkyAttitude) {
            segments = segments.toMutableList().also { list ->
                val seg = list[si]
                val items = seg.items.toMutableList()
                items[i] = items[i].copy(attitude = attitude)
                list[si] = seg.copy(items = items)
            }
        }

        fun remove(si: Int, i: Int) {
            segments = segments.toMutableList().also { list ->
                val seg = list[si]
                list[si] = seg.copy(items = seg.items.filterIndexed { idx, _ -> idx != i })
            }
            // 删掉第 i 项后：原来的 i+1.. 全部前移一位
            checked = checked.toMutableList().also { list ->
                list[si] = list[si].filter { it != i }.map { if (it > i) it - 1 else it }.toSet()
            }
        }

        fun move(si: Int, i: Int, delta: Int) {
            val j = i + delta
            segments = segments.toMutableList().also { list ->
                val seg = list[si]
                val items = seg.items.toMutableList()
                items.add(j, items.removeAt(i))
                list[si] = seg.copy(items = items)
            }
            checked = checked.toMutableList().also { list ->
                list[si] = list[si].swapped(i, j)
            }
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
                    "导入种子清单",
                    fontSize = pageSp(17f),
                    fontWeight = FontWeight.ExtraBold,
                    color = StockNoteColors.TextPrimary,
                )
            }

            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 26.dp)) {
                // ---- ① 复制提示词（入口来的那一组排前面）----
                item(key = "prompt") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SectionLabel("① 复制提示词，去问 AI")
                        Spacer(Modifier.height(8.dp))
                        val order = if (initialDomain == SkyDomain.SKY) {
                            listOf(SkyDomain.SKY, SkyDomain.EARTH)
                        } else {
                            listOf(SkyDomain.EARTH, SkyDomain.SKY)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            order.forEach { d ->
                                Box(Modifier.weight(1f)) {
                                    GhostButton("📋 复制「${d.label}」提示词", onClick = { copyPrompt(d) })
                                }
                            }
                        }
                        copyHint?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = pageSp(11f), fontWeight = FontWeight.Bold, color = StockNoteColors.Brand)
                        }
                    }
                }

                // ---- ② 粘贴 ----
                item(key = "paste") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SectionLabel("② 把 AI 返回的整段文字粘在这里")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "可以一次粘贴「看天 + 看地」两段（各以 SEED: 开头），会分别归到两组。",
                            fontSize = pageSp(11f),
                            lineHeight = pageSp(16f),
                            color = StockNoteColors.TextTertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = rawText,
                            onValueChange = { rawText = it },
                            placeholder = {
                                Text("SEED:SKY\n1|美联储降息路径|中性|理由……", fontSize = pageSp(12f))
                            },
                            minLines = 6,
                            maxLines = 12,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton("解析", onClick = { doParse() }, enabled = rawText.isNotBlank())
                    }
                }

                // ---- 解析失败 ----
                invalidMsg?.let { msg ->
                    item(key = "invalid") {
                        HintBar(text = msg, danger = true)
                    }
                }

                // ---- ③ 预览（按段分组）----
                segments.forEachIndexed { si, seg ->
                    val cap = caps.getOrElse(si) { 0 }
                    val segChecked = checked.getOrElse(si) { emptySet() }

                    item(key = "seg-$si-head") {
                        SegmentHead(ordinal = si + 1, seg = seg, selected = segChecked.size)
                    }

                    // 头行标的与当前地组标的不一致（只提示，不静默切换）
                    val headerSymbol = seg.symbol
                    if (seg.domain == SkyDomain.EARTH && headerSymbol != null &&
                        normalizeSymbol(headerSymbol) != normalizeSymbol(holder.earthSymbol)
                    ) {
                        item(key = "seg-$si-symbol") {
                            HintBar(
                                text = "这段是「$headerSymbol」的清单，当前看地标的为「${holder.earthSymbol}」——" +
                                    "条目会导入当前标的；如需导入「$headerSymbol」请先切换标的。",
                                danger = false,
                            )
                        }
                    }
                    // AI 信息过时（超 7 天）
                    val infoDate = seg.infoDate
                    if (infoDate != null &&
                        runCatching { CivilDate.daysBetween(infoDate, todayIso()) }.getOrDefault(0L) > 7
                    ) {
                        item(key = "seg-$si-stale") {
                            HintBar(text = "这段的 AI 信息截至 $infoDate，可能已过时。", danger = false)
                        }
                    }
                    // 超上限
                    if (seg.items.size > cap) {
                        item(key = "seg-$si-cap") {
                            HintBar(
                                text = "${seg.domain.label}最多 ${seg.domain.maxCount} 条" +
                                    "（当前已占 ${seg.domain.maxCount - cap} 条），超出部分已不勾选。",
                                danger = false,
                            )
                        }
                    }

                    itemsIndexed(seg.items, key = { i, _ -> "seg-$si-item-$i" }) { i, item ->
                        PreviewRow(
                            item = item,
                            checked = i in segChecked,
                            overCap = i >= cap,
                            onToggle = { toggle(si, i) },
                            onPickAttitude = { att -> pick(si, i, att) },
                            onEdit = { editing = si to i },
                            onDelete = { remove(si, i) },
                            onMoveUp = if (i > 0) ({ move(si, i, -1) }) else null,
                            onMoveDown = if (i < seg.items.lastIndex) ({ move(si, i, 1) }) else null,
                        )
                    }
                }

                // ---- ④ 导入 ----
                if (segments.isNotEmpty()) {
                    item(key = "import-btn") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                            val perSeg = segments.indices.map { si -> checked.getOrElse(si) { emptySet() }.size }
                            val total = perSeg.sum()
                            val parts = segments.mapIndexedNotNull { si, seg ->
                                if (perSeg[si] > 0) "${seg.domain.label} ${perSeg[si]}" else null
                            }
                            PrimaryButton(
                                text = if (total == 0) "导入" else "导入共 $total 条（${parts.joinToString(" + ")}）",
                                onClick = {
                                    scope.launch {
                                        // 先清旧错误：最后若仍有 error，一定是**本次**某段没导进去
                                        holder.dismissError()
                                        var totalImported = 0
                                        for ((si, seg) in segments.withIndex()) {
                                            val picked = seg.items.filterIndexed { i, _ ->
                                                i in checked.getOrElse(si) { emptySet() }
                                            }
                                            if (picked.isEmpty()) continue
                                            val n = holder.importSeeds(seg.domain, picked)
                                            // ⚠️ 某段失败（如天组已满）**不能中断其它段**：
                                            // 老周真机场景就是「天组已满 + 地组空」，天段失败也要把地段导进去；
                                            // error 保留在 holder 上，回主页会弹出来告诉他哪一段没进。
                                            if (n == 0) continue
                                            totalImported += n
                                        }
                                        if (totalImported > 0) onBack()
                                    }
                                },
                                enabled = total > 0,
                            )
                        }
                    }
                }
            }
        }

        // 预览项改标题 / 依据
        editing?.let { (si, i) ->
            val seg = segments.getOrNull(si)
            val item = seg?.items?.getOrNull(i)
            if (seg == null || item == null) {
                editing = null
            } else {
                FactorEditDialog(
                    title = "修改条目",
                    initialTitle = item.title,
                    initialNote = item.note.orEmpty(),
                    confirmLabel = "保存",
                    onSubmit = { t, n ->
                        segments = segments.toMutableList().also { list ->
                            val cur = list[si]
                            val items = cur.items.toMutableList()
                            items[i] = items[i].copy(
                                title = t.take(SkySeedParser.TITLE_MAX),
                                note = n.ifBlank { null },
                            )
                            list[si] = cur.copy(items = items)
                        }
                        null
                    },
                    onDismiss = { editing = null },
                )
            }
        }
    }
}

// ==================== 子组件 ====================

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = pageSp(13f),
        fontWeight = FontWeight.Bold,
        color = StockNoteColors.TextSecondary,
    )
}

/** 段标题行：「③ 预览 · 看天（10 条）」/「第 2 段 · 看地 腾讯控股 00700（5 条）」+ 已选计数。 */
@Composable
private fun SegmentHead(ordinal: Int, seg: SkySeedSegment, selected: Int) {
    val who = when (seg.domain) {
        SkyDomain.SKY -> "看天"
        SkyDomain.EARTH -> listOfNotNull(seg.name, seg.symbol).joinToString(" ").ifBlank { "看地" }
    }
    val prefix = if (ordinal == 1) "③ 预览 · " else "第 $ordinal 段 · "
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (ordinal == 1) 14.dp else 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$prefix$who（${seg.items.size} 条）",
            fontSize = pageSp(13f),
            fontWeight = FontWeight.Bold,
            color = StockNoteColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "已选 $selected / ${seg.items.size}",
            fontSize = pageSp(11.5f),
            fontWeight = FontWeight.Bold,
            color = StockNoteColors.TextSecondary,
        )
    }
}

/** 页内提示条（黄 / 红两态；不弹窗）。 */
@Composable
private fun HintBar(text: String, danger: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (danger) Color(0xFFFEF3F2) else Color(0xFFFFF7E6))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = pageSp(11.5f),
            lineHeight = pageSp(17f),
            fontWeight = FontWeight.Bold,
            color = if (danger) StockNoteColors.Danger else Color(0xFFB54708),
        )
    }
}

/** 预览行：勾选 + 标题/理由/提示 + 三档 + 上下移 / 删。 */
@Composable
private fun PreviewRow(
    item: SkySeedItem,
    checked: Boolean,
    overCap: Boolean,
    onToggle: () -> Unit,
    onPickAttitude: (SkyAttitude) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(StockNoteColors.Surface)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 勾选方块
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) StockNoteColors.Brand else Color(0xFFF1F4F9))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (checked) "✓" else "",
                fontSize = pageSp(11f),
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onEdit),
        ) {
            Text(
                item.title,
                fontSize = pageSp(13f),
                fontWeight = FontWeight.Bold,
                color = StockNoteColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.note?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    it,
                    fontSize = pageSp(10.5f),
                    lineHeight = pageSp(15f),
                    color = StockNoteColors.TextTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (overCap) {
                Spacer(Modifier.height(3.dp))
                WarnText("超出上限，默认不导入")
            }
            if (item.titleTruncated) {
                Spacer(Modifier.height(3.dp))
                WarnText("标题超 ${SkySeedParser.TITLE_MAX} 字，已截断")
            }
            if (item.attitude == null) {
                Spacer(Modifier.height(3.dp))
                WarnText("未识别档位 → 将导入为「未判断」")
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SkyFaceRow(current = item.attitude, onPick = onPickAttitude)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallArrow("↑", enabled = onMoveUp != null) { onMoveUp?.invoke() }
                SmallArrow("↓", enabled = onMoveDown != null) { onMoveDown?.invoke() }
                Text(
                    "🗑",
                    fontSize = pageSp(12f),
                    modifier = Modifier
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun WarnText(text: String) {
    Text(
        text,
        fontSize = pageSp(10.5f),
        fontWeight = FontWeight.Bold,
        color = Color(0xFFB54708),
    )
}

/** 勾选索引随相邻交换同步（拖/移一位）。 */
private fun Set<Int>.swapped(a: Int, b: Int): Set<Int> = map {
    when (it) {
        a -> b
        b -> a
        else -> it
    }
}.toSet()

/** 去掉市场前缀，便于比较 '00700' 与 'hk00700' 这类同一标的的两种写法。 */
private fun normalizeSymbol(symbol: String): String {
    val s = symbol.trim().lowercase()
    return when {
        s.startsWith("hk") -> s.removePrefix("hk")
        s.startsWith("sh") -> s.removePrefix("sh")
        s.startsWith("sz") -> s.removePrefix("sz")
        s.startsWith("us") -> s.removePrefix("us")
        else -> s
    }
}
