package com.stocknote.data.seed

import com.stocknote.core.model.TradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 演示数据的**自洽性校验**（老周 2026-09-20）。
 *
 * 为什么值得单独写测试：演示数据是「给人看」的，一旦现金算错，
 * 整条资产曲线、总资产、收益率全都跟着错，而且**看起来像程序 bug**。
 * 上一版种子数据就是直接拍了个 `account.cash = 401_920.0` 没核算过。
 *
 * 这里不依赖数据库，纯粹对 [DemoData] 的常量做算术复算 ——
 * 所以改了数据忘记同步 [DemoData.ACCOUNT_CASH] 时，构建会直接失败。
 */
class DemoDataConsistencyTest {

    /** 与 TradeForms.feeAmountOf 同口径：价 × 量 × 费率 / 10000，不足 5 元按 5 元（免佣 0 不受限）。 */
    private fun feeOf(price: Double, qty: Double, rate: Double): Double {
        val raw = price * qty * rate / 10000.0
        return if (rate > 0.0 && raw < 5.0) 5.0 else raw
    }

    private fun isOffBook(securityId: String): Boolean =
        DemoData.SECURITIES.first { it.id == securityId }.offBook

    /**
     * 印花税（REQ-ACC-17，2026-09-30 加）：A 股**只有卖出**收（万分之 5）、
     * 港股**买卖都由**收（千分之 1）、ETF / 场外基金 / 美股不收；四舍五入到分。
     *
     * ⚠️ 这里**刻意手写规则**而不调 `StampDuty.dutyOf`：测试要能**独立复算**，
     * 复用被测代码会让"规则写错"时两边一起错、断言形同虚设。
     */
    private fun stampDutyOf(t: DemoData.TradeRow): Double {
        val market = DemoData.SECURITIES.first { it.id == t.securityId }.market
        val rate = when (market) {
            com.stocknote.core.model.Market.A_SHARE -> if (t.side == TradeSide.BUY) 0.0 else 5.0
            com.stocknote.core.model.Market.HK -> 10.0
            else -> 0.0
        }
        if (rate <= 0.0) return 0.0
        return kotlin.math.round(t.price * t.qty * rate / 10_000.0 * 100.0) / 100.0
    }

    private fun cashDeltaOf(t: DemoData.TradeRow): Double {
        // 印花税与手续费同等计入现金（REQ-ACC-17）
        val duty = stampDutyOf(t)
        val gross = if (t.side == TradeSide.BUY) {
            -(t.price * t.qty + t.fee + duty)
        } else {
            t.price * t.qty - t.fee - duty
        }
        return gross * t.fxRate
    }

