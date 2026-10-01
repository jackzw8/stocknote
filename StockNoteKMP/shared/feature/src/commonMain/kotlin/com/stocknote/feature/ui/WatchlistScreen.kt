package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.format.Format
import com.stocknote.core.model.Market
import com.stocknote.core.model.Quote
import com.stocknote.core.model.Security
import com.stocknote.core.model.WatchItem
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.repo.WatchlistRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.state.analystTargetHint
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 自选管理（REQ-TOOL-02，原型 13/13b/13c）。
 *
 * 需求入口是行情页自选区「管理›」，行情页尚未立项（无底部第 5 tab），
 * 入口暂放统计页；行情页落地后把入口挪过去，本页路由与逻辑不动。
 *
 * 手动排序：原型是长按拖拽（行首 ⠿），当前用 ▲▼ 交换实现同一语义，
 * 数据模型（sort_order 交换）与将来接入拖拽完全一致。
 */

// ==================================================================================
// 状态
// ==================================================================================

class WatchlistHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-29 拆分（第 5 批）：自选域（本页的主数据源）。 */
    private val watch: WatchlistRepository,
    /** **标的域**（第 11 批）。 */
    private val security: SecurityRepository,
    private val scope: CoroutineScope,
) {
    data class UiState(
        val items: List<WatchItem> = emptyList(),
        val securitiesById: Map<String, Security> = emptyMap(),
        val quotesBySymbol: Map<String, Quote> = emptyMap(),
        /** 当前分组过滤；null = 全部 */
        val groupFilter: String? = null,
        /** 编辑态（批量） */
        val editing: Boolean = false,
        val selectedIds: Set<String> = emptySet(),
    /** 分组选择弹窗（批量移动目标） */
    val groupPicker: Boolean = false,
    /** 新建分组输入框（groupPicker 弹窗里用，老周 2026-09-22） */
    val newGroupInput: String = "",
        val error: String? = null,
        /** 保存失败次数：UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
        // ---- 目标价编辑弹窗（老周 2026-09-21）----
        /** 正在编辑目标价的自选行；null = 弹窗关闭 */
        val targetEditing: WatchItem? = null,
        val targetInput: String = "",
        val targetBusy: Boolean = false,
        val targetHint: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 拖拽重排（本地实时，不落库）：把 id 在**当前可见列表** [shownIds] 里前/后挪 |delta| 位。
     *
     * ⚠️ 必须按**可见列表**算（老周 2026-09-23 排查发现）：分组过滤（如「重点」）下，
     * 可见相邻的两项在 items 里可能隔着别的分组的项 —— 若直接按 items 索引 ±delta，
     * 就会出现「拖了但界面毫无变化」（行在 items 里动了，却被过滤条件排出视野），
     * 这正是「分组里拖不动」的根因。置顶区间仍然不跨（pinned 不同就停）。
     */
    fun moveItemLocal(id: String, delta: Int, shownIds: List<String>) {
        if (delta == 0 || shownIds.isEmpty()) return
        val step = if (delta > 0) 1 else -1
        var remaining = kotlin.math.abs(delta)
        _state.update { s ->
            val list = s.items.toMutableList()
            var moved = false
            while (remaining-- > 0) {
                val cur = list.indexOfFirst { it.id == id }
                if (cur < 0) break
                val shownIdx = shownIds.indexOf(id)
                val nbIdx = shownIdx + step
                if (shownIdx < 0 || nbIdx !in shownIds.indices) break
                val nbId = shownIds[nbIdx]
                val nbCur = list.indexOfFirst { it.id == nbId }
                if (nbCur < 0) break
                // 不跨 pinned 分组边界（置顶项固定在最前）
                if (list[cur].pinned != list[nbCur].pinned) break
                // 跳过可见邻居：把本行插入到邻居的另一侧
                val item = list.removeAt(cur)
                val nbAfter = list.indexOfFirst { it.id == nbId }
                val insertAt = (if (step > 0) nbAfter + 1 else nbAfter).coerceIn(0, list.size)
                list.add(insertAt, item)
                moved = true
            }
            if (!moved) s else s.copy(items = list)
        }
    }

    /** 拖拽结束：把当前本地顺序写入库（sort_order = 1..n） */
    fun persistOrder() {
        val ids = _state.value.items.map { it.id }
        scope.launch {
            runCatching { watch.reorder(ids) }
                .onFailure { e ->
                    _state.update {
                        it.copy(error = "排序保存失败：${e.message ?: "未知"}", saveFailTick = it.saveFailTick + 1)
                    }
                }
        }
    }

    fun load() {
        scope.launch {
            try {
                val items = watch.all()
                val secs = security.securities().associateBy { it.id }
                val quotes = LinkedHashMap<String, Quote>()
                items.forEach { w ->
                    val sec = secs[w.securityId] ?: return@forEach
                    val q = repo.cachedQuote(sec.symbol) ?: return@forEach
                    quotes[sec.symbol] = q
                }
                _state.update {
                    it.copy(items = items, securitiesById = secs, quotesBySymbol = quotes)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = "加载失败：${t.message ?: t::class.simpleName}") }
            }
        }
    }

    fun setGroupFilter(group: String?) = _state.update { it.copy(groupFilter = group) }

    fun toggleEditing() = _state.update { it.copy(editing = !it.editing, selectedIds = emptySet()) }

    fun toggleSelect(id: String) = _state.update {
        it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id)
    }

    fun pin(item: WatchItem) {
        scope.launch {
            watch.setPinned(item.id, !item.pinned)
            load()
        }
    }

    // ---- 目标价（老周 2026-09-21）----

    /** 打开目标价编辑弹窗（预填当前值） */
    fun openTargetDialog(item: WatchItem) {
        _state.update {
            it.copy(
                targetEditing = item,
                targetInput = item.targetPrice?.let { v -> trimTarget(v) } ?: "",
                targetHint = null,
                targetBusy = false,
            )
        }
    }

    fun closeTargetDialog() = _state.update { it.copy(targetEditing = null, targetHint = null, targetBusy = false) }

    fun setTargetInput(v: String) = _state.update { it.copy(targetInput = v, targetHint = null) }

    /**
     * 联网取目标价（老周 2026-09-21，两级口径）：
     *  ① 近 6 个月研报目标价 → 去极值平均（主口径）；取到时把 ② 的信息也写进提示对照；
     *  ② 机构评级聚合 → 评级家数 + 目标价区间；①没样本时用**区间中点**兜底。
     * 只填进输入框供确认，不直接落库。
     */
    fun fetchTargetFromWeb() {
        val s = _state.value
        val item = s.targetEditing ?: return
        val symbol = s.securitiesById[item.securityId]?.symbol
        if (symbol == null) {
            _state.update { it.copy(targetHint = "找不到这只标的的代码") }
            return
        }
        if (s.targetBusy) return
        _state.update { it.copy(targetBusy = true, targetHint = null) }
        scope.launch {
            val suggestion = runCatching { repo.analystTargetSuggestion(symbol) }
                .getOrDefault(com.stocknote.data.net.AnalystTargetSource.Suggestion.None)
            val fill = when (suggestion) {
                is com.stocknote.data.net.AnalystTargetSource.Suggestion.FromConsensus -> suggestion.price
                is com.stocknote.data.net.AnalystTargetSource.Suggestion.FromRangeMid -> suggestion.price
                com.stocknote.data.net.AnalystTargetSource.Suggestion.None -> null
            }
            val isAShare = symbol.startsWith("sh") || symbol.startsWith("sz") || symbol.startsWith("bj")
            _state.update {
                it.copy(
                    targetBusy = false,
                    targetInput = fill?.let { v -> trimTarget(kotlin.math.round(v * 100) / 100.0) }
                        ?: it.targetInput,
                    targetHint = analystTargetHint(suggestion, isAShare),
                )
            }
        }
    }

    /** 保存目标价（空输入 = 清除） */
    fun saveTarget() {
        val s = _state.value
        val item = s.targetEditing ?: return
        val text = s.targetInput.trim()
        val value = if (text.isEmpty()) null else text.toDoubleOrNull()
        if (text.isNotEmpty() && (value == null || value <= 0.0)) {
            _state.update { it.copy(targetHint = "目标价要是大于 0 的数字（留空 = 清除）") }
            return
        }
        scope.launch {
            runCatching { watch.setTargetPriceBySecurity(item.securityId, value) }
                .onFailure { e -> _state.update { it.copy(targetHint = "保存失败：${e.message ?: "未知错误"}") } }
                .onSuccess {
                    _state.update { it.copy(targetEditing = null, targetHint = null) }
                    load()
                }
        }
    }

    fun move(item: WatchItem, up: Boolean) {
        scope.launch {
            watch.move(item.id, up)
            load()
        }
    }

    fun removeSelected() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        scope.launch {
            try {
                ids.forEach { id ->
                    _state.value.items.firstOrNull { it.id == id }
                        ?.let { watch.removeBySecurity(it.securityId) }
                }
                _state.update { it.copy(editing = false, selectedIds = emptySet()) }
                load()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(error = "移除失败：${t.message ?: t::class.simpleName}", saveFailTick = it.saveFailTick + 1)
                }
            }
        }
    }

    fun moveSelectedTo(group: String) {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        scope.launch {
            watch.moveGroup(ids.toList(), group)
            _state.update { it.copy(editing = false, selectedIds = emptySet(), groupPicker = false) }
            load()
        }
    }

    fun openGroupPicker() = _state.update {
        if (it.selectedIds.isEmpty()) it else it.copy(groupPicker = true, newGroupInput = "")
    }

    fun closeGroupPicker() = _state.update { it.copy(groupPicker = false) }

    fun setNewGroupInput(v: String) = _state.update { it.copy(newGroupInput = v.take(10)) }

    /**
     * 手工新建分组并把已选标的移入（老周 2026-09-22）。
     * 名字撞已有分组时等价于「移入该分组」（幂等，不报错）；
     * 新分组名会随着移入动作自动出现在顶部分组 chips 与列表行 pill 上。
     */
    fun createGroupAndMove() {
        val name = _state.value.newGroupInput.trim()
        if (name.isEmpty()) return
        moveSelectedTo(name)
    }
}

