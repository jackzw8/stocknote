package com.stocknote.core.calc

import com.stocknote.core.model.TradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 资产曲线与最大回撤测试（M4 数据真值，REQ-VIEW-05 / REQ-ANA-01）。
 * 手算样例与需求验收口径对齐：曲线与快照吻合、回撤与手算一致。
 *
 * **现金口径（2026-09-14 明确）**：`currentCash` 是**当前**可用现金，曲线以它为锚
 * **反推**历史各时点现金（起点现金 = 当前现金 − 全部事件），因此最后一个点必须回到当前现金。
 */
class EquityCurveTest {

    private fun tx(
        date: String, side: TradeSide, qty: Double, price: Double, fee: Double = 0.0,
    ) = com.stocknote.core.model.Transaction(
        id = "$date-$side-$qty",
        securityId = "s1",
        side = side,
        quantity = qty,
        price = price,
        fee = fee,
        tradeDate = date,
        seq = 1,
    )

    // ---------------------------------------------------------------- 每只标的的当日盈亏
    // （老周 2026-10-01：盈亏日历「点某天看明细」的数据基础）
    //
    // 口径：`市值(t) − 市值(t−1) + 当日该标的现金净流入`（买入为负、卖出为正）。
    // 下面钉住四个最容易写错的点，尤其是「买入当天不能被算成亏损」。

