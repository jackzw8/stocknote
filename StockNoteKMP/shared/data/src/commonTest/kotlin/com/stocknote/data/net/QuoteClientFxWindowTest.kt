package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「成交日汇率」回填的**窗口分档**（[QuoteClient.fxOnWindows]）。
 *
 * ⚠️ 老周 2026-09-29 实测腾讯外汇日 K：
 *  - `days=400` 只覆盖约 18 个月 → 更早的交易取不到汇率（表单只能留空让用户手填）；
 *  - `days=2000` 可覆盖到 2018-07（约 8 年）；
 *  - 但 `days=3000` 会被接口拒绝、**整批返回空**（比 400 还差）。
 * 因此必须"先小后大"分档补拉，且上限不得放大到 2000 以上。
 */
class QuoteClientFxWindowTest {

    @Test
    fun 默认分档_先小后大() {
        assertEquals(
            listOf(400, 2000),
            QuoteClient.fxOnWindows(QuoteClient.FX_ON_DEFAULT_DAYS),
            "⚠️ 默认必须先 400（快）再 2000（补老日期）",
        )
    }

    @Test
    fun 自定义窗口_去重且升序() {
        // 传 2000（等于补拉档）不应产生重复请求
        assertEquals(listOf(2000), QuoteClient.fxOnWindows(2000))
        // 传中间值 → 两档
        assertEquals(listOf(1000, 2000), QuoteClient.fxOnWindows(1000))
    }

    @Test
    fun 非正窗口被剔除() {
        assertEquals(listOf(2000), QuoteClient.fxOnWindows(0))
        assertEquals(listOf(2000), QuoteClient.fxOnWindows(-5))
    }
}
