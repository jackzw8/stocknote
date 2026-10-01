package com.stocknote.feature.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.TradeForms
import com.stocknote.core.format.Format
import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.state.TradeFormHolder
import com.stocknote.feature.theme.StockNoteColors
import com.stocknote.feature.theme.pnlColor

/**
 * 可记账的市场。
 *
 * ⚠️ `FUND`（场外基金）2026-09-20 才进来（老周：场外基金也要能记）。
 * 它缺省是**账外备忘**（「不计入统计与分析」打开），见下方开关。
 */
private val MARKET_OPTIONS = listOf(Market.A_SHARE, Market.ETF, Market.HK, Market.US, Market.FUND)

/** 情绪枚举（REQ-NOTE-02，与技术说明书 4.3 一致，可扩展） */
private val EMOTIONS = listOf("恐惧", "贪婪", "冷静", "犹豫", "自信")

/** 预置策略标签（REQ-NOTE-04；标签管理 UI 在后续里程碑） */
private val TAGS = listOf("价值投资", "突破", "网格", "趋势", "波段", "打板")

/**
 * 记一笔 / 编辑（REQ-ACC-01 / REQ-ACC-09）。
 * 同一套表单两个形态，只差「标的名是否只读」。
 */
@Composable
private fun TradeFormScreenContent(
    holder: TradeFormHolder,
    securityId: String?,
    editTxId: String?,
    onSaved: () -> Unit,
) {
    val state by holder.state.collectAsState()

    LaunchedEffect(securityId, editTxId) {
        holder.start(securityId, editTxId)
        // 老周 2026-09-28：读入设置里的「整手校验」开关（缺省开）
        holder.refreshLotCheck()
        // 情绪选项（预设 + 自定义），A9
        holder.loadEmotions()
        // 编辑态：载入已有截图供预览（REQ-ACC-11）—— 老周 2026-09-17
        if (editTxId != null) holder.loadExistingPhoto()
    }

    // 币种展示：直接取枚举的 code/symbol（CNY→¥、HKD→HK$、USD→$）——
    // 原实现只判 HKD/其它，美股会被错标成 CNY（老周 2026-09-19 报币种文字不跟着变）
    val formCurrencyLabel = state.formCurrency.code
    val formCurrencySymbol = state.formCurrency.symbol

    // 数量单位一律走 Market.quantityUnit（老周 2026-09-21）：股票/场内 ETF = 股，场外基金 = 份。
    // 此前页面里写死了「股」，给基金记一笔时会显示「当前最多可卖 1200 股」。
    // 市场取法与上方「记账外」开关同一逻辑（见 fundInContext）：已选/编辑态用标的市场，
    // 还在新建标的时用表单里选的市场。
    val quantityUnit = when {
        state.locked -> state.selectedMarket?.quantityUnit ?: "股"
        state.showNewSecurity -> state.newMarket.quantityUnit
        else -> state.selectedMarket?.quantityUnit ?: "股"
    }

    // 每手规则（老周 2026-09-28）：市场取法同上；A股/ETF/港股 = 100，美股/场外基金无约束。
    val lotMarket = when {
        state.locked -> state.selectedMarket
        state.showNewSecurity -> state.newMarket
        else -> state.selectedMarket
    }
    val lotHint = lotMarket?.let { m ->
        com.stocknote.core.calc.LotRule.hintOf(m, state.selectedLotSize)
    }

    // 保存成功且无警示：主线程自动返回（老周定稿，不再多按「完成」）；
    // 有超卖警示：停留展示，由「完成」按钮手动返回。
    // 删除完成（编辑态）：同样自动返回上一页。
    LaunchedEffect(state.saved, state.warning, state.deleted) {
        if (state.deleted) {
            onSaved()
            return@LaunchedEffect
        }
        if (state.saved && state.warning == null) {
            onSaved()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(title = if (state.editTxId != null) "编辑交易" else "记一笔") { AppNav.pop() } }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { AppNav.pop() }) } }

        // ---- 标的 ----
        item {
            SectionCard(title = if (state.locked) "标的（已锁定）" else "选择标的") {
                if (state.locked) {
                    Text(
                        text = state.securityLabel,
                        fontSize = pageSp(15f),
                        fontWeight = FontWeight.Medium,
                        color = StockNoteColors.TextPrimary,
                    )
                    Text(
                        text = "编辑与预填态下标的不可更改；要换标的请删除这笔后重录。",
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
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
                            // 自动化测试锚点（与 app_settings_button 同套路）：「搜索」在页面里出现两次
                            //（区块标题 + 按钮），用文字定位会误点，故用无障碍语义精确定位。
                            modifier = Modifier
                                .testTag("搜索标的")
                                .semantics { contentDescription = "搜索标的" },
                        ) {
                            Text("搜索", fontSize = pageSp(13f))
                        }
                    }

                    if (state.showNewSecurity) {
                        Spacer(Modifier.height(12.dp))
                        LabeledField(
                            label = "代码",
                            value = state.newSymbol,
                            onValueChange = holder::setNewSymbol,
                            placeholder = "如 sh600519 / hk00700 / usAAPL",
                        )
                        Spacer(Modifier.height(8.dp))
                        LabeledField(
                            label = "名称",
                            value = state.newName,
                            onValueChange = holder::setNewName,
                            placeholder = "如 贵州茅台",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("市场", fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        ChipRow(
                            options = MARKET_OPTIONS,
                            selectedIndex = MARKET_OPTIONS.indexOf(state.newMarket),
                            onSelect = { holder.setNewMarket(MARKET_OPTIONS[it]) },
                            labelOf = { it.label },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("币种", fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        ChipRow(
                            options = listOf(Currency.CNY, Currency.HKD, Currency.USD),
                            selectedIndex = listOf(Currency.CNY, Currency.HKD, Currency.USD)
                                .indexOf(state.newCurrency),
                            onSelect = {
                                holder.setNewCurrency(listOf(Currency.CNY, Currency.HKD, Currency.USD)[it])
                            },
                            labelOf = { it.code },
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = holder::createAndPick) {
                            Text("保存并选用", fontSize = pageSp(13f))
                        }
                    } else {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "找不到？手动新增标的 ›",
                            fontSize = pageSp(13f),
                            color = StockNoteColors.Brand,
                            modifier = Modifier.clickable { holder.toggleNewSecurity() },
                        )
                    }

                    // V-02：未选标的就保存时，红字提示（修复「生成了 issue 但 UI 从不显示」）
                    if (!state.locked && state.issues.any { it.field == "security" }) {
                        Spacer(Modifier.height(8.dp))
                        FieldError(
                            state.issues.first { it.field == "security" }.message,
                        )
                    }

                    if (state.candidates.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        // 空关键词点搜索 → 这批是「最近用过的标的」（老周 2026-09-21），标题如实写清
                        if (state.recentMode) {
                            Text(
                                "最近用过的标的（点击直接选中）",
                                fontSize = pageSp(11f),
                                color = StockNoteColors.TextTertiary,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                        state.candidates.forEach { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { holder.pick(candidate) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = candidate.name,
                                    fontSize = pageSp(14f),
                                    color = StockNoteColors.TextPrimary,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "${candidate.symbol} · ${candidate.market.label}",
                                    fontSize = pageSp(11f),
                                    color = StockNoteColors.TextTertiary,
                                )
                            }
                        }
                    }

                    if (state.recentMode && !state.searching && state.candidates.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "还没有可以「最近使用」的标的（记过账的会出现在这里）；也可以直接搜代码。",
                            fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                        )
                    }

                    if (state.searchingOnline) {
                        Spacer(Modifier.height(8.dp))
                        Text("联网搜索中…", fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
                    } else if (state.onlineHits.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "联网结果（点选后自动建档）",
                            fontSize = pageSp(11f),
                            color = StockNoteColors.TextTertiary,
                        )
                        state.onlineHits.forEach { hit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { holder.pickOnline(hit) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(hit.name, fontSize = pageSp(14f), color = StockNoteColors.TextPrimary)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${hit.symbol} · ${hit.marketLabel}",
                                    fontSize = pageSp(11f),
                                    color = StockNoteColors.TextTertiary,
                                )
                            }
                        }
                    }
                }
                issueFor(state, "newSecurity")?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = pageSp(11f), color = StockNoteColors.Up)
                }
            }
        }

        // ---- 场外基金：是否计入统计与分析（老周 2026-09-20）----
        // 只对**基金**显示（股票 / 场内 ETF 恒为账内，没有第二选项）；
        // 新建标的时按 newMarket 判断，已选/编辑态按标的真实市场判断。
        val fundInContext = (state.locked && state.selectedMarket == Market.FUND) ||
            (!state.locked && state.showNewSecurity && state.newMarket == Market.FUND)
        if (fundInContext) {
            item {
                SectionCard(title = "统计与分析") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "计入统计与分析",
                                fontSize = pageSp(14f),
                                fontWeight = FontWeight.Medium,
                                color = StockNoteColors.TextPrimary,
                            )
                            Text(
                                text = if (state.excludeFromStats) {
                                    "已关闭（基金缺省）：完全账外 —— 买入不扣现金、卖出不加现金，" +
                                        "市值不进总资产 / 资产曲线 / 收益率；这里只单独看它自己的份额与盈亏。"
                                } else {
                                    "已开启：与股票同口径，计入现金、总资产、资产曲线与收益率统计。"
                                },
                                fontSize = pageSp(11f),
                                color = StockNoteColors.TextTertiary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = !state.excludeFromStats,
                            onCheckedChange = { holder.setExcludeFromStats(!it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = StockNoteColors.Brand,
                                checkedBorderColor = StockNoteColors.Brand,
                            ),
                        )
                    }
                }
            }
        }

        // ---- 交易内容 ----
        item {
            SectionCard(title = "交易内容") {
                Text("方向", fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = listOf(TradeSide.BUY, TradeSide.SELL),
                    selectedIndex = if (state.side == TradeSide.BUY) 0 else 1,
                    onSelect = { holder.setSide(if (it == 0) TradeSide.BUY else TradeSide.SELL) },
                    labelOf = { it.label },
                )
                Spacer(Modifier.height(12.dp))
                LabeledField(
                    // 老周 2026-09-28：数量旁标出每手股数（如「股 · 100股/手」），便于录入时对照
                    label = "数量（$quantityUnit${lotHint?.let { " · $it" } ?: ""}）",
                    value = state.quantity,
                    onValueChange = holder::setQuantity,
                    placeholder = "如 100",
                    numeric = true,
                    errorText = issueFor(state, "quantity"),
                )
                // 整手校验的**即时**提示（老周 2026-09-28）：买入/卖出都提示，不必等到点保存。
                // 保存时同样会拦（见 TradeFormHolder.save）。
                val lotMsg = state.quantity.trim().toDoubleOrNull()
                    ?.let { q -> lotMarket?.let { m -> com.stocknote.core.calc.LotRule.check(q, m, state.selectedLotSize, state.lotCheckEnabled) } }
                if (lotMsg != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "⚠️ $lotMsg",
                        fontSize = pageSp(11.5f),
                        color = StockNoteColors.Down,
                    )
                }
                // 卖出时提示最多可卖，并在超出时即时警告（老周 2026-09-16）
                if (state.side == TradeSide.SELL) {
                    val maxQ = state.maxSellable
                    val inputQ = state.quantity.trim().toDoubleOrNull()
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // ⚠️ 编辑态的上限已**剔除本笔**（含本笔在内可填的最大值），
                        // 文案要说清，否则"清仓卖出"编辑时会显示"最多可卖 0"吓人一跳（老周 2026-09-29）
                        text = if (maxQ == null) "正在读取当前持仓…"
                        else if (state.editTxId != null) "本笔在内，最多可卖 ${Format.quantity(maxQ)} $quantityUnit"
                        else "当前最多可卖 ${Format.quantity(maxQ)} $quantityUnit",
                        fontSize = pageSp(11.5f),
                        color = StockNoteColors.TextSecondary,
                    )
                    if (maxQ != null && inputQ != null && inputQ > maxQ) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "⚠️ 超出可卖数量：最多可填 ${Format.quantity(maxQ)} $quantityUnit，请调整（超出部分不会成交）",
                            fontSize = pageSp(11.5f),
                            color = StockNoteColors.Down,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 成交价：港股标注 HKD，A股/其它标注 CNY（老周 2026-09-16）
                LabeledField(
                    label = "成交价（${formCurrencyLabel}）",
                    value = state.price,
                    onValueChange = holder::setPrice,
                    placeholder = "每股价格",
                    numeric = true,
                    errorText = issueFor(state, "price"),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        LabeledField(
                            label = "手续费率（万分之，必填）",
                            value = state.feeRate,
                            onValueChange = holder::setFeeRate,
                            placeholder = "填 2.5 即万分之 2.5",
                            numeric = true,
                            errorText = issueFor(state, "fee"),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // 清零按钮（老周 2026-09-16：免佣场景一键设 0）
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(StockNoteColors.Brand.copy(alpha = 0.10f))
                            .clickable { holder.clearFeeRate() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text("清零", fontSize = pageSp(12f), color = StockNoteColors.Brand, fontWeight = FontWeight.Bold)
                    }
                }
                // 换算提示：按币种符号（港股 HK$、A股 ¥）
                TradeForms.feeAmountOf(state.price, state.quantity, state.feeRate)?.let { feeAmt ->
                    if (feeAmt > 0) {
                        // 佣金下限（老周 2026-09-19）：命中时明确告知，避免用户以为费率算错了
                        val rateVal = state.feeRate.trim().toDoubleOrNull() ?: 0.0
                        val rawFee = (state.price.trim().toDoubleOrNull() ?: 0.0) *
                            (state.quantity.trim().toDoubleOrNull() ?: 0.0) * rateVal / 10000.0
                        val minApplied = rateVal > 0.0 && rawFee < TradeForms.MIN_FEE
                        Text(
                            "= ${formCurrencySymbol}${Format.money(feeAmt, "")}" +
                                if (minApplied) "（不足 5 元按 5 元计）" else "",
                            fontSize = pageSp(11f),
                            color = StockNoteColors.TextTertiary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                // ---- 印花税（REQ-ACC-17，老周 2026-09-30）：**只展示、不可改** ----
                // 规则：A 股买入不收 / 卖出万分之 5；港股买卖各千分之 1；ETF / 基金 / 美股不收。
                // 金额由 holder.stampDutyOf() 按市场+方向+设置税率现算，保存时用同一个函数。
                if (state.selectedMarket != null) {
                    val duty = holder.stampDutyOf()
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF3F6FB))
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "印花税（自动计算 · 不可修改）",
                                fontSize = pageSp(12f),
                                color = StockNoteColors.TextSecondary,
                            )
                            Text(
                                if (duty > 0) "随本笔收付，已计入下方总价与现金" else "本市场 / 本方向不收印花税",
                                fontSize = pageSp(11f),
                                color = StockNoteColors.TextTertiary,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Text(
                            "$formCurrencySymbol${Format.money(duty, "")}",
                            fontSize = pageSp(14f),
                            fontWeight = FontWeight.Bold,
                            color = if (duty > 0) StockNoteColors.TextPrimary else StockNoteColors.TextTertiary,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                DateField(
                    label = "交易日期",
                    value = state.date,
                    onValueChange = holder::setDate,
                    maxIso = com.stocknote.data.platform.todayIso(),
                    errorText = issueFor(state, "date"),
                )
                // 成交日汇率（REQ-ACC-15 路径 1，老周 2026-09-19）：**只对非本位币标的显示**。
                // 按交易日期自动带出市场汇率；想跟券商账单对齐就手改成结算价。
                if (state.formCurrency != Currency.CNY) {
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "成交日汇率（1 $formCurrencyLabel = ? CNY）",
                        value = state.fxRate,
                        onValueChange = holder::setFxRate,
                        placeholder = if (state.fxRateLoading) "自动带出中…" else "按交易日期自动带出，可手改",
                        numeric = true,
                    )
                    Text(
                        "用于把这笔交易折算成人民币：默认取成交日的市场汇率，可改成券商交割单上的结算价。",
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                MultiLineField(
                    label = "交易理由 / 备注（必填）",
                    value = state.note,
                    onValueChange = holder::setNote,
                    placeholder = "当时的想法、依据…",
                    maxLength = 500,
                    errorText = issueFor(state, "note"),
                )
                // 总价只读联动（原型 03）：买入 = 成交额 + 手续费；卖出 = 成交额 − 手续费。
                // 手续费含**佣金下限**（不足 5 元按 5 元），与 feeAmountOf 同源，公式文案随之改准确。
                // 总价含**印花税**（REQ-ACC-17）：与上面展示的金额、以及入库的现金联动三者同源
                TradeForms.totalAmountOf(
                    state.side.name, state.price, state.quantity, state.feeRate,
                    stampDuty = holder.stampDutyOf(),
                )?.let { total ->
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFEAF1FE))
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (state.side == TradeSide.BUY) "总价（成交额 + 手续费 + 印花税）"
                            else "总价（成交额 − 手续费 − 印花税）",
                            fontSize = pageSp(11.5f),
                            color = StockNoteColors.TextSecondary,
                            // 允许换行（老周 2026-09-16）：文字长时折行，不挤占右侧金额
                            maxLines = 2,
                            lineHeight = pageSp(15f),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${formCurrencySymbol}${Format.money(total, "")}",
                            maxLines = 1,
                            fontSize = pageSp(14f),
                            fontWeight = FontWeight.ExtraBold,
                            color = StockNoteColors.BrandDark,
                        )
                    }
                }
            }
        }

        // ---- 交易截图（REQ-ACC-11，老周 2026-09-17）----
        // 系统文件选择器选图 → Android 侧压缩为 JPEG（最长边 1600 / 质量 85）→ BLOB 入库。
        // 说明：截图**不参与加密备份**（备份是 SQL 文本 dump，BLOB 需额外编码，本期从简）。
        item {
            SectionCard(title = "交易截图") {
                val photo = state.photo
                if (photo == null) {
                    Text(
                        if (state.photoRemoved) {
                            "已移除原有截图（保存后生效）"
                        } else {
                            "可附一张成交截图，便于日后复盘；选图后自动压缩，仅存本机加密库。"
                        },
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                    )
                    Spacer(Modifier.height(8.dp))
                    GhostButton(
                        if (state.photoBusy) "选择中…" else "📷 添加截图",
                        onClick = { holder.pickPhoto() },
                    )
                } else {
                    Text(
                        "已添加：${photo.size / 1024} KB",
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    val bmp = state.photoBitmap
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = "交易截图",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("重新选择", onClick = { holder.pickPhoto() })
                        GhostButton("移除", onClick = { holder.removePhoto() })
                    }
                }
            }
        }

        // ---- 复盘要素（REQ-NOTE-02/04/05）：情绪 + 评分 + 标签 ----
        item {
            SectionCard(title = "复盘要素") {
                Text("情绪", fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                // 情绪：预设 5 个 + 用户自定义（A9，老周 2026-09-16 确认做）
                val emotions = state.emotions.ifEmpty { EMOTIONS }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    emotions.forEach { name ->
                        val on = name == state.emotion
                        val isCustom = name in state.customEmotions
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (on) StockNoteColors.Brand else Color(0xFFF3F6FB))
                                // 自定义情绪：长按删除（预设不可删）
                                .then(
                                    if (isCustom) Modifier.combinedClickable(
                                        onClick = { holder.setEmotion(if (on) null else name) },
                                        onLongClick = { holder.removeEmotion(name) },
                                    ) else Modifier.clickable {
                                        holder.setEmotion(if (on) null else name)
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                name + if (isCustom) " ✕" else "",
                                fontSize = pageSp(12f),
                                color = if (on) Color.White else StockNoteColors.TextSecondary,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                    // ＋ 自定义
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(StockNoteColors.Brand.copy(alpha = 0.10f))
                            .clickable { holder.toggleEmotionInput() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text("＋ 自定义", fontSize = pageSp(12f), color = StockNoteColors.Brand, fontWeight = FontWeight.Bold)
                    }
                }
                if (state.showEmotionInput) {
                    var text by remember { mutableStateOf("") }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            LabeledField(
                                label = "情绪名称",
                                value = text,
                                onValueChange = { text = it },
                                placeholder = "最多 6 字",
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(StockNoteColors.Brand)
                                .clickable { holder.addEmotion(text) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) { Text("添加", fontSize = pageSp(12f), color = Color.White, fontWeight = FontWeight.Bold) }
                    }
                    if (state.emotionError != null) {
                        Text(
                            state.emotionError!!,
                            fontSize = pageSp(11f),
                            color = StockNoteColors.Down,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        "长按自定义情绪可删除",
                        fontSize = pageSp(10.5f),
                        color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("执行评分（1-5）", fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                Row {
                    (1..5).forEach { star ->
                        Text(
                            text = if (star <= state.score) "⭐" else "☆",
                            fontSize = pageSp(26f),
                            modifier = Modifier
                                .clickable { holder.setScore(star) }
                                .padding(end = 8.dp),
                        )
                    }
                    if (state.score > 0) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${state.score} 分",
                            fontSize = pageSp(12f),
                            fontWeight = FontWeight.Bold,
                            color = StockNoteColors.Brand,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("策略标签（可多选）", fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                val opts = state.tagOptions.ifEmpty { TAGS }
                opts.chunked(4).forEach { rowTags ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        rowTags.forEach { t -> TagChip(t, t in state.tags) { holder.toggleTag(t) } }
                        // 每行末尾留「＋ 新建」只在最后一行
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagChip("＋ 新建", false) { holder.toggleTagCreate() }
                }
            }
        }

        state.warning?.let { warn ->
            item { InfoCard(title = "提示", message = warn, tone = Color(0xFFFAEEDA), textColor = Color(0xFF854F0B)) }
        }

        if (state.saved) {
            // 保存成功后停在原页，让用户看到「超卖已截断」这类提示，再手动返回
            item {
                Button(
                    onClick = onSaved,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Down),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("完成", fontSize = pageSp(15f), fontWeight = FontWeight.Medium)
                }
            }
        } else {
            item {
                Button(
                    // 保存落 saved 态；无警示由 LaunchedEffect 自动 onSaved 返回，
                    // 有超卖警示走上方「完成」按钮返回
                    onClick = { holder.save() },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Brand),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            if (state.editTxId != null) "保存修改" else "保存这笔交易",
                            fontSize = pageSp(15f),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // ---- 删除交易（**仅编辑态**）----
        // 老周 2026-09-19：与交易计划的操作方式统一 —— 交易记录列表**不再左滑**，
        // 也没有行内删除；删除入口只有这一处（本页底部红色按钮）。
        // ⚠️ 点它不会直接删：先弹「删除预告」（重算前后的持仓/成本差异），确认才落库。
        if (state.editTxId != null) {
            item {
                Button(
                    onClick = { holder.requestDelete() },
                    enabled = !state.deleting && !state.saving,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (state.deleting) "删除中…" else "删除交易",
                        fontSize = pageSp(15f),
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
            }
            item {
                Text(
                    "删除不可恢复；持仓由剩余流水全量重放，确认前会先展示重算结果。",
                    fontSize = pageSp(10f),
                    color = StockNoteColors.TextTertiary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    // 表单内快捷新建标签（小B 定稿 2026-09-14，原型 03 的「＋ 新建」）
    if (state.showTagCreate) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { holder.toggleTagCreate() },
            title = { Text("新建策略标签", fontSize = pageSp(16f), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("新建后自动勾选，也会出现在标签管理页。", fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "标签名",
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "如 龙头战法",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { holder.createTagAndSelect(newName) },
                    enabled = newName.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { holder.toggleTagCreate() }) { Text("取消") }
            },
        )
    }

    // 删除预告弹窗（REQ-ACC-09）：先展示重算前后的持仓/成本差异，确认才真正删库。
    // 老周 2026-09-19：弹窗由详情页移到编辑页（删除入口统一在这里）。
    state.deletePreview?.let { preview ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(enabled = false) { },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = StockNoteColors.Surface),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = "确认删除这笔交易？",
                        fontSize = pageSp(16f),
                        fontWeight = FontWeight.SemiBold,
                        color = StockNoteColors.TextPrimary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "将删除 ${Format.quantity(preview.removedQuantity)} ${preview.before.market.quantityUnit} @ " +
                            Format.money(preview.removedPrice, preview.before.currency.symbol),
                        fontSize = pageSp(13f),
                        color = StockNoteColors.TextSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = deletePreviewSummary(preview),
                        fontSize = pageSp(12f),
                        color = StockNoteColors.TextPrimary,
                        lineHeight = pageSp(18f),
                    )
                    Text(
                        text = "已实现盈亏变化 " +
                            Format.moneySigned(preview.realizedPnlDelta, preview.before.currency.symbol),
                        fontSize = pageSp(12f),
                        color = pnlColor(preview.realizedPnlDelta),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "删除不可恢复；持仓由剩余流水全量重放，无需手工修正。",
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { holder.cancelDelete() },
                            enabled = !state.deleting,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Surface),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("取消", fontSize = pageSp(14f), color = StockNoteColors.TextPrimary)
                        }
                        Button(
                            onClick = { holder.confirmDelete() },
                            enabled = !state.deleting,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Up),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                if (state.deleting) "删除中…" else "确认删除",
                                fontSize = pageSp(14f),
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }

    // 保存 / 修改失败弹窗（老周 2026-09-18）：失败时**明确弹出**，避免「点了按钮没反应」的错觉
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = state.issues.map { it.message } + listOfNotNull(state.error),
    )
}

private fun issueFor(state: TradeFormHolder.UiState, field: String): String? =
    state.issues.firstOrNull { it.field == field }?.message

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) StockNoteColors.Brand else Color.White)
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = pageSp(12f),
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else StockNoteColors.TextSecondary,
        )
    }
}

@Composable
internal fun TopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(10.dp))
                .clickable { onBack() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("‹", fontSize = pageSp(20f), color = StockNoteColors.Brand)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            fontSize = pageSp(18f),
            fontWeight = FontWeight.SemiBold,
            color = StockNoteColors.TextPrimary,
        )
    }
}

/**
 * 删除预告摘要：把重算前后的差异说清楚，用户不用自己心算。
 * 与实际删除走同一个 `PositionCalculator.replay`，所以预告必然等于实况。
 */
private fun deletePreviewSummary(preview: com.stocknote.core.model.DeletePreview): String {
    val b = preview.before
    val a = preview.after
    return "删除后：${Format.quantity(a.quantity)} ${b.market.quantityUnit} · 成本均价 " +
        "${Format.money(a.avgCost, b.currency.symbol)}（原 ${Format.money(b.avgCost, b.currency.symbol)}）"
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与统计页一致；未包的页面默认 1f 不受影响）。 */
@Composable
fun TradeFormScreen(
    holder: TradeFormHolder,
    securityId: String?,
    editTxId: String?,
    onSaved: () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        TradeFormScreenContent(holder, securityId, editTxId, onSaved)
    }
}
