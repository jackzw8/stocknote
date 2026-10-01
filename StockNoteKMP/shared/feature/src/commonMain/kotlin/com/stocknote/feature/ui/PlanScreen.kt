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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.PlanPricing
import com.stocknote.core.format.Format
import com.stocknote.feature.state.PlanHolder
import com.stocknote.feature.theme.StockNoteColors

/**
 * 交易计划列表页 —— 对照 `界面原型-2.0/plan.html`（REQ-PLAN-01）。
 *
 * ⚠️ 计划**不是交易**：不写入账本、不影响持仓与盈亏，页面底部有明确说明。
 * 列表只负责「点行进编辑」；**删除不做左滑**（老周 2026-09-19 取消），统一走编辑页底部按钮。
 */
@Composable
private fun PlanScreenContent(
    holder: PlanHolder,
    refreshKey: Int,
    onNewPlan: () -> Unit,
    onEditPlan: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by holder.state.collectAsState()

    LaunchedEffect(refreshKey) { holder.load() }

    // 搜索展开态（老周 2026-09-19：与持仓页同款，🔍 展开后按名称/代码过滤计划）
    var searchVisible by remember { mutableStateOf(false) }

    // 带着搜索词进来时（详情页「🎯 查看计划」跳转）自动展开搜索框，否则用户看不到"正在搜什么"
    LaunchedEffect(state.searchText) {
        if (state.searchText.isNotBlank()) searchVisible = true
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // ---- 顶部栏：标题 + 「＋ 新建计划」 ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("交易计划", fontSize = pageSp(21f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(StockNoteColors.Brand)
                        .clickable { onNewPlan() }
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                        .semantics { contentDescription = "新建计划" },
                ) {
                    Text("＋ 新建计划", fontSize = pageSp(12f), fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                // 🔍 搜索（老周 2026-09-19：与持仓页同款，展开后按名称/代码过滤）
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (searchVisible) StockNoteColors.Brand else Color.White)
                        .clickable {
                            searchVisible = !searchVisible
                            if (!searchVisible) holder.setSearchText("")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🔍", fontSize = pageSp(14f), modifier = Modifier.semantics { contentDescription = "搜索计划" })
                }
            }
        }

        // ---- 搜索框（展开时，与持仓页同款）----
        if (searchVisible) {
            item {
                LabeledField(
                    label = "搜索计划",
                    value = state.searchText,
                    onValueChange = holder::setSearchText,
                    placeholder = "名称或代码",
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.load() }) } }

        if (state.loading && state.allRows.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp))
                }
            }
        }

        // ---- 汇总：计划金额合计 + 进行中计划 ----
        // 老周 2026-09-22：两张卡**分两行上下堆叠**（他说的「分两行」= 上下区块，不是左右并排），
        // 计划金额合计在上、进行中计划在下。
        if (state.allRows.isNotEmpty()) {
            item {
                val doneAll = state.allRows.count { it.plan.isDone }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    // 金额合计不用 MetricCell：它的 value 只有一行，多币种串必然被省略号截断
                    // （真机截图：「¥121,503.…」，港币部分整个看不见）。改成每币种一行。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(StockNoteColors.Surface)
                            .padding(horizontal = 15.dp, vertical = 14.dp),
                    ) {
                        Text("计划金额合计", fontSize = pageSp(11f), color = StockNoteColors.TextSecondary)
                        Spacer(Modifier.height(7.dp))
                        if (state.totals.isEmpty()) {
                            Text("—", fontSize = pageSp(16f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                        } else {
                            val symbolOf = { code: String ->
                                com.stocknote.core.model.Currency.entries
                                    .firstOrNull { it.code == code }?.symbol ?: "¥"
                            }
                            if (state.splitTotals.isNotEmpty()) {
                                // 「全部」筛选：每币种一行，买入/卖出分列（老周 2026-09-22）。
                                // 两栏各占一半宽（weight），单边金额再长也只在自己那栏截断，互不挤压。
                                // 标签颜色与列表行的 买/卖 pill 一致（买=Up 红、卖=Down 绿）。
                                // ⚠️ 2026-10-01：`toSortedMap()` 是 **JVM 专有扩展**（返回 java.util.SortedMap），
                                // Kotlin/Native 上直接 `Unresolved reference`（且会连锁引发 6 条级联报错）。
                                // 改用跨平台的 `entries.sortedBy { it.key }` —— 同样是按币种 code 升序，语义不变。
                                state.splitTotals.entries.sortedBy { it.key }.forEach { (code, bs) ->
                                    val symbol = symbolOf(code)
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("买", fontSize = pageSp(11f), fontWeight = FontWeight.Bold, color = StockNoteColors.Down)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            // 老周 2026-09-28：计划金额合计以「万」为单位、保留 1 位小数
                                            "$symbol${com.stocknote.core.format.Format.wan(bs.first, 1)}",
                                            fontSize = pageSp(14.5f),
                                            fontWeight = FontWeight.ExtraBold,
                                            color = StockNoteColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text("卖", fontSize = pageSp(11f), fontWeight = FontWeight.Bold, color = StockNoteColors.Up)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            // 老周 2026-09-28：同「买」列，以「万」为单位、1 位小数
                                            "$symbol${com.stocknote.core.format.Format.wan(bs.second, 1)}",
                                            fontSize = pageSp(14.5f),
                                            fontWeight = FontWeight.ExtraBold,
                                            color = StockNoteColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            } else {
                                state.totals.forEach { (code, amount) ->
                                    Text(
                                        // 老周 2026-09-28：以「万」为单位、保留 1 位小数
                                        "${symbolOf(code)}${com.stocknote.core.format.Format.wan(amount, 1)}",
                                        fontSize = pageSp(15f),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = StockNoteColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            // 老周 2026-09-22：合计只统计未完成的（完成的意向已落地，不再占「待执行金额」）；
                            // 「全部」筛选下再按买卖分列
                            "未完成口径 · 按币种" + if (state.splitTotals.isNotEmpty()) " · 买卖分列" else "",
                            fontSize = pageSp(11f),
                            maxLines = 2,
                            color = StockNoteColors.TextTertiary,
                        )
                    }
                    // 进行中计划 = 当前筛选下**未完成**的条数；有已完成的再补一句对照
                    MetricCell(
                        "🎯 进行中计划",
                        "${state.rows.count { !it.plan.isDone }} 条",
                        if (doneAll > 0) "共 ${state.allRows.size} 条 · 已完成 $doneAll 条" else "共 ${state.allRows.size} 条",
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ---- 筛选 / 排序 chips ----
        if (state.allRows.isNotEmpty()) {
            item { ChipStripLite(PlanHolder.Filter.entries.map { it.label }, state.filter.ordinal) { holder.setFilter(PlanHolder.Filter.entries[it]) } }
            item { ChipStripLite(PlanHolder.Sort.entries.map { it.label }, state.sort.ordinal, topPad = 8) { holder.setSort(PlanHolder.Sort.entries[it]) } }
        }

        // ---- 空态 ----
        if (!state.loading && state.allRows.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0xFFDDE7FB)),
                        contentAlignment = Alignment.Center,
                    ) { Text("🎯", fontSize = pageSp(42f)) }
                    Spacer(Modifier.height(18.dp))
                    Text("还没有交易计划", fontSize = pageSp(16f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "给持仓或关注的标的定个目标价与折扣，\n到价时照着计划执行，不被情绪带跑。",
                        fontSize = pageSp(13f),
                        color = StockNoteColors.TextSecondary,
                        lineHeight = pageSp(20f),
                    )
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 26.dp)) {
                    GhostButton("＋ 新建计划", onClick = onNewPlan)
                }
            }
        }

        // ---- 搜索 / 筛选后无匹配 ----
        if (state.allRows.isNotEmpty() && state.rows.isEmpty()) {
            item {
                Sec(title = "计划明细（0）", modifier = Modifier.padding(top = 16.dp)) {
                    CardBox {
                        EmptyHint(
                            if (state.searchText.isNotBlank()) "没有名称或代码匹配「${state.searchText}」的计划。"
                            else "当前筛选下没有计划。",
                        )
                    }
                }
            }
        }

        // ---- 计划明细 ----
        if (state.rows.isNotEmpty()) {
            item {
                Sec(
                    title = "计划明细（${state.rows.size}）",
                    more = "点行进入编辑",
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    CardBox {
                        state.rows.forEachIndexed { i, row ->
                            PlanRowItem(
                                row,
                                onToggleDone = { holder.setDone(row.plan.id, !row.plan.isDone) },
                            ) { onEditPlan(row.plan.id) }
                            if (i != state.rows.lastIndex) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 15.dp)
                                        .height(1.dp)
                                        .background(StockNoteColors.Divider),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- 说明（计划不进账本，必须讲清楚）----
        item {
            Spacer(Modifier.height(16.dp))
            NoteBar("⚠ 计划仅作记录与提醒，不会写入账本、不影响持仓市值与盈亏统计。")
        }
        item {
            ProtoFoot("「需跌 / 需涨」= 当前行情价距计划价的差额百分比 · 行尾圆点可标记/取消完成 · 点行进入编辑 · 删除走编辑页底部「删除计划」")
        }
    }
}

/**
 * 列表一行：头像 + 名称/市场 + 目标→计划 + 数量行（含需涨/需跌）+ 行尾完成圆点。
 *
 * 完成标记（老周 2026-09-22）：行尾圆点点了就标记/取消，不进编辑页；
 * 已完成的行整行置灰、名称旁挂「✓ 已完成」标。
 */
@Composable
private fun PlanRowItem(
    row: PlanHolder.PlanRow,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
) {
    val sec = row.security
    val plan = row.plan
    val discountLabel = plan.discount?.let { d ->
        PlanPricing.DISCOUNT_OPTIONS.firstOrNull { it.second == d }?.first
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像：标的首字，**按市场上色**（老周 2026-09-21，与持仓/自选同一套 marketAccent；
        // 此前红绿底表示买卖方向，但方向已由名称旁的「买/卖」标签表达，不缺这一处）
        // 已完成的行整体置灰（alpha 只压头像与文字两块，行尾圆点保持全亮可点）
        Box(
            modifier = Modifier
                .size(40.dp)
                .alpha(if (plan.isDone) 0.45f else 1f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    sec?.market?.let { com.stocknote.feature.theme.marketAccentSoft(it) } ?: Color(0xFFE8F6EF),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                sec?.name?.take(1) ?: "?",
                fontSize = pageSp(15f),
                fontWeight = FontWeight.ExtraBold,
                color = sec?.market?.let { com.stocknote.feature.theme.marketAccent(it) }
                    ?: StockNoteColors.TextPrimary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (plan.isDone) 0.45f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sec?.name ?: plan.securityId,
                    fontSize = pageSp(14f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.TextPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    // 老周 2026-09-19：列表里只写一个字，避免把标的名称/金额挤窄
                    if (plan.side == com.stocknote.core.model.TradeSide.SELL) "卖" else "买",
                    fontSize = pageSp(10f),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        // 老周 2026-09-25：买卖配色**对调**（买=绿 / 卖=红，与股票涨跌红绿口径相反）
                        .background(if (plan.side == com.stocknote.core.model.TradeSide.SELL) StockNoteColors.Up else StockNoteColors.Down)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (plan.isDone) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "✓ 已完成",
                        fontSize = pageSp(10f),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(StockNoteColors.Brand)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // 金额独立一行（老周 2026-09-21）：股票名和金额分两行展示，金额不再挤在右侧窄列。
            // 仍用**该计划标的的币种符号**：港股计划不能再显示 ¥
            Text(
                Format.money(row.amount, sec?.currency?.symbol ?: "¥"),
                fontSize = pageSp(14f),
                fontWeight = FontWeight.ExtraBold,
                color = StockNoteColors.TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // 行3：计划价（折扣）。老周 2026-09-22：**目标价不在列表展示**（字段仍在，
            // 表单与「取自选/反写」链路照旧），列表只留执行价，一行更清爽也更短。
            Text(
                "计划 ${Format.money(plan.plannedPrice, "")}" +
                    (discountLabel?.let { "（$it）" } ?: ""),
                fontSize = pageSp(11f),
                color = StockNoteColors.TextSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // 行4：数量 · 需涨/需跌（老周 2026-09-22：需涨/需跌不再单独占一列，挪到「多少股」后面；
            // 涨跌颜色只给差额那一段，其余保持次要色）
            // 老周 2026-09-23：**去掉「A股 / 港股」等市场文字**——行更短，且数量单位
            //（股 / 份）已足以区分口径；列表头部的市场信息另有市场标签承担。
            val gap = row.gap
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = StockNoteColors.TextSecondary)) {
                        append("${Format.quantity(plan.quantity)}${sec?.market?.quantityUnit ?: "股"}")
                    }
                    if (gap != null) {
                        withStyle(SpanStyle(color = if (gap >= 0) StockNoteColors.Up else StockNoteColors.Down)) {
                            append(
                                " · ${if (gap >= 0) "需涨" else "需跌"} " +
                                    Format.percent(kotlin.math.abs(gap), signed = false),
                            )
                        }
                    }
                },
                fontSize = pageSp(11f),
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        // 完成标记（老周 2026-09-22）：行尾圆点，点一下标记完成、再点取消 —— 不进编辑页。
        // 完成态 = 品牌色实心 + 白勾；未完成 = 空心圆。
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (plan.isDone) StockNoteColors.Brand else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (plan.isDone) StockNoteColors.Brand else StockNoteColors.TextTertiary,
                    shape = CircleShape,
                )
                .clickable { onToggleDone() }
                .semantics { contentDescription = if (plan.isDone) "取消完成" else "标记完成" },
            contentAlignment = Alignment.Center,
        ) {
            if (plan.isDone) {
                Text("✓", fontSize = pageSp(13f), fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = pageSp(14f), color = StockNoteColors.TextTertiary)
    }
}

/** 轻量 chips（与持仓页同款：等分 + 选中浅蓝底） */
@Composable
private fun ChipStripLite(options: List<String>, selected: Int, topPad: Int = 13, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = topPad.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) Color(0xFFEAF1FE) else Color.White)
                    .clickable { onSelect(i) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = pageSp(12f),
                    fontWeight = FontWeight.Bold,
                    color = if (on) StockNoteColors.BrandDark else StockNoteColors.TextSecondary,
                )
            }
        }
    }
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与统计页一致；未包的页面默认 1f 不受影响）。 */
@Composable
fun PlanScreen(
    holder: PlanHolder,
    refreshKey: Int,
    onNewPlan: () -> Unit,
    onEditPlan: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        PlanScreenContent(holder, refreshKey, onNewPlan, onEditPlan, modifier)
    }
}
