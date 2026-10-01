package com.stocknote.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 汇率四小数口径（老周 2026-09-21 定）。
 *
 * 这条口径的意义：**屏幕上看到的 = 库里存的 = 参与折算的**。
 * 网络源给到 6~8 位（0.85362499）时不收敛，列表页按 4 位显示、折算却用 8 位，
 * 用户照着屏幕手改后又变成第三个值 —— 三位数对冲实测差几分钱。
 */
class FxTableTest {

    @Test
    fun roundRateKeepsFourDecimals() {
        assertEquals(0.8536, FxTable.roundRate(0.85362499))
        assertEquals(6.7071, FxTable.roundRate(6.7070999999))
        assertEquals(0.8537, FxTable.roundRate(0.85366))
        assertEquals(0.8536, FxTable.roundRate(0.85364))
        // 不足 4 位不做补零（值本身没变）
        assertEquals(0.86, FxTable.roundRate(0.86))
        assertEquals(1.0, FxTable.roundRate(1.0))
    }

    @Test
    fun roundRateIsStableUnderRepeat() {
        val once = FxTable.roundRate(0.85362499)
        // 幂等：再收敛一次必须完全相等（否则"保存两次金额会漂"）
        assertEquals(once, FxTable.roundRate(once))
    }

    @Test
    fun roundRateLeavesNonFiniteAlone() {
        assertTrue(FxTable.roundRate(Double.NaN).isNaN())
        assertTrue(FxTable.roundRate(Double.POSITIVE_INFINITY).isInfinite())
    }

    @Test
    fun convertWithUsesTheRoundedRate() {
        val raw = 0.85362499
        val r4 = FxTable.roundRate(raw)
        assertEquals(0.8536, r4)
        // 1 万港币按收敛后的汇率折算 = 8536.00
        val amount = FxTable.convertWith(10_000.0, Currency.HKD, Currency.CNY, mapOf("HKD" to r4))
        assertEquals(8536.0, kotlin.math.round(amount * 100.0) / 100.0)
        // 用未收敛的原始值会多出一点点（说明"显示 4 位"与"按 8 位算"确实不是一回事）
        assertTrue(FxTable.convertWith(10_000.0, Currency.HKD, Currency.CNY, mapOf("HKD" to raw)) > amount)
    }

    // ---------------------------------------------------------------- 逐日汇率（2026-10-01 老周对账修复）

    /**
     * ⚠️ 这组守的是「**港股当日盈亏少了汇率波动那一块**」那次事故：
     * 曲线原先只从 `fx_rate` 表取逐日汇率，而用户手工只录了最近 3 天（老周真机实测），
     * 于是 `rateOn(昨天)` 与 `rateOn(今天)` 命中同一条 → **汇率波动被整段抵消**，
     * 港股盈亏与券商差约 1%（腾讯 9-01：−15,853.95 vs −15,997.91）。
     * 现在主源换成腾讯外汇日 K（逐日连续），本组钉住两路的合并规则。
     */
    @Test
    fun mergeRateSeriesPrefersKlineOnSameDay() {
        val kline = listOf("2026-09-01" to 0.8540, "2026-09-02" to 0.8545)
        val manual = listOf("2026-09-02" to 0.9000, "2026-09-03" to 0.8560)
        val merged = FxTable.mergeRateSeries(primary = kline, fallback = manual)
        // 升序 + 去重 + 并集
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03"), merged.map { it.first })
        assertEquals(0.8540, merged[0].second, 1e-9)
        // 同一天两者都有 → 以**日 K** 为准（手工值可能录错、也可能只录了几天）
        assertEquals(0.8545, merged[1].second, 1e-9, "同日应取日 K 的 0.8545，而不是手工的 0.9")
        assertEquals(0.8560, merged[2].second, 1e-9, "日 K 没有的日期由手工补齐")
    }

    @Test
    fun mergeRateSeriesHandlesOneSidedInputAndSorts() {
        // ⚠️ 日 K 乱序进来也必须排好 —— EquityCurve.rateOn 是**二分查找**，顺序错了会取到错的汇率
        val kline = listOf("2026-09-02" to 0.85, "2026-09-01" to 0.86)
        assertEquals(
            listOf("2026-09-01", "2026-09-02"),
            FxTable.mergeRateSeries(primary = kline, fallback = emptyList()).map { it.first },
        )
        assertEquals(
            listOf("2026-09-02", "2026-09-03"),
            FxTable.mergeRateSeries(
                primary = emptyList(),
                fallback = listOf("2026-09-03" to 1.0, "2026-09-02" to 2.0),
            ).map { it.first },
        )
        // 两路都空 = 没有逐日汇率 → 交给调用方回退「最新汇率」，不编造数据
        assertTrue(FxTable.mergeRateSeries(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun fxQuoteOfUsesWhPrefixedCodes() {
        // ⚠️ 必须带 `wh` 前缀：`HKDCNY` / `hkHKDCNY` / `usdCNY` 都是**无效**代码，
        //    接口回 `v_pv_none_match="1"`，宽松正则还能抠出个 "1" 当汇率写库（2026-09-16 真机踩过）
        assertEquals("whHKDCNY" to 0.8, FxTable.fxQuoteOf(Currency.HKD))
        assertEquals("whUSDCNY" to 6.0, FxTable.fxQuoteOf(Currency.USD))
        assertNull(FxTable.fxQuoteOf(Currency.CNY), "人民币没有外汇行情，不该发请求")
    }

    @Test
    fun isSaneRateRejectsOutliers() {
        val center = 0.8
        assertTrue(FxTable.isSaneRate(0.8544, center), "实测的正常港币汇率必须通过")
        assertTrue(FxTable.isSaneRate(0.8536, center))
        // 量级完全不对的（残数据 / 串了别的字段）→ 挡掉，宁可回退手工汇率
        assertFalse(FxTable.isSaneRate(2.0, center))
        assertFalse(FxTable.isSaneRate(0.1, center))
        assertFalse(FxTable.isSaneRate(0.0, center))
        assertFalse(FxTable.isSaneRate(Double.NaN, center))
        assertFalse(FxTable.isSaneRate(Double.POSITIVE_INFINITY, center))
    }
}