    @Test
    fun 明细_买入当天价格未动_盈亏必须为零() {
        // 1/10 买 100@10（掏 1000），当日收盘也是 10.0 → 市值增加 1000 是自己付的钱，盈亏必须是 0
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-01-10" to 10.0)),
        )
        val p = points.first { it.date == "2026-01-10" }
        assertTrue(p.pnlBySecurity.isEmpty(), "买入当天价格未动，不该有任何盈亏：${p.pnlBySecurity}")
    }

    @Test
    fun 明细_价格变动等于昨日市值乘涨跌幅() {
        // 1/10 买 100@10；1/11 收盘 11（涨 10%）→ 昨日市值 1000，盈利 100
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf(
                "s1" to listOf("2026-01-10" to 10.0, "2026-01-11" to 11.0),
            ),
        )
        val d11 = points.first { it.date == "2026-01-11" }
        assertEquals(100.0, d11.pnlBySecurity["s1"]!!, 1e-6, "昨日市值 1000 × 10% = 100")
    }

    @Test
    fun 明细_清仓当天等于卖出所得减昨日市值() {
        // 1/10 买 100@10；1/11 以 12 全部卖出 → 今日市值 0、昨日 1000、收回 1200 → 盈亏 +200
        val points = EquityCurve.build(
            transactions = listOf(
                tx("2026-01-10", TradeSide.BUY, 100.0, 10.0),
                tx("2026-01-11", TradeSide.SELL, 100.0, 12.0),
            ),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf(
                "s1" to listOf("2026-01-10" to 10.0, "2026-01-11" to 12.0),
            ),
        )
        val d11 = points.first { it.date == "2026-01-11" }
        assertEquals(200.0, d11.pnlBySecurity["s1"]!!, 1e-6, "0 − 1000 + 1200 = +200")
    }

    /**
     * **对账**（这条最重要）：`Σ 明细盈亏 + 当日现金分红 = 逐日盈亏`。
     *
     * 分红在数据里没有标的归属（只有除权日 + 金额），所以**拆不进任何一只票**，
     * 归到「现金流水」那一段展示。这条测试把两者之间的差额钉死在「分红」上 ——
     * 日后若有人改动任一侧口径，这里会立刻红。
     */
    @Test
    fun 明细_与逐日盈亏对账_差额恰为当日现金分红() {
        // 1/10 买 100@10；1/11 收盘 11（市值 +100），且当日有 30 元现金分红
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf(
                "s1" to listOf("2026-01-10" to 10.0, "2026-01-11" to 11.0),
            ),
            dividends = listOf("2026-01-11" to 30.0),
        )
        val daily = EquityCurve.dailyPnl(points, emptyMap())
        val sumDetail = points.first { it.date == "2026-01-11" }.pnlBySecurity.values.sum()

        // 逐日盈亏取「总资产差分」：1/10 总资产 = 10000 + 100×10 = 11000；
        // 1/11 总资产 = (10000 + 30) + 100×11 = 11130 → 差分 +130（市值 +100、分红 +30）
        assertEquals(130.0, daily["2026-01-11"]!!, 1e-6, "逐日盈亏 = 市值 +100 加分红 +30")
        // 而明细只含价格波动（+100），差额 30 恰是当日分红 —— 这正是分红要单列展示的原因
        assertEquals(100.0, sumDetail, 1e-6, "明细只含价格波动")
        assertEquals(30.0, daily["2026-01-11"]!! - sumDetail, 1e-6, "差额必须是当日分红")
    }

    @Test
    fun 曲线_买入后持有_市值随收盘价() {
        // 当前可用现金 10,000；1/10 买 100 股 @10（花 1,000）
        // 反推：起点现金 = 10000 − (−1000) = 11000 → 1/10 花掉 1000 后回到 10000
        // 收盘：1/10=10.5、1/11=11.0 → 市值 1050 / 1100
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf(
                "s1" to listOf(
                    "2026-01-10" to 10.5,
                    "2026-01-11" to 11.0,
                ),
            ),
        )
        assertEquals(2, points.size)
        // 1/10：现金 = 10000；市值 = 100×10.5 = 1050 → 11050
        assertEquals("2026-01-10", points[0].date)
        assertEquals(10_000.0 + 1_050.0, points[0].totalAsset, 1e-6)
        // 1/11：市值 1100 → 11100
        assertEquals(10_000.0 + 1_100.0, points[1].totalAsset, 1e-6)
    }

    @Test
    fun 曲线_卖出回现金() {
        // 当前可用现金 20,000；1/2 买 100@100（−10,000）、1/5 卖 60@110（+6,600）
        // 反推起点：20000 − (−10000 + 6600) = 23400
        // 连续自然日轴（1/3、1/4 顺延收盘 100.0）
        val points = EquityCurve.build(
            transactions = listOf(
                tx("2026-01-02", TradeSide.BUY, 100.0, 100.0),
                tx("2026-01-05", TradeSide.SELL, 60.0, 110.0),
            ),
            cashFlows = emptyList(),
            currentCash = 20_000.0,
            closesBySecurityId = mapOf(
                "s1" to listOf(
                    "2026-01-02" to 100.0,
                    "2026-01-05" to 110.0,
                    "2026-01-06" to 105.0,
                ),
            ),
        )
        // 连续轴：1/2~1/6 共 5 点
        assertEquals(5, points.size)
        fun at(date: String) = points.first { it.date == date }.totalAsset
        // 1/2：现金 23400−10000=13400 + 100×100 = 23400
        assertEquals(23_400.0, at("2026-01-02"), 1e-6)
        // 1/3、1/4：顺延收盘 100.0 → 23400
        assertEquals(23_400.0, at("2026-01-03"), 1e-6)
        assertEquals(23_400.0, at("2026-01-04"), 1e-6)
        // 1/5：现金 13400+6600=20000 + 40×110 = 24400
        assertEquals(20_000.0 + 4_400.0, at("2026-01-05"), 1e-6)
        // 1/6：现金 20000 + 40×105 = 24200
        assertEquals(20_000.0 + 4_200.0, at("2026-01-06"), 1e-6)
    }

    @Test
    fun 曲线_终点现金等于当前现金_锚点自洽() {
        // 全部清仓后无持仓，最后一点总资产必须等于「当前可用现金」——这是反推口径的核心不变量。
        val points = EquityCurve.build(
            transactions = listOf(
                tx("2026-01-02", TradeSide.BUY, 100.0, 10.0),
                tx("2026-01-03", TradeSide.SELL, 100.0, 12.0),
            ),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-01-02" to 10.0, "2026-01-03" to 12.0)),
        )
        val last = points.last()
        assertEquals("2026-01-03", last.date)
        assertEquals(10_000.0, last.totalAsset, 1e-6)
    }

    @Test
    fun 曲线_收盘价缺失顺延前值() {
        // 1/10 买 100@10；收盘只有 1/10 与 1/12 → 1/11 顺延 1/10 的 10.5
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf(
                "s1" to listOf(
                    "2026-01-10" to 10.5,
                    "2026-01-12" to 12.0,
                ),
            ),
        )
        // 时间轴含 1/12（收盘价日期并入）
        val p11 = points.first { it.date == "2026-01-11" }
        assertEquals(10_000.0 + 100 * 10.5, p11.totalAsset, 1e-6)
        val p12 = points.first { it.date == "2026-01-12" }
        assertEquals(10_000.0 + 100 * 12.0, p12.totalAsset, 1e-6)
    }

    @Test
    fun 曲线_出入金计入现金() {
        // 当前现金 10,000；1/10 买 100@10（−1000）、1/11 存入 5,000
        // 反推起点：10000 − (−1000 + 5000) = 6000
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = listOf("2026-01-11" to 5_000.0),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-01-10" to 10.5, "2026-01-11" to 10.5)),
        )
        val p11 = points.first { it.date == "2026-01-11" }
        // 现金 = 6000 − 1000 + 5000 = 10000；市值 = 1050
        assertEquals(10_000.0 + 1_050.0, p11.totalAsset, 1e-6)
    }

    @Test
    fun 空输入返回空曲线() {
        assertTrue(EquityCurve.build(emptyList(), emptyList(), 0.0, emptyMap()).isEmpty())
    }

    @Test
    fun 曲线_收盘日期未排序含远古离群值_不得为空() {
        // 回归（2026-09-14 真机确诊）：closeDates 曾只 distinct 未排序，用 first()/last() 当
        // 最早/最晚收盘日。当某个标的（美股 usAAPL）只剩 2011 年残数据且排在最后时，
        // 「最晚收盘日」被算成 2011-06-02 → 区间倒挂 → 整条曲线为空 → UI 误报"需要联网"。
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf(
                // 正常标的在前，远古离群标的后置 —— 刻意复现触发条件（离群值落在 flatMap 末尾）
                "s1" to listOf("2026-01-10" to 10.5, "2026-01-11" to 11.0),
                "s2" to listOf("2011-06-02" to 346.22),
            ),
        )
        assertTrue(points.isNotEmpty(), "收盘日期未排序且含远古离群值时，曲线不应为空")
        assertEquals("2026-01-10", points.first().date)
        assertEquals("2026-01-11", points.last().date)
        assertEquals(10_000.0 + 1_050.0, points.first().totalAsset, 1e-6)
    }

    @Test
    fun 曲线_外币持仓按汇率折算到本位币() {
        // 港股 100 股，收盘 110 HKD，汇率 0.92 → 市值必须算成 100×110×0.92 = 10,120，
        // 而不是把 HK$ 按面值当人民币（11,000）。这是图注「外币按最新汇率折算」的实现依据。
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 100.0)),
            cashFlows = emptyList(),
            currentCash = 0.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-01-10" to 110.0)),
            rateToBase = mapOf("s1" to 0.92),
        )
        assertEquals(100.0 * 110.0 * 0.92, points[0].totalAsset, 1e-6)
    }

    @Test
    fun 曲线_卖出超仓按实际持仓截断() {
        // 只买 100 股却卖 160 股：数量与现金都必须按实际成交 100 股计 ——
        // 既不得出现 −60 股（负市值），也不得记 160 股的卖出回款。
        // 真机上正是这处缺失让某标的变成 −6840 股、把总资产压低约 47 万。
        val points = EquityCurve.build(
            transactions = listOf(
                tx("2026-01-10", TradeSide.BUY, 100.0, 10.0),
                tx("2026-01-11", TradeSide.SELL, 160.0, 12.0),
            ),
            cashFlows = emptyList(),
            currentCash = 200.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-01-10" to 10.0, "2026-01-11" to 12.0)),
        )
        // 事件净额 = −1000（买）+1200（按 100 股成交，不是 160×12=1920）→ 起点现金 = 200−200 = 0
        // 1/10：现金 −1000、持仓 100 股 → 市值 1000 → 合计 0
        assertEquals(0.0, points.first { it.date == "2026-01-10" }.totalAsset, 1e-6)
        // 1/11：清仓，现金回到 200（= 当前现金锚点）
        val last = points.last()
        assertEquals("2026-01-11", last.date)
        assertEquals(200.0, last.totalAsset, 1e-6)
    }

    @Test
    fun 最大回撤_标准手算() {
        // 峰 110（1/2）→ 谷 88（1/4）：回撤 = 22/110 = 20%
        val points = listOf(
            EquityCurve.Point("2026-01-01", 100.0),
            EquityCurve.Point("2026-01-02", 110.0),
            EquityCurve.Point("2026-01-03", 95.0),
            EquityCurve.Point("2026-01-04", 88.0),
            EquityCurve.Point("2026-01-05", 96.0),
        )
        val dd = EquityCurve.maxDrawdown(points)
        assertEquals(0.20, dd.maxDrawdown, 1e-9)
        assertEquals("2026-01-02", dd.peakDate)
        assertEquals("2026-01-04", dd.troughDate)
    }

    @Test
    fun 最大回撤_全程上涨为零() {
        val points = listOf(
            EquityCurve.Point("2026-01-01", 100.0),
            EquityCurve.Point("2026-01-02", 110.0),
            EquityCurve.Point("2026-01-03", 120.0),
        )
        assertEquals(0.0, EquityCurve.maxDrawdown(points).maxDrawdown, 1e-9)
    }

    // ---- 以下 4 个用例为「峰值/谷值日期」的核实而补（老周 2026-09-24 要求再核实一遍）----
    // 原实现是标准算法（维护 running peak → 取 (peak−low)/peak 的最大值），但只测了单段回撤，
    // 多段回撤时的「峰值是否跟着创新高更新」没有任何覆盖，而这正是最容易算错的地方。

    @Test
    fun 最大回撤_两段回撤取更大的那次且峰值跟着更新() {
        // 第一段：峰 120（1/2）→ 谷 108（1/3）= 10%
        // 第二段：峰 130（1/5 创新高）→ 谷 91（1/7）= 30%
        // 期望取 30%，且峰值日必须是后一次的峰（1/5）。
        // ⚠️ 若峰值不随创新高更新，这里会算成 (120−91)/120 = 24.2%，与 30% 不符 → 该用例能抓出来
        val points = listOf(
            EquityCurve.Point("2026-01-01", 100.0),
            EquityCurve.Point("2026-01-02", 120.0),
            EquityCurve.Point("2026-01-03", 108.0),
            EquityCurve.Point("2026-01-04", 115.0),
            EquityCurve.Point("2026-01-05", 130.0),
            EquityCurve.Point("2026-01-06", 100.0),
            EquityCurve.Point("2026-01-07", 91.0),
            EquityCurve.Point("2026-01-08", 105.0),
        )
        val dd = EquityCurve.maxDrawdown(points)
        assertEquals(0.30, dd.maxDrawdown, 1e-9)
        assertEquals("2026-01-05", dd.peakDate)
        assertEquals("2026-01-07", dd.troughDate)
    }

    @Test
    fun 最大回撤_峰后反弹未创新高_不回算旧峰() {
        // 口径：回撤永远是「当前 running peak → 其后最低点」，不能跨过中间的反弹去凑旧峰。
        // 1/2 峰 110；1/4 谷 90 → 18.18%；1/5 反弹到 105（未创新高）；1/6 回到 100（相对 110 只有 9.09%）。
        // 期望仍是「峰 1/2 → 谷 1/4」的 18.18%。
        val points = listOf(
            EquityCurve.Point("2026-01-01", 100.0),
            EquityCurve.Point("2026-01-02", 110.0),
            EquityCurve.Point("2026-01-03", 95.0),
            EquityCurve.Point("2026-01-04", 90.0),
            EquityCurve.Point("2026-01-05", 105.0),
            EquityCurve.Point("2026-01-06", 100.0),
        )
        val dd = EquityCurve.maxDrawdown(points)
        assertEquals((110.0 - 90.0) / 110.0, dd.maxDrawdown, 1e-9)
        assertEquals("2026-01-02", dd.peakDate)
        assertEquals("2026-01-04", dd.troughDate)
    }

    @Test
    fun 最大回撤_幅度相同时保留先出现的那次() {
        // 两段都是 10%（100→90；120→108）。实现用严格大于替换 → 应保留**早先**那次。
        // 这是个明确的产品口径（先发生的那次更有解释力），用测试把它钉住。
        val points = listOf(
            EquityCurve.Point("2026-01-01", 100.0),
            EquityCurve.Point("2026-01-02", 90.0),
            EquityCurve.Point("2026-01-03", 120.0),
            EquityCurve.Point("2026-01-04", 108.0),
        )
        val dd = EquityCurve.maxDrawdown(points)
        assertEquals(0.10, dd.maxDrawdown, 1e-9)
        assertEquals("2026-01-01", dd.peakDate)
        assertEquals("2026-01-02", dd.troughDate)
    }

    @Test
    fun 最大回撤_点数不足时不崩且日期为空() {
        // 界面据此显示「区间内没有回撤」，所以这里必须返回空日期，
        // 不能返回 points[0] 之类（否则界面会出现「峰值 2026-01-01 → 谷值 2026-01-01」的同日怪文案）
        val empty = EquityCurve.maxDrawdown(emptyList())
        assertEquals(0.0, empty.maxDrawdown, 1e-9)
        assertEquals("", empty.peakDate)
        assertEquals("", empty.troughDate)
        val single = EquityCurve.maxDrawdown(listOf(EquityCurve.Point("2026-01-01", 100.0)))
        assertEquals(0.0, single.maxDrawdown, 1e-9)
        assertEquals("", single.peakDate)
        assertEquals("", single.troughDate)
    }

    @Test
    fun 手续费计入现金流出() {
        // 当前现金 10,000；1/10 买 100@10 且手续费 5 → 事件 −1005 → 起点现金 11005
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0, fee = 5.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-01-10" to 10.0)),
        )
        // 现金 = 11005 − 1005 = 10000；市值 = 1000
        assertEquals(10_000.0 + 1_000.0, points[0].totalAsset, 1e-6)
    }

    /**
     * 外币交易的现金流必须**折本位币**后再入事件流（2026-09-20 修复）。
     *
     * 修复前：`cashDelta` 直接用 `price × qty`（原币）当人民币加，而 `currentCash`（锚点）
     * 与持仓市值都是折算后的 —— 两者口径不一致 → 起点现金被反推出偏差、整条曲线偏移。
     * 真机实测：港股 200×385 + 美股 60×305 两笔，事件总和比锚点高 97,001.61，
     * 把实际 +134 万的组合画成 −6.4 万。
     */
    @Test
    fun 曲线_外币交易现金流折本位币() {
        // 港股 200 股 @ 385 HKD，费 38.50 HKD；HKD→CNY = 0.86
        // 买入现金支出 = (200×385 + 38.50) × 0.86 = 66,253.11 CNY（不是 77,038.50）
        val hk = com.stocknote.core.model.Transaction(
            id = "hk1", securityId = "hk", side = TradeSide.BUY,
            quantity = 200.0, price = 385.0, fee = 38.50, tradeDate = "2026-03-16", seq = 1,
        )
        val points = EquityCurve.build(
            transactions = listOf(hk),
            cashFlows = emptyList(),
            currentCash = 100_000.0,
            closesBySecurityId = mapOf("hk" to listOf("2026-03-16" to 419.0)),
            rateToBase = mapOf("hk" to 0.86),
        )
        // 现金回到锚点 100,000；市值 = 200 × 419 × 0.86 = 72,068
        assertEquals(100_000.0 + 200.0 * 419.0 * 0.86, points[0].totalAsset, 1e-6)
        // 反例护栏：若把 HKD 当 CNY 用（修复前的行为），会得到 161,282.61
        val wrong = 100_000.0 - (200.0 * 385.0 + 38.50) + 200.0 * 419.0 * 0.86
        assertTrue(
            kotlin.math.abs(points[0].totalAsset - wrong) > 1.0,
            "外币现金流未折算 —— 算出了修复前的错误值 $wrong",
        )
    }

    /** 外币卖出同理：回款也要折本位币。 */
    @Test
    fun 曲线_外币卖出回款折本位币() {
        val buy = com.stocknote.core.model.Transaction(
            id = "us1", securityId = "us", side = TradeSide.BUY,
            quantity = 60.0, price = 305.0, fee = 0.0, tradeDate = "2026-06-22", seq = 1,
        )
        val sell = com.stocknote.core.model.Transaction(
            id = "us2", securityId = "us", side = TradeSide.SELL,
            quantity = 60.0, price = 336.13, fee = 0.0, tradeDate = "2026-06-23", seq = 2,
        )
        val points = EquityCurve.build(
            transactions = listOf(buy, sell),
            cashFlows = emptyList(),
            currentCash = 50_000.0,
            closesBySecurityId = mapOf("us" to listOf("2026-06-22" to 305.0, "2026-06-23" to 336.13)),
            rateToBase = mapOf("us" to 6.89),
        )
        // 卖完后无持仓 → 末点 = 现金锚点（回款已折 CNY：60×336.13×6.89 = 138,956.14）
        assertEquals(50_000.0, points.last().totalAsset, 1e-6)
    }

    /**
     * 起点**之前**发生的现金流事件（2026-09-20 修复）。
     *
     * `timeline` 是从「最早交易日与最早收盘日取较大者」开始的连续自然日。
     * 若某笔入金早于这个起点，它既不在起点现金里、也不会被逐日累加 → **末点回不到当前现金**。
     * 真机实测：2026-02-02 的 50 万入金早于 2026-02-11 的收盘起点，
     * 曲线末点 ¥842,209.98，比统计页总资产 ¥1,342,209.98 恰好少 50 万。
     */
    @Test
    fun 曲线_起点之前的入金计入起点() {
        // 入金 50 万（2026-01-05，早于最早收盘日）→ 3/10 买 100@10 → 3/11 卖 100@11
        // 卖完后无持仓，末点应**恰好**回到当前可用现金：
        //   现金 = 500,000 − 1,000 + 1,100 = 500,100
        // 起点 = max(最早事件 01-05, 最早收盘 03-10) = 03-10 → 起点现金需含那 50 万
        val points = EquityCurve.build(
            transactions = listOf(
                tx("2026-03-10", TradeSide.BUY, 100.0, 10.0),
                com.stocknote.core.model.Transaction(
                    id = "sell", securityId = "s1", side = TradeSide.SELL,
                    quantity = 100.0, price = 11.0, fee = 0.0, tradeDate = "2026-03-11", seq = 2,
                ),
            ),
            cashFlows = listOf("2026-01-05" to 500_000.0),
            currentCash = 500_100.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-03-10" to 10.0, "2026-03-11" to 11.0)),
        )
        // 修复前：起点现金从 0 算起，末点只有 ¥100（那 50 万永远加不上）
        assertEquals(500_100.0, points.last().totalAsset, 1e-6)
        // 起点已含窗口前入金：500,000 − 1,000（买入）= 499,000 现金 + 1,000 市值
        assertEquals(500_000.0, points.first().totalAsset, 1e-6)
    }

    @Test
    fun 逐日盈亏_剔除当日出入金() {
        // ⚠️ 兜老周 2026-09-29 真机回归：9-01 入金 19,000，
        // 旧口径（总资产差分）把入金当成当天赚的钱 → 日历显示 +2,212，而券商是 −16,986。
        val points = listOf(
            EquityCurve.Point("2026-01-10", 100_000.0),
            EquityCurve.Point("2026-01-11", 119_000.0),   // 当天入金 19,000
            EquityCurve.Point("2026-01-12", 120_000.0),
        )
        val pnl = EquityCurve.dailyPnl(points, cashFlowsByDate = mapOf("2026-01-11" to 19_000.0))
        assertEquals(0.0, pnl["2026-01-11"]!!, 1e-6, "⚠️ 入金那天不该产生盈亏")
        assertEquals(1_000.0, pnl["2026-01-12"]!!, 1e-6, "次日无入金，盈亏照常")

        // 不传入金（旧口径）→ 那 19,000 全被算成盈利
        val legacy = EquityCurve.dailyPnl(points)
        assertEquals(19_000.0, legacy["2026-01-11"]!!, 1e-6)

        // 取出（负数）同样要剔除：总资产降 5,000 是"把钱拿走"，不是亏 5,000
        val withdraw = EquityCurve.dailyPnl(points, cashFlowsByDate = mapOf("2026-01-11" to -5_000.0))
        assertEquals(24_000.0, withdraw["2026-01-11"]!!, 1e-6)
    }

    @Test
    fun 外币持仓按当日汇率折算_而非全段用最新汇率() {
        // ⚠️ 兜老周 2026-09-29 的口径修正：此前整条曲线用"最新汇率"，
        // 与券商"按当日汇率"差约 1%（腾讯 1600 股 9-01 差 148 元）。
        // 汇率逐日变化：1/10 = 0.8、1/11 = 0.9（rateToBase=0.75 只是兜底，不应被用到）
        val points = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-01-10" to 10.0, "2026-01-11" to 11.0)),
            rateToBase = mapOf("s1" to 0.75),
            rateSeriesBySecurityId = mapOf("s1" to listOf("2026-01-10" to 0.8, "2026-01-11" to 0.9)),
        )
        // 1/10：现金 = 10,000 + 100×10×0.8 = 10,800；市值 = 100×10×0.8 = 800
        assertEquals(10_800.0, points[0].totalAsset, 1e-6)
        // 1/11：市值按**当日** 0.9 → 100×11×0.9 = 990（用 0.8 会是 880、用兜底 0.75 是 825）
        assertEquals(10_000.0 + 990.0, points[1].totalAsset, 1e-6)

        // 该日之前没有汇率记录 → 顺延用**最早**一条（汇率表常常只有一条最新值）
        val single = EquityCurve.build(
            transactions = listOf(tx("2026-01-10", TradeSide.BUY, 100.0, 10.0)),
            cashFlows = emptyList(),
            currentCash = 10_000.0,
            closesBySecurityId = mapOf("s1" to listOf("2026-01-10" to 10.0)),
            rateSeriesBySecurityId = mapOf("s1" to listOf("2026-01-11" to 0.9)),
        )
        assertEquals(10_000.0 + 100.0 * 10.0 * 0.9, single[0].totalAsset, 1e-6)
    }
}