    private fun approx(expected: Double, actual: Double, tolerance: Double = 0.01) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "期望 $expected，实际 $actual（差 ${expected - actual}）",
        )
    }

    /**
     * 每笔手续费都必须能由「价 × 量 × 费率」反推出来 —— 防止手抄 fee 时打错数字。
     *
     * ⚠️ 这里**不**断言「最多 2 位小数」：`TradeForms.feeAmountOf` 返回的是
     * `价 × 量 × 费率 / 10000` 的原始乘积，**不做舍入**（已核对源码），
     * 所以 300 × 285 × 2.5‱ = 21.375 就是入库值，3 位小数是正常的。
     * 「金额 2 位小数」是**展示层**规则（`Format.money`），不是存储层规则。
     */
    @Test
    fun 每笔手续费都符合费率公式() {
        DemoData.TRADES.forEach { t ->
            approx(feeOf(t.price, t.qty, t.feeRate), t.fee, 1e-9)
        }
    }

    /**
     * 核心断言：`account.cash` == Σ 账内交易的现金增减。
     *
     * ⚠️ 账外备忘标的（场外基金「不计入统计」）的买卖**不动现金**，必须剔除 ——
     * 否则「纯备忘记一笔」会被当成真的扣了钱。
     */
    @Test
    fun account_cash等于账内交易净额() {
        val net = DemoData.TRADES
            .filterNot { isOffBook(it.securityId) }
            .sumOf { cashDeltaOf(it) }
        approx(DemoData.ACCOUNT_CASH, net)
    }

    /**
     * 可用现金 = account.cash + 出入金净额 + 账内现金分红。
     * 必须为正 —— 负现金说明买入金额算错了（或入金给少了）。
     */
    @Test
    fun 可用现金为正且等于期望值() {
        val flows = DemoData.FLOWS.sumOf { it.amount }
        val divCash = DemoData.DIVIDENDS
            .filterNot { isOffBook(it.securityId) }
            .sumOf { it.qty * it.perShare * it.fxRate }
        val available = DemoData.ACCOUNT_CASH + flows + divCash

        approx(DemoData.EXPECTED_AVAILABLE_CASH, available)
        assertTrue(available > 0.0, "可用现金不能为负，实际 $available")
    }

    /**
     * 资产曲线以「当前可用现金」为锚**反推历史**：起点现金 = 当前现金 − 全部事件。
     * 这里等价于要求 `Σ全部事件 == 可用现金`，即曲线起点恰好落在 0。
     *
     * 上一台 debug 机就是因为「交易日期早于入金日期」，把起点反推成 ¥0.90
     * （看着像 bug，其实是数据自相矛盾）。这条断言把那类错误挡在种子这一层。
     */
    @Test
    fun 曲线起点现金为零() {
        val tradeNet = DemoData.TRADES
            .filterNot { isOffBook(it.securityId) }
            .sumOf { cashDeltaOf(it) }
        val flows = DemoData.FLOWS.sumOf { it.amount }
        val divCash = DemoData.DIVIDENDS
            .filterNot { isOffBook(it.securityId) }
            .sumOf { it.qty * it.perShare * it.fxRate }

        val available = DemoData.ACCOUNT_CASH + flows + divCash
        val allEvents = tradeNet + flows + divCash
        approx(0.0, available - allEvents)
    }

    /** 所有交易日期必须**晚于**最早入金日期，否则反推不出正确现金（真踩过的坑）。 */
    @Test
    fun 交易日期不早于首笔入金() {
        val firstFlow = DemoData.FLOWS.minOf { it.date }
        DemoData.TRADES.forEach { t ->
            assertTrue(
                t.date >= firstFlow,
                "${t.securityId} 的交易日期 ${t.date} 早于首笔入金 $firstFlow —— 曲线起点会被反推成异常值",
            )
        }
    }

    /** 贵州茅台净持仓 100 股 —— 与界面原型同源的对账锚。 */
    @Test
    fun 茅台净持仓为100股() {
        val net = DemoData.TRADES
            .filter { it.securityId == "sec_sh600519" }
            .sumOf { if (it.side == TradeSide.BUY) it.qty else -it.qty }
        approx(100.0, net, 1e-9)
    }

    /** 分红数量不能超过除权日的持仓（否则凭空多出分红现金）。 */
    @Test
    fun 分红数量不超过除权日持仓() {
        DemoData.DIVIDENDS.forEach { d ->
            val held = DemoData.TRADES
                .filter { it.securityId == d.securityId && it.date <= d.exDate }
                .sumOf { if (it.side == TradeSide.BUY) it.qty else -it.qty }
            assertTrue(
                d.qty <= held + 1e-9,
                "${d.securityId} 除权日 ${d.exDate} 分红 ${d.qty} 份，但当时只持有 $held",
            )
        }
    }

    /** 演示数据必须覆盖全部市场（含账内 / 账外两类场外基金），否则演示时看不出功能。 */
    @Test
    fun 覆盖全部市场且场外基金分账内账外() {
        val markets = DemoData.SECURITIES.map { it.market }.toSet()
        assertEquals(
            setOf(
                com.stocknote.core.model.Market.A_SHARE,
                com.stocknote.core.model.Market.ETF,
                com.stocknote.core.model.Market.HK,
                com.stocknote.core.model.Market.US,
                com.stocknote.core.model.Market.FUND,
            ),
            markets,
        )
        assertTrue(
            DemoData.SECURITIES.any { it.market == com.stocknote.core.model.Market.FUND && !it.offBook },
            "缺少「账内」场外基金，演示时看不到它进统计",
        )
        assertTrue(
            DemoData.SECURITIES.any { it.market == com.stocknote.core.model.Market.FUND && it.offBook },
            "缺少「账外备忘」场外基金，演示时看不到备忘账",
        )
    }
}
