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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.Capitalize
import com.stocknote.core.format.Format
import com.stocknote.core.model.Currency
import com.stocknote.core.model.DividendRecord
import com.stocknote.core.model.Market
import com.stocknote.core.model.Position
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.state.SecurityDetailHolder
import com.stocknote.feature.theme.StockNoteColors
import com.stocknote.feature.theme.pnlColor

/**
 * 标的历史交易页（REQ-VIEW-10）。
 *
 * 两条粒度线（技术说明书 5.13）在这里交汇：
 *   顶部 = 合并持仓概览（读聚合结果）
 *   下方 = 逐笔交易（读 trade 表，日期倒序），每行暴露编辑
 *
 * 新增（REQ-ACC-16，老周 2026-09-20）：**利润转增资本**。
 * 它不是成交，走不了「记一笔」表单，所以新增与撤销都在本页弹窗完成：
 * 输入金额 X → 实时预告（成本均价 / 浮动盈亏 / 总盈亏 前→后）→ 确认落一条 CAPITALIZE 流水；
 * 该流水混在交易记录里，点它即可撤销（删除即回到转增前，重放保证口径一致）。
 */
@Composable
private fun SecurityDetailScreenContent(
    holder: SecurityDetailHolder,
    securityId: String,
    /** 外层数据版本号：编辑/删除后 +1，触发本页重新读取（重放即在这里发生） */
    refreshKey: Int,
    onEdit: (securityId: String, txId: String) -> Unit,
    onAddNew: (securityId: String) -> Unit,
    onAddDividend: (securityId: String) -> Unit,
    /**
     * 查看该标的的交易计划（老周 2026-09-19）：跳到计划 tab，按该标的名称搜索、按「距达成最近」排序。
     * 参数是**标的名称**（计划页搜索支持名称/代码，这里用名称对用户最直观）。
     */
    onViewPlan: (securityName: String) -> Unit,
    onChanged: () -> Unit,
) {
    val state by holder.state.collectAsState()

    LaunchedEffect(securityId, refreshKey) { holder.load(securityId) }

    // ---- 利润转增资本（REQ-ACC-16）的交互状态 ----
    var showCapitalize by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<Transaction?>(null) }
    // ---- 一键清空该标的流水（老周 2026-09-30）：只控制两次确认弹窗的开关 ----
    var showClearAll by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .clickable { AppNav.pop() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("‹", fontSize = pageSp(20f), color = StockNoteColors.Brand)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = state.detail?.security?.name ?: "标的详情",
                        fontSize = pageSp(18f),
                        fontWeight = FontWeight.SemiBold,
                        color = StockNoteColors.TextPrimary,
                    )
                    Text(
                        text = state.detail?.security?.let { "${it.symbol} · ${it.market.label}" } ?: "",
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                    )
                }
            }
        }

        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.load(securityId) }) } }

        state.detail?.let { detail ->
            // 数量单位统一走 Market.quantityUnit（场外基金 = 份，其余 = 股），别再写字面量
            val unit = detail.security.market.quantityUnit
            item {
                SectionCard(title = "合并持仓") {
                    MetaRow("持仓数量", Format.quantity(detail.position.quantity) + " $unit")
                    MetaRow("成本均价", Format.money(detail.position.avgCost, detail.security.currency.symbol))
                    MetaRow("现价", detail.position.marketPrice?.let {
                        Format.money(it, detail.security.currency.symbol)
                    } ?: "—")
                    MetaRow(
                        "市值",
                        Format.money(detail.position.marketValue, detail.security.currency.symbol),
                        valueColor = StockNoteColors.Brand,
                    )
                    MetaRow(
                        "浮动盈亏",
                        Format.moneySigned(detail.position.unrealizedPnl, detail.security.currency.symbol),
                        valueColor = pnlColor(detail.position.unrealizedPnl),
                    )
                    MetaRow(
                        "已实现盈亏",
                        Format.moneySigned(detail.position.realizedPnl, detail.security.currency.symbol),
                        valueColor = pnlColor(detail.position.realizedPnl),
                    )
                    // 总盈亏 = 已实现 + 浮动（原币）。转增资本正是对它做减项，用户要能当场看到
                    val totalPnl = detail.position.realizedPnl + detail.position.unrealizedPnl
                    MetaRow(
                        "总盈亏",
                        Format.moneySigned(totalPnl, detail.security.currency.symbol),
                        valueColor = pnlColor(totalPnl),
                    )
                    // 总分红 + 股息率（老周 2026-09-24）：该标的历次分红在**持仓口径**下的累计；
                    // 股息率 = 累计分红 ÷ 持仓总成本（成本均价×数量）—— 拿成本算出来的分红回报率，
                    // 与"每股分红÷现价"的市场口径不同，这里标注口径以免混淆
                    val totalDiv = detail.position.dividendCash
                    val costTotal = detail.position.avgCost * detail.position.quantity
                    val divYield = if (costTotal > 1e-9) totalDiv / costTotal else null
                    MetaRow(
                        "总分红",
                        Format.money(totalDiv, detail.security.currency.symbol),
                        valueColor = StockNoteColors.Up,
                    )
                    MetaRow(
                        "股息率（分红÷成本）",
                        divYield?.let { Format.percent(it, decimals = 2) } ?: "—",
                        valueColor = if ((divYield ?: 0.0) >= 0) StockNoteColors.Up else StockNoteColors.Down,
                    )
                    detail.quote?.let { q ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "行情来源 ${q.source} · 更新于 ${
                                q.updatedAtEpochMs
                                    ?.let { com.stocknote.data.platform.formatLocalDateTime(it) }
                                    ?: "未知"
                            }",
                            fontSize = pageSp(11f),
                            color = StockNoteColors.TextTertiary,
                        )
                    }
                }
            }

            // ---- 统计口径开关（**仅场外基金**，老周 2026-09-20）----
            // 基金缺省「不计入统计」（完全账外 / 纯备忘）；想让它跟股票一样进总资产就在这里打开。
            // 放在本页是因为这是**标的级**属性，与具体哪一笔交易无关。
            if (detail.security.market == Market.FUND) {
                item {
                    SectionCard(title = "统计口径") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "计入统计与分析",
                                    fontSize = pageSp(14f),
                                    fontWeight = FontWeight.Medium,
                                    color = StockNoteColors.TextPrimary,
                                )
                                Text(
                                    text = if (detail.security.excludeFromStats) {
                                        "已关闭：完全账外（纯备忘）—— 买卖不联动现金，" +
                                            "市值不进总资产 / 资产曲线 / 收益率，只在持仓页「账外备忘」区单列。"
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
                                checked = !detail.security.excludeFromStats,
                                enabled = !state.busy,
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

            item {
                Button(
                    onClick = { onAddNew(securityId) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Brand),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("＋ 新增一笔（标的已预填）", fontSize = pageSp(14f), fontWeight = FontWeight.Medium)
                }
            }

            // ---- 利润转增资本（REQ-ACC-16）----
            // 白底 + 蓝字（原型 11 的 .btn.tint 口径）；**仅仍持仓的标的显示** ——
            // 清仓后没有本金可抬，按钮出现只会让人困惑。判定与仓储层同源（Capitalize.canCapitalize）。
            if (Capitalize.canCapitalize(detail.position)) {
                item {
                    Button(
                        onClick = { showCapitalize = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .semantics { contentDescription = "利润转增资本" },
                        colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Surface),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            "💰 利润转增资本",
                            fontSize = pageSp(14f),
                            fontWeight = FontWeight.Medium,
                            color = StockNoteColors.Brand,
                        )
                    }
                }
            }

            // ---- 查看计划（老周 2026-09-19）----
            // 白底 + 蓝字，与上方「新增一笔」构成主次；点了跳到计划 tab 并**按该标的预置搜索 + 距达成最近排序**
            item {
                var planBusy by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        planBusy = true
                        onViewPlan(detail.security.name)
                    },
                    enabled = !planBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .semantics { contentDescription = "查看计划" },
                    colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Surface),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "🎯 查看计划",
                        fontSize = pageSp(14f),
                        fontWeight = FontWeight.Medium,
                        color = StockNoteColors.Brand,
                    )
                }
            }

            // ---- 一键清空该标的流水（老周 2026-09-30）----
            // 位置：紧跟「查看计划」之后（老周 2026-09-30 调整）；白底**红字**（DangerButton），
            // 点了先过**两次确认**；标的本身保留，可重新记一笔。没有流水时不显示。
            if (detail.transactions.isNotEmpty() || detail.dividends.isNotEmpty()) {
                item {
                    DangerButton(
                        text = "🗑 一键清空（${detail.transactions.size} 笔交易 · ${detail.dividends.size} 条分红）",
                        enabled = !state.busy,
                        onClick = { showClearAll = true },
                    )
                    Text(
                        "只清该标的的流水，标的本身保留；现金会同步回冲。不可撤销，建议先做一次加密备份。",
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                    )
                }
            }

            // ---- 分红送股（REQ-ACC-04）----
            item {
                Sec(
                    title = "分红送股（${detail.dividends.size}）",
                    more = "登记 ›",
                    onMore = { onAddDividend(securityId) },
                ) {
                    CardBox {
                        if (detail.dividends.isEmpty()) {
                            EmptyHint("暂无分红送股记录。")
                        } else {
                            detail.dividends.forEach { d ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = when (d.type) {
                                                "CASH" -> "现金分红 · 每$unit ${Format.money(d.perShare, detail.security.currency.symbol)}"
                                                "BONUS" -> "送股 ${Format.quantity(d.bonusShares)} 股"
                                                else -> "配股 ${Format.quantity(d.quantity)} 股 @ ${Format.money(d.rightsPrice, detail.security.currency.symbol)}"
                                            },
                                            fontSize = pageSp(13f),
                                            fontWeight = FontWeight.Bold,
                                            color = StockNoteColors.TextPrimary,
                                        )
                                        Text(
                                            d.exDate,
                                            fontSize = pageSp(11f),
                                            color = StockNoteColors.TextTertiary,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "交易记录 ${detail.transactions.size} 笔（按日期倒序）",
                    fontSize = pageSp(13f),
                    color = StockNoteColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (detail.transactions.isEmpty()) {
                item { EmptyHint("该标的还没有交易记录。") }
            } else {
                items(detail.transactions, key = { it.id }) { tx ->
                    if (tx.side == TradeSide.CAPITALIZE) {
                        // 转增不是成交，进不了「编辑交易」页 —— 点它即「撤销」入口（删除该流水）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { revokeTarget = tx },
                        ) {
                            CapitalizeRow(
                                amount = tx.price,
                                currency = detail.security.currency,
                                date = tx.tradeDate,
                            )
                        }
                    } else {
                        // 老周 2026-09-19：**取消左滑**（原「左滑露出 ✏️/🗑」已废）——
                        // 改为**点击整行进入编辑页**，与交易计划的操作方式统一；
                        // 删除入口不再出现在列表里，只放在编辑页底部（先预告后确认）。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEdit(securityId, tx.id) },
                        ) {
                            TradeRow(
                                side = tx.side.label,
                                date = tx.tradeDate,
                                quantity = tx.quantity,
                                price = tx.price,
                                fee = tx.fee,
                                currency = detail.security.currency,
                                isBuy = tx.side.name == "BUY",
                                note = tx.note,
                                unit = unit,
                            )
                        }
                    }
                }
            }

        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    // ================= 弹窗层 =================

    val detail = state.detail

    // 转增输入 + 实时预告（原型 11d）
    if (showCapitalize && detail != null) {
        CapitalizeDialog(
            security = detail.security,
            transactions = detail.transactions,
            position = detail.position,
            dividends = detail.dividends,
            busy = state.busy,
            onDismiss = { if (!state.busy) showCapitalize = false },
            onConfirm = { amount, tradeDate ->
                holder.capitalize(
                    securityId = securityId,
                    amount = amount,
                    tradeDate = tradeDate,
                    successMessage = "已将 ${Format.money(amount, detail.security.currency.symbol)} 折入本金",
                    onDone = {
                        showCapitalize = false
                        onChanged()
                    },
                )
            },
        )
    }

    // 撤销确认（删除即撤销：重放自然回到转增前）
    revokeTarget?.let { tx ->
        RevokeCapitalizeDialog(
            amount = tx.price,
            currency = detail?.security?.currency ?: Currency.CNY,
            date = tx.tradeDate,
            busy = state.busy,
            onDismiss = { if (!state.busy) revokeTarget = null },
            onConfirm = {
                holder.revokeCapitalize(
                    securityId = securityId,
                    txId = tx.id,
                    successMessage = "已撤销该笔转增，本金回到转增前",
                    onDone = {
                        revokeTarget = null
                        onChanged()
                    },
                )
            },
        )
    }

    // 一键清空：**两次确认**（老周 2026-09-30 要求）——第 1 次说做什么，第 2 次说不可撤销的后果
    if (showClearAll && detail != null) {
        DangerConfirmDialog(
            title = "清空「${detail.security.name}」的全部流水？",
            message = "将删除该标的的 ${detail.transactions.size} 笔交易与 ${detail.dividends.size} 条分红送股，" +
                "并把这些交易当初对现金的影响回冲。标的本身保留，之后可以重新记一笔。",
            warning = "此操作不可撤销：交易 / 分红 / 它们的备注与截图都会被删除，删掉就找不回来了" +
                "（除非事先做过加密备份）。确定要清空吗？",
            confirmLabel = "清空",
            busy = state.busy,
            onDismiss = { if (!state.busy) showClearAll = false },
            onConfirm = {
                holder.clearAllTrades {
                    showClearAll = false
                    onChanged()
                }
            },
        )
    }

    // 清空完成也走这个弹窗（老周 2026-09-30：清空后必须给明确反馈，否则像"点了没反应"）
    InfoDialog(
        message = state.message,
        title = if (state.message?.startsWith("已清空") == true) "已清空" else "提示",
        onDismiss = { holder.dismissMessage() },
    )
}

/**
 * 利润转增资本弹窗（原型 11d）。
 *
 * 关键设计：预告与实况**同源** —— 预告调 [Capitalize.preview]，它把一条合成 CAPITALIZE
 * 流水并进现有流水后跑**同一个** [com.stocknote.core.calc.PositionCalculator.replay]，
 * 所以弹窗里给的数字必然等于确认后的结果，不存在"两套算法各自算一遍"的漂移。
 *
 * 币种锁定为该标的原币（与持仓一致，不可切换）：转增是本位内部的账内重分类，
 * 换币种既无意义也会让成本口径说不清。
 */
@Composable
private fun CapitalizeDialog(
    security: Security,
    transactions: List<Transaction>,
    position: Position,
    dividends: List<DividendRecord>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double, String) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    // 转增日期（老周 2026-09-24）：默认今天，可选历史日期 —— 落库时汇率按该日折算
    // （repo.addCapitalize 内部走 fxRateOn(tradeDate, currency)，与记一笔的"成交日汇率"同一口径）
    var dateText by remember { mutableStateOf(com.stocknote.data.platform.todayIso()) }

    val sym = security.currency.symbol
    val amount = amountText.trim().toDoubleOrNull() ?: 0.0
    val error = if (amountText.isBlank()) null else Capitalize.validate(amountText)
    val preview = remember(amountText, transactions, position.marketPrice, dividends) {
        Capitalize.preview(
            security = security,
            transactions = transactions,
            amount = amount,
            marketPrice = position.marketPrice,
            dividends = dividends,
        )
    }
    val totalBefore = position.realizedPnl + position.unrealizedPnl
    val canConfirm = !busy && error == null && amount > 0.0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = StockNoteColors.Surface),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "利润转增资本",
                    fontSize = pageSp(17f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${security.name} · 币种 $sym ${security.currency.code}（与持仓一致，不可切换）",
                    fontSize = pageSp(12f),
                    color = StockNoteColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )

                Spacer(Modifier.height(14.dp))

                // 转增日期（老周 2026-09-24）：选历史日期时，汇率按该日折算（fxRateOn）；
                // 不允许选未来（maxIso = 今天）
                DateField(
                    label = "转增日期",
                    value = dateText,
                    onValueChange = { dateText = it },
                    maxIso = com.stocknote.data.platform.todayIso(),
                )
                Spacer(Modifier.height(12.dp))

                // 金额输入：**币种符号固定在框内左侧**（原型 11d 的 ¥ 号），符号不进输入值 ——
                // 解析始终是纯数字，避免 "¥6500".toDouble() 这类失败。
                // 配色与 FormControls.LabeledField 同源，保证与「记一笔」表单观感一致。
                Text(
                    text = "转增金额（$sym）",
                    fontSize = pageSp(12f),
                    color = StockNoteColors.TextSecondary,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { new -> if (new.all { it.isDigit() || it == '.' }) amountText = new },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "转增金额" },
                    singleLine = true,
                    prefix = {
                        Text(
                            text = sym,
                            fontSize = pageSp(20f),
                            fontWeight = FontWeight.Bold,
                            color = StockNoteColors.Brand,
                        )
                    },
                    placeholder = { Text("如 6500", fontSize = pageSp(16f), color = StockNoteColors.TextTertiary) },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = TextStyle(
                        fontSize = pageSp(20f),
                        fontWeight = FontWeight.Bold,
                        color = StockNoteColors.TextPrimary,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StockNoteColors.Brand,
                        unfocusedBorderColor = StockNoteColors.Divider,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                    ),
                    shape = RoundedCornerShape(10.dp),
                )
                if (error != null) {
                    Text(
                        text = error,
                        fontSize = pageSp(11f),
                        color = StockNoteColors.Up,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "将该股票的盈利折入本金（抬高成本价）。该股当前总盈亏 " +
                        Format.moneySigned(totalBefore, sym) +
                        "，转增额超出部分会让浮动盈亏转负（成本被抬到市价之上）。",
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextTertiary,
                    lineHeight = pageSp(17f),
                )

                // ---- 实时预告：前 → 后 ----
                preview?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF7F9FC))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        ChangeRow(
                            label = "成本均价",
                            before = Format.money(p.before.avgCost, sym),
                            after = Format.money(p.after.avgCost, sym),
                            afterColor = StockNoteColors.BrandDark,
                        )
                        ChangeRow(
                            label = "浮动盈亏",
                            before = Format.moneySigned(p.before.unrealizedPnl, sym),
                            after = Format.moneySigned(p.after.unrealizedPnl, sym),
                            afterColor = pnlColor(p.after.unrealizedPnl),
                        )
                        ChangeRow(
                            label = "总盈亏",
                            before = Format.moneySigned(p.before.realizedPnl + p.before.unrealizedPnl, sym),
                            after = Format.moneySigned(p.after.realizedPnl + p.after.unrealizedPnl, sym),
                            afterColor = pnlColor(p.after.realizedPnl + p.after.unrealizedPnl),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Surface),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("取消", fontSize = pageSp(14f), color = StockNoteColors.TextPrimary)
                    }
                    Button(
                        onClick = { onConfirm(amount, dateText) },
                        enabled = canConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "确认转增" },
                        colors = ButtonDefaults.buttonColors(containerColor = StockNoteColors.Brand),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            if (busy) "提交中…" else "确认转增",
                            fontSize = pageSp(14f),
                            color = Color.White,
                        )
                    }
                }

                Text(
                    text = "总资产 / 现金 / 真实收益率不受影响；可在交易记录中删除该笔以撤销。",
                    fontSize = pageSp(10.5f),
                    color = StockNoteColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
                )
            }
        }
    }
}