// ==================================================================================
// 页面：自选管理（正常态 + 编辑态共用一页，原型 13 / 13b）
// ==================================================================================

@Composable
fun rememberWatchlistHolder(
    repo: PortfolioRepository,
    watch: WatchlistRepository,
    security: SecurityRepository,
): WatchlistHolder {
    val scope = rememberCoroutineScope()
    return remember(repo, watch) { WatchlistHolder(repo, watch, security, scope) }
}

@Composable
private fun WatchlistScreenContent(
    holder: WatchlistHolder,
    onOpenSecurity: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(Unit) { holder.load() }

    // 分组 chips：「全部」+ 按列表出现顺序的去重分组
    val groups = state.items.map { it.groupName }.distinct()
    val shown = if (state.groupFilter == null) state.items
    else state.items.filter { it.groupName == state.groupFilter }

    // ===== 长按拖拽排序（老周 2026-09-17，替代 ▲▼ 按钮）=====
    // 行高**实测**（老周 2026-09-22）：字号 1.2 后 WatchRow 实际 ≈ 90dp+，旧的 64dp 估算
    // 让「跨过一行」的判定跑偏——一次拖动要么连跳两行要么挪不动，正是「跨不了多行」的根因之一。
    val density = LocalDensity.current
    var rowHeightPx by remember { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 拖动跨整屏（老周 2026-09-22「可一次跨多个滑动」）：手指贴近上下边缘时列表自动滚动，
    // 滚动量同时计入拖动位移——手指不动也能一路跨行，一次长按可从列表头拖到尾。
    val listState = rememberLazyListState()
    var listTopY by remember { mutableFloatStateOf(0f) }
    var listBottomY by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    // 上一事件时刻被拖行的窗口 top：行重排/自动滚动会让节点瞬移，
    // 用它把节点相对坐标的 positionChange 还原成真实手指位移（见 onDrag 注释）
    var prevRowTop by remember { mutableFloatStateOf(0f) }

    /** 各行在窗口里的 y（onGloballyPositioned 写入；把行内坐标换算成手指窗口坐标用） */
    val rowTopCache = remember { mutableMapOf<String, Float>() }

    // 拖拽回调里必须读**最新的**可见顺序：pointerInput 的 lambda 捕获的是启动那次组合的快照，
    // 直接用 shown 会拿到过期列表（拖动中顺序一直在变）——rememberUpdatedState 保证读到最新值。
    val currentShownIds: List<String> by androidx.compose.runtime.rememberUpdatedState(shown.map { it.id })

    /** 每跨过一行高度就移动 |steps| 位（本地实时重排，不落库；persistOrder 在拖动结束时统一写库） */
    fun processDragStep(id: String) {
        val steps = (dragOffset / rowHeightPx).toInt()
        if (steps != 0) {
            holder.moveItemLocal(id, steps, currentShownIds)
            dragOffset -= steps * rowHeightPx
        }
    }

    // 拖动中的边缘自动滚动：每帧检查手指位置，贴边就滚动并把滚动量算进拖动位移。
    // 只累计**实际滚动的像素**（scrollBy 返回值）——列表到头/内容不满一屏时不会凭空跨行。
    LaunchedEffect(dragId) {
        val id = dragId ?: return@LaunchedEffect
        val zone = with(density) { 76.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val scrolled = when {
                fingerY > 0f && fingerY < listTopY + zone -> listState.scrollBy(-12f)
                listBottomY > 0f && fingerY > listBottomY - zone -> listState.scrollBy(12f)
                else -> 0f
            }
            if (scrolled != 0f) {
                dragOffset += scrolled
                processDragStep(id)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(StockNoteColors.Background)
            .onGloballyPositioned {
                listTopY = it.positionInWindow().y
                listBottomY = listTopY + it.size.height
            },
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBar(title = "自选管理") { AppNav.pop() }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (state.editing) "完成" else "编辑",
                    fontSize = pageSp(13.5f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.Brand,
                    modifier = Modifier.clickable { holder.toggleEditing() }.padding(8.dp),
                )
            }
        }

        // 分组 chips（老周 2026-09-22：分组多了要**自动换行**，不再横向单行滚动）
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChipPill(
                    label = "全部（${state.items.size}）",
                    selected = state.groupFilter == null,
                    onClick = { holder.setGroupFilter(null) },
                )
                groups.forEach { g ->
                    val count = state.items.count { it.groupName == g }
                    FilterChipPill(
                        label = "$g（$count）",
                        selected = state.groupFilter == g,
                        onClick = { holder.setGroupFilter(g) },
                    )
                }
            }
        }

        // 排序说明条
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⠿ 手动排序", fontSize = pageSp(12f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextSecondary)
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.editing) "已选 ${state.selectedIds.size} 只 · 可批量移动分组或移除"
                    else "长按拖动排序 · 点 📌 置顶",
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextTertiary,
                )
            }
        }

        // 列表
        item {
            CardBox {
                if (shown.isEmpty()) {
                    EmptyHint("暂无自选标的，点下方「＋ 添加自选标的」。")
                } else {
                    shown.forEachIndexed { index, w ->
                        val sec = state.securitiesById[w.securityId]
                        if (sec != null) {
                            // ⚠️ 必须 key(w.id)（老周 2026-09-22「跨一行就定住」的主根因）：
                            // forEach 按位置匹配，重排后每个槽位的行都算「换了数据」→
                            // pointerInput(w.id) 键变化重启协程 → 拖动手势流被掐断。
                            // key 后 Compose 按键移动组（布局节点复用），手势不断。
                            key(w.id) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 实测行高（全部行同高，取任一行即可）+ 行的窗口坐标（手指换算用）
                                    .onSizeChanged { if (it.height > 0) rowHeightPx = it.height.toFloat() }
                                    .onGloballyPositioned { rowTopCache[w.id] = it.positionInWindow().y }
                                    // 拖动中：置顶绘制 + 跟随手指（translationY 由外层状态驱动）
                                    .zIndex(if (w.id == dragId) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (w.id == dragId) dragOffset else 0f
                                        shadowElevation = if (w.id == dragId) 8f else 0f
                                    }
                                    .pointerInput(w.id, state.editing) {
                                        if (state.editing) return@pointerInput
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { start ->
                                                dragId = w.id
                                                dragOffset = 0f
                                                rowTopCache[w.id]?.let { top ->
                                                    fingerY = top + start.y
                                                    prevRowTop = top
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                // ⚠️⚠️ 必须在 consume() **之前**读位移（老周 2026-09-23 根因）：
                                                // Compose 的 `positionChange()` 语义是「一旦被消费就返回 Offset.Zero」，
                                                // 原代码先 consume 再取 → 位移恒为 0 → dragOffset 永远 0
                                                // → 行不跟随、永不跨行，表现为「长按拖动完全没反应」（真机日志实测证实）。
                                                // positionChangeIgnoreConsumed() 不受消费影响，取它才拿得到真实位移。
                                                val dy = change.positionChangeIgnoreConsumed().y
                                                change.consume()
                                                // ⚠️ 不能直接累加 delta.y：行重排/自动滚动会让本行节点在窗口里
                                                // 瞬移一个行高，节点相对坐标随之跳变 → positionChange 混入
                                                // 「-行高」的伪增量，把刚跨行的进度抵消 → 行被弹回原位定住
                                                // （老周 2026-09-22「跨一个还没松手就定位了」的根因）。
                                                // 还原：真实位移 = 坐标增量 + 节点位移。
                                                val top = rowTopCache[w.id] ?: prevRowTop
                                                val nodeJump = top - prevRowTop
                                                prevRowTop = top
                                                dragOffset += dy + nodeJump
                                                // 手指窗口坐标（边缘自动滚动判区用）：节点当前 top + 行内相对 y
                                                fingerY = top + change.position.y
                                                processDragStep(w.id)
                                            },
                                            onDragEnd = {
                                                dragId = null
                                                dragOffset = 0f
                                                holder.persistOrder()
                                            },
                                            onDragCancel = {
                                                dragId = null
                                                dragOffset = 0f
                                                // ⚠️ 取消也必须落库（老周 2026-09-23）：拖动过程中 items 顺序
                                                // **已经改了**（本实现没有"撤销"语义），这里若不保存，就会出现
                                                // 「界面顺序与库里不一致」→ 下次进页面顺序跳回原样，
                                                // 用户以为是「排序没保存上」。真机上拖动很容易被系统手势打断
                                                //（MIUI 边缘返回、来电等）→ 走的就是这个分支。
                                                holder.persistOrder()
                                            },
                                        )
                                    },
                            ) {
                                WatchRow(
                                    item = w,
                                    security = sec,
                                    quote = state.quotesBySymbol[sec.symbol],
                                    editing = state.editing,
                                    selected = w.id in state.selectedIds,
                                    onToggleSelect = { holder.toggleSelect(w.id) },
                                    onPin = { holder.pin(w) },
                                    // 上下移动（老周 2026-09-24）：挪 1 位 + 落库；置顶分组边界处按钮置灰
                                    //（规则与拖拽一致，moveItemLocal 内部还会再校验一遍）
                                    onMoveUp = if (index > 0 && shown[index - 1].pinned == w.pinned) {
                                        {
                                            holder.moveItemLocal(w.id, -1, currentShownIds)
                                            holder.persistOrder()
                                        }
                                    } else null,
                                    onMoveDown = if (index < shown.lastIndex && shown[index + 1].pinned == w.pinned) {
                                        {
                                            holder.moveItemLocal(w.id, 1, currentShownIds)
                                            holder.persistOrder()
                                        }
                                    } else null,
                                    onEditTarget = { holder.openTargetDialog(w) },
                                    onClick = { onOpenSecurity(w.securityId) },
                                    showDivider = index < shown.lastIndex,
                                )
                            }
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.load() }) } }

        if (!state.editing) {
            item {
                PrimaryButton("＋ 添加自选标的", onClick = onAdd)
            }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已选 ${state.selectedIds.size} ·",
                        fontSize = pageSp(12f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextSecondary,
                    )
                    Text(
                        "📁 移动分组",
                        fontSize = pageSp(13f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .clickable(enabled = state.selectedIds.isNotEmpty()) {
                                holder.openGroupPicker()
                            }
                            .padding(vertical = 13.dp),
                    )
                    Text(
                        "🗑 移除所选",
                        fontSize = pageSp(13f), fontWeight = FontWeight.Bold, color = StockNoteColors.Up,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFFDF0EE))
                            .clickable(enabled = state.selectedIds.isNotEmpty()) { holder.removeSelected() }
                            .padding(vertical = 13.dp),
                    )
                }
            }
            item {
                NoteBar("批量移动默认进入「未分组」；移除自选只取消关注，不影响该标的的交易记录、持仓与笔记。")
            }
        }

        item { ProtoFoot("自选仅存本机 · 移除不影响已有账本") }
    }
    // 保存 / 修改失败弹窗（老周 2026-09-18）：失败时**明确弹出**，避免「点了按钮没反应」的错觉
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = listOfNotNull(state.error),
    )

    // 批量移动分组：弹窗选目标分组（内置分组 + 现有分组去重）
    if (state.groupPicker) {
        val options = (listOf("重点", "未分组") + state.items.map { it.groupName }).distinct()
        AlertDialog(
            onDismissRequest = { holder.closeGroupPicker() },
            title = { Text("移动 ${state.selectedIds.size} 只到分组", fontSize = pageSp(16f), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    options.forEach { g ->
                        Text(
                            g,
                            fontSize = pageSp(14f), fontWeight = FontWeight.SemiBold,
                            color = StockNoteColors.TextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { holder.moveSelectedTo(g) }
                                .padding(vertical = 13.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // 手工新建分组（老周 2026-09-22）：输入名字即创建并把已选标的移入；
                    // 撞已有分组名 = 等价于移入该组（幂等）。新分组随移入自动出现在顶部 chips。
                    LabeledField(
                        label = "新建分组",
                        value = state.newGroupInput,
                        onValueChange = holder::setNewGroupInput,
                        placeholder = "如 网格 / 打新",
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "＋ 创建并移入",
                        fontSize = pageSp(13.5f), fontWeight = FontWeight.Bold,
                        color = if (state.newGroupInput.isBlank()) StockNoteColors.TextTertiary else StockNoteColors.Brand,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = state.newGroupInput.isNotBlank()) { holder.createGroupAndMove() }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                Text(
                    "取消",
                    fontSize = pageSp(14f), color = StockNoteColors.TextSecondary,
                    modifier = Modifier.clickable { holder.closeGroupPicker() }.padding(8.dp),
                )
            },
        )
    }

    // 目标价编辑弹窗（老周 2026-09-21）：手填 or 联网获取；留空 = 清除
    state.targetEditing?.let { item ->
        val sec = state.securitiesById[item.securityId]
        AlertDialog(
            onDismissRequest = { holder.closeTargetDialog() },
            title = {
                Text(
                    "目标价 · ${sec?.name ?: item.securityId}",
                    fontSize = pageSp(16f), fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    Text(
                        "给这只票定个目标价：交易计划里可以「取自选」一键带出；" +
                            "计划里改过也会反写回这里。留空 = 清除。\n" +
                            "「获取」先看近 6 个月研报目标价的均值（去掉最大最小）；" +
                            "没有逐条数据时退回机构目标价区间的中点（提示里会写明依据）。",
                        fontSize = pageSp(11f), color = StockNoteColors.TextTertiary, lineHeight = pageSp(16f),
                    )
                    Spacer(Modifier.height(8.dp))
                    LabeledField(
                        label = "目标价（${sec?.currency?.code ?: "CNY"}）",
                        value = state.targetInput,
                        onValueChange = holder::setTargetInput,
                        placeholder = "如 480",
                        numeric = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 一个入口两级取数（老周 2026-09-21）：先逐条研报均值，没有则退回机构区间中点
                        MiniActionButton(
                            text = if (state.targetBusy) "获取中…" else "🌐 获取分析师目标价",
                            onClick = holder::fetchTargetFromWeb,
                            enabled = !state.targetBusy,
                        )
                    }
                    state.targetHint?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary, lineHeight = pageSp(16f))
                    }
                }
            },
            confirmButton = {
                Text(
                    "保存",
                    fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.Brand,
                    modifier = Modifier.clickable { holder.saveTarget() }.padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    "取消",
                    fontSize = pageSp(14f), color = StockNoteColors.TextSecondary,
                    modifier = Modifier.clickable { holder.closeTargetDialog() }.padding(8.dp),
                )
            },
        )
    }
}

// ==================================================================================
// 自选行（对应 .wl：拖柄/勾选 + 头像 + 名称/市场 + 代码·币种·分组 + 现价/涨跌 + 📌）
// ==================================================================================

@Composable
private fun WatchRow(
    item: WatchItem,
    security: Security,
    quote: Quote?,
    editing: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPin: () -> Unit,
    /** 上移/下移一位（老周 2026-09-24）：null = 不可移（已是边界或跨置顶分组），按钮置灰 */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onEditTarget: () -> Unit,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    val (avBg, avFg) = marketColors(security)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (item.pinned) Color(0xFFF7FAFF) else Color.Transparent)
                .clickable {
                    if (editing) onToggleSelect() else onClick()
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing) {
                CheckSquare(checked = selected, onClick = onToggleSelect)
                Spacer(Modifier.width(11.dp))
            }
            // 拖拽手柄 ⠿ 已移除（老周 2026-09-21 真机看效果）：字号放大后行内宽度紧张，
            // 手柄只是视觉提示（顶部排序条已写明「长按拖动排序」），省出的宽度给名称与代码。

            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(avBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(avatarOf(security.name), fontSize = pageSp(14f), fontWeight = FontWeight.ExtraBold, color = avFg)
            }
            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                // 名称独占一行（老周 2026-09-21 真机看效果：名称和标签挤一行会被截成"贵州…"，
                // 市场标签挪到第二行开头，名称拿到整行宽度）
                Text(
                    security.name,
                    fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(avBg)) {
                        Text(
                            marketLabel(security),
                            fontSize = pageSp(10f), fontWeight = FontWeight.Bold, color = avFg,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        // 币种代码不写：市场标签已隐含（A股→CNY / 港股通→HKD）；
                        // 分组也挪到下方 pill 行右端（老周 2026-09-21：这行宽度只够放完整代码）
                        security.symbol,
                        fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                        maxLines = 1, softWrap = false,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    quote?.let { Format.money(it.price, "") } ?: "—",
                    fontSize = pageSp(13.5f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                val chg = quote?.changeRatio
                Text(
                    chg?.let { Format.percent(it) } ?: "—",
                    fontSize = pageSp(11f), fontWeight = FontWeight.Bold,
                    color = when {
                        chg == null -> StockNoteColors.TextTertiary
                        chg >= 0 -> StockNoteColors.Up
                        else -> StockNoteColors.Down
                    },
                )
            }
            Spacer(Modifier.width(10.dp))

            if (!editing) {
                // 🧭 上/下/置顶 竖排三按钮（老周 2026-09-24）：在 📌 上下加 ▲▼，
                // 点击与相邻项交换顺序并落库（复用拖拽的 moveItemLocal + persistOrder，
                // 置顶分组边界规则与拖拽一致：pinned 不同就不动）。null = 到边界，按钮置灰不可点。
                Column(
                    modifier = Modifier.width(34.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MoveButton("▲", enabled = onMoveUp != null, onClick = { onMoveUp?.invoke() })
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (item.pinned) Color(0xFFEAF1FE) else Color(0xFFF3F6FB))
                            .clickable { onPin() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📌", fontSize = pageSp(12f))
                    }
                    MoveButton("▼", enabled = onMoveDown != null, onClick = { onMoveDown?.invoke() })
                }
            }
        }
        // 目标价 pill 整行放置（老周 2026-09-21）：字号放大后左列宽度不够，胶囊文字被截断
        // （真机截图：茅台只剩"🎯 目标"，价格数字被省略）。挪到整行就放得下「目标 + 距现价」。
        // 点这里就地设/改；与「交易计划-目标价」互通（计划里改过会反写回来）。
        if (!editing) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (item.targetPrice != null) Color(0xFFEAF1FE) else Color(0xFFF3F6FB))
                        .clickable { onEditTarget() }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    val t = item.targetPrice
                    Text(
                        if (t != null) {
                            "🎯 目标 ${Format.money(t, "")}" + (quote?.takeIf { it.price > 0 }?.let { q ->
                                " · " + Format.percent((t - q.price) / q.price)
                            } ?: "")
                        } else "🎯 设目标价",
                        fontSize = pageSp(10.5f),
                        fontWeight = FontWeight.Bold,
                        color = if (item.targetPrice != null) StockNoteColors.BrandDark else StockNoteColors.TextTertiary,
                        maxLines = 1, softWrap = false,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 分组名放这行右端（老周 2026-09-21）：名称行宽度只够名称，代码行只够代码
                Text(
                    item.groupName,
                    fontSize = pageSp(10.5f), color = StockNoteColors.TextTertiary,
                    maxLines = 1, softWrap = false,
                )
            }
        }
        if (showDivider) {
            Box(Modifier.padding(horizontal = 14.dp).height(1.dp).background(StockNoteColors.Divider))
        }
    }
}

