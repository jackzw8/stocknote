package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.stocknote.core.calc.SkyEarthScore
import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain
import com.stocknote.core.model.SkyFactor
import com.stocknote.core.model.SkyLevel
import com.stocknote.data.platform.nowEpochMs
import com.stocknote.feature.state.SkyEarthHolder
import com.stocknote.feature.theme.StockNoteColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * **看天看地**（FR-SE-01~FR-SE-11，老周 2026-10-09）—— 底部导航第 5 个一级页。
 *
 * 页面骨架：天组图卡 → 天组 ≤10 条 → 地组图卡（带标的名/代码）→ 地组 ≤5 条。
 * 交互：三档点一下即写库（无保存按钮）· 长按拖动排序 · 编辑模式增删改 · 30 天提示条 · 右上菜单。
 *
 * ⚠️ 字号一律 `LocalPageTextScale` + `pageSp()`（不许 fixedSp）。
 * ⚠️ 失败必须有反馈（P1-35）：所有动作的错误经 `holder.error` 弹窗展示，不许点了没反应。
 */
@Composable
fun SkyEarthScreen(
    holder: SkyEarthHolder,
    onOpenDetail: (String) -> Unit = {},
    onOpenImport: (SkyDomain) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        val scope = rememberCoroutineScope()

        // ⚠️ **首次进入必须加载**（修 bug：此前漏了这一句 —— 清单与自选标的列表一直是初始空值，
        // 表现为页面永远"还没有关注点"、切换标的时说"自选里还没有标的"，老周 2026-10-09 报）。
        // 页面在 MainTabs 的 pager 里常驻（beyondViewportPageCount=3），本 effect 只在首次组合跑一次。
        LaunchedEffect(Unit) { holder.load() }

        var menuOpen by remember { mutableStateOf(false) }
        var editingDomain by remember { mutableStateOf<SkyDomain?>(null) }
        var addDomain by remember { mutableStateOf<SkyDomain?>(null) }
        var editTarget by remember { mutableStateOf<SkyFactor?>(null) }
        var deleteTarget by remember { mutableStateOf<SkyFactor?>(null) }
        var switchOpen by remember { mutableStateOf(false) }
        var copyOpen by remember { mutableStateOf(false) }
        var limitHint by remember { mutableStateOf<String?>(null) }

        // 打开「切换标的」时重读一次自选 —— 老周很可能刚去「自选管理」加过标的再回来
        // （页面本身常驻组合，不重进就不会再走上面那个首次加载）。
        // ⚠️ 只刷弹窗要用的两样、不走整个 load（L-3，审核报告 2026-10-09）：load 会置 loading
        // 并重读清单，弹窗打开时毫无必要。
        LaunchedEffect(switchOpen) { if (switchOpen) holder.refreshWatchSymbols() }

        Column(modifier.fillMaxSize().background(StockNoteColors.Background)) {
            // ---- 顶栏：标题 + ⋯ 菜单 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "看天看地",
                    fontSize = pageSp(21f),
                    fontWeight = FontWeight.ExtraBold,
                    color = StockNoteColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Box {
                    Text(
                        "⋯",
                        fontSize = pageSp(20f),
                        fontWeight = FontWeight.Bold,
                        color = StockNoteColors.TextSecondary,
                        modifier = Modifier
                            .clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("导入种子清单") },
                            onClick = { menuOpen = false; onOpenImport(SkyDomain.SKY) },
                        )
                        DropdownMenuItem(
                            text = { Text("切换看地标的") },
                            onClick = { menuOpen = false; switchOpen = true },
                        )
                        DropdownMenuItem(
                            text = { Text("新增关注点（看天）") },
                            onClick = { menuOpen = false; addDomain = SkyDomain.SKY },
                        )
                        DropdownMenuItem(
                            text = { Text("新增关注点（看地）") },
                            onClick = { menuOpen = false; addDomain = SkyDomain.EARTH },
                        )
                    }
                }
            }

            val skyEmpty = holder.sky.isEmpty()
            val earthEmpty = holder.earth.isEmpty()

            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 26.dp)) {
                // ---- 30 天未更新提示条（页内常驻，不弹窗）----
                if (holder.staleCount > 0) {
                    item(key = "stale-banner") { StaleBanner(holder.staleCount) }
                }

                if (holder.loading && skyEmpty && earthEmpty) {
                    // 首次加载中：别让「还没有关注点」的空态先闪一下
                    item(key = "loading") {
                        Text(
                            "加载中…",
                            fontSize = pageSp(13f),
                            color = StockNoteColors.TextTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                        )
                    }
                } else if (skyEmpty && earthEmpty) {
                    item(key = "empty") {
                        EmptyStateCard(
                            onImportSky = { onOpenImport(SkyDomain.SKY) },
                            onImportEarth = { onOpenImport(SkyDomain.EARTH) },
                            onManual = { addDomain = SkyDomain.SKY },
                        )
                    }
                } else {
                    // ================= 天组 =================
                    item(key = "sky-scene") {
                        SceneCard(
                            level = holder.skyLevel,
                            score = holder.skyScore,
                            caption = skySceneCaption(holder.skyLevel),
                            sub = "看天 · 宏观",
                            judgedText = "已判断 ${holder.skyJudged}/${holder.sky.size}",
                            canvas = { SkySceneCanvas(holder.skyLevel, Modifier.fillMaxSize()) },
                        )
                    }
                    item(key = "sky-meta") {
                        MetaLine(
                            text = listOfNotNull(
                                holder.skyUpdatedDate?.let { "更新于 $it" },
                                "共 ${holder.sky.size} 条（上限 ${SkyDomain.SKY.maxCount}）",
                            ).joinToString(" · "),
                        )
                    }
                    item(key = "sky-header") {
                        GroupHeader(
                            domain = SkyDomain.SKY,
                            count = holder.sky.size,
                            editing = editingDomain == SkyDomain.SKY,
                            onToggleEdit = {
                                editingDomain = if (editingDomain == SkyDomain.SKY) null else SkyDomain.SKY
                            },
                            onAdd = { addDomain = SkyDomain.SKY },
                            onLimit = { limitHint = "${SkyDomain.SKY.label}最多 ${SkyDomain.SKY.maxCount} 条，请先删减" },
                        )
                    }
                    if (skyEmpty) {
                        item(key = "sky-empty") {
                            GroupEmptyHint(
                                text = "看天还没有关注点",
                                onImport = { onOpenImport(SkyDomain.SKY) },
                                onManual = { addDomain = SkyDomain.SKY },
                            )
                        }
                    } else {
                        itemsIndexed(holder.sky, key = { _, f -> f.id }) { idx, f ->
                            FactorRow(
                                factor = f,
                                index = idx,
                                lastIndex = holder.sky.lastIndex,
                                editing = editingDomain == SkyDomain.SKY,
                                onOpenDetail = { onOpenDetail(f.id) },
                                onPick = { att -> scope.launch { holder.setScore(f.id, att) } },
                                onEdit = { editTarget = f },
                                onDelete = { deleteTarget = f },
                                onMoveTo = { target ->
                                    val ids = holder.sky.map { it.id }.toMutableList()
                                    val from = ids.indexOf(f.id)
                                    if (target != from && target in ids.indices) {
                                        ids.add(target, ids.removeAt(from))
                                        scope.launch { holder.reorder(SkyDomain.SKY, ids) }
                                    }
                                },
                            )
                        }
                    }

                    item(key = "gap") { Spacer(Modifier.height(16.dp)) }

                    // ================= 地组 =================
                    item(key = "earth-scene") {
                        SceneCard(
                            level = holder.earthLevel,
                            score = holder.earthScore,
                            caption = earthSceneCaption(holder.earthLevel),
                            sub = "看地 · ${holder.earthName.ifBlank { holder.earthSymbol }}",
                            judgedText = "已判断 ${holder.earthJudged}/${holder.earth.size}",
                            canvas = { EarthSceneCanvas(holder.earthLevel, Modifier.fillMaxSize()) },
                        )
                    }
                    item(key = "earth-meta") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = listOfNotNull(
                                    holder.earthName.ifBlank { null },
                                    holder.earthSymbol.takeIf { it.isNotBlank() },
                                ).joinToString(" ") +
                                    holder.earthUpdatedDate?.let { " · 更新于 $it" }.orEmpty(),
                                fontSize = pageSp(11f),
                                color = StockNoteColors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "切换标的 ›",
                                fontSize = pageSp(11f),
                                fontWeight = FontWeight.Bold,
                                color = StockNoteColors.Brand,
                                modifier = Modifier
                                    .clickable { switchOpen = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                    item(key = "earth-header") {
                        GroupHeader(
                            domain = SkyDomain.EARTH,
                            count = holder.earth.size,
                            editing = editingDomain == SkyDomain.EARTH,
                            onToggleEdit = {
                                editingDomain = if (editingDomain == SkyDomain.EARTH) null else SkyDomain.EARTH
                            },
                            onAdd = { addDomain = SkyDomain.EARTH },
                            onLimit = { limitHint = "${SkyDomain.EARTH.label}最多 ${SkyDomain.EARTH.maxCount} 条，请先删减" },
                        )
                    }
                    if (earthEmpty) {
                        item(key = "earth-empty") {
                            GroupEmptyHint(
                                text = "看地还没有关注点",
                                onImport = { onOpenImport(SkyDomain.EARTH) },
                                onManual = { addDomain = SkyDomain.EARTH },
                                onCopy = if (holder.earthScopes.isNotEmpty()) ({ copyOpen = true }) else null,
                            )
                        }
                    } else {
                        itemsIndexed(holder.earth, key = { _, f -> f.id }) { idx, f ->
                            FactorRow(
                                factor = f,
                                index = idx,
                                lastIndex = holder.earth.lastIndex,
                                editing = editingDomain == SkyDomain.EARTH,
                                onOpenDetail = { onOpenDetail(f.id) },
                                onPick = { att -> scope.launch { holder.setScore(f.id, att) } },
                                onEdit = { editTarget = f },
                                onDelete = { deleteTarget = f },
                                onMoveTo = { target ->
                                    val ids = holder.earth.map { it.id }.toMutableList()
                                    val from = ids.indexOf(f.id)
                                    if (target != from && target in ids.indices) {
                                        ids.add(target, ids.removeAt(from))
                                        scope.launch { holder.reorder(SkyDomain.EARTH, ids) }
                                    }
                                },
                            )
                        }
                    }
                }

                item(key = "foot") {
                    ProtoFoot("判断变更自动留档 · 同一天多次修改只保留最后一次 · 点标题看「我的判断 × 同期走势」")
                }
            }
        }

        // ---- 对话框群 ----
        addDomain?.let { domain ->
            FactorEditDialog(
                title = "新增${domain.label}关注点",
                initialTitle = "",
                initialNote = "",
                confirmLabel = "添加",
                onSubmit = { t, n -> holder.addFactor(domain, t, n) },
                onDismiss = { addDomain = null },
            )
        }
        editTarget?.let { f ->
            FactorEditDialog(
                title = "编辑关注点",
                initialTitle = f.title,
                initialNote = f.note.orEmpty(),
                confirmLabel = "保存",
                onSubmit = { t, n -> holder.renameFactor(f.id, t, n) },
                onDismiss = { editTarget = null },
            )
        }
        deleteTarget?.let { f ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除关注点", fontSize = pageSp(16f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary) },
                text = {
                    Text(
                        "「${f.title}」及其全部判断历史将一并删除，不可恢复。",
                        fontSize = pageSp(13f),
                        lineHeight = pageSp(20f),
                        color = StockNoteColors.TextPrimary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        scope.launch { holder.removeFactor(f.id) }
                    }) { Text("删除", color = StockNoteColors.Danger, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
                containerColor = Color.White,
            )
        }
        if (switchOpen) {
            SwitchSymbolDialog(
                items = holder.watchSymbols,
                current = holder.earthSymbol,
                onPick = { symbol ->
                    switchOpen = false
                    scope.launch { holder.switchSymbol(symbol) }
                },
                onDismiss = { switchOpen = false },
            )
        }
        if (copyOpen) {
            CopyTitlesDialog(
                scopes = holder.earthScopes,
                nameOf = { holder.displayNameOf(it) },
                onPick = { source ->
                    copyOpen = false
                    scope.launch { holder.copyTitlesFrom(source) }
                },
                onDismiss = { copyOpen = false },
            )
        }
        limitHint?.let { msg ->
            InfoDialog(message = msg, title = "已达上限", onDismiss = { limitHint = null })
        }
        holder.error?.let { msg ->
            InfoDialog(message = msg, title = "看天看地", onDismiss = { holder.dismissError() })
        }
    }
}

