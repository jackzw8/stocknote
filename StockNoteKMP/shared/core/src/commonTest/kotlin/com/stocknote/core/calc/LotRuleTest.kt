package com.stocknote.core.calc

import com.stocknote.core.model.Market
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 每手规则与整手校验的护栏（老周 2026-09-28）。 */
class LotRuleTest {

    @Test
    fun 每手规则按市场区分() {
        assertEquals(100.0, LotRule.lotSizeOf(Market.A_SHARE))
        assertEquals(100.0, LotRule.lotSizeOf(Market.ETF))
        assertEquals(100.0, LotRule.lotSizeOf(Market.HK))   // 近似值
        assertEquals(1.0, LotRule.lotSizeOf(Market.US))     // 美股没有「手」
        assertEquals(1.0, LotRule.lotSizeOf(Market.FUND))   // 场外基金按份额
    }

    @Test
    fun 提示文案只有整手市场才显示() {
        assertEquals("100股/手", LotRule.hintOf(Market.A_SHARE))
        assertEquals("100股/手", LotRule.hintOf(Market.ETF))
        assertNull(LotRule.hintOf(Market.US), "美股没有手的概念，不该显示提示")
        assertNull(LotRule.hintOf(Market.FUND))
    }

    @Test
    fun 整手数量通过() {
        assertNull(LotRule.check(100.0, Market.A_SHARE, true))
        assertNull(LotRule.check(200.0, Market.ETF, true))
        assertNull(LotRule.check(1000.0, Market.HK, true))
    }

    @Test
    fun 零星股被拦下() {
        val msg = LotRule.check(37.0, Market.A_SHARE, true)
        assertNotNull(msg)
        assertTrue(msg.contains("不足一手"), "应提示不足一手，实际：$msg")
    }

    @Test
    fun 非整手倍数被拦下() {
        val msg = LotRule.check(150.0, Market.A_SHARE, true)
        assertNotNull(msg)
        assertTrue(msg.contains("整数倍"), "应提示整数倍，实际：$msg")
    }

    @Test
    fun 开关关闭时一律放行() {
        assertNull(LotRule.check(37.0, Market.A_SHARE, false))
        assertNull(LotRule.check(150.0, Market.ETF, false))
    }

    @Test
    fun 美股与场外基金不受整手约束() {
        assertNull(LotRule.check(37.0, Market.US, true))
        assertNull(LotRule.check(37.5, Market.FUND, true))
    }

    @Test
    fun 浮点容差_接近整手不误判() {
        assertNull(LotRule.check(100.0000000001, Market.A_SHARE, true))
    }

    @Test
    fun 非正数交给既有校验处理() {
        assertNull(LotRule.check(0.0, Market.A_SHARE, true))
        assertNull(LotRule.check(-5.0, Market.A_SHARE, true))
    }
}
