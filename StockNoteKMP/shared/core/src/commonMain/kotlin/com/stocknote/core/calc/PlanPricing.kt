package com.stocknote.core.calc

/**
 * 交易计划的计价与校验（REQ-PLAN-01，老周 2026-09-19 定稿）。
 *
 * 与记一笔的口径关系：
 *  - 费率仍是**万分位**（填 2.5 = 万分之 2.5），与 [TradeForms] 完全一致；
 *  - 计划金额 = 计划价 × 数量 × (1±费率)，买入加、卖出减，与 [TradeForms.totalAmountOf] 同源；
 *  - 唯一新增的是 **目标价 × 折扣 → 计划价**（[plannedPriceOf]）。
 *
 * 放在领域层而不是 UI 层，理由同 TradeForms：纯函数、两端共享、可单测。
 */
object PlanPricing {

    /** 字段级错误。field 与表单字段一一对应，UI 据此决定红字落在哪。 */
    data class Issue(val field: String, val message: String)

    /**
     * 折扣档位（原型 plan-edit.html 的 9 档）。
     * 显示文案 → 计算值：4折=0.4 … 9折=0.9、1倍=1.0、1.1倍=1.1、1.2倍=1.2。
     */
    val DISCOUNT_OPTIONS: List<Pair<String, Double>> = listOf(
        "4折" to 0.4,
        "5折" to 0.5,
        "6折" to 0.6,
        "7折" to 0.7,
        "8折" to 0.8,
        "9折" to 0.9,
        "1倍" to 1.0,
        "1.1倍" to 1.1,
        "1.2倍" to 1.2,
    )

    /**
     * 计划价 = round(目标价 × 折扣, 2)（金额一律 2 位小数，REQ-VIEW-19）。
     *
     * **两者都有值才计算**——否则返回 null，调用方保持成交价原样（不覆盖用户手动填的值）。
     * 触发时机：填目标价 / 选折扣 / 改目标价 / 改折扣，任一变化都**重新计算并覆盖**
     * （老周 Q3 拍板：不用「手改后加锁」那套，避免用户忘了锁过以为价没跟上）。
     */
    fun plannedPriceOf(targetPriceText: String, discount: Double?): Double? {
        val target = targetPriceText.trim().toDoubleOrNull() ?: return null
        val d = discount ?: return null
        if (target <= 0.0 || d <= 0.0) return null
        return round2(target * d)
    }

    /**
     * 距达成空间：(计划价 − 现价) / 现价。
     * 正数 = 还需上涨才能到计划价（计划卖出常见），负数 = 已低于计划价（计划买入可接）。
     * 现价缺失（未拉到行情）返回 null，UI 不展示。
     */
    fun gapOf(currentPrice: Double?, plannedPrice: Double?): Double? {
        val now = currentPrice ?: return null
        val plan = plannedPrice ?: return null
        if (now <= 0.0) return null
        return (plan - now) / now
    }

    /** 金额/价格统一 2 位小数（REQ-VIEW-19） */
    fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    /**
     * 计划表单校验（与记一笔的差异：**备注非必填**）。
     * 拦截项：标的 / 目标价 / 计划价 / 数量 / 费率。
     */
    fun validate(
        securityId: String?,
        targetPriceText: String,
        plannedPriceText: String,
        quantityText: String,
        feeRateText: String = "",
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (securityId.isNullOrBlank()) {
            issues += Issue("security", "请先选择标的")
        }

        val target = targetPriceText.trim().toDoubleOrNull()
        when {
            targetPriceText.isBlank() -> issues += Issue("targetPrice", "请填写目标价")
            target == null -> issues += Issue("targetPrice", "目标价必须是数字")
            // ⚠️ M2（2026-09-28）：NaN/Inf 必须显式拦（`NaN <= 0` 为 false，只判 <=0 会放行）
            !target.isFinite() -> issues += Issue("targetPrice", "目标价必须是有效数字")
            target <= 0.0 -> issues += Issue("targetPrice", "目标价必须大于 0")
        }

        val price = plannedPriceText.trim().toDoubleOrNull()
        when {
            plannedPriceText.isBlank() -> issues += Issue("plannedPrice", "请填写成交价（选折扣后自动填入）")
            price == null -> issues += Issue("plannedPrice", "成交价必须是数字")
            // ⚠️ M2（2026-09-28）：同上
            !price.isFinite() -> issues += Issue("plannedPrice", "成交价必须是有效数字")
            price <= 0.0 -> issues += Issue("plannedPrice", "成交价必须大于 0")
        }

        val qty = quantityText.trim().toDoubleOrNull()
        when {
            quantityText.isBlank() -> issues += Issue("quantity", "请填写数量")
            qty == null -> issues += Issue("quantity", "数量必须是数字")
            // ⚠️ M2（2026-09-28）：同上
            !qty.isFinite() -> issues += Issue("quantity", "数量必须是有效数字")
            qty <= 0.0 -> issues += Issue("quantity", "数量必须大于 0")
        }

        // 费率：缺省取设置值，可改；须 ≥ 0（免佣填 0）
        val fee = feeRateText.trim().toDoubleOrNull()
        when {
            feeRateText.isBlank() -> issues += Issue("feeRate", "请填写手续费率（免佣请填 0）")
            fee == null -> issues += Issue("feeRate", "手续费率必须是数字（单位：万分之）")
            fee < 0.0 -> issues += Issue("feeRate", "手续费率不能为负")
        }

        return issues
    }
}