// ==================== 页面子组件 ====================

/** 30 天未更新提示条（页内常驻横幅；原型 `.note` 黄条）。 */
@Composable
private fun StaleBanner(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFF7E6))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏳", fontSize = pageSp(12f))
        Spacer(Modifier.width(8.dp))
        Text(
            "有 $count 条关注点已超过 30 天未更新，建议重新判断。",
            fontSize = pageSp(11.5f),
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB54708),
            lineHeight = pageSp(17f),
        )
    }
}

/**
 * 情景图卡（天 / 地共用外框，[canvas] 各画各的主题）。
 *
 * ⚠️ 白字叠在浅色渐变上要有阴影（原型 text-shadow 0 1px 6px rgba(0,0,0,.4)）。
 */
@Composable
private fun SceneCard(
    level: SkyLevel,
    score: Int?,
    caption: String,
    sub: String,
    judgedText: String,
    canvas: @Composable () -> Unit,
) {
    val shadow = TextStyle(shadow = Shadow(color = Color.Black.copy(alpha = 0.4f), blurRadius = 6f))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(106.dp)
            .clip(RoundedCornerShape(18.dp)),
    ) {
        canvas()
        Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 13.dp)) {
            Text(
                caption,
                fontSize = pageSp(16f),
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                style = shadow,
            )
            Spacer(Modifier.height(3.dp))
            Text(sub, fontSize = pageSp(11f), color = Color.White.copy(alpha = 0.92f), style = shadow)
        }
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 15.dp, top = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                scoreText(score),
                fontSize = pageSp(31f),
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                style = shadow,
            )
            Text(level.label, fontSize = pageSp(11.5f), fontWeight = FontWeight.Bold, color = Color.White, style = shadow)
            Text(judgedText, fontSize = pageSp(10.5f), color = Color.White.copy(alpha = 0.85f), style = shadow)
        }
        if (level == SkyLevel.UNJUDGED) {
            Text(
                "?",
                fontSize = pageSp(30f),
                fontWeight = FontWeight.ExtraBold,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 图卡下的一行小字（更新于 / 条数）。 */
@Composable
private fun MetaLine(text: String) {
    Text(
        text,
        fontSize = pageSp(11f),
        color = StockNoteColors.TextTertiary,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp),
    )
}

/** 组标题行：看天/看地 · 关注点（N） + 编辑 / ＋。 */
@Composable
private fun GroupHeader(
    domain: SkyDomain,
    count: Int,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onAdd: () -> Unit,
    onLimit: () -> Unit,
) {
    val full = count >= domain.maxCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${domain.label} · 关注点（$count）",
            fontSize = pageSp(13f),
            fontWeight = FontWeight.Bold,
            color = StockNoteColors.TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (editing) "完成" else "编辑",
            fontSize = pageSp(12f),
            fontWeight = FontWeight.Bold,
            color = if (editing) StockNoteColors.TextSecondary else StockNoteColors.Brand,
            modifier = Modifier
                .clickable(onClick = onToggleEdit)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            "＋",
            fontSize = pageSp(16f),
            fontWeight = FontWeight.ExtraBold,
            color = if (full) StockNoteColors.TextTertiary else StockNoteColors.Brand,
            modifier = Modifier
                .clickable { if (full) onLimit() else onAdd() }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 一条关注点。
 *
 * - 非编辑：点标题进详情、点三档改判断、**长按拖动排序**（松手落位）；
 * - 编辑：右侧换成 ▲ ▼ 🗑，点标题区改名 / 改依据。
 *
 * 行高固定（标题 1 行 + 依据 1 行），拖拽落位按实测行高折算。
 */
@Composable
private fun FactorRow(
    factor: SkyFactor,
    index: Int,
    lastIndex: Int,
    editing: Boolean,
    onOpenDetail: () -> Unit,
    onPick: (SkyAttitude) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveTo: (Int) -> Unit,
) {
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val staleDays = remember(factor.judgedAt) { staleDaysOf(factor) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .onSizeChanged { if (it.height > 0) rowHeightPx = it.height.toFloat() }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragOffset else 0f
                shadowElevation = if (dragging) 8f else 0f
            }
            .pointerInput(factor.id, editing) {
                if (editing) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        dragOffset = 0f
                    },
                    onDrag = { change, _ ->
                        // 必须先取位移再 consume（positionChange 一旦被消费就返回 Zero —— 自选页踩过）
                        val dy = change.positionChangeIgnoreConsumed().y
                        change.consume()
                        dragOffset += dy
                    },
                    onDragEnd = {
                        val shift = if (rowHeightPx > 0f) (dragOffset / rowHeightPx).roundToInt() else 0
                        dragging = false
                        dragOffset = 0f
                        if (shift != 0) onMoveTo((index + shift).coerceIn(0, lastIndex))
                    },
                    onDragCancel = {
                        dragging = false
                        dragOffset = 0f
                    },
                )
            }
            .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "${index + 1}",
            fontSize = pageSp(12f),
            fontWeight = FontWeight.ExtraBold,
            color = if (staleDays != null) Color(0xFFC6CDD8) else StockNoteColors.TextTertiary,
            modifier = Modifier.width(19.dp).padding(top = 2.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { if (editing) onEdit() else onOpenDetail() },
        ) {
            Text(
                factor.title,
                fontSize = pageSp(14f),
                fontWeight = FontWeight.Bold,
                lineHeight = pageSp(19f),
                color = if (staleDays != null) StockNoteColors.TextTertiary else StockNoteColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val note = factor.note
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    note,
                    fontSize = pageSp(11f),
                    lineHeight = pageSp(15f),
                    color = StockNoteColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (staleDays != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "$staleDays 天前判断",
                    fontSize = pageSp(10.5f),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB54708),
                )
            }
        }
        if (editing) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallArrow("▲", enabled = index > 0) { onMoveTo(index - 1) }
                    SmallArrow("▼", enabled = index < lastIndex) { onMoveTo(index + 1) }
                }
                Text(
                    "🗑",
                    fontSize = pageSp(13f),
                    modifier = Modifier
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        } else {
            SkyFaceRow(current = factor.score, onPick = onPick)
        }
    }
}

