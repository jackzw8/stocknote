package com.stocknote.data.repo

import com.stocknote.core.model.Currency
import com.stocknote.core.model.FxRateRecord

/**
 * **汇率域 Repository**（阶段 1：委托式拆分，2026-09-28）。
 *
 * ⚠️ **当前实现只做转发**，内部逻辑仍在 [PortfolioRepository] 里 —— 这是刻意的两步走：
 *  1. **阶段 1（本文件）**：先把「调用方依赖的是哪个域」这件事定下来。转发是**零行为变化**，
 *     编译器就能保证签名与类型一致（参数顺序写错、返回值类型不符都会直接报错）。
 *  2. **阶段 2（后续）**：再把实现逐个搬过来。那时因为调用方已经依赖本类，
 *     搬动对调用方**无感**，可以一次搬一个域、逐个验证。
 *
 * 之所以不一次性搬完：`PortfolioRepository` 有 117 个方法、189 处调用，
 * 而 data 层目前**没有针对它的行为测试**，一次性重构无法验证。
 *
 * 域内职责：汇率录入 / 查询 / 自动更新 / 删除 / 裁剪（4 位小数口径见 [com.stocknote.core.model.FxTable.roundRate]）。
 */
class FxRepository internal constructor(
    private val repo: PortfolioRepository,
) {
    /** 每币种最新一条（基准日 = 今天）。 */
    suspend fun latest(onOrBefore: String = com.stocknote.data.platform.todayIso()): Map<String, Double> =
        repo.latestFxRates(onOrBefore)

    /** 全量汇率记录（管理页列表用）。 */
    suspend fun all(): List<FxRateRecord> = repo.fxRates()

    /** 录入 / 覆盖一条汇率（写库前统一 4 位小数）。 */
    suspend fun save(currency: String, effectiveDate: String, rate: Double) =
        repo.saveFxRate(currency, effectiveDate, rate)

    /** 某日某币种的汇率（联网取，CNY 恒 1.0）。 */
    suspend fun rateOn(dateIso: String, currency: Currency): Double? =
        repo.fxRateOn(dateIso, currency)

    /** 每天首次打开 App 时自动更新一次。 */
    suspend fun autoUpdateIfNeeded() = repo.autoUpdateFxRatesIfNeeded()

    /** 删除一条汇率（会触发曲线缓存失效）。 */
    suspend fun delete(currency: String, effectiveDate: String) =
        repo.deleteFxRate(currency, effectiveDate)

    /** 裁剪：每币种只保留最近 keepPerCurrency 条。 */
    suspend fun prune(keepPerCurrency: Int = repo.FX_KEEP_PER_CURRENCY) =
        repo.pruneFxRates(keepPerCurrency)
}
