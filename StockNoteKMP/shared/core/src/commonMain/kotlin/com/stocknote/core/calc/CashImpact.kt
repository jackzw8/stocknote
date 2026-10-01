package com.stocknote.core.calc

/**
 * 交易对**现金账户（本位币 CNY）**的影响 —— 纯函数，便于单测。
 *
 * ⚠️ 2026-09-16 修复（老周发现的缺陷）：
 *   此前现金联动直接用**原币数值**增减 CNY 现金账户 —— 买港股时按「港元数字」当人民币扣，
 *   少扣约 15%（港元 ≈ 0.85 人民币）；卖出港股同理少加。
 *   现统一按 `rateToBase` 折算本位币后再增减。
 *
 * 口径：
 *  - 买入：现金减少 (价×量 + 费 **+ 印花税**)，再折算本位币
 *  - 卖出：现金增加 (价×量 − 费 **− 印花税**)，再折算本位币
 *  - 利润转增资本（CAPITALIZE）：**恒为 0**（账内重分类，不动现金，REQ-ACC-16）
 *
 * @param rateToBase 标的市场币种 → 本位币(CNY) 的汇率；CNY 标的传 1.0
 * @param stampDuty 印花税（原币金额，REQ-ACC-17 老周 2026-09-30）。与手续费同等计入现金 ——
 *   A 股只有卖出有（万分之 5）、港股买卖都有（千分之 1）、ETF/基金/美股恒 0。
 */
object CashImpact {

    fun cashDeltaOf(
        side: String,
        quantity: Double,
        price: Double,
        fee: Double,
        rateToBase: Double,
        stampDuty: Double = 0.0,
    ): Double {
        // 利润转增资本（REQ-ACC-16）：**账内重分类，不产生任何现金流动**。
        // 必须在此显式拦住 —— 它是"既不是买入也不是卖出"的第三态，
        // 若落进下面的 else 分支会被当成卖出而**凭空增加现金**。
        if (side == "CAPITALIZE") return 0.0
        val orig = if (side == "BUY") {
            -(price * quantity + fee + stampDuty)
        } else {
            price * quantity - fee - stampDuty
        }
        return orig * rateToBase
    }

    /** 便捷重载（枚举形态，供 Repo 调用） */
    fun cashDeltaOf(
        isBuy: Boolean,
        quantity: Double,
        price: Double,
        fee: Double,
        rateToBase: Double,
        stampDuty: Double = 0.0,
    ): Double = cashDeltaOf(if (isBuy) "BUY" else "SELL", quantity, price, fee, rateToBase, stampDuty)
}
