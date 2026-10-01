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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.stocknote.core.model.TagDef
import com.stocknote.data.repo.TagRepository
import com.stocknote.data.repo.TradeRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 标签管理（REQ-NOTE-04 的配套页，M3 第二批）。
 *
 * 设计基点：**标签名即主键**（trade.tag_ids CSV 存的就是名字）。
 * 重命名 = 换主键 + 批量改 CSV（Repo.renameTag 逐笔精确处理，防子串误伤）；
 * 删除 = 清 CSV 引用后删行，删除前提示影响笔数。
 */

class TagManageHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-29 拆分（第 4 批）：标签域。 */
    private val tag: TagRepository,
    /** ⚠️ 2026-09-29 拆分（第 6 批）：交易域（统计各标签被多少笔交易引用）。 */
    private val trade: TradeRepository,
    private val scope: CoroutineScope,
) {
    data class TagWithCount(val tag: TagDef, val usageCount: Int)

    data class UiState(
        val tags: List<TagWithCount> = emptyList(),
        val newName: String = "",
        val error: String? = null,
        /** 保存失败次数：UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
        /** 重命名中的标签（弹 dialog） */
        val renaming: TagDef? = null,
        /** 删除确认中的标签（弹 dialog，含影响笔数） */
        val deleting: TagWithCount? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            try {
                val tags = tag.all()
                val counts = trade.allTransactions()
                    .flatMap { it.tags }
                    .groupingBy { it }
                    .eachCount()
                _state.update {
                    it.copy(tags = tags.map { t -> TagWithCount(t, counts[t.id] ?: 0) })
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = "加载失败：${t.message ?: t::class.simpleName}") }
            }
        }
    }

    fun setNewName(v: String) = _state.update { it.copy(newName = v) }

    fun add() {
        val n = _state.value.newName
        scope.launch {
            val ok = runCatching { tag.add(n) }.getOrDefault(false)
            if (!ok) {
                _state.update {
                    it.copy(error = "标签名不能为空且不能与现有标签重复", saveFailTick = it.saveFailTick + 1)
                }
            } else {
                _state.update { it.copy(newName = "", error = null) }
                load()
            }
        }
    }

    fun startRename(tag: TagDef) = _state.update { it.copy(renaming = tag) }
    fun cancelRename() = _state.update { it.copy(renaming = null) }

    fun confirmRename(newName: String) {
        val old = _state.value.renaming ?: return
        if (newName.isBlank()) return
        scope.launch {
            runCatching { tag.rename(old.id, newName) }
            _state.update { it.copy(renaming = null) }
            load()
        }
    }

    fun startDelete(item: TagWithCount) = _state.update { it.copy(deleting = item) }
    fun cancelDelete() = _state.update { it.copy(deleting = null) }

    fun confirmDelete() {
        val target = _state.value.deleting ?: return
        scope.launch {
            runCatching { tag.delete(target.tag.id) }
            _state.update { it.copy(deleting = null) }
            load()
        }
    }
}

@Composable
fun rememberTagManageHolder(
    repo: PortfolioRepository,
    tag: TagRepository,
    trade: TradeRepository,
): TagManageHolder {
    val scope = rememberCoroutineScope()
    return androidx.compose.runtime.remember(repo, tag, trade) {
        TagManageHolder(repo, tag, trade, scope)
    }
}

@Composable
fun TagManageScreen(holder: TagManageHolder) {
    val state by holder.state.collectAsState()
    LaunchedEffect(Unit) { holder.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBar(title = "标签管理") { AppNav.pop() }
            }
        }

        item {
            NoteBar("标签用于记一笔的「策略标签」与分析页的按策略分析。名字即编号：重命名会同步更新全部历史交易；删除会从所有交易里移除该标签（不影响交易本身）。")
        }

        item {
            SectionCard(title = "新增标签") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField(
                        label = "标签名",
                        value = state.newName,
                        onValueChange = holder::setNewName,
                        placeholder = "如 龙头战法",
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "添加",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.Brand,
                        modifier = Modifier.clickable { holder.add() }.padding(8.dp),
                    )
                }
                state.error?.let { FieldError(it) }
            }
        }

        item {
            Sec(title = "全部标签（${state.tags.size}）") {
                CardBox {
                    if (state.tags.isEmpty()) {
                        EmptyHint("还没有标签。")
                    } else {
                        state.tags.forEachIndexed { i, item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.tag.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                                    Text(
                                        if (item.usageCount > 0) "已用于 ${item.usageCount} 笔交易" else "暂无交易引用",
                                        fontSize = 11.sp, color = StockNoteColors.TextTertiary,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                                Text("✏️", fontSize = 14.sp, modifier = Modifier.clickable { holder.startRename(item.tag) }.padding(6.dp)
                                        .semantics { contentDescription = "重命名标签" })
                                Text("🗑", fontSize = 14.sp, modifier = Modifier.clickable { holder.startDelete(item) }.padding(6.dp)
                                        .semantics { contentDescription = "删除标签" })
                            }
                            if (i < state.tags.lastIndex) {
                                Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                            }
                        }
                    }
                }
            }
        }

        item { ProtoFoot("内置 6 个标签（价值投资/突破/网格/趋势/波段/打板）可重命名但建议保留") }
    }

    // 保存 / 修改失败弹窗（老周 2026-09-18）：失败时**明确弹出**，避免「点了按钮没反应」的错觉
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = listOfNotNull(state.error),
    )

    // 重命名 dialog
    state.renaming?.let { tag ->
        var newName by remember(tag.id) { mutableStateOf(tag.name) }
        AlertDialog(
            onDismissRequest = { holder.cancelRename() },
            title = { Text("重命名标签", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "将同步更新所有历史交易里的引用。",
                        fontSize = 12.sp, color = StockNoteColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "新名称",
                        value = newName,
                        onValueChange = { newName = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { holder.confirmRename(newName) }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { holder.cancelRename() }) { Text("取消") }
            },
        )
    }

    // 删除确认 dialog
    state.deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { holder.cancelDelete() },
            title = { Text("删除标签「${item.tag.name}」？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (item.usageCount > 0)
                        "有 ${item.usageCount} 笔交易在用这个标签，删除后这些交易的该标签会被移除，交易本身不受影响。"
                    else "该标签暂无引用，可直接删除。",
                    fontSize = 13.sp, color = StockNoteColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { holder.confirmDelete() }) { Text("删除", color = StockNoteColors.Up) }
            },
            dismissButton = {
                TextButton(onClick = { holder.cancelDelete() }) { Text("取消") }
            },
        )
    }
}
