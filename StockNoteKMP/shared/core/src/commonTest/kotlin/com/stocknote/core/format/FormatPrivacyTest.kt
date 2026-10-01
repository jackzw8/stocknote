package com.stocknote.core.format

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「隐藏盈亏模式」的展示层遮罩行为（REQ-VIEW-09，老周 2026-09-30）。
 *
 * 这个开关的**关键不变量**有两条，都在下面钉住：
 *  1. 只遮**展示**函数（money / moneySigned / plain / percent / quantity(展示态) / wan）；
 *  2. **数据侧**（fixedPlain —— CSV / 备份走它）与**回填输入框**（quantity group = false）**不受影响**，
 *     否则"开着隐私模式导出 CSV"会导出一堆 `•••`，编辑交易的输入框也会变成 `•••` 直接存不下。
 */
class FormatPrivacyTest {

    @AfterTest
    fun tearDown() {
        // 全局开关，跑完必须复位，避免污染其它测试类
        Format.privacyMasked = false
    }

    @Test
    fun 遮罩只盖数字与正负号_保留货币符与千分位结构() {
        Format.privacyMasked = true
        assertEquals("¥•,•••,•••.••", Format.money(1_286_540.20))
        assertEquals("•¥••,•••.••", Format.moneySigned(86_540.0))
        assertEquals("•••••.••", Format.plain(23_481.34))
        assertEquals("••.••%", Format.percent(0.094))
        assertEquals("•••.•万", Format.wan(1_347_649.36, decimals = 1))
        // 零与负号同样遮掉（避免泄漏盈亏方向）
        assertEquals("•¥•••.••", Format.moneySigned(-100.0))
    }

    @Test
    fun 关闭时行为与原先完全一致() {
        Format.privacyMasked = false
        assertEquals("¥1,286,540.20", Format.money(1_286_540.20))
        assertEquals("+¥86,540.00", Format.moneySigned(86_540.0))
        assertEquals("+9.40%", Format.percent(0.094))
    }

    @Test
    fun 数量只在展示态遮罩_回填输入框不受影响() {
        Format.privacyMasked = true
        // 展示态（group = true）：遮罩
        assertEquals("•,•••", Format.quantity(1_000.0))
        // ⚠️ group = false 是"回填输入框"用的：必须是可解析的真数字 ——
        // 遮了会让编辑交易时数量框变成 •••，保存被判「数量必须是数字」而完全存不下
        assertEquals("1000", Format.quantity(1_000.0, group = false))
    }

    @Test
    fun 数据侧函数不受遮罩影响() {
        Format.privacyMasked = true
        // CSV / 备份走 fixedPlain：**绝不能**被遮罩
        assertEquals("16986.17", Format.fixedPlain(16_986.17))
        assertTrue(Format.fixedPlain(16_986.17).none { it == '•' }, "fixedPlain 不得出现遮罩字符")
        assertEquals("0.5", Format.fixedPlain(0.5, decimals = 1))
    }
}
