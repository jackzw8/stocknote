package com.stocknote.core.model

/**
 * 出入金（账户维度，与标的无关）。字段口径与技术说明书 4.2 一致。
 * IRR 用的现金流符号归一由 calc 层的 CashFlow 承担，这里只做记录。
 */
data class CashFlowRecord(
    val id: String,
    val accountId: String,
    val flowDate: String,
    val type: String,
    val amountOrig: Double,
    val currency: Currency,
    val fxRate: Double,
    val amountBase: Double,
    val note: String? = null,
)

/** 分红送股事件 */
data class DividendRecord(
    val id: String,
    val securityId: String,
    val exDate: String,
    val type: String,
    val quantity: Double,
    val perShare: Double,
    val bonusShares: Double,
    val rightsPrice: Double,
    val fxRate: Double,
)

/** 交易笔记（1:1 挂在交易上；M1 仅建数据通道，截图 M2 接入） */
data class TradeNote(
    val id: String,
    val transactionId: String,
    val content: String,
    val annotation: String? = null,
)

/** 标的历史交易页（REQ-VIEW-10）一次性取齐的数据 */
data class SecurityDetail(
    val security: Security,
    /** 该标的合并后的持仓概览（与持仓列表行同源） */
    val position: Position,
    /** 逐笔交易，日期倒序、同日按 seq 倒序 */
    val transactions: List<Transaction>,
    val quote: Quote? = null,
    /** 该标的的分红送股流水（REQ-ACC-04） */
    val dividends: List<DividendRecord> = emptyList(),
)

/**
 * 删除一笔交易前的重算预告（REQ-ACC-09）。
 * before / after 都是**同一套重放逻辑**算出来的，因此「预告结果 = 实际结果」有结构性保证。
 */
data class DeletePreview(
    val before: Position,
    val after: Position,
    val removedQuantity: Double,
    val removedPrice: Double,
) {
    val quantityDelta: Double get() = after.quantity - before.quantity
    val avgCostDelta: Double get() = after.avgCost - before.avgCost
    val realizedPnlDelta: Double get() = after.realizedPnl - before.realizedPnl
}