/** 编辑模式的小箭头（自选页 MoveButton 同款尺寸）；导入页预览也复用它。 */
@Composable
internal fun SmallArrow(arrow: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (enabled) Color(0xFFF3F6FB) else Color(0xFFF7F8FA))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            arrow,
            fontSize = pageSp(10f),
            color = if (enabled) StockNoteColors.Brand else StockNoteColors.TextTertiary,
        )
    }
}

/** 组内空态（整页非空、只是这一组空）。 */
@Composable
private fun GroupEmptyHint(
    text: String,
    onImport: () -> Unit,
    onManual: () -> Unit,
    onCopy: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(StockNoteColors.Surface)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, fontSize = pageSp(13f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniActionButton("📋 导入种子清单", onClick = onImport)
            MiniActionButton("✏️ 手动新建", onClick = onManual)
            if (onCopy != null) {
                MiniActionButton("📄 复制其他标的", onClick = onCopy)
            }
        }
    }
}

/** 两组都空时的整页空态（原型 23c）：图形 + 文案 + 两条创建入口。 */
@Composable
private fun EmptyStateCard(
    onImportSky: () -> Unit,
    onImportEarth: () -> Unit,
    onManual: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 150.dp, height = 84.dp)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            SkySceneCanvas(SkyLevel.UNJUDGED, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        Text("还没有关注点", fontSize = pageSp(14f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
        Spacer(Modifier.height(7.dp))
        Text(
            "把此刻真正在盯的关键点列出来（天 ≤${SkyDomain.SKY.maxCount} 条 · 地 ≤${SkyDomain.EARTH.maxCount} 条），逐条给个判断，App 会合成一张情绪图",
            fontSize = pageSp(11.5f),
            lineHeight = pageSp(18f),
            color = StockNoteColors.TextTertiary,
        )
        Spacer(Modifier.height(20.dp))
        GhostButton("📋 导入「看天」种子清单（去问 AI）", onClick = onImportSky)
        Spacer(Modifier.height(10.dp))
        GhostButton("📋 导入「看地」种子清单（去问 AI）", onClick = onImportEarth)
        Spacer(Modifier.height(10.dp))
        GhostButton("✏️ 手动逐条新建", onClick = onManual)
    }
}

/** 新增 / 编辑关注点对话框（标题必填 ≤30 字、依据可选 ≤200 字）；导入页改条目也复用它。 */
@Composable
internal fun FactorEditDialog(
    title: String,
    initialTitle: String,
    initialNote: String,
    confirmLabel: String,
    onSubmit: suspend (String, String) -> String?,
    onDismiss: () -> Unit,
) {
    var t by remember { mutableStateOf(initialTitle) }
    var n by remember { mutableStateOf(initialNote) }
    var err by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(title, fontSize = pageSp(16f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = t,
                    onValueChange = { if (it.length <= 30) t = it },
                    label = { Text("标题（≤30 字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = n,
                    onValueChange = { if (it.length <= 200) n = it },
                    label = { Text("依据（可选，≤200 字）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                err?.let {
                    Spacer(Modifier.height(8.dp))
                    // ⚠️ 用 Up 而不是 Danger（L-2，审核报告 2026-10-09）：Danger 是**危险操作色**
                    // （删除/清空按钮），不当错误文字色；错误文字全项目统一走 ErrorBanner 那一套色。
                    Text(it, fontSize = pageSp(12f), color = StockNoteColors.Up)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val e = onSubmit(t, n)
                        saving = false
                        if (e == null) onDismiss() else err = e
                    }
                },
            ) { Text(confirmLabel, color = StockNoteColors.Brand, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = { if (!saving) onDismiss() }) { Text("取消") } },
        containerColor = Color.White,
    )
}

/** 切换看地标的（数据源 = 自选）。 */
@Composable
private fun SwitchSymbolDialog(
    items: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换看地标的", fontSize = pageSp(16f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary) },
        text = {
            if (items.isEmpty()) {
                Text(
                    "自选里还没有标的。请先到「自选管理」添加，或继续用当前标的（${current}）。",
                    fontSize = pageSp(13f),
                    lineHeight = pageSp(20f),
                    color = StockNoteColors.TextPrimary,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(items, key = { _, p -> p.first }) { _, (symbol, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(symbol) }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(symbol, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
                            Spacer(Modifier.weight(1f))
                            if (symbol == current) {
                                Text("当前", fontSize = pageSp(11f), fontWeight = FontWeight.Bold, color = StockNoteColors.Brand)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = StockNoteColors.Brand, fontWeight = FontWeight.Bold) } },
        containerColor = Color.White,
    )
}

/** 复制其他标的的条目（只复制标题与排序，不带打分；FR-SE-10）。 */
@Composable
private fun CopyTitlesDialog(
    scopes: List<String>,
    nameOf: suspend (String) -> String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从其他标的复制条目", fontSize = pageSp(16f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary) },
        text = {
            Column {
                Text(
                    "只复制标题与排序，不带判断与依据。",
                    fontSize = pageSp(11.5f),
                    color = StockNoteColors.TextTertiary,
                )
                Spacer(Modifier.height(6.dp))
                scopes.forEach { symbol ->
                    var name by remember(symbol) { mutableStateOf(symbol) }
                    LaunchedEffect(symbol) { name = nameOf(symbol) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(symbol) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(symbol, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = StockNoteColors.TextSecondary) } },
        containerColor = Color.White,
    )
}

// ==================== 文案 / 工具 ====================

/** 分数展示：带正负号，未判断显示「—」（FR-SE-05：不是 0）。 */
private fun scoreText(score: Int?): String = when {
    score == null -> "—"
    score >= 0 -> "+$score"
    else -> "−${-score}"
}

/** 天组图上「天气」文案（FR-SE-05 表格）。 */
private fun skySceneCaption(level: SkyLevel): String = when (level) {
    SkyLevel.UNJUDGED -> "待判断"
    SkyLevel.VERY_BEARISH -> "风暴"
    SkyLevel.BEARISH -> "阴云"
    SkyLevel.NEUTRAL -> "多云"
    SkyLevel.BULLISH -> "转晴"
    SkyLevel.VERY_BULLISH -> "晴朗"
}

/** 地组图上「地面」文案（原型 23b）。 */
private fun earthSceneCaption(level: SkyLevel): String = when (level) {
    SkyLevel.UNJUDGED -> "待判断"
    SkyLevel.VERY_BEARISH -> "干裂"
    SkyLevel.BEARISH -> "灰土 · 低水位"
    SkyLevel.NEUTRAL -> "浅土 · 半水位"
    SkyLevel.BULLISH -> "湿润 · 短苗"
    SkyLevel.VERY_BULLISH -> "沃土 · 茂盛"
}

/** 已过期则给出「多少天前判断」，否则 null。 */
private fun staleDaysOf(f: SkyFactor): Long? {
    val at = f.judgedAt ?: return null
    val now = nowEpochMs()
    if (!SkyEarthScore.isStale(at, now)) return null
    return (now - at) / 86_400_000L
}