/** 上移/下移小按钮（自选行竖排按钮组）：enabled=false 时置灰不可点 */
@Composable
private fun MoveButton(arrow: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 20.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (enabled) Color(0xFFF3F6FB) else Color(0xFFF7F8FA))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(arrow, fontSize = pageSp(10f), color = if (enabled) StockNoteColors.Brand else StockNoteColors.TextTertiary)
    }
}

@Composable
private fun CheckSquare(checked: Boolean, onClick: () -> Unit) {    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (checked) StockNoteColors.Brand else Color.White)
            .border(1.8.dp, if (checked) StockNoteColors.Brand else Color(0xFFCBD4E1), RoundedCornerShape(7.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", fontSize = pageSp(13f), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) StockNoteColors.Brand else Color.White)
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = pageSp(12f), fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else StockNoteColors.TextSecondary,
        )
    }
}

internal fun avatarOf(name: String): String =
    name.trim().take(1).ifEmpty { "?" }

/** 目标价去尾零（480.0 → "480"，432.50 → "432.5"）——输入框预填用 */
private fun trimTarget(v: Double): String {
    // ⚠️ 不用 `"%.4f".format(v)` —— String.format 是 **JVM 专有**（iOS 编译失败），
    // 且走默认 Locale（小数分隔符为逗号的地区会输出 480,0000）。走项目自己的 Locale 无关实现。
    val s = com.stocknote.core.format.Format.fixedPlain(v, 4).trimEnd('0').trimEnd('.')
    return s.ifEmpty { "0" }
}

