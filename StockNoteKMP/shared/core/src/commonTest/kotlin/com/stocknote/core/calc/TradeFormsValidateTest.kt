package com.stocknote.core.calc

import com.stocknote.core.model.TradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「测试用例集-记一笔」§3.5 字段校验（TC-VAL-*）+ §2.5 规则矩阵 V-01~V-15 的单测实现。
 *
 * 用例集 §6 执行说明：校验类优先以单元测试实现（TradeForms.validate / CivilDate.parseIso
 * 均为纯函数，可 100% 断言）。编号与用例集一一对应。
 */
class TradeFormsValidateTest {

    // ---------- helpers ----------

    private fun validate(
        side: String = "BUY",
        quantity: String = "100",
        price: String = "1723.0",
        date: String = "2026-09-13",
        note: String = "分批建仓",
    ) = TradeForms.validate(side, quantity, price, date, note)

    private fun issuesFor(vararg changes: Pair<String, String>): List<TradeForms.Issue> =
        validate().filter { it.field in changes.map { c -> c.first } }

    private fun assertHasIssue(issues: List<TradeForms.Issue>, field: String, messagePart: String? = null) {
        val issue = issues.firstOrNull { it.field == field }
        assertTrue(issue != null, "期望存在 field=$field 的校验问题，实际: $issues")
        if (messagePart != null) {
            assertTrue(
                issue.message.contains(messagePart),
                "期望提示含「$messagePart」，实际: ${issue.message}",
            )
        }
    }

    private fun assertNoIssue(issues: List<TradeForms.Issue>, field: String) {
        assertTrue(issues.none { it.field == field }, "不期望 field=$field 的问题，实际: $issues")
    }

    // ---------- TC-VAL-SIDE ----------

    @Test
    fun `SIDE-01 默认买入无校验问题`() {
        assertNoIssue(validate(side = "BUY"), "side")
        assertNoIssue(validate(side = "SELL"), "side")
    }

    @Test
    fun `SIDE-02 非法方向被拦截`() {
        assertHasIssue(validate(side = "X"), "side", "方向必须是买入或卖出")
    }

    // ---------- TC-VAL-QTY（V-05~V-08） ----------

    @Test
    fun `QTY-01 空`() = assertHasIssue(validate(quantity = ""), "quantity", "请填写数量")

    @Test
    fun `QTY-02 非数字`() {
        assertHasIssue(validate(quantity = "abc"), "quantity", "数量必须是数字")
        assertHasIssue(validate(quantity = "一百"), "quantity", "数量必须是数字")
    }

    @Test
    fun `QTY-03 零`() = assertHasIssue(validate(quantity = "0"), "quantity", "数量必须大于 0")

    @Test
    fun `QTY-04 负数`() = assertHasIssue(validate(quantity = "-100"), "quantity", "数量必须大于 0")

    @Test
    fun `QTY-05 小数股拦截`() =
        assertHasIssue(validate(quantity = "100.5"), "quantity", "A股与ETF按整数股录入")

    @Test
    fun `QTY-06 合法整数通过`() = assertNoIssue(validate(quantity = "100"), "quantity")

    @Test
    fun `QTY-07 首尾空格trim后通过`() = assertNoIssue(validate(quantity = " 100 "), "quantity")

    @Test
    fun `QTY-08 极大值当前放行_G9缺口`() = assertNoIssue(validate(quantity = "1e12"), "quantity")

    // ---------- TC-VAL-PRC（V-09~V-11） ----------

    @Test
    fun `PRC-01 空`() = assertHasIssue(validate(price = ""), "price", "请填写价格")

    @Test
    fun `PRC-02 非数字`() = assertHasIssue(validate(price = "abc"), "price", "价格必须是数字")

    @Test
    fun `PRC-03 零`() = assertHasIssue(validate(price = "0"), "price", "价格必须大于 0")

    @Test
    fun `PRC-04 负数`() = assertHasIssue(validate(price = "-5"), "price", "价格必须大于 0")

    @Test
    fun `PRC-05 小数价格通过`() {
        assertNoIssue(validate(price = "1723.00"), "price")
        assertNoIssue(validate(price = "0.001"), "price")
    }

    // ---------- TC-VAL-DATE（V-12~V-14） ----------

    @Test
    fun `DATE-01 空`() = assertHasIssue(validate(date = ""), "date", "请填写交易日期")

    @Test
    fun `DATE-02 斜杠分隔符拦截`() =
        assertHasIssue(validate(date = "2026/09/13"), "date", "日期格式应为 yyyy-MM-dd")

    @Test
    fun `DATE-03 位数不足拦截`() =
        assertHasIssue(validate(date = "2026-9-3"), "date", "日期格式应为 yyyy-MM-dd")

    @Test
    fun `DATE-04 不存在的日期`() = assertHasIssue(validate(date = "2026-02-30"), "date", "日期越界")

    @Test
    fun `DATE-05 月份越界`() = assertHasIssue(validate(date = "2026-13-01"), "date", "月份越界")