/** 撤销转增确认。删除流水即撤销，重放保证成本/盈亏回到转增前。 */
@Composable
private fun RevokeCapitalizeDialog(
    amount: Double,
    currency: Currency,
    date: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "撤销这笔转增？",
                fontSize = pageSp(16f),
                fontWeight = FontWeight.Bold,
                color = StockNoteColors.TextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text = "$date 折入本金 ${Format.money(amount, currency.symbol)}",
                    fontSize = pageSp(13f),
                    color = StockNoteColors.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "撤销后该标本金回到转增前，成本均价与盈亏由剩余流水重放得出；" +
                        "总资产 / 现金 / 真实收益率不受影响。",
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextTertiary,
                    lineHeight = pageSp(17f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(
                    if (busy) "撤销中…" else "确认撤销",
                    color = StockNoteColors.Up,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("取消", color = StockNoteColors.TextSecondary)
            }
        },
        containerColor = Color.White,
    )
}

/** 交易记录里的「转增资本」行。点它 = 撤销入口（不是编辑交易）。 */
@Composable
private fun CapitalizeRow(
    amount: Double,
    currency: Currency,
    date: String,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(color = Color(0xFFEAF1FE), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "转增资本",
                    fontSize = pageSp(11f),
                    fontWeight = FontWeight.Medium,
                    color = StockNoteColors.BrandDark,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "折入本金 ${Format.money(amount, currency.symbol)}",
                    fontSize = pageSp(14f),
                    fontWeight = FontWeight.Medium,
                    color = StockNoteColors.TextPrimary,
                )
                Text(
                    text = date,
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextTertiary,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("撤销 ›", fontSize = pageSp(12f), color = StockNoteColors.Brand)
        }
    }
}

