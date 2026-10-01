package com.stocknote.feature.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.calc.CashEquivalents
import com.stocknote.core.format.Format
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.stocknote.data.repo.BackupRepository
import com.stocknote.data.repo.CsvRepository
import com.stocknote.data.repo.PlanRepository
import com.stocknote.data.repo.TradeRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.data.repo.SettingsRepository
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.state.AppDisplaySettings
import com.stocknote.feature.nav.AppRoute
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页（原型 09「设置 · 账户与安全」，老周反馈 2026-09-14）。
 *
 * v2.5.0 范围：汇率管理（M4 数据真值：手动录入 fx_rate，列表展示）+ 标签管理入口 +
 * 关于入口 + 账户/数据安全说明。备份恢复（REQ-SEC-03）、本位币切换按排期在 M5 接入。
 */
/** 备份操作模式（REQ-SEC-03） */
enum class BackupMode { NONE, EXPORT, IMPORT }

class SettingsHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-28 拆分：设置域（字体缩放 / 缺省费率 / 整手校验开关）。 */
    private val settings: SettingsRepository,
    /** ⚠️ 2026-09-29 拆分（第 6 批）：计划域（计划 CSV 导出/导入）。 */
    private val plan: PlanRepository,
    /** ⚠️ 2026-09-29 拆分（第 6 批）：CSV 域（交易/出入金导出与模板 zip）。 */
    private val csv: CsvRepository,
    /** ⚠️ 2026-09-29 拆分（第 8 批）：交易域（交易/出入金 CSV 导入）。 */
    private val trade: TradeRepository,
    /** ⚠️ 2026-09-29 拆分（第 9 批）：备份域（加密备份导出/恢复）。 */
    private val backup: BackupRepository,
    private val scope: CoroutineScope,
) {
    data class UiState(
        val rates: List<com.stocknote.core.model.FxRateRecord> = emptyList(),
        /** 录入表单 */
        val currency: String = "HKD",
        val effectiveDate: String = "",
        val rateText: String = "",
        val showCreate: Boolean = false,
        /** 「整手校验」开关（老周 2026-09-28；缺省**开**）—— 关掉后数量不再要求整手 */
        val lotCheckEnabled: Boolean = true,
        val error: String? = null,
        /** 保存失败次数：UI 据此弹「无法保存」弹窗（老周 2026-09-18） */
        val saveFailTick: Int = 0,
        /** 备份与恢复（REQ-SEC-03） */
        val backupMode: BackupMode = BackupMode.NONE,
        val backupMessage: String? = null,
        val backupBusy: Boolean = false,
        /**
         * 「导出运行日志」的结果文案（老周 2026-10-01）。
         * ⚠️ 单独一个字段而**不复用 `csvMessage`** —— 否则 CSV 那边的消息会串到日志卡片上。
         */
        val logMessage: String? = null,
        /** 字体缩放档位（1.0 / 1.15 / 1.3 / 1.4）—— 技术方案-字体大小设置 */
        val fontScale: Float = 1.0f,
        // ---- 手续费率缺省（万分之值，老周 2026-09-18：A 股 2.5 / 港股 5）----
        val feeAShare: String = "2.5",
        val feeHk: String = "5",
        // ---- 印花税率（REQ-ACC-17，老周 2026-09-30：万分位，与手续费率同区）----
        /** A 股**卖出**印花税率（万分之值）；缺省 5。 */
        val stampAShare: String = "5",
        /** 港股印花税率（万分之值，**双向**）；缺省 10（千分之 1）。 */
        val stampHk: String = "10",
        /** 费率设置的结果提示；非空弹 InfoDialog */
        val feeMessage: String? = null,
        val feeTick: Int = 0,
        // ---- 现金等价物名单（REQ-ACC-07，老周 2026-09-20）----
        /** 输入框内容（逗号分隔的标的代码）；初值从 app_setting 读出 */
        val cashEquivInput: String = "",
        val cashEquivMessage: String? = null,
        val cashEquivTick: Int = 0,
        // ---- CSV 导入/导出（老周 2026-09-16 确认设计）----
        val csvMessage: String? = null,
        val csvBusy: Boolean = false,
        /**
         * 真实账户列表（老周 2026-09-17：设置页「账户」卡片改为**数据驱动**，
         * 不再写死文案）。空列表 = 尚未创建（记第一笔交易/出入金时自动创建）。
         */
        val accounts: List<com.stocknote.data.db.Account> = emptyList(),
        /** 「清空所有标的（账本流水）」进行中：禁用按钮，避免重复提交 —— 老周 2026-09-30 */
        val clearBusy: Boolean = false,
        /**
         * 清空结果提示（老周 2026-09-30：清空完成后**必须给个明确提示**）。
         * 不复用 `csvMessage` —— 那个只在「数据管理」页弹，在设置页弹不出来。
         */
        val clearMessage: String? = null,
        /** 隐藏盈亏模式（REQ-VIEW-09，老周 2026-09-30）：与 [AppDisplaySettings.privacyMode] 同步。 */
        val privacyMode: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState(effectiveDate = com.stocknote.data.platform.todayIso()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            val rates = runCatching { repo.fxRates() }.getOrDefault(emptyList())
            val scale = runCatching { settings.fontScale() }.getOrDefault(1.0)
            val accounts = runCatching { repo.accounts() }.getOrDefault(emptyList())
            val feeA = runCatching { settings.feeRateAShare() }.getOrDefault(2.5)
            val feeH = runCatching { settings.feeRateHk() }.getOrDefault(5.0)
            // 印花税率（REQ-ACC-17）：与手续费率同时读入
            val stampA = runCatching { settings.stampDutyAShare() }.getOrDefault(5.0)
            val stampH = runCatching { settings.stampDutyHk() }.getOrDefault(10.0)
            // 隐藏盈亏模式（REQ-VIEW-09）
            val privacy = runCatching { settings.privacyMode() }.getOrDefault(false)
            val cashEq = runCatching { repo.cashEquivalentRaw() }.getOrDefault("")
            _state.update {
                it.copy(
                    rates = rates.sortedWith(compareBy({ it.currency }, { it.effectiveDate })),
                    fontScale = scale.toFloat(),
                    accounts = accounts,
                    feeAShare = AppDisplaySettings.feeText(feeA),
                    feeHk = AppDisplaySettings.feeText(feeH),
                    stampAShare = AppDisplaySettings.feeText(stampA),
                    stampHk = AppDisplaySettings.feeText(stampH),
                    privacyMode = privacy,
                    cashEquivInput = cashEq,
                )
            }
        }
    }

    /**
     * **清空所有标的的账本流水**（老周 2026-09-30）。
     *
     * 清掉：全部交易（含备注 / 截图）+ 全部分红送股 + 全部出入金，账户现金归零；
     * **保留**标的 / 自选 / 计划 / 标签 —— 便于"推倒重来"后直接重新导入 CSV。
     * 调用方（UI）必须先过**两次确认**再调本方法。
     *
     * @param onDone 清空完成后回调（UI 用它刷新账本 + 关弹窗），参数是清掉的交易笔数
     */
    fun clearAllLedger(onDone: (Int) -> Unit) {
        if (_state.value.clearBusy) return
        _state.update { it.copy(clearBusy = true, clearMessage = null) }
        scope.launch {
            runCatching { repo.clearAllLedger() }
                .onSuccess { n ->
                    _state.update {
                        it.copy(
                            clearBusy = false,
                            clearMessage = "已清空全部流水：$n 笔交易 + 全部分红送股 + 全部出入金，" +
                                "账户现金已归零。\n标的、自选、交易计划与标签都保留，可以直接重新导入 CSV。",
                        )
                    }
                    onDone(n)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(clearBusy = false, clearMessage = "清空失败：${e.message ?: "未知错误"}")
                    }
                }
        }
    }

    /** 关掉「清空结果」弹窗。 */
    fun dismissClearMessage() {
        _state.update { it.copy(clearMessage = null) }
    }

    // ---- CSV 导入/导出 ----

    /** 导出交易 / 出入金 / 模板 */
    /**
     * 导出 CSV —— 走**系统文件选择器**（老周 2026-09-17，SAF）：用户自选保存位置与文件名。
     * 旧实现固定写 Documents/StockNote/csv/，用户找不到文件或无法选位置。
     */
    fun exportCsv(kind: String, onDone: () -> Unit) {
        if (_state.value.csvBusy) return
        _state.update { it.copy(csvBusy = true, csvMessage = null) }
        scope.launch {
            runCatching {
                val bridge = com.stocknote.feature.state.FileBridgeHolder.impl
                    ?: error("当前平台未接入系统文件选择器")
                val today = com.stocknote.data.platform.todayIso().replace("-", "")
                val name: String
                val content: String
                val label: String
                when (kind) {
                    "trades" -> {
                        name = "stocknote-trades-$today.csv"
                        content = csv.buildTradesCsv()
                        label = "交易记录"
                    }
                    "cashflows" -> {
                        name = "stocknote-cashflows-$today.csv"
                        content = csv.buildCashFlowsCsv()
                        label = "出入金"
                    }
                    "plans" -> {
                        // 交易计划（老周 2026-09-21）：与交易/出入金同一入口，只是另一个表
                        name = "stocknote-plans-$today.csv"
                        content = plan.buildCsv()
                        label = "交易计划"
                    }
                    else -> {
                        name = "stocknote-template-$kind.csv"
                        content = csv.buildCsvTemplate(kind)
                        label = "模板"
                    }
                }
                val saved = bridge.saveFile(name, "text/csv", content)
                if (saved == null) "已取消导出" else "$label 已导出：$saved"
            }.onSuccess { msg ->
                _state.update { it.copy(csvBusy = false, csvMessage = msg) }
                onDone()
            }.onFailure { e ->
                _state.update { it.copy(csvBusy = false, csvMessage = "导出失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /** 导入（kind: trades / cashflows） */
    /**
     * 导入 CSV —— 走**系统文件选择器**（老周 2026-09-17，SAF）：用户自选文件，不再依赖固定目录。
     */
    fun importCsv(kind: String, onDone: () -> Unit) {
        if (_state.value.csvBusy) return
        _state.update { it.copy(csvBusy = true, csvMessage = null) }
        scope.launch {
            runCatching {
                val bridge = com.stocknote.feature.state.FileBridgeHolder.impl
                    ?: error("当前平台未接入系统文件选择器")
                val picked = bridge.openFile("text/csv")
                    ?: return@runCatching null
                val r = when (kind) {
                    "cashflows" -> trade.applyCashFlowsCsv(picked.second)
                    "plans" -> plan.applyCsv(picked.second)
                    else -> trade.applyTradesCsv(picked.second)
                }
                picked.first to r
            }.onSuccess { pair ->
                if (pair == null) {
                    _state.update { it.copy(csvBusy = false, csvMessage = "已取消导入") }
                    return@onSuccess
                }
                val (_, r) = pair
                val tail = if (r.skipped.isEmpty()) "" else "；跳过 ${r.skipped.size} 行（${r.skipped.take(3).joinToString("；")}${if (r.skipped.size > 3) " …" else ""}）"
                _state.update {
                    it.copy(
                        csvBusy = false,
                        csvMessage = "导入完成：成功 ${r.okRows} 行$tail",
                    )
                }
                onDone()
            }.onFailure { e ->
                _state.update { it.copy(csvBusy = false, csvMessage = "导入失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * 下载**三个模板的 zip 包**（老周 2026-09-21）：交易记录 / 出入金 / 交易计划，一次拿到。
     *
     * 走 SAF 自选保存位置；内容是二进制 zip，所以用 `saveBytes`（不是 saveFile）。
     */
    fun downloadTemplates(onDone: () -> Unit) {
        if (_state.value.csvBusy) return
        _state.update { it.copy(csvBusy = true, csvMessage = null) }
        scope.launch {
            runCatching {
                val bridge = com.stocknote.feature.state.FileBridgeHolder.impl
                    ?: error("当前平台未接入系统文件选择器")
                val today = com.stocknote.data.platform.todayIso().replace("-", "")
                val bytes = csv.buildTemplatesZip()
                bridge.saveBytes("stocknote-templates-$today.zip", "application/zip", bytes)
            }.onSuccess { saved ->
                _state.update {
                    it.copy(
                        csvBusy = false,
                        csvMessage = if (saved == null) "已取消导出"
                        else "模板包已导出（交易记录 / 出入金 / 交易计划 3 个 CSV）：$saved",
                    )
                }
                onDone()
            }.onFailure { e ->
                _state.update {
                    it.copy(csvBusy = false, csvMessage = "导出失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /**
     * 导出**运行日志**（老周 2026-10-01）。
     *
     * 为什么需要：App 一旦出现「行情拉不到」「数字算不对」「备份恢复失败」这类问题，
     * 用户把日志文件发过来即可定位 —— 日志里含每只标的的取数失败原因、异常堆栈、
     * 设备与系统信息、App 版本。复用 CSV 导出那套 `csvBusy / csvMessage` 状态。
     *
     * ⚠️ 日志只含**标的代码与操作时间**，不含密码、口令与备份内容本身。
     */
    fun exportLog(versionLabel: String, onDone: () -> Unit) {
        if (_state.value.csvBusy) return
        // csvBusy 是「文件导出中」的公用重入锁（日志导出也走系统文件选择器，同样不能并发）
        _state.update { it.copy(csvBusy = true, logMessage = null) }
        scope.launch {
            runCatching {
                val bridge = com.stocknote.feature.state.FileBridgeHolder.impl
                    ?: error("当前平台未接入系统文件选择器")
                val stamp = com.stocknote.data.platform.todayIso().replace("-", "") + "-" +
                    (com.stocknote.data.platform.nowEpochMs() % 100_000)
                val header = "App 版本：${versionLabel.ifBlank { "未知" }}\n" +
                    "平台：${com.stocknote.data.platform.platformInfo()}"
                bridge.saveFile(
                    "StockNote-log-$stamp.txt",
                    "text/plain",
                    com.stocknote.data.log.SnLog.dump(header),
                )
            }.onSuccess { saved ->
                _state.update {
                    it.copy(
                        csvBusy = false,
                        logMessage = if (saved == null) "已取消导出" else "运行日志已导出：$saved",
                    )
                }
                onDone()
            }.onFailure { e ->
                _state.update {
                    it.copy(csvBusy = false, logMessage = "导出日志失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 改字体大小：落库 + 更新全局状态（即时生效，无需重启） */
    fun setFontScale(v: Float) {
        _state.update { it.copy(fontScale = v) }
        scope.launch {
            runCatching { settings.saveFontScale(v.toDouble()) }
            com.stocknote.feature.state.AppDisplaySettings.setFontScale(v)
        }
    }

    // ---- 备份与恢复（REQ-SEC-03）----

    fun toggleBackup(mode: BackupMode) {
        if (mode == BackupMode.NONE) {
            _state.update { it.copy(backupMode = BackupMode.NONE, backupMessage = null) }
            return
        }
        // 旧「预扫固定目录列出备份文件」的逻辑已随 BackupFileStore 一起移除（老周 2026-09-23）：
        // 导出/导入都走 SAF 由用户自选位置，不需要预先扫描任何目录。
        _state.update { it.copy(backupMode = mode, backupMessage = null) }
    }

    fun closeBackup() = _state.update { it.copy(backupMode = BackupMode.NONE, backupMessage = null) }

    fun runBackup(password: String, onRestoreDone: () -> Unit) {
        val s = _state.value
        if (s.backupBusy) return
        _state.update { it.copy(backupBusy = true, backupMessage = null) }
        scope.launch {
            try {
                when (s.backupMode) {
                    BackupMode.EXPORT -> {
                        // SAF：用户自选保存位置（老周 2026-09-17）
                        val bridge = com.stocknote.feature.state.FileBridgeHolder.impl
                            ?: error("当前平台未接入系统文件选择器")
                        val encoded = backup.buildEncryptedBackup(password)
                        val name = "StockNote-backup-" +
                            com.stocknote.data.platform.todayIso().replace("-", "") + "-" +
                            (com.stocknote.data.platform.nowEpochMs() % 100_000) + ".snbk"
                        val saved = bridge.saveFile(name, "application/octet-stream", encoded)
                        // 导出成功后**自动关闭弹窗**，结果文案显示在「备份与恢复」卡片区
                        // （老周 2026-09-16：此前弹窗不关，用户不知道还要手动取消）
                        _state.update {
                            it.copy(
                                backupMode = BackupMode.NONE,
                                backupBusy = false,
                                backupMessage = if (saved == null) "已取消导出" else "已导出：$saved",
                            )
                        }
                    }
                    BackupMode.IMPORT -> {
                        // SAF：用户选 .snbk 文件（老周 2026-09-17）
                        val bridge = com.stocknote.feature.state.FileBridgeHolder.impl
                            ?: error("当前平台未接入系统文件选择器")
                        val picked = bridge.openFile("*/*") ?: run {
                            _state.update { it.copy(backupBusy = false, backupMessage = "已取消恢复") }
                            return@launch
                        }
                        val count = backup.restoreFromEncoded(picked.second, password)
                        _state.update {
                            it.copy(
                                backupBusy = false,
                                backupMessage = "恢复完成（$count 条记录），账本已刷新",
                            )
                        }
                        load()
                        onRestoreDone()
                    }
                    BackupMode.NONE -> {}
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(backupBusy = false, backupMessage = "失败：${t.message ?: "未知错误"}")
                }
            }
        }
    }

    fun setCurrency(v: String) = _state.update { it.copy(currency = v) }
    fun setEffectiveDate(v: String) = _state.update { it.copy(effectiveDate = v) }
    fun setRateText(v: String) = _state.update { it.copy(rateText = v) }
    fun toggleCreate() = _state.update { it.copy(showCreate = !it.showCreate, error = null) }

    /**
     * 读取「整手校验」开关（老周 2026-09-28）。界面进入时调一次 —— 不进 [load] 是为了
     * 把它与"汇率/费率"那批设置解耦，避免以后再动 load 时牵到它。
     */
    fun loadLotCheck() {
        scope.launch {
            val enabled = runCatching { settings.lotCheckEnabled() }.getOrDefault(true)
            _state.update { it.copy(lotCheckEnabled = enabled) }
        }
    }

    /** 切换「整手校验」并立即落库（加密 KV）。 */
    fun setLotCheck(enabled: Boolean) {
        _state.update { it.copy(lotCheckEnabled = enabled) }
        scope.launch { runCatching { settings.setLotCheckEnabled(enabled) } }
    }

    fun setFeeAShare(v: String) = _state.update { it.copy(feeAShare = v) }
    fun setFeeHk(v: String) = _state.update { it.copy(feeHk = v) }
    fun setStampAShare(v: String) = _state.update { it.copy(stampAShare = v) }
    fun setStampHk(v: String) = _state.update { it.copy(stampHk = v) }

    /**
     * 切换「隐藏盈亏模式」（REQ-VIEW-09，老周 2026-09-30）：落库 + **立即**写进
     * [AppDisplaySettings]（App 根部据此设置 `Format.privacyMasked`）。
     *
     * ⚠️ 不写 AppDisplaySettings 的话，只有设置页自己的开关变了、别的页面仍显示真数字。
     */
    fun setPrivacyMode(on: Boolean) {
        _state.update { it.copy(privacyMode = on) }
        AppDisplaySettings.setPrivacyMode(on)
        scope.launch { runCatching { settings.setPrivacyMode(on) } }
    }
    fun dismissFeeMessage() = _state.update { it.copy(feeMessage = null) }

    /**
     * 保存**手续费率 + 印花税率**（老周 2026-09-30 合并同一保存按钮）：
     * 落库 + 同步到 [AppDisplaySettings]（记一笔立即生效）。
     *
     * 校验：四个值都必须 > 0；**印花税允许为 0**（用户可能想按法定但某些市场不收，
     * 或干脆关掉税的计算），但为负数一律拒绝。
     */
    fun saveFeeRates() {
        val s = _state.value
        val a = s.feeAShare.trim().toDoubleOrNull()
        val h = s.feeHk.trim().toDoubleOrNull()
        val sa = s.stampAShare.trim().toDoubleOrNull()
        val sh = s.stampHk.trim().toDoubleOrNull()
        if (a == null || a <= 0.0 || h == null || h <= 0.0) {
            _state.update {
                it.copy(feeMessage = "两个手续费率都必须是大于 0 的数字（万分之值）", feeTick = it.feeTick + 1)
            }
            return
        }
        if (sa == null || sa < 0.0 || sh == null || sh < 0.0) {
            _state.update {
                it.copy(feeMessage = "印花税率必须是不小于 0 的数字（万分之值，填 0 = 不计税）", feeTick = it.feeTick + 1)
            }
            return
        }
        scope.launch {
            runCatching { settings.saveFeeRates(a, h) }
            runCatching { settings.saveStampDutyRates(sa, sh) }
            AppDisplaySettings.setFeeRates(AppDisplaySettings.feeText(a), AppDisplaySettings.feeText(h))
            AppDisplaySettings.setStampDutyRates(AppDisplaySettings.feeText(sa), AppDisplaySettings.feeText(sh))
            _state.update {
                it.copy(
                    feeMessage = "已保存：手续费 A 股万分之 ${AppDisplaySettings.feeText(a)} · 港股万分之 ${AppDisplaySettings.feeText(h)}；" +
                        "印花税 A 股卖出万分之 ${AppDisplaySettings.feeText(sa)} · 港股万分之 ${AppDisplaySettings.feeText(sh)}",
                    feeTick = it.feeTick + 1,
                )
            }
        }
    }

    fun setCashEquivInput(v: String) = _state.update { it.copy(cashEquivInput = v) }
    fun dismissCashEquivMessage() = _state.update { it.copy(cashEquivMessage = null) }

    /**
     * 保存现金等价物名单（REQ-ACC-07，老周 2026-09-20）：落库 + 全量回写 security.is_cash_equivalent。
     * 保存后持仓 / 现金口径即时变化 —— 两者都是**实时重放**算出来的，无需改任何历史账。
     */
    fun saveCashEquivalents(onDone: () -> Unit = {}) {
        val raw = _state.value.cashEquivInput
        scope.launch {
            runCatching { repo.saveCashEquivalentSymbols(raw) }
                .onSuccess { hit ->
                    val entries = CashEquivalents.parse(raw)
                    val msg = when {
                        entries.isEmpty() -> "已清空名单：所有标的都按持仓统计"
                        hit == 0 -> "已保存：${entries.joinToString("、")}（当前账本暂无匹配标的，之后记账会自动归入现金）"
                        else -> "已保存：${entries.joinToString("、")}，共 $hit 个标的归入现金"
                    }
                    _state.update { it.copy(cashEquivMessage = msg, cashEquivTick = it.cashEquivTick + 1) }
                    // 口径变了 → 让外层重算（持仓 / 现金 / 总资产）
                    onDone()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            cashEquivMessage = "保存失败：${e.message ?: "未知错误"}",
                            cashEquivTick = it.cashEquivTick + 1,
                        )
                    }
                }
        }
    }

    fun saveRate(onDone: () -> Unit) {
        val s = _state.value
        val rate = s.rateText.trim().toDoubleOrNull()
        when {
            s.currency.isBlank() ->
                _state.update { it.copy(error = "请选择币种", saveFailTick = it.saveFailTick + 1) }
            s.effectiveDate.isBlank() ->
                _state.update { it.copy(error = "请选择生效日期", saveFailTick = it.saveFailTick + 1) }
            rate == null || rate <= 0.0 ->
                _state.update {
                    it.copy(error = "汇率必须是大于 0 的数字（1 单位外币兑 CNY）", saveFailTick = it.saveFailTick + 1)
                }
            else -> scope.launch {
                runCatching { repo.saveFxRate(s.currency, s.effectiveDate, rate) }
                _state.update { it.copy(showCreate = false, rateText = "", error = null) }
                load()
                onDone()
            }
        }
    }

    fun deleteRate(currency: String, effectiveDate: String) {
        scope.launch {
            runCatching { repo.deleteFxRate(currency, effectiveDate) }
            load()
        }
    }
}

@Composable
fun rememberSettingsHolder(
    repo: PortfolioRepository,
    settings: SettingsRepository,
    plan: PlanRepository,
    csv: CsvRepository,
    trade: TradeRepository,
    backup: BackupRepository,
): SettingsHolder {
    val scope = rememberCoroutineScope()
    return androidx.compose.runtime.remember(repo, settings, plan, csv, trade, backup) {
        SettingsHolder(repo, settings, plan, csv, trade, backup, scope)
    }
}

@Composable
private fun SettingsScreenContent(
    holder: SettingsHolder,
    onBackupDone: () -> Unit = {},
    /**
     * 账本口径被改动后回调（老周 2026-09-20）：目前只有「现金等价物名单」用到 ——
     * 名单变化会改变持仓 / 现金的统计口径，需让外层重算（dataVersion++）。
     */
    onLedgerChanged: () -> Unit = {},
) {
    val state by holder.state.collectAsState()
    LaunchedEffect(Unit) { holder.load() }
    // 老周 2026-09-28：整手校验开关（单独读，避免与「汇率/费率」那批 load 耦合）
    LaunchedEffect(Unit) { holder.loadLotCheck() }
    // 清空所有标的（老周 2026-09-30）：开关只控制两次确认弹窗
    var showClearLedger by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background).testTag("screen_settings"),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TopBar(title = "设置") { AppNav.pop() }
            }
        }

        // ---- 账户 ----
        item {
            Sec(title = "账户", modifier = Modifier.padding(top = 8.dp)) {
                CardBox {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEAF1FE)),
                            contentAlignment = Alignment.Center,
                        ) { Text("🏦", fontSize = pageSp(17f)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            // 数据驱动（老周 2026-09-17）：显示库里的账户名 + 币种；
                            // 没有账户时提示"尚未创建"——避免此前写死文案与真实数据脱节
                            // （BUG-03 就是这么被掩盖的：空账本没账户，卡片却写着有账户）。
                            if (state.accounts.isEmpty()) {
                                Text(
                                    "尚未创建（记第一笔交易时自动创建）",
                                    fontSize = pageSp(14f), fontWeight = FontWeight.Bold,
                                    color = StockNoteColors.TextSecondary,
                                )
                                Text(
                                    "无账号 · 无云端 · 数据仅在本机",
                                    fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            } else {
                                state.accounts.forEachIndexed { idx, acc ->
                                    Text(
                                        acc.name,
                                        fontSize = pageSp(14f), fontWeight = FontWeight.Bold,
                                        color = StockNoteColors.TextPrimary,
                                        modifier = Modifier.padding(top = if (idx == 0) 0.dp else 3.dp),
                                    )
                                    Text(
                                        "币种 ${acc.currency}",
                                        fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- 显示：字体大小（技术方案-字体大小设置 · 方案 C 务实版，老周 2026-09-16）----
        item {
            // ---- 手续费率缺省（老周 2026-09-18：A 股 2.5 / 港股 5，记一笔自动填入）----
            Sec(title = "费率 · 手续费 / 印花税", modifier = Modifier.padding(top = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Column(Modifier.weight(1f)) {
                        LabeledField(
                            label = "手续费 A 股（万分之）",
                            value = state.feeAShare,
                            onValueChange = holder::setFeeAShare,
                            numeric = true,
                            placeholder = "2.5",
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        LabeledField(
                            label = "手续费 港股（万分之）",
                            value = state.feeHk,
                            onValueChange = holder::setFeeHk,
                            numeric = true,
                            placeholder = "5",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "记一笔时按标的所属市场自动填入的缺省费率，每笔仍可在表单里单独修改；美股沿用 A 股的值。",
                    fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                    lineHeight = pageSp(16f),
                    modifier = Modifier.padding(top = 3.dp),
                )
                Spacer(Modifier.height(12.dp))
                // ---- 印花税（REQ-ACC-17，老周 2026-09-30）：与手续费率同一个区块、同一个保存按钮 ----
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Column(Modifier.weight(1f)) {
                        LabeledField(
                            label = "印花税 A 股（万分之，仅卖出）",
                            value = state.stampAShare,
                            onValueChange = holder::setStampAShare,
                            numeric = true,
                            placeholder = "5",
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        LabeledField(
                            label = "印花税 港股（万分之，双向）",
                            value = state.stampHk,
                            onValueChange = holder::setStampHk,
                            numeric = true,
                            placeholder = "10",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "法定税率：A 股只有卖出收（万分之 5）；港股买卖都收（千分之 1 = 万分之 10）。" +
                        "ETF、场外基金、美股不收；交易表单里印花税只展示、不可修改，填 0 表示不计税。",
                    fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                    lineHeight = pageSp(16f),
                    modifier = Modifier.padding(top = 3.dp),
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("保存费率", onClick = { holder.saveFeeRates() })
            }

            // ---- 现金等价物名单（REQ-ACC-07，老周 2026-09-20）----
            Sec(title = "现金等价物", modifier = Modifier.padding(top = 16.dp)) {
                LabeledField(
                    label = "标的代码（多个用逗号分隔）",
                    value = state.cashEquivInput,
                    onValueChange = holder::setCashEquivInput,
                    placeholder = "如 511660,511880",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "填在这里的标的按「类现金」处理：不计入持仓明细与占比，市值并入「现金」栏、" +
                        "与可用现金合并展示，总资产不变。填代码即可，市场前缀可省" +
                        "（填 511660 同样能匹配 sh511660）。",
                    fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                    lineHeight = pageSp(16f),
                    modifier = Modifier.padding(top = 3.dp),
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("保存名单", onClick = { holder.saveCashEquivalents(onLedgerChanged) })
            }

            Sec(title = "显示", modifier = Modifier.padding(top = 16.dp)) {
                CardBox {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Text("字体大小", fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                        Text(
                            "放大后主流程页面自动适配；个别边角页面允许轻微折行。",
                            fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                            modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                        )
                        val scales = listOf(
                            1.0f to "标准",
                            1.15f to "大",
                            1.3f to "特大",
                            1.4f to "超大",
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            scales.forEach { (v, label) ->
                                val on = kotlin.math.abs(state.fontScale - v) < 0.001f
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(if (on) StockNoteColors.Brand else Color(0xFFF3F6FB))
                                        .clickable { holder.setFontScale(v) }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        label,
                                        fontSize = pageSp(12f),
                                        color = if (on) Color.White else StockNoteColors.TextSecondary,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "预览：总资产 ¥1,208,734.08 · 今日 +1.23%",
                            // 交给 LocalDensity.fontScale 自动缩放即可（与页面真实效果一致）；
                            // 这里不能再手动乘 scale —— 否则 13 × scale² 二次放大，预览比实际大 40%。
                            fontSize = pageSp(13f),
                            color = StockNoteColors.TextPrimary,
                        )
                    }
                }
            }
        }

        // ---- 录入校验（老周 2026-09-28）----
        item {
            Sec(title = "录入校验", modifier = Modifier.padding(top = 16.dp)) {
                CardBox {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEAF1FB)),
                            contentAlignment = Alignment.Center,
                        ) { Text("✅", fontSize = pageSp(17f)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "整手校验",
                                fontSize = pageSp(14f),
                                fontWeight = FontWeight.Bold,
                                color = StockNoteColors.TextPrimary,
                            )
                            Text(
                                "A股 / ETF / 港股的数量须为 100 的整数倍（美股与场外基金不受限）",
                                fontSize = pageSp(11f),
                                color = StockNoteColors.TextTertiary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.lotCheckEnabled,
                            onCheckedChange = { holder.setLotCheck(it) },
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

        // ---- 数据安全 ----
        item {
            Sec(title = "数据安全", modifier = Modifier.padding(top = 16.dp)) {
                CardBox {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEAF7F1)),
                            contentAlignment = Alignment.Center,
                        ) { Text("🔒", fontSize = pageSp(17f)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("本地数据库加密", fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
                        }
                    }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    // ---- 隐藏盈亏模式（REQ-VIEW-09，老周 2026-09-30）----
                    // 演示时保护隐私：所有**展示用**金额/盈亏/数量/百分比变成 ••••（见 core 的 Format.privacyMasked）。
                    // 只影响显示，不改数据；CSV 导出 / 备份走 fixedPlain，不受影响。
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1EFE8)),
                            contentAlignment = Alignment.Center,
                        ) { Text("🙈", fontSize = pageSp(17f)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "隐藏盈亏模式",
                                fontSize = pageSp(14f),
                                fontWeight = FontWeight.Bold,
                                color = StockNoteColors.TextPrimary,
                            )
                            Text(
                                "所有界面只显示 •••• 占位（金额 / 盈亏 / 数量 / 百分比），给别人看手机时保护隐私；" +
                                    "不影响数据与 CSV 导出。",
                                fontSize = pageSp(11f),
                                color = StockNoteColors.TextTertiary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = state.privacyMode,
                            onCheckedChange = { holder.setPrivacyMode(it) },
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

        // ---- 功能 ----
        // 老周 2026-09-22：设置页瘦身 —— 导入导出 / 备份恢复 / 汇率管理各自独立成页，
        // 这里只留入口（入口样式与标签管理一致）。
        item {
            Sec(title = "功能", modifier = Modifier.padding(top = 16.dp)) {
                CardBox {
                    SettingEntryRow(
                        glyph = "🏷",
                        tint = Color(0xFFEAF1FE),
                        title = "标签管理",
                        sub = "策略标签的新增 / 重命名 / 删除（同步历史交易）",
                    ) { AppNav.push(AppRoute.TagManage) }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    SettingEntryRow(
                        glyph = "💾",
                        tint = Color(0xFFEAF7F1),
                        title = "数据管理",
                        sub = "CSV 导入导出 · 加密备份与恢复",
                    ) { AppNav.push(AppRoute.DataManage) }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    SettingEntryRow(
                        glyph = "💱",
                        tint = Color(0xFFF3F0FE),
                        title = "汇率管理",
                        sub = "手动录入 / 修正各币种对人民币的汇率",
                    ) { AppNav.push(AppRoute.FxRate) }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    // 老周 2026-09-24：巨潮诉讼/担保数据手工同步到本地（个股扫雷只读本地，不每次拉全市场）
                    SettingEntryRow(
                        glyph = "⚖️",
                        tint = Color(0xFFFDF3E7),
                        title = "诉讼担保查询",
                        sub = "拉取巨潮全市场诉讼 / 担保数据并保存到本地",
                    ) { AppNav.push(AppRoute.CninfoSync) }
                }
            }
        }

        // ---- 危险操作（老周 2026-09-30）----
        // 「推倒重来」用：清空全部交易 / 分红 / 出入金（标的与自选保留），两次确认后才执行。
        item {
            Sec(title = "危险操作", modifier = Modifier.padding(top = 16.dp)) {
                DangerButton(
                    text = "🗑 清空所有标的（账本流水）",
                    enabled = !state.clearBusy,
                    onClick = { showClearLedger = true },
                )
                Text(
                    "清空全部交易、分红送股与出入金，账户现金归零；标的 / 自选 / 计划 / 标签保留，" +
                        "便于重新导入 CSV。此操作不可撤销，务必先做一次加密备份。",
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
        }

        item { ProtoFoot("无账号 · 无云端 · 联网仅查公开行情") }
    }

    // 保存 / 修改失败弹窗（老周 2026-09-18）：失败时**明确弹出**，避免「点了按钮没反应」的错觉
    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = listOfNotNull(state.error),
    )

    // 手续费率的保存结果提示（老周 2026-09-18）
    InfoDialog(
        message = state.feeMessage,
        title = "手续费率",
        onDismiss = { holder.dismissFeeMessage() },
    )

    // 现金等价物名单的保存结果提示（老周 2026-09-20）
    InfoDialog(
        message = state.cashEquivMessage,
        title = "现金等价物",
        onDismiss = { holder.dismissCashEquivMessage() },
    )

    // 清空完成提示（老周 2026-09-30）：清空后**必须**给个明确反馈，否则像"点了没反应"
    InfoDialog(
        message = state.clearMessage,
        title = if (state.clearMessage?.startsWith("已清空") == true) "已清空" else "清空结果",
        onDismiss = { holder.dismissClearMessage() },
    )

    // 清空所有标的：**两次确认**（老周 2026-09-30 要求）——第 1 次说做什么，第 2 次说不可撤销的后果
    if (showClearLedger) {
        DangerConfirmDialog(
            title = "清空所有标的的账本流水？",
            message = "将删除全部交易（含备注与截图）、全部分红送股、全部出入金，并把账户现金归零。" +
                "标的、自选、交易计划与标签会保留，之后可以直接重新导入 CSV。",
            warning = "此操作不可撤销：所有流水都会被删除，删掉就找不回来了" +
                "（除非事先做过加密备份）。确定要清空吗？",
            confirmLabel = "清空",
            busy = state.clearBusy,
            onDismiss = { if (!state.clearBusy) showClearLedger = false },
            onConfirm = {
                holder.clearAllLedger {
                    showClearLedger = false
                    onLedgerChanged()
                }
            },
        )
    }
}

/**
 * 设置类「一行入口」：圆角色块图标 + 标题 + 说明 + ›。
 * 老周 2026-09-22：设置页与「数据管理 / 汇率管理」子页共用同一入口样式。
 */
@Composable
internal fun SettingEntryRow(
    glyph: String,
    tint: Color,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, fontSize = pageSp(17f)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = pageSp(14f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
            Text(
                sub,
                fontSize = pageSp(11f), color = StockNoteColors.TextTertiary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Text("›", fontSize = pageSp(14f), color = StockNoteColors.TextTertiary)
    }
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与统计页一致；未包的页面默认 1f 不受影响）。 */
@Composable
fun SettingsScreen(
    holder: SettingsHolder,
    onBackupDone: () -> Unit = {},
    /**
     * 账本口径被改动后回调（老周 2026-09-20）：目前只有「现金等价物名单」用到 ——
     * 名单变化会改变持仓 / 现金的统计口径，需让外层重算（dataVersion++）。
     */
    onLedgerChanged: () -> Unit = {},
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        SettingsScreenContent(holder, onBackupDone, onLedgerChanged)
    }
}