    @Test
    fun `DATE-06 闰年2月29通过`() = assertNoIssue(validate(date = "2024-02-29"), "date")

    @Test
    fun `DATE-07 非闰年2月29拦截`() = assertHasIssue(validate(date = "2026-02-29"), "date", "日期越界")

    @Test
    fun `DATE-08 只有年月拦截`() =
        assertHasIssue(validate(date = "2026-09"), "date", "日期格式应为 yyyy-MM-dd")

    @Test
    fun `DATE-09 带时间后缀取前10位通过_G待确认`() {
        assertNoIssue(validate(date = "2026-09-13 10:00"), "date")
    }

    @Test
    fun `DATE-10 未来日期当前放行_G10缺口`() = assertNoIssue(validate(date = "2030-01-01"), "date")

    // ---------- TC-VAL-NOTE（V-15 / REQ-NOTE-01） ----------

    @Test
    fun `NOTE-01 空`() = assertHasIssue(validate(note = ""), "note", "不能留空")

    @Test
    fun `NOTE-02 纯空格视为空`() {
        assertHasIssue(validate(note = "   "), "note", "不能留空")
        assertHasIssue(validate(note = "\n\t"), "note", "不能留空")
    }

    @Test
    fun `NOTE-03 正常文本通过`() = assertNoIssue(validate(note = " 分批建仓 "), "note")

    // ---------- TC-VAL-FEE（费率**万分位**口径 + **不可为空**，老周 2026-09-16 定稿） ----------

    @Test
    fun `FEE-01 空费率被拦截（不可为空）`() {
        val issues = TradeForms.validate("BUY", "100", "10", "2026-09-13", "x", feeText = "")
        assertHasIssue(issues, "fee", "请填写手续费率")
    }

    @Test
    fun `FEE-02 非数字不再静默归零`() {
        val issues = TradeForms.validate("BUY", "100", "10", "2026-09-13", "x", feeText = "abc")
        assertHasIssue(issues, "fee", "手续费率必须是数字（单位：万分之）")
    }

    @Test
    fun `FEE-03 负费率拦截`() {
        val issues = TradeForms.validate("BUY", "100", "10", "2026-09-13", "x", feeText = "-10")
        assertHasIssue(issues, "fee", "手续费率不能为负")
    }

    @Test
    fun `FEE-04 合法费率通过（万分位）`() {
        // 填 2.5 = 万分之 2.5（A股缺省）
        val issues = TradeForms.validate("BUY", "100", "10", "2026-09-13", "x", feeText = "2.5")
        assertNoIssue(issues, "fee")
        // 0 = 免佣，同样合法
        val zero = TradeForms.validate("BUY", "100", "10", "2026-09-13", "x", feeText = "0")
        assertNoIssue(zero, "fee")
    }

    // ---------- 费率换算与总价（原型 03 口径，小B 定稿 2026-09-14） ----------

    @Test
    fun `费率换算_原型样例（万分位）`() {
        // 价 1723 × 量 100 × 万分之 2.5 = ¥43.075（与原型 43.08 一致）
        val fee = TradeForms.feeAmountOf("1723", "100", "2.5")!!
        assertEquals(43.075, fee, 1e-6)
        assertEquals(43.08, kotlin.math.round(fee * 100) / 100, 1e-9)
    }

    @Test
    fun `费率换算_非法输入返回null`() {
        assertEquals(null, TradeForms.feeAmountOf("abc", "100", "2.5"))
        assertEquals(null, TradeForms.feeAmountOf("1723", "", "2.5"))
        assertEquals(null, TradeForms.feeAmountOf("1723", "100", "abc"))
        assertEquals(null, TradeForms.feeAmountOf("1723", "100", "-1"))
    }

    @Test
    fun `费率反算_编辑回填`() {
        // 存了 43.075 元、价 1723 量 100 → 万分位费率 2.5
        val rate = TradeForms.feeRateOf("1723", "100", 43.075)
        assertEquals("2.5", rate)
        // 零费用 → "0"（可解析，正常回填 0 费率）
        assertEquals("0", TradeForms.feeRateOf("1723", "100", 0.0))
    }

    @Test
    fun `费率反算_入参不可解析时返回null而非静默0`() {
        // ⚠️ H1 护栏（2026-09-28）：带千分位的数量串（旧版回填就传这个）必须返回 null，
        // 绝不能退回 "0" —— 那会让用户一保存就把该笔手续费写成 0（成本价与盈亏全跟着错）。
        assertNull(TradeForms.feeRateOf("1723", "1,000", 43.075), "带千分位的数量应返回 null")
        assertNull(TradeForms.feeRateOf("1723", "", 43.075), "空数量应返回 null")
        assertNull(TradeForms.feeRateOf("abc", "100", 43.075), "非法价格应返回 null")
        // 价×量 为 0 时也无法反算 → null（不是 "0"）
        assertNull(TradeForms.feeRateOf("0", "100", 43.075), "基数为 0 应返回 null")
        // 而**不带千分位**的 1000 股能正常反算 —— 这是修复后的回填口径
        assertEquals("0.25", TradeForms.feeRateOf("1723", "1000", 43.075))
    }

