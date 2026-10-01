package com.stocknote.data.repo

import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **设置域 Repository**（阶段 2：实现已搬迁，2026-09-28）。
 *
 * 为什么选它作搬迁对象：它属于**叶子域** —— 在 `PortfolioRepository` 内部
 * **零自用**（动手前逐个 grep 确认过，计数均为 1 = 只有定义），因此可以整体搬走
 * 而不牵动其它逻辑。这与汇率域形成对照：汇率被本类内部 10+ 处使用，属于
 * 「被依赖的基础能力」，必须**最后**搬 —— 那一批已回滚，见当日日志。
 *
 * 分两类键：
 * - **加密 KV**（`keyStore`）：整手校验开关 —— 与账本无关的纯偏好，走 Keystore 加密存储；
 * - **app_setting 表**（`db`）：字体缩放、A 股/港股缺省费率。
 *
 * ⚠️ 口径：费率单位是**万分之值**（A股缺省 2.5、港股缺省 5）。
 */
class SettingsRepository internal constructor(
    private val db: StockNoteDb,
    private val keyStore: SecureKeyStore?,
) {
    /** ⚠️ 与 `PortfolioRepository` 的键名**必须一致** —— 它们是同一份本地数据。 */
    private companion object {
        const val LOT_CHECK_KEY = "lot_check_enabled"
        const val FONT_SCALE_KEY = "font_scale"
        const val FEE_A_SHARE_KEY = "fee_rate_a_share"
        const val FEE_HK_KEY = "fee_rate_hk"
        // 印花税率（REQ-ACC-17，老周 2026-09-30）：**万分位**，与手续费率同口径
        const val STAMP_A_SHARE_KEY = "stamp_duty_a_share"
        const val STAMP_HK_KEY = "stamp_duty_hk"
        // 隐藏盈亏模式（REQ-VIEW-09，老周 2026-09-30）
        const val PRIVACY_KEY = "privacy_mode"
    }

    // ---- 隐藏盈亏模式（REQ-VIEW-09）----

    /**
     * 「隐藏盈亏模式」开关：`"1"` = 开；缺省（无记录）= 关。
     *
     * ⚠️ 它只影响**展示层**（`Format.money` 等一批格式化函数遮罩成 `•••`），
     * 不改任何数据；CSV 导出 / 备份走 `fixedPlain`，**不受影响**。
     */
    suspend fun privacyMode(): Boolean = withContext(Dispatchers.Default) {
        db.settingQueries.selectByKey(PRIVACY_KEY).executeAsOneOrNull()?.setting_value == "1"
    }

    suspend fun setPrivacyMode(on: Boolean) = withContext(Dispatchers.Default) {
        db.settingQueries.upsert(PRIVACY_KEY, if (on) "1" else "0")
    }

    // ---- 录入校验（老周 2026-09-28）----

    /**
     * 「整手校验」是否启用。
     * ⚠️ **缺省开**：只有显式存过 `"0"` 才算关闭 —— KV 里没有记录表示用户从没改过，按默认（开）处理。
     */
    suspend fun lotCheckEnabled(): Boolean = keyStore?.getString(LOT_CHECK_KEY) != "0"

    /** 设置「整手校验」开关。 */
    suspend fun setLotCheckEnabled(enabled: Boolean) {
        keyStore?.putString(LOT_CHECK_KEY, if (enabled) "1" else "0")
    }

    // ---- 字体缩放（技术方案-方案 C）----

    /** 字体缩放（app_setting.font_scale）；未设置默认 1.0。 */
    suspend fun fontScale(): Double = withContext(Dispatchers.Default) {
        db.settingQueries.selectByKey(FONT_SCALE_KEY).executeAsOneOrNull()
            ?.setting_value?.toDoubleOrNull()
            ?: 1.0
    }

    suspend fun saveFontScale(scale: Double) = withContext(Dispatchers.Default) {
        db.settingQueries.upsert(FONT_SCALE_KEY, scale.toString())
    }

    // ---- 手续费率缺省（老周 2026-09-18）----

    /** A 股手续费率缺省（万分之值）；未设置 2.5。 */
    suspend fun feeRateAShare(): Double = withContext(Dispatchers.Default) {
        db.settingQueries.selectByKey(FEE_A_SHARE_KEY).executeAsOneOrNull()
            ?.setting_value?.toDoubleOrNull() ?: 2.5
    }

    /** 港股手续费率缺省（万分之值）；未设置 5。 */
    suspend fun feeRateHk(): Double = withContext(Dispatchers.Default) {
        db.settingQueries.selectByKey(FEE_HK_KEY).executeAsOneOrNull()
            ?.setting_value?.toDoubleOrNull() ?: 5.0
    }

    suspend fun saveFeeRates(aShare: Double, hk: Double) = withContext(Dispatchers.Default) {
        db.settingQueries.upsert(FEE_A_SHARE_KEY, aShare.toString())
        db.settingQueries.upsert(FEE_HK_KEY, hk.toString())
    }

    // ---- 印花税率（REQ-ACC-17，老周 2026-09-30）----
    //
    // 与手续费率同口径：单位**万分位**。缺省 = 法定税率（见 core/calc/StampDuty）：
    // A股卖出万分之 5（填 5）、港股双向万分之 10（填 10，即千分之 1）。
    // ⚠️ ETF / 场外基金 / 美股恒 0（不看这里），判在 StampDuty.rateOf 里。

    /** A 股**卖出**印花税率（万分之值）；未设置 5。 */
    suspend fun stampDutyAShare(): Double = withContext(Dispatchers.Default) {
        db.settingQueries.selectByKey(STAMP_A_SHARE_KEY).executeAsOneOrNull()
            ?.setting_value?.toDoubleOrNull() ?: com.stocknote.core.calc.StampDuty.A_SHARE_SELL_RATE
    }

    /** 港股印花税率（万分之值，**买卖双向**）；未设置 10（千分之 1）。 */
    suspend fun stampDutyHk(): Double = withContext(Dispatchers.Default) {
        db.settingQueries.selectByKey(STAMP_HK_KEY).executeAsOneOrNull()
            ?.setting_value?.toDoubleOrNull() ?: com.stocknote.core.calc.StampDuty.HK_RATE
    }

    suspend fun saveStampDutyRates(aShare: Double, hk: Double) = withContext(Dispatchers.Default) {
        db.settingQueries.upsert(STAMP_A_SHARE_KEY, aShare.toString())
        db.settingQueries.upsert(STAMP_HK_KEY, hk.toString())
    }
}
