package com.stocknote.core.calc

import com.stocknote.core.model.Market
import kotlin.math.round

/**
 * 印花税（老周 2026-09-30，REQ-ACC-17）。
 *
 * 规则（按**市场 + 方向**，与券商口径一致）：
 *  - **A 股股票**：买入**不收**；卖出收 —— 缺省 **万分之 5**（0.05%）；
 *  - **港股**：买入、卖出**都收** —— 缺省 **万分之 10**（千分之 1 = 0.1%）；
 *  - **ETF / 场外基金 / 美股**：**不收**（境内 ETF 免印花税、场外基金无此税、美股无此税）。
 *
 * ⚠️ **税率单位与手续费率同口径：万分位** —— 港股"千分之 1"在设置里填 `10`。
 *
 * ⚠️ 金额**四舍五入到分**：券商账单也是按分取整，不取整会在对账时出现 1~2 分误差。
 *
 * ⚠️ 与手续费的区别：手续费是**用户可改**的（券商差异大），印花税是**法定税率**，
 * 界面上**只展示不可改**（老周要求）；用户在设置里统一配税率。
 */
object StampDuty {

    /** A 股卖出缺省税率（万分之 5）。 */
    const val A_SHARE_SELL_RATE = 5.0

    /** 港股缺省税率（万分之 10 = 千分之 1），买卖双向。 */
    const val HK_RATE = 10.0

    /**
     * 该市场 + 方向的印花税率（万分位）。
     * @return 0 = 本方向不收印花税（A 股买入 / ETF / 基金 / 美股）
     */
    fun rateOf(
        market: Market,
        isBuy: Boolean,
        aShareRate: Double = A_SHARE_SELL_RATE,
        hkRate: Double = HK_RATE,
    ): Double = when (market) {
        Market.A_SHARE -> if (isBuy) 0.0 else aShareRate
        Market.HK -> hkRate
        else -> 0.0
    }

    /**
     * 本笔的印花税金额（**原币**，四舍五入到分）。
     *
     * @param aShareRate A 股卖出税率（万分位，缺省 5）
     * @param hkRate 港股税率（万分位，缺省 10）
     */
    fun dutyOf(
        market: Market,
        isBuy: Boolean,
        price: Double,
        quantity: Double,
        aShareRate: Double = A_SHARE_SELL_RATE,
        hkRate: Double = HK_RATE,
    ): Double {
        if (price <= 0.0 || quantity <= 0.0) return 0.0
        val rate = rateOf(market, isBuy, aShareRate, hkRate)
        if (rate <= 0.0) return 0.0
        return round(price * quantity * rate / 10_000.0 * 100.0) / 100.0
    }
}
