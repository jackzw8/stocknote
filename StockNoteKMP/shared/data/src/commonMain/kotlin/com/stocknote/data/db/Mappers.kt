package com.stocknote.data.db

import com.stocknote.core.model.Currency
import com.stocknote.core.model.DividendRecord
import com.stocknote.core.model.CashFlowRecord
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Security as DomainSecurity
import com.stocknote.core.model.Transaction as DomainTransaction

/**
 * 数据库行 ↔ 领域模型 的映射。
 *
 * 刻意分成两层而不是让 SQLDelight 直接生成领域模型：
 * 表结构演进（加列、加索引）不应该波及领域层，反之亦然。
 */

internal fun Security.toDomain(): DomainSecurity = DomainSecurity(
    id = id,
    symbol = symbol,
    name = name,
    market = Market.entries.firstOrNull { it.name == market } ?: Market.A_SHARE,
    currency = Currency.entries.firstOrNull { it.code == currency } ?: Currency.CNY,
    industry = industry,
    isCashEquivalent = is_cash_equivalent == 1L,
    // 不计入统计与分析（老周 2026-09-20）：1 = 完全账外（场外基金备忘账）
    excludeFromStats = exclude_from_stats == 1L,
    // 每手股数（老周 2026-09-28）：NULL = 未取到 → 由 LotRule 按市场兜底
    lotSize = lot_size?.toDouble(),
)

internal fun Trade.toDomain(): DomainTransaction = DomainTransaction(
    id = id,
    securityId = security_id,
    // ⚠️ 必须按名字全量解析，不能写成 `if (SELL) SELL else BUY` ——
    // 那样 CAPITALIZE（REQ-ACC-16）会被**静默读成 BUY**，凭空多出一笔买入。
    side = TradeSide.entries.firstOrNull { it.name == side } ?: TradeSide.BUY,
    quantity = quantity,
    price = price,
    fee = fee,
    tradeDate = trade_date,
    seq = seq,
    note = note,
    emotion = emotion,
    score = score?.toInt(),
    // tag_ids 实际存的是标签名 CSV（标签管理页落地后名字即 tag.id，无需再迁移）
    tags = tag_ids?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
    quality = quality?.let { q -> com.stocknote.core.model.TradeQuality.entries.firstOrNull { it.name == q } },
    // 成交日汇率（REQ-ACC-15）：NULL = A股 或 本列上线前的历史数据 → 折算回退最新汇率
    fxRate = fx_rate,
    // 印花税（REQ-ACC-17）：0 = 本列上线前的历史数据（不追溯）
    stampDuty = stamp_duty,
)

internal fun Watchlist.toDomain(): com.stocknote.core.model.WatchItem =
    com.stocknote.core.model.WatchItem(
        id = id,
        securityId = security_id,
        groupName = group_name,
        sortOrder = sort_order,
        pinned = pinned == 1L,
        createdAt = created_at,
        // 目标价（老周 2026-09-21）：NULL = 未设
        targetPrice = target_price,
    )

internal fun Review.toDomain(): com.stocknote.core.model.Review =
    com.stocknote.core.model.Review(
        id = id,
        periodType = period_type,
        period = period,
        content = content,
        updatedAt = updated_at,
    )

internal fun Dividend.toDomain(): DividendRecord = DividendRecord(
    id = id,
    securityId = security_id,
    exDate = ex_date,
    type = type,
    quantity = quantity,
    perShare = per_share,
    bonusShares = bonus_shares,
    rightsPrice = rights_price,
    fxRate = fx_rate,
)

// ⚠️ SQLDelight 对 snake_case 表名只做首字母大写：cash_flow → Cash_flow（不是 CashFlow）。
// queries 属性名则取自 .sq 文件名（CashFlow.sq → cashFlowQueries），两者规则不同。
internal fun Cash_flow.toDomain(): CashFlowRecord = CashFlowRecord(
    id = id,
    accountId = account_id,
    flowDate = flow_date,
    type = type,
    amountOrig = amount_orig,
    currency = Currency.entries.firstOrNull { it.code == currency } ?: Currency.CNY,
    fxRate = fx_rate,
    amountBase = amount_base,
    note = note,
)

// 交易计划：trade_plan → Trade_plan（同一套 snake_case 规则，见上方 Cash_flow 的说明）
internal fun Trade_plan.toDomain(): com.stocknote.core.model.TradePlan =
    com.stocknote.core.model.TradePlan(
        id = id,
        securityId = security_id,
        // ⚠️ 轻微-12（2026-09-28）：与同文件上方 Trade 的解析警告保持一致 —— **按名字全量解析**，
        // 不写"非 SELL 即 BUY"。将来交易计划若支持第三方向，这里静默读成 BUY 的 bug 就不会发生。
        side = TradeSide.entries.firstOrNull { it.name == side } ?: TradeSide.BUY,
        targetPrice = target_price,
        discount = discount,
        plannedPrice = planned_price,
        quantity = quantity,
        feeRate = fee_rate,
        note = note,
        tags = tag_ids.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        createdAt = created_at,
        updatedAt = updated_at,
        doneAt = done_at,
    )
