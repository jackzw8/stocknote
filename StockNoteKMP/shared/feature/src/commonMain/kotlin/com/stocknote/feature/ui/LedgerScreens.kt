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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.TradeForms
import com.stocknote.core.format.Format
import com.stocknote.core.model.CashFlowRecord
import com.stocknote.core.model.DividendRecord
import com.stocknote.data.platform.todayIso
import com.stocknote.data.repo.CashRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ==================================================================================
// 出入金（REQ-ACC-03）
// ==================================================================================

class CashFlowHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 第 10 批：现金域（出入金流水的增改删与查询）。 */
    private val cash: CashRepository,
    private val scope: CoroutineScope,
) {
    data class UiState(
        /** 非空 = 正在编辑这条流水 */
        val editingId: String? = null,
        val isDeposit: Boolean = true,
        val amount: String = "",
        val date: String = todayIso(),
        val note: String = "",
        val flows: List<CashFlowRecord> = emptyList(),
        val issues: List<TradeForms.Issue> = emptyList(),
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
        /** 保存失败次数：UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            try {
                val flows = cash.cashFlows()
                _state.update { it.copy(flows = flows) }
            } catch (t: Throwable) {
                _state.update { it.copy(error = "加载失败：${t.message ?: t::class.simpleName}") }
            }
        }
    }

    fun setDeposit(v: Boolean) = _state.update { it.copy(isDeposit = v) }
    fun setAmount(v: String) = _state.update { it.copy(amount = v) }
    fun setDate(v: String) = _state.update { it.copy(date = v) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }

        /** 进入编辑态：把某条流水回填到表单（老周 2026-09-16：流水记录可修改/删除） */
    fun startEdit(f: CashFlowRecord) = _state.update {
        it.copy(
            editingId = f.id,
            isDeposit = f.type == "DEPOSIT",
            amount = f.amountOrig.let { v -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString() },
            date = f.flowDate,
            note = f.note ?: "",
            saved = false,
        )
    }

    fun cancelEdit() = _state.update {
        it.copy(editingId = null, amount = "", note = "", date = todayIso(), saved = false)
    }

    /**
     * 删除一笔出入金。
     * @param onChanged 删除成功后的回调 —— 用来让上层刷新统计页快照
     *  （老周 2026-09-16：此前删除后回退，可用现金没跟着变，因为没有触发全局刷新）
     */
    fun deleteFlow(id: String, onChanged: () -> Unit = {}) {
        scope.launch {
            runCatching {
                cash.deleteCashFlow(id)
            }.onFailure { e ->
                _state.update { it.copy(error = "删除失败：${e.message ?: "未知错误"}") }
                return@launch
            }
            if (_state.value.editingId == id) cancelEdit()
            load()
            onChanged()
        }
    }

fun save(onDone: () -> Unit) {
        val s = _state.value
        val issues = buildList {
            val amt = s.amount.trim().toDoubleOrNull()
            if (s.amount.isBlank()) add(TradeForms.Issue("amount", "请填写金额"))
            else if (amt == null || amt <= 0.0) add(TradeForms.Issue("amount", "金额必须是大于 0 的数字"))
            runCatching { com.stocknote.core.calc.CivilDate.parseIso(s.date) }
                .onFailure { add(TradeForms.Issue("date", "日期格式应为 yyyy-MM-dd")) }
        }
        if (issues.isNotEmpty()) {
            _state.update { it.copy(issues = issues, saveFailTick = it.saveFailTick + 1) }
            return
        }
        scope.launch {
            _state.update { it.copy(saving = true, issues = emptyList()) }
            try {
                val editing = s.editingId
                if (editing != null) {
                    // 编辑态：更新这一条（老周 2026-09-16）
                    cash.updateCashFlow(
                        id = editing,
                        flowDate = s.date.trim(),
                        isDeposit = s.isDeposit,
                        amountOrig = s.amount.trim().toDouble(),
                        note = s.note,
                    )
                } else {
                    cash.addCashFlow(
                        accountId = "acc_a",
                        flowDate = s.date.trim(),
                        isDeposit = s.isDeposit,
                        amountOrig = s.amount.trim().toDouble(),
                        currency = com.stocknote.core.model.Currency.CNY,
                        fxRate = 1.0,
                        note = s.note,
                    )
                }
                val wasEditing = editing != null
                _state.update {
                    it.copy(
                        saving = false,
                        saved = true,
                        amount = "",
                        note = "",
                        editingId = null,
                    )
                }
                load()
                // 编辑保存同样要刷新上层（金额变化会影响可用现金）
                if (wasEditing) onDone()
                onDone()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "保存失败：${t.message ?: t::class.simpleName}",
                        saveFailTick = it.saveFailTick + 1,
                    )
                }
            }
        }
    }
}

