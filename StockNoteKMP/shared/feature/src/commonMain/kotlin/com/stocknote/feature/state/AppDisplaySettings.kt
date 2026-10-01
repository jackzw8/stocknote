package com.stocknote.feature.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局显示设置（技术方案-字体大小设置 · 方案 C）。
 *
 * 字体缩放由用户在「设置 → 显示」选择（1.0 / 1.15 / 1.3 / 1.4），
 * 存 `app_setting.font_scale`；本对象是**运行时可观察的副本**，
 * 供 App 根部的 `LocalDensity.fontScale` 使用 —— 变化即触发全局重组，无需重启。
 *
 * 注意：**不跟随系统 fontScale**（避免"系统 1.4 × 应用 1.4"叠乘），
 * 只以本值为准；未设置时默认 1.0。
 */
object AppDisplaySettings {

    private val _fontScale = MutableStateFlow(1.0f)
    val fontScale: StateFlow<Float> = _fontScale.asStateFlow()

    fun setFontScale(v: Float) {
        _fontScale.value = v.coerceIn(0.8f, 1.6f)
    }

    // ---- 启动图（Splash）数据就绪信号（老周 2026-09-17：联网数据加载完可关启动图，2~5 秒）----
    // App.kt 首屏数据就绪时置 true；MainActivity 的 Splash 覆盖层观察它决定何时淡出。

    private val _dataReady = MutableStateFlow(false)
    val dataReady: StateFlow<Boolean> = _dataReady.asStateFlow()

    fun markDataReady() {
        _dataReady.value = true
    }

    // ---- 手续费率缺省（老周 2026-09-18：万分之值；A 股缺省 2.5、港股缺省 5）----
    // 记一笔 / 编辑交易按市场自动填入的缺省费率从这里取；设置页可改，改完立即生效。

    private val _feeRateAShare = MutableStateFlow("2.5")
    val feeRateAShare: StateFlow<String> = _feeRateAShare.asStateFlow()

    private val _feeRateHk = MutableStateFlow("5")
    val feeRateHk: StateFlow<String> = _feeRateHk.asStateFlow()

    fun setFeeRates(aShare: String, hk: String) {
        _feeRateAShare.value = aShare
        _feeRateHk.value = hk
    }

    // ---- 印花税率（REQ-ACC-17，老周 2026-09-30：万分位，与手续费率同口径）----
    // A 股**卖出**缺省万分之 5（填 5）；港股**买卖双向**缺省万分之 10（填 10，即千分之 1）。
    // ETF / 场外基金 / 美股不收 —— 判断在 core 的 StampDuty.rateOf，这里只管税率数值。

    private val _stampDutyAShare = MutableStateFlow("5")
    val stampDutyAShare: StateFlow<String> = _stampDutyAShare.asStateFlow()

    private val _stampDutyHk = MutableStateFlow("10")
    val stampDutyHk: StateFlow<String> = _stampDutyHk.asStateFlow()

    fun setStampDutyRates(aShare: String, hk: String) {
        _stampDutyAShare.value = aShare
        _stampDutyHk.value = hk
    }

    // ---- 隐藏盈亏模式（REQ-VIEW-09，老周 2026-09-30：演示时保护隐私）----
    //
    // 打开后所有**展示用**金额 / 盈亏 / 数量 / 百分比都变成 `•••`（见 core 的 Format.privacyMasked）；
    // 只影响显示，不改数据、不影响 CSV 导出与备份。

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()

    fun setPrivacyMode(on: Boolean) {
        _privacyMode.value = on
    }

    /** 费率万分之值的显示文本：最多 3 位小数，去尾零（2.50 → 2.5、5.000 → 5） */
    fun feeText(v: Double): String =
        // ⚠️ M3 修复（2026-09-28）：同上，改用 Locale 无关的 Format.fixedPlain
        com.stocknote.core.format.Format.fixedPlain(v, 3).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /** 启动时从仓库读一次并同步到内存。 */
    suspend fun loadFrom(settings: com.stocknote.data.repo.SettingsRepository) {
        runCatching { settings.fontScale() }.getOrNull()?.let { setFontScale(it.toFloat()) }
        runCatching { settings.feeRateAShare() }.getOrNull()?.let { _feeRateAShare.value = feeText(it) }
        runCatching { settings.feeRateHk() }.getOrNull()?.let { _feeRateHk.value = feeText(it) }
        // 印花税率（REQ-ACC-17）：同手续费率一并在启动时读入内存
        runCatching { settings.stampDutyAShare() }.getOrNull()?.let { _stampDutyAShare.value = feeText(it) }
        runCatching { settings.stampDutyHk() }.getOrNull()?.let { _stampDutyHk.value = feeText(it) }
        // 隐藏盈亏模式（REQ-VIEW-09）：启动就恢复上次的隐私状态（避免打开 App 先露一帧真数字）
        runCatching { settings.privacyMode() }.getOrNull()?.let { _privacyMode.value = it }
    }
}
