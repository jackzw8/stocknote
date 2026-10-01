package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.PlanPricing
import com.stocknote.core.calc.TradeForms
import com.stocknote.core.format.Format
import com.stocknote.core.model.TradeSide
import com.stocknote.feature.state.PlanEditHolder
import com.stocknote.feature.theme.StockNoteColors

/** 预置策略标签（与记一笔 `TradeFormScreen.TAGS` 同源；标签库为空时兜底列出） */
private val DEFAULT_PLAN_TAGS = listOf("价值投资", "突破", "网格", "趋势", "波段", "打板")

/**
 * 新建 / 编辑交易计划的表单 —— 对照 `界面原型-2.0/plan-edit.html`（REQ-PLAN-01）。
 *
 * 与记一笔页的差异：
 *  - **不写账本**（顶部蓝色说明条明确告知）；
 *  - 没有交易日期、情绪、执行评分、交易截图（计划未执行，这些属于复盘要素）；
 *  - 新增**目标价**与**折扣**：目标价 × 折扣 → 计划价（[PlanPricing]）；
 *  - 备注**选填**；编辑态底部多一个红色「删除计划」（二次确认）。
 *
 * ⚠️ 全屏表单页**不带底部导航**（老周 Q6：与记一笔一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanEditScreenContent(
    holder: PlanEditHolder,
    /** 新建成功 / 编辑保存 / 删除完成后的返回 */
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()
    val isEdit = state.editPlanId != null

    // 币种 / 数量单位一律**跟着标的所属市场走**（老周 2026-09-21）：
    // 此前表单里所有金额都写死 ¥，选港股标的时目标价、计划金额、手续费仍显示人民币。
    val curCode = state.formCurrency.code
    val curSymbol = state.formCurrency.symbol
    val quantityUnit = state.formMarket?.quantityUnit ?: "股"
    // 每手提示（老周 2026-09-28）：A股/ETF/港股显示「100股/手」，美股/场外基金不显示
    val lotHint = state.formMarket?.let { m ->
        com.stocknote.core.calc.LotRule.hintOf(m, state.formLotSize)
    }

    // 老周 2026-09-28：读入设置里的「整手校验」开关（缺省开）
    LaunchedEffect(Unit) { holder.refreshLotCheck() }

    LaunchedEffect(state.saved, state.deleted) {
        if (state.saved || state.deleted) onDone()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(title = if (isEdit) "编辑计划" else "新建计划", onBack = onBack) }

        item {
            NoteBar("ℹ 计划不是交易，不写入账本、不影响持仓与盈亏，仅供查阅。")
        }

        // ---- 方向：计划买入 / 计划卖出 ----
        item {
            ChipRow(
                options = listOf(TradeSide.BUY, TradeSide.SELL),
                selectedIndex = if (state.side == TradeSide.SELL) 1 else 0,
                onSelect = { i -> holder.setSide(if (i == 1) TradeSide.SELL else TradeSide.BUY) },
                labelOf = { if (it == TradeSide.SELL) "计划卖出" else "计划买入" },
            )
        }

        // ---- 标的 ----
        item {
            SectionCard(title = if (state.locked) "标的（已锁定）" else "选择标的") {
                if (state.locked) {
                    Text(state.securityLabel, fontSize = pageSp(15f), fontWeight = FontWeight.Medium, color = StockNoteColors.TextPrimary)
                    if (!isEdit) {
                        Text(
                            "新建计划中标的一旦选定不可更改；要换标的请返回重开。",
                            fontSize = pageSp(11f),
                            color = StockNoteColors.TextTertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabeledField(
                            label = "搜索",
                            value = state.keyword,
                            onValueChange = holder::setKeyword,
                            modifier = Modifier.weight(1f),
                            placeholder = "搜索名称或代码",
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = holder::search,
                            modifier = Modifier.semantics { contentDescription = "搜索标的" },
                        ) { Text("搜索", fontSize = pageSp(13f)) }
                    }
                    if (state.searching) {
                        Text("搜索中…", fontSize = pageSp(11f), color = StockNoteColors.TextTertiary, modifier = Modifier.padding(top = 6.dp))
                    }
                    // 空关键词点搜索 → 这批是「最近用过的标的」（老周 2026-09-21）
                    if (state.recentMode && state.candidates.isNotEmpty()) {
                        Text(
                            "最近用过的标的（点击直接选中）",
                            fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (state.recentMode && !state.searching && state.candidates.isEmpty()) {
                        Text(
                            "还没有可以「最近使用」的标的（记过账或建过计划的会出现在这里）；也可以直接搜代码。",
                            fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    state.candidates.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { holder.pick(s) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.name, fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(s.symbol, fontSize = pageSp(12f), color = StockNoteColors.TextTertiary)
                            Spacer(Modifier.weight(1f))
                            Text(s.market.label, fontSize = pageSp(11f), color = StockNoteColors.TextSecondary)
                        }
                    }
                    state.onlineHits.forEach { hit ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { holder.pickOnline(hit) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(hit.name, fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(hit.symbol, fontSize = pageSp(12f), color = StockNoteColors.TextTertiary)
                            Spacer(Modifier.weight(1f))
                            Text("联网 · ${hit.marketLabel}", fontSize = pageSp(11f), color = StockNoteColors.TextSecondary)
                        }
                    }
                }
            }
        }

        // ---- 目标价（核心新增）----
        item {
            SectionCard(title = "目标价") {
                LabeledField(
                    label = "目标价（$curCode） *",
                    value = state.targetPrice,
                    onValueChange = holder::setTargetPrice,
                    placeholder = "你对该标的的估值 / 目标位",
                    numeric = true,
                    errorText = state.issues.firstOrNull { it.field == "targetPrice" }?.message,
                )
                Spacer(Modifier.height(8.dp))
                // 两个来源（老周 2026-09-21）：
                //  🌐 获取   = 近 6 个月研报目标价去极值平均；**没有逐条数据时自动退回机构目标价区间中点**
                //  ⭐ 取自选 = 用自选股里存的目标价（保存计划时也会反写回去）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniActionButton(
                        text = if (state.targetFetching) "获取中…" else "🌐 获取",
                        onClick = holder::fetchAnalystTarget,
                        enabled = !state.targetFetching && state.securitySymbol != null,
                    )
                    if (state.watched) {
                        Spacer(Modifier.width(8.dp))
                        MiniActionButton(
                            text = state.watchTarget?.let { "⭐ 取自选 ${Format.money(it, "")}" } ?: "⭐ 自选未设目标价",
                            onClick = holder::applyWatchTarget,
                            enabled = state.watchTarget != null,
                        )
                    }
                }
                state.targetFetchHint?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary, lineHeight = pageSp(16f))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.watched) "保存计划后会把这里的目标价写回自选股（两处始终一致）。"
                    else "该标的还没加入自选：保存只作用于计划，不会自动加自选。",
                    fontSize = pageSp(11f), color = StockNoteColors.TextTertiary, lineHeight = pageSp(16f),
                )
            }
        }

        // ---- 成交价 + 折扣 ----
        item {
            SectionCard(title = "计划价与折扣") {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) {
                        LabeledField(
                            label = "成交价（$curCode） *",
                            value = state.plannedPrice,
                            onValueChange = holder::setPlannedPrice,
                            placeholder = "选折扣后自动填入",
                            numeric = true,
                            errorText = state.issues.firstOrNull { it.field == "plannedPrice" }?.message,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    // ⚠️ 必须用 Column 而不是 Box：Box 是叠加布局，会把「折扣」标签与下拉框叠在一起
                    // （2026-09-19 老周指出对不齐，根因就在此）。标签字号/间距与 LabeledField 保持一致，
                    // 框高 56dp = Material3 OutlinedTextField 默认高度。
                    Column(Modifier.width(112.dp)) {
                        Text("折扣", fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
                        DiscountDropdown(
                            selected = state.discount,
                            onSelect = holder::setDiscount,
                        )
                    }
                }
                // 计算式提示（原型 .hintlink）
                val target = state.targetPrice.toDoubleOrNull()
                if (target != null && state.discount != null) {
                    val label = PlanPricing.DISCOUNT_OPTIONS.firstOrNull { it.second == state.discount }?.first
                    Text(
                        "↳ ${Format.money(target, "")} × ${label ?: state.discount} = ${Format.money(state.plannedPrice.toDoubleOrNull() ?: 0.0, "")}",
                        fontSize = pageSp(11f),
                        color = StockNoteColors.Brand,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ---- 数量 / 手续费率 ----
        item {
            SectionCard(title = "数量与费率") {
                Row {
                    Box(Modifier.weight(1f)) {
                        LabeledField(
                            // 老周 2026-09-28：数量旁标出每手股数
                            label = "数量（$quantityUnit${lotHint?.let { " · $it" } ?: ""}） *",
                            value = state.quantity,
                            onValueChange = holder::setQuantity,
                            placeholder = "如 100",
                            numeric = true,
                            errorText = state.issues.firstOrNull { it.field == "quantity" }?.message,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        LabeledField(
                            label = "手续费率（万分之）",
                            value = state.feeRate,
                            onValueChange = holder::setFeeRate,
                            numeric = true,
                            errorText = state.issues.firstOrNull { it.field == "feeRate" }?.message,
                        )
                        // 佣金下限（老周 2026-09-19）：与记一笔同口径，命中时明确告知
                        TradeForms.feeAmountOf(state.plannedPrice, state.quantity, state.feeRate)?.let { feeAmt ->
                            if (feeAmt > 0) {
                                val rateVal = state.feeRate.trim().toDoubleOrNull() ?: 0.0
                                val rawFee = (state.plannedPrice.trim().toDoubleOrNull() ?: 0.0) *
                                    (state.quantity.trim().toDoubleOrNull() ?: 0.0) * rateVal / 10000.0
                                val minApplied = rateVal > 0.0 && rawFee < TradeForms.MIN_FEE
                                Text(
                                    "= ${Format.money(feeAmt, curSymbol)}" +
                                        if (minApplied) "（不足 5 元按 5 元计）" else "",
                                    fontSize = pageSp(11f),
                                    color = StockNoteColors.TextTertiary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- 计划金额（只读联动）----
        item {
            val amount = TradeForms.totalAmountOf(
                state.side.name,
                state.plannedPrice,
                state.quantity,
                state.feeRate,
            )
            // 老周 2026-09-19：标题与金额**分两行**（一行时金额被标题挤窄，长数字要缩字号）
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFDDE7FB))
                    .border(1.dp, Color(0xFFCFE0FC), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    "计划金额（成交额 ± 手续费）",
                    fontSize = pageSp(13f),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF20488F),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    amount?.let { Format.money(it, curSymbol) } ?: "$curSymbol—",
                    fontSize = pageSp(22f),
                    fontWeight = FontWeight.ExtraBold,
                    color = StockNoteColors.BrandDark,
                )
            }
        }

        // ---- 计划理由（选填）----
        item {
            SectionCard(title = "计划理由") {
                MultiLineField(
                    label = "计划理由 / 备注（选填）",
                    value = state.note,
                    onValueChange = holder::setNote,
                    placeholder = "例如：跌到年线附近分批接，第一笔先用 3 成仓…",
                    maxLength = 500,
                )
            }
        }

        // ---- 策略标签 ----
        item {
            SectionCard(title = "策略标签") {
                TagChips(
                    // 同记一笔（老周 2026-09-19）：标签库为空时列出预置 6 个，而不是只剩「＋ 新建」
                    options = state.tagOptions.ifEmpty { DEFAULT_PLAN_TAGS },
                    selected = state.tags,
                    onToggle = holder::toggleTag,
                    onCreate = holder::createTagAndSelect,
                )
            }
        }

        // ---- 保存 ----
        item {
            Button(
                onClick = holder::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(
                        if (isEdit) "保存修改" else "保存计划",
                        fontSize = pageSp(15f),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // ---- 删除（仅编辑态）----
        if (isEdit) {
            item {
                var confirm by remember { mutableStateOf(false) }
                Button(
                    onClick = { confirm = true },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) { Text("删除计划", fontSize = pageSp(15f), fontWeight = FontWeight.Medium, color = Color.White) }

                if (confirm) {
                    AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = {
                            Text("确认删除这笔计划？", fontSize = pageSp(16f), fontWeight = FontWeight.SemiBold)
                        },
                        text = { Text("删除后不可恢复。计划本就不在账本里，删除不影响持仓与盈亏。", fontSize = pageSp(13f)) },
                        confirmButton = {
                            TextButton(onClick = { confirm = false; holder.delete() }) {
                                Text("删除", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirm = false }) { Text("取消") }
                        },
                    )
                }
            }
        }

        item {
            Text(
                if (isEdit) "编辑态才有「删除计划」· 删除后不可恢复" else "计划不进账本 · 不影响持仓与盈亏",
                fontSize = pageSp(10f),
                color = StockNoteColors.TextTertiary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        state.error?.let {
            item { ErrorBanner(message = it, onRetry = { }) }
        }
    }

    // 保存/删除失败弹窗（老周 2026-09-18：保存类按钮出错必弹窗）
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = state.issues.map { it.message } + listOfNotNull(state.error),
        title = "无法保存",
    )
}

/** 折扣下拉：9 档（4折 ~ 1.2倍）；未选择显示「折扣 ▾」 */
@Composable
private fun DiscountDropdown(selected: Double?, onSelect: (Double?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = selected?.let { d -> PlanPricing.DISCOUNT_OPTIONS.firstOrNull { it.second == d }?.first }
    Box {
        // 高度与圆角对齐 Material3 的 OutlinedTextField（56dp / 4dp），这样与左侧「成交价」输入框齐平
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(1.dp, StockNoteColors.Divider, RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label ?: "折扣",
                    fontSize = pageSp(15f),
                    maxLines = 1,
                    color = if (label == null) StockNoteColors.TextTertiary else StockNoteColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text("▾", fontSize = pageSp(12f), color = StockNoteColors.TextTertiary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlanPricing.DISCOUNT_OPTIONS.forEach { (text, value) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text, fontSize = pageSp(13f), fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal)
                            Spacer(Modifier.width(8.dp))
                            Text("×$value", fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
                        }
                    },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

/** 策略标签 chips + 「＋ 新建」 */
@Composable
private fun TagChips(
    options: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { name ->
            val on = name in selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) StockNoteColors.Brand else Color(0xFFF3F6FB))
                    .clickable { onToggle(name) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    name,
                    fontSize = pageSp(12f),
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) Color.White else StockNoteColors.TextSecondary,
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(0.5.dp, StockNoteColors.Divider, RoundedCornerShape(20.dp))
                .clickable { showCreate = true }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) { Text("＋ 新建", fontSize = pageSp(12f), color = StockNoteColors.Brand) }
    }
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建策略标签", fontSize = pageSp(16f), fontWeight = FontWeight.Bold) },
            text = {
                LabeledField(label = "标签名", value = newName, onValueChange = { newName = it }, placeholder = "如 价值投资")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) onCreate(newName)
                    newName = ""
                    showCreate = false
                }) { Text("添加", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与统计页一致；未包的页面默认 1f 不受影响）。 */
@Composable
fun PlanEditScreen(
    holder: PlanEditHolder,
    /** 新建成功 / 编辑保存 / 删除完成后的返回 */
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        PlanEditScreenContent(holder, onDone, onBack, modifier)
    }
}