@Composable
fun rememberCashFlowHolder(
    repo: PortfolioRepository,
    cash: CashRepository,
): CashFlowHolder {
    val scope = rememberCoroutineScope()
    return remember(repo, cash) { CashFlowHolder(repo, cash, scope) }
}

@Composable
fun CashFlowScreen(holder: CashFlowHolder, onDone: () -> Unit) {
    val state by holder.state.collectAsState()
    LaunchedEffect(Unit) { holder.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(title = "出入金") { AppNav.pop() } }

        item {
            InfoCard(
                title = "为什么记录出入金？",
                message = "存入/取出不改变盈亏，但会扭曲收益率。记下它们，" +
                    "「真实收益率(IRR)」才能剔除资金进出的干扰。",
            )
        }

        item {
            SectionCard(title = if (state.editingId == null) "登记一笔" else "修改这笔（已回填）") {
                if (state.editingId != null) {
                    Text(
                        "正在修改一条流水，改完点保存；或点「取消修改」放弃。",
                        fontSize = 11.sp,
                        color = StockNoteColors.Brand,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                ChipRow(
                    options = listOf("存入", "取出"),
                    selectedIndex = if (state.isDeposit) 0 else 1,
                    onSelect = { holder.setDeposit(it == 0) },
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "金额（CNY）",
                    value = state.amount,
                    onValueChange = holder::setAmount,
                    placeholder = "如 100000",
                    numeric = true,
                    errorText = state.issues.firstOrNull { it.field == "amount" }?.message,
                )
                Spacer(Modifier.height(10.dp))
                DateField(
                    label = "日期",
                    value = state.date,
                    onValueChange = holder::setDate,
                    maxIso = todayIso(),
                    errorText = state.issues.firstOrNull { it.field == "date" }?.message,
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "备注（可空）",
                    value = state.note,
                    onValueChange = holder::setNote,
                    placeholder = "工资转入 / 缴水电…",
                )
            }
        }

        if (state.saved) {
            item { InfoCard(title = "已保存", message = "流水已入账，统计页的可用现金与真实收益率已更新。") }
        }

        item {
            PrimaryButton(
                if (state.saving) "保存中…" else (if (state.editingId == null) "保存" else "保存修改"),
                onClick = { holder.save(onDone = onDone) },
                enabled = !state.saving,
            )
            if (state.editingId != null) {
                Spacer(Modifier.height(8.dp))
                GhostButton("取消修改", onClick = { holder.cancelEdit() })
            }
        }

        item {
            Sec(title = "流水记录（${state.flows.size}）", modifier = Modifier.padding(top = 8.dp)) {
                CardBox {
                    if (state.flows.isEmpty()) {
                        EmptyHint("还没有出入金记录。")
                    } else {
                        state.flows.forEach { f ->
                            val deposit = f.type == "DEPOSIT"
                            // 老周 2026-09-27：备注**独占卡片整行** —— 结构改为
                            // `Column { Row{ 类型+日期 / 金额 / 图标 } ; 备注整行 }`。
                            // ⚠️ 第一版把备注放进左列 Column，结果左列被宽金额挤压 → **日期被折成
                            // 「2026-07-1 / 5」两行**。提到 Row 之外，才是真正的"占一整行"。
                            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (deposit) "存入" else "取出",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StockNoteColors.TextPrimary,
                                        )
                                        Text(
                                            f.flowDate,
                                            fontSize = 10.sp,
                                            color = StockNoteColors.TextTertiary,
                                            // 日期绝不被折行（真机实测：1.2 倍字号下 10 字符需要 ~363px，
                                            // 而左列被宽金额+两个图标挤到只剩 ~310px → 会折成「2026-07-1 / 5」）
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                    Text(
                                        // 2026-09-17 B 方案：出入金常有零头，金额统一 2 位小数
                                        Format.moneySigned(
                                            if (deposit) f.amountOrig else -f.amountOrig,
                                            f.currency.symbol,
                                        ),
                                        // 13sp（原 14sp）：把横向空间让给左列的日期，避免日期折行
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (deposit) StockNoteColors.Up else StockNoteColors.Down,
                                        maxLines = 1,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    // 修改：回填表单进入编辑态（老周 2026-09-16）
                                    Text(
                                        "✏️",
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clickable { holder.startEdit(f) }
                                            .padding(horizontal = 6.dp),
                                    )
                                    Text(
                                        "🗑",
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clickable { holder.deleteFlow(f.id) { onDone() } }
                                            .padding(start = 6.dp),
                                    )
                                }
                                // 备注：占卡片**整行**（跨模块 public 属性不能 smart cast，先取局部变量）
                                val note = f.note
                                if (!note.isNullOrBlank()) {
                                    Text(
                                        note.trim(),
                                        fontSize = 11.sp,
                                        color = StockNoteColors.TextTertiary,
                                        modifier = Modifier.padding(top = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.load() }) } }
        item { Spacer(Modifier.height(8.dp)) }
    }

    // 保存 / 修改失败弹窗（老周 2026-09-18）：失败时**明确弹出**，避免「点了按钮没反应」的错觉
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = state.issues.map { it.message } + listOfNotNull(state.error),
    )
}

// ==================================================================================
// 分红送股登记（REQ-ACC-04）
// ==================================================================================

class DividendHolder(
    private val repo: PortfolioRepository,
    private val scope: CoroutineScope,
    val securityId: String,
) {
    data class UiState(
        val securityName: String = "",
        val type: String = "CASH",
        val exDate: String = todayIso(),
        val perShare: String = "",
        val shares: String = "",
        val rightsPrice: String = "",
        val dividends: List<DividendRecord> = emptyList(),
        val issues: List<TradeForms.Issue> = emptyList(),
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
        /** 保存失败次数：UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            try {
                val detail = repo.loadSecurityDetail(securityId)
                val list = repo.dividendsOf(securityId)
                _state.update {
                    it.copy(securityName = detail?.security?.name ?: "", dividends = list)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = "加载失败：${t.message ?: t::class.simpleName}") }
            }
        }
    }

    fun setType(v: String) = _state.update { it.copy(type = v) }
    fun setExDate(v: String) = _state.update { it.copy(exDate = v) }
    fun setPerShare(v: String) = _state.update { it.copy(perShare = v) }
    fun setShares(v: String) = _state.update { it.copy(shares = v) }
    fun setRightsPrice(v: String) = _state.update { it.copy(rightsPrice = v) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        val issues = buildList {
            when (s.type) {
                "CASH" -> if ((s.perShare.toDoubleOrNull() ?: 0.0) <= 0.0) {
                    add(TradeForms.Issue("perShare", "请填写每股分红"))
                }

                "BONUS" -> if ((s.shares.toDoubleOrNull() ?: 0.0) <= 0.0) {
                    add(TradeForms.Issue("shares", "请填写送股数量"))
                }

                "RIGHTS" -> {
                    if ((s.shares.toDoubleOrNull() ?: 0.0) <= 0.0) add(TradeForms.Issue("shares", "请填写配股数量"))
                    if ((s.rightsPrice.toDoubleOrNull() ?: 0.0) <= 0.0) {
                        add(TradeForms.Issue("rightsPrice", "请填写配股价"))
                    }
                }
            }
            runCatching { com.stocknote.core.calc.CivilDate.parseIso(s.exDate) }
                .onFailure { add(TradeForms.Issue("exDate", "日期格式应为 yyyy-MM-dd")) }
        }
        if (issues.isNotEmpty()) {
            _state.update { it.copy(issues = issues, saveFailTick = it.saveFailTick + 1) }
            return
        }
        scope.launch {
            _state.update { it.copy(saving = true, issues = emptyList()) }
            try {
                repo.addDividend(
                    securityId = securityId,
                    exDate = s.exDate.trim(),
                    type = s.type,
                    quantity = s.shares.toDoubleOrNull() ?: 0.0,
                    perShare = s.perShare.toDoubleOrNull() ?: 0.0,
                    bonusShares = s.shares.toDoubleOrNull() ?: 0.0,
                    rightsPrice = s.rightsPrice.toDoubleOrNull() ?: 0.0,
                )
                _state.update { it.copy(saving = false, saved = true, perShare = "", shares = "", rightsPrice = "") }
                load()
                onDone()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "保存失败：${t.message ?: t::class.simpleName}",
                        saveFailTick = it.saveFailTick + 1,
                    )
                }
            }
        }
    }
}

@Composable
fun rememberDividendHolder(repo: PortfolioRepository, securityId: String): DividendHolder {
    val scope = rememberCoroutineScope()
    return remember(repo, securityId) { DividendHolder(repo, scope, securityId) }
}

private val DIV_TYPES = listOf(
    Triple("CASH", "现金分红", "每股分红（元）"),
    Triple("BONUS", "送股", "送股数量（股）"),
    Triple("RIGHTS", "配股", "配股数量（股）"),
)

@Composable
fun DividendScreen(holder: DividendHolder, onDone: () -> Unit) {
    val state by holder.state.collectAsState()
    LaunchedEffect(Unit) { holder.load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(title = "分红送股登记") { AppNav.pop() } }

        item {
            InfoCard(
                title = "登记后成本自动调整",
                message = "现金分红 → 每股成本下调；送股 → 股数增加、均价摊薄；配股 → 按配股价计入成本。" +
                    "登记后立即生效（持仓由全量重放得出）。",
            )
        }

        item {
            SectionCard(title = state.securityName.ifEmpty { "标的" }) {
                ChipRow(
                    options = DIV_TYPES,
                    selectedIndex = DIV_TYPES.indexOfFirst { it.first == state.type },
                    onSelect = { holder.setType(DIV_TYPES[it].first) },
                    labelOf = { it.second },
                )
                Spacer(Modifier.height(10.dp))
                DateField(
                    label = "除权除息日",
                    value = state.exDate,
                    onValueChange = holder::setExDate,
                    maxIso = todayIso(),
                    errorText = state.issues.firstOrNull { it.field == "exDate" }?.message,
                )
                Spacer(Modifier.height(10.dp))
                val fieldLabel = DIV_TYPES.first { it.first == state.type }.third
                if (state.type == "CASH") {
                    LabeledField(
                        label = fieldLabel,
                        value = state.perShare,
                        onValueChange = holder::setPerShare,
                        numeric = true,
                        errorText = state.issues.firstOrNull { it.field == "perShare" }?.message,
                    )
                } else {
                    LabeledField(
                        label = fieldLabel,
                        value = state.shares,
                        onValueChange = holder::setShares,
                        numeric = true,
                        errorText = state.issues.firstOrNull { it.field == "shares" }?.message,
                    )
                }
                if (state.type == "RIGHTS") {
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "配股价（元/股）",
                        value = state.rightsPrice,
                        onValueChange = holder::setRightsPrice,
                        numeric = true,
                        errorText = state.issues.firstOrNull { it.field == "rightsPrice" }?.message,
                    )
                }
            }
        }

        if (state.saved) {
            item { InfoCard(title = "已登记", message = "成本与股数已按规则重算，返回标的页可见最新持仓。") }
        }

        item {
            PrimaryButton(
                if (state.saving) "登记中…" else "登记",
                onClick = { holder.save(onDone = onDone) },
                enabled = !state.saving,
            )
        }

        item {
            Sec(title = "已登记（${state.dividends.size}）", modifier = Modifier.padding(top = 8.dp)) {
                CardBox {
                    if (state.dividends.isEmpty()) {
                        EmptyHint("该标的还没有分红送股记录。")
                    } else {
                        state.dividends.forEach { d ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        when (d.type) {
                                            "CASH" -> "现金分红 · 每股 ${Format.money(d.perShare, "")}"
                                            "BONUS" -> "送股 ${Format.quantity(d.bonusShares)} 股"
                                            else -> "配股 ${Format.quantity(d.quantity)} 股 @ ${Format.money(d.rightsPrice, "")}"
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StockNoteColors.TextPrimary,
                                    )
                                    Text(
                                        d.exDate,
                                        fontSize = 11.sp,
                                        color = StockNoteColors.TextTertiary,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { item { ErrorBanner(message = it, onRetry = { holder.load() }) } }
        item { Spacer(Modifier.height(8.dp)) }
    }

    // 保存 / 修改失败弹窗（老周 2026-09-18）：失败时**明确弹出**，避免「点了按钮没反应」的错觉
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = state.issues.map { it.message } + listOfNotNull(state.error),
    )
}
