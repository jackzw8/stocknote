package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 美股代码 → 东财 `SECUCODE`（[QuoteClient.usSecuCode]，老周 2026-09-30）。
 *
 * ⚠️ 两套后缀**不一样**，混用会**静默查不到**（美股分红于是永远为空）：
 *  - 东财 F10 用**单字母**：`O` = 纳斯达克 / `N` = 纽交所 / `A` = 美交所；
 *  - 腾讯行情用 `.OQ` / `.N` / `.A`。
 * 本 App 建档的 symbol 多为无后缀形态（`usAAPL`）→ 按 O → N → A **依次探测**。
 */
class UsSecuCodeTest {

    @Test
    fun 无后缀时探测三个交易所() {
        assertEquals("AAPL" to listOf("O", "N", "A"), QuoteClient.usSecuCode("usAAPL"))
        // 大小写不敏感（历史建档可能有 usaapl）
        assertEquals("AAPL" to listOf("O", "N", "A"), QuoteClient.usSecuCode("usaapl"))
    }

    @Test
    fun 带腾讯后缀时映射成东财单字母() {
        assertEquals("AAPL" to listOf("O"), QuoteClient.usSecuCode("usAAPL.OQ"))
        assertEquals("IBM" to listOf("N"), QuoteClient.usSecuCode("usIBM.N"))
        assertEquals("XXX" to listOf("A"), QuoteClient.usSecuCode("usXXX.A"))
    }

    @Test
    fun 非美股或空代码返回null() {
        assertNull(QuoteClient.usSecuCode("sh600519"))
        assertNull(QuoteClient.usSecuCode("hk00700"))
        assertNull(QuoteClient.usSecuCode("of000001"))
        // ⚠️ 只有前缀没有代码 → null（否则会拿空代码去请求）
        assertNull(QuoteClient.usSecuCode("us"))
    }
}