/** 预告里的一行「前 → 后」。
 *
 * 窄屏（Redmi K30 视口仅 1080px）上「浮动盈亏 −¥309,288.00 → −¥315,788.00」这一行
 * 恰好把 after 值挤到第二行 —— 数字折行会让"前→后"难读。因此：
 *   · label 固定宽；
 *   · before 用 `weight(1f)` 吃剩余空间并**允许收缩**；
 *   · after 不参与权重，保证它永远完整显示在一行。
 */
@Composable
private fun ChangeRow(
    label: String,
    before: String,
    after: String,
    afterColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = pageSp(12f),
            color = StockNoteColors.TextSecondary,
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = before,
            fontSize = pageSp(12f),
            color = StockNoteColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("→", fontSize = pageSp(12f), color = StockNoteColors.TextTertiary)
        Text(
            text = after,
            fontSize = pageSp(12f),
            fontWeight = FontWeight.Bold,
            color = afterColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun TradeRow(
    side: String,
    date: String,
    quantity: Double,
    price: Double,
    fee: Double,
    currency: Currency,
    isBuy: Boolean,
    note: String?,
    /** 数量单位：股票 / 场内 ETF 用「股」，场外基金用「份」（老周 2026-09-20）。 */
    unit: String = "股",
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(
                        color = if (isBuy) Color(0xFFEAF3DE) else Color(0xFFFCEBEB),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = side,
                    fontSize = pageSp(11f),
                    fontWeight = FontWeight.Medium,
                    color = if (isBuy) Color(0xFF3B6D11) else Color(0xFFA32D2D),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "${Format.quantity(quantity)} $unit @ ${Format.money(price, currency.symbol)}",
                    fontSize = pageSp(14f),
                    fontWeight = FontWeight.Medium,
                    color = StockNoteColors.TextPrimary,
                )
                Text(
                    text = date + if (fee > 0) " · 手续费 ${Format.money(fee, currency.symbol)}" else "",
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextTertiary,
                )
                if (!note.isNullOrBlank()) {
                    Text(
                        text = note,
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // 操作入口 = **点击整行进入编辑页**；删除在编辑页底部（老周 2026-09-19，原左滑方案已废）
        }
    }
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与各主页/表单页一致）。 */
@Composable
fun SecurityDetailScreen(
    holder: SecurityDetailHolder,
    securityId: String,
    /** 外层数据版本号：编辑/删除后 +1，触发本页重新读取（重放即在这里发生） */
    refreshKey: Int,
    onEdit: (securityId: String, txId: String) -> Unit,
    onAddNew: (securityId: String) -> Unit,
    onAddDividend: (securityId: String) -> Unit,
    /**
     * 查看该标的的交易计划（老周 2026-09-19）：跳到计划 tab，按该标的名称搜索、按「距达成最近」排序。
     * 参数是**标的名称**（计划页搜索支持名称/代码，这里用名称对用户最直观）。
     */
    onViewPlan: (securityName: String) -> Unit,
    onChanged: () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        SecurityDetailScreenContent(holder, securityId, refreshKey, onEdit, onAddNew, onAddDividend, onViewPlan, onChanged)
    }
}