    @Test
    fun `数量回填_不带千分位`() {
        // ⚠️ H1 护栏：展示用带千分位，**回填必须不带**
        assertEquals("1,000", com.stocknote.core.format.Format.quantity(1000.0))
        assertEquals("1000", com.stocknote.core.format.Format.quantity(1000.0, group = false))
        assertEquals("1234567", com.stocknote.core.format.Format.quantity(1234567.0, group = false))
        // 小数不受影响
        assertEquals("1200.5", com.stocknote.core.format.Format.quantity(1200.5, group = false))
    }

    @Test
    fun `总价_买入加费卖出减费`() {
        // 买入：1723×100×(1+2.5/10000) = 172343.075（万分位）
        val buy = TradeForms.totalAmountOf("BUY", "1723", "100", "2.5")!!
        assertEquals(172343.075, buy, 1e-6)
        // 卖出：1750×40×(1−0.025%) = 69982.5
        val sell = TradeForms.totalAmountOf("SELL", "1750", "40", "2.5")!!
        assertEquals(69982.5, sell, 1e-6)
        // 费率空 = 0：总价 = 价×量
        assertEquals(172300.0, TradeForms.totalAmountOf("BUY", "1723", "100", "")!!, 1e-6)
        // 非法输入
        assertEquals(null, TradeForms.totalAmountOf("BUY", "abc", "100", ""))
    }

    // ---------- 佣金下限（老周 2026-09-19：不足 5 元按 5 元） ----------

    @Test
    fun `佣金下限_不足5元按5元`() {
        // 90 × 100 × 万分之 2.5 = 2.25 → 抬到 5.00
        assertEquals(5.0, TradeForms.feeAmountOf("90", "100", "2.5")!!, 1e-9)
        // 恰好 5 元（2000 × 100 × 万分之 0.25）= 5.00，保持
        assertEquals(5.0, TradeForms.feeAmountOf("2000", "100", "0.25")!!, 1e-9)
        // 超过 5 元按实收（不受影响）
        assertEquals(43.075, TradeForms.feeAmountOf("1723", "100", "2.5")!!, 1e-6)
    }

    @Test
    fun `佣金下限_免佣不受限`() {
        // 费率 0 = 免佣：手续费就是 0，不能被抬到 5
        assertEquals(0.0, TradeForms.feeAmountOf("90", "100", "0")!!, 1e-9)
    }

    @Test
    fun `佣金下限_总价与手续费同源`() {
        // 买入：90×100 = 9000，手续费抬到 5 → 总价 9005（不是 9002.25）
        assertEquals(9005.0, TradeForms.totalAmountOf("BUY", "90", "100", "2.5")!!, 1e-9)
        // 卖出：9000 − 5 = 8995
        assertEquals(8995.0, TradeForms.totalAmountOf("SELL", "90", "100", "2.5")!!, 1e-9)
        // 免佣：总价就是价×量
        assertEquals(9000.0, TradeForms.totalAmountOf("BUY", "90", "100", "0")!!, 1e-9)
    }

    @Test
    fun `佣金下限_边界值`() {
        assertEquals(5.0, TradeForms.applyMinFee(0.0, 2.5), 1e-9)     // 极小值也抬到 5
        assertEquals(5.0, TradeForms.applyMinFee(4.999, 2.5), 1e-9)   // 差一点点也抬
        assertEquals(5.0, TradeForms.applyMinFee(5.0, 2.5), 1e-9)     // 恰好 5 保持
        assertEquals(6.0, TradeForms.applyMinFee(6.0, 2.5), 1e-9)     // 超过不动
        assertEquals(0.0, TradeForms.applyMinFee(0.0, 0.0), 1e-9)     // 免佣不动
        assertEquals(2.25, TradeForms.applyMinFee(2.25, 0.0), 1e-9)   // 费率 0 不做任何抬升
    }

    // ---------- 多字段组合（TC-UX-01 的校验层） ----------

    @Test
    fun `UX-01 多字段同时报错`() {
        val issues = TradeForms.validate("BUY", "", "0", "", "")
        assertTrue(issues.any { it.field == "quantity" })
        assertTrue(issues.any { it.field == "price" })
        assertTrue(issues.any { it.field == "date" })
        assertTrue(issues.any { it.field == "note" })
    }

    @Test
    fun `UX-02 全部合法则无任何问题`() {
        assertEquals(0, TradeForms.validate("BUY", "100", "1723.0", "2026-09-13", "分批建仓", "43.08").size)
    }

    // ---------- 卖出方向的额外语义（方向合法即可，超卖为软约束在保存后警示） ----------

    @Test
    fun `V-20 超卖不阻断校验_软约束`() {
        // 卖出 150 股本身能通过 validate（持仓校验在重放层做警示，不拦截）——G5 已统一为软约束
        assertNoIssue(validate(side = TradeSide.SELL.name, quantity = "150"), "quantity")
    }
}