private fun marketColors(security: Security): Pair<Color, Color> = when (security.market) {
    Market.HK -> Color(0xFFFFF4E3) to Color(0xFFB8791A)
    Market.US -> Color(0xFFEEF0FB) to Color(0xFF6D5BD0)
    else -> Color(0xFFEAF1FE) to StockNoteColors.BrandDark
}

internal fun marketLabel(security: Security): String = when (security.market) {
    Market.HK -> "港股通"
    Market.US -> "美股"
    Market.ETF -> "ETF"
    Market.FUND -> "基金"
    else -> "A股"
}

// ==================================================================================
// 添加自选（原型 13c：搜索多选）
// ==================================================================================

class WatchAddHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-29 拆分（第 5 批）：自选域。 */
    private val watch: WatchlistRepository,
    /** **标的域**（第 11 批）：本地候选与联网搜索。 */
    private val security: SecurityRepository,
    private val scope: CoroutineScope,
) {
    /** 候选行：本地标的带 securityId 直接入自选；联网命中添加时先 findOrCreateSecurity。 */
    data class HitRow(
        val symbol: String,
        val name: String,
        val marketText: String,
        val currencyCode: String,
        val securityId: String?,
    )

    data class UiState(
        val keyword: String = "",
        val searching: Boolean = false,
        val hits: List<HitRow> = emptyList(),
        val searched: Boolean = false,
        val selectedSymbols: Set<String> = emptySet(),
        val watchedIds: Set<String> = emptySet(),
        val error: String? = null,
        /** 保存失败次数：UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            val watched = watch.all().map { it.securityId }.toSet()
            _state.update { it.copy(watchedIds = watched) }
        }
    }

    fun setKeyword(v: String) = _state.update { it.copy(keyword = v) }

    fun search() {
        val kw = _state.value.keyword.trim()
        if (kw.isEmpty()) return
        scope.launch {
            _state.update { it.copy(searching = true, error = null) }
            val local = runCatching { security.searchSecurities(kw) }.getOrDefault(emptyList())
            // 联网后置：本地先出（<400ms 可感目标），网络失败静默（与技术说明书 8.2 一致）
            val online = runCatching { security.searchOnline(kw) }.getOrDefault(emptyList())
                .filter { h -> local.none { l -> l.symbol == h.symbol } }
            val rows = buildList {
                local.forEach { s ->
                    add(HitRow(s.symbol, s.name, marketLabel(s), s.currency.code, s.id))
                }
                online.forEach { h ->
                    add(HitRow(h.symbol, h.name, h.marketLabel, h.currencyCode, null))
                }
            }
            _state.update { it.copy(searching = false, searched = true, hits = rows) }
        }
    }

    fun toggle(symbol: String) = _state.update {
        it.copy(selectedSymbols = if (symbol in it.selectedSymbols) it.selectedSymbols - symbol else it.selectedSymbols + symbol)
    }

    fun selectAll() = _state.update { s ->
        s.copy(selectedSymbols = s.hits.map { it.symbol }.toSet())
    }

    /** 批量加入自选（已在自选的自动跳过，返回成功加入数）。 */
    fun addSelected(onDone: (Int) -> Unit) {
        val s = _state.value
        val chosen = s.hits.filter { it.symbol in s.selectedSymbols }
        if (chosen.isEmpty()) {
            _state.update {
                it.copy(error = "请先勾选要加入自选的标的", saveFailTick = it.saveFailTick + 1)
            }
            return
        }
        scope.launch {
            var added = 0
            chosen.forEach { h ->
                val target = h.securityId ?: run {
                    // 联网命中未入库：先建标的再入自选。
                    // ⚠️ 2026-09-18 审查修复（老周拍板）：此前 market 硬编码 A_SHARE，
                    // 从这里添加新的港股/美股会被建成 A 股（CNY 计价）→ 行情拉取、记账币种全错。
                    // 现按联网候选的市场标签判断，与记一笔的 pickOnline 同口径。
                    val market = when (h.marketText) {
                        "ETF" -> Market.ETF
                        // 场外基金（老周 2026-09-20）：走 here 建标的一律缺省「不计入统计」，
                        // 与记一笔的 pickOnline 同口径
                        "基金" -> Market.FUND
                        "港股" -> Market.HK
                        "美股" -> Market.US
                        else -> Market.A_SHARE
                    }
                    runCatching {
                        security.findOrCreateSecurity(
                            symbol = h.symbol,
                            name = h.name,
                            market = market,
                            currency = com.stocknote.core.model.Currency.entries
                                .firstOrNull { it.code == h.currencyCode }
                                ?: com.stocknote.core.model.Currency.CNY,
                            excludeFromStats = market == Market.FUND,
                        ).id
                    }.getOrNull()
                }
                if (target != null && watch.add(target)) added++
            }
            // 全部失败时留在本页并弹提示，不能静默 pop 让用户误以为添加成功（老周 2026-09-18）
            if (added == 0) {
                _state.update {
                    it.copy(error = "加入自选失败（可能网络不通），请重试", saveFailTick = it.saveFailTick + 1)
                }
                return@launch
            }
            _state.update { it.copy(selectedSymbols = emptySet()) }
            onDone(added)
        }
    }
}

@Composable
fun rememberWatchAddHolder(
    repo: PortfolioRepository,
    watch: WatchlistRepository,
    security: SecurityRepository,
): WatchAddHolder {
    val scope = rememberCoroutineScope()
    return remember(repo, watch, security) { WatchAddHolder(repo, watch, security, scope) }
}

@Composable
private fun WatchlistAddScreenContent(
    holder: WatchAddHolder,
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(Unit) { holder.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(title = "添加自选") { AppNav.pop() } }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField(
                    label = "搜索标的",
                    value = state.keyword,
                    onValueChange = holder::setKeyword,
                    placeholder = "搜索名称或代码",
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "搜索",
                    fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.Brand,
                    modifier = Modifier.clickable { holder.search() }.padding(8.dp),
                )
            }
        }

        if (state.searched || state.hits.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("实时匹配候选 · 可多选", fontSize = pageSp(12.5f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "全选",
                        fontSize = pageSp(12f), fontWeight = FontWeight.Bold, color = StockNoteColors.Brand,
                        modifier = Modifier.clickable { holder.selectAll() }.padding(6.dp),
                    )
                }
            }
            item {
                CardBox {
                    if (state.hits.isEmpty()) {
                        EmptyHint(if (state.searching) "搜索中…" else "没有匹配的标的。")
                    } else {
                        state.hits.forEachIndexed { i, h ->
                            val watched = h.securityId != null && h.securityId in state.watchedIds
                            val selected = h.symbol in state.selectedSymbols
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !watched) { holder.toggle(h.symbol) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CheckSquare(checked = watched || selected, onClick = { holder.toggle(h.symbol) })
                                Spacer(Modifier.width(11.dp))
                                Box(
                                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEAF1FE)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(avatarOf(h.name), fontSize = pageSp(13f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.BrandDark)
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(h.name, fontSize = pageSp(13.5f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                                        Spacer(Modifier.width(6.dp))
                                        Text(h.symbol, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
                                    }
                                    Text(
                                        "${h.marketText} · ${h.currencyCode}" + if (watched) " · 已在自选" else "",
                                        fontSize = pageSp(11f),
                                        color = if (watched) StockNoteColors.Brand else StockNoteColors.TextTertiary,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                            }
                            if (i < state.hits.lastIndex) {
                                Box(Modifier.padding(horizontal = 14.dp).height(1.dp).background(StockNoteColors.Divider))
                            }
                        }
                    }
                }
            }
        } else {
            item { NoteBar("支持文字 / 部分代码搜索（中文或裸代码，如 600519）；已在自选中的标的不可重复添加。") }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.search() }) } }

        if (state.selectedSymbols.isNotEmpty()) {
            item {
                PrimaryButton(
                    text = "＋ 添加 ${state.selectedSymbols.size} 只到自选",
                    onClick = {
                        holder.addSelected(onDone = { AppNav.pop() })
                    },
                )
            }
        }

        item { ProtoFoot("搜索范围：本地标的 + 联网实时候选（东方财富）") }
    }
    // 保存 / 修改失败弹窗（老周 2026-09-18）：失败时**明确弹出**，避免「点了按钮没反应」的错觉
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = listOfNotNull(state.error),
    )
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与统计页一致；未包的页面默认 1f 不受影响）。 */
@Composable
fun WatchlistScreen(
    holder: WatchlistHolder,
    onOpenSecurity: (String) -> Unit,
    onAdd: () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        WatchlistScreenContent(holder, onOpenSecurity, onAdd)
    }
}

/** 整页字号放大 1.2 倍（与自选管理页一致）。 */
@Composable
fun WatchlistAddScreen(
    holder: WatchAddHolder,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        WatchlistAddScreenContent(holder)
    }
}
