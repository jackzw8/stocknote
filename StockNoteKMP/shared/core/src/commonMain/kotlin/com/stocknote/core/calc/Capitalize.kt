package com.stocknote.core.calc

import com.stocknote.core.model.DeletePreview
import com.stocknote.core.model.DividendRecord
import com.stocknote.core.model.Security
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction

/**
 * 利润转增资本（REQ-ACC-16）的校验与**操作预告** —— 纯函数，可单测。
 *
 * 与《删除预告》（[TradeForms.previewAfterDelete]）同一思路：把一条 CAPITALIZE 流水
 * 并进该标的的流水集合后跑**同一个** [PositionCalculator.replay]，
 * 因此「预告结果 = 保存后的实际结果」有结构性保证（不是两套算各自算一遍）。
 */
object Capitalize {

    /** 预告用的哨兵日期：排在所有流水之后（等价于"在此刻追加一笔"）。 */
    private const val PREVIEW_DATE = "9999-12-31"

    /** 校验金额输入。返回 null 表示通过。 */
    fun validate(amountText: String): String? {
        val v = amountText.trim().toDoubleOrNull() ?: return "请填写转增金额"
        if (v <= 0.0) return "转增金额必须大于 0"
        if (v.isNaN() || v.isInfinite()) return "转增金额非法"
        return null
    }

    /**
     * 是否可对该标的转增：**仅仍持仓**的标的可转（0 持仓没有本金可增）。
     * UI 用它决定按钮显隐；仓储层还会再校验一次（防止绕过 UI 调用）。
     */
    fun canCapitalize(position: com.stocknote.core.model.Position): Boolean = position.isOpen

    /**
     * 转增预告：`before` = 当前持仓，`after` = 追加这笔转增后的持仓。
     * 页面上要展示的「成本均价 / 浮动盈亏 / 总盈亏」都是**原币口径**字段，
     * 与汇率无关，所以本预告不需要真实汇率就已经是精确值。
     *
     * @param amount 转增金额 X（原币，必须 > 0；否则返回 null，UI 不显示预告）
     */
    fun preview(
        security: Security,
        transactions: List<Transaction>,
        amount: Double,
        marketPrice: Double?,
        dividends: List<DividendRecord> = emptyList(),
    ): DeletePreview? {
        if (amount <= 0.0 || amount.isNaN() || amount.isInfinite()) return null

        val before = PositionCalculator.replay(security, transactions, marketPrice, dividends)

        val synthetic = Transaction(
            id = "__preview_capitalize__",
            securityId = security.id,
            side = TradeSide.CAPITALIZE,
            quantity = 0.0,
            price = amount,
            fee = 0.0,
            // 排在最后：转增是针对"当前"这笔盈利的操作，语义上就该作用在全部流水之后
            tradeDate = PREVIEW_DATE,
            seq = Long.MAX_VALUE,
        )
        val after = PositionCalculator.replay(
            security,
            transactions + synthetic,
            marketPrice,
            dividends,
        )
        return DeletePreview(
            before = before,
            after = after,
            removedQuantity = 0.0,
            removedPrice = amount,
        )
    }
}
