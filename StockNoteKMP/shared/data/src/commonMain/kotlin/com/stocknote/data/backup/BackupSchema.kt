package com.stocknote.data.backup

/**
 * 加密备份的**表清单**（老周 2026-09-21 从 `PortfolioRepository` 提出来，单独成文件）。
 *
 * 为什么要提出来：这份清单的正确性只能靠「和 schema 对得上」，
 * 而它原来埋在仓库类的私有字段里，**除了人眼没人能核对**。
 * 现在 [BackupCoverageTest] 会直接读 `*.sq` 里的建表语句逐表比对，
 * 漏一张表就会红。
 *
 * ⚠️ 教训（2026-09-21 老周实测）：`trade_plan` 曾整表漏在备份之外。
 *   症状是「备份后新加一条计划，恢复备份后那条计划还在」—— 因为恢复只按
 *   [tables] 做 `DELETE`，漏了的表既没被备份、也没被清空，成了「恢复不掉的残留」。
 *
 * 新增表的**三处同步**（缺一处就会静默丢数据）：
 *   ① 表结构：`*.sq` / 迁移 `*.sqm`；② [columns]；③ [tables]。
 */
internal object BackupSchema {

    /** 备份覆盖的账本表（`quote` / `daily_close` 是行情缓存，不备；恢复后自动重新拉取）。 */
    val tables: List<String> = listOf(
        "security", "trade", "cash_flow", "dividend", "note",
        "watchlist", "review", "fx_rate", "account", "tag", "industry_tag", "app_setting",
        "trade_plan",
        // 巨潮诉讼/担保本地快照（老周 2026-09-24 加 → 三处同步：.sq / 13.sqm / 这里）
        "cninfo_risk",
        // 资讯收藏（老周 2026-09-25 加 → 三处同步：.sq / 14.sqm / 这里）
        "news_favorite",
    )

    /** 每张表的**全部业务列** —— 漏一列，恢复时该列直接落回默认值 = 静默丢数据。 */
    val columns: Map<String, List<String>> = mapOf(
        //   - exclude_from_stats：场外基金「不计入统计」开关（老周 2026-09-20 加）
        //   - fx_rate：成交日汇率（REQ-ACC-15），此前漏在这里 → 备份恢复会丢掉成交日汇率
        "security" to listOf(
            "id", "symbol", "name", "market", "currency", "industry",
            "is_cash_equivalent", "exclude_from_stats", "lot_size",
        ),
        "trade" to listOf(
            "id", "security_id", "side", "quantity", "price", "fee", "trade_date", "seq",
            "note", "emotion", "score", "tag_ids", "quality", "fx_rate",
            // 印花税（REQ-ACC-17，老周 2026-09-30 加 → 三处同步：Trade.sq / 16.sqm / 这里）
            "stamp_duty",
        ),
        "cash_flow" to listOf(
            "id", "account_id", "flow_date", "type", "amount_orig", "currency", "fx_rate",
            "amount_base", "note",
        ),
        "dividend" to listOf(
            "id", "security_id", "ex_date", "type", "quantity", "per_share",
            "bonus_shares", "rights_price", "fx_rate",
        ),
        // L4 修复（2026-09-27）：**去掉 `screenshot`** —— 它是 BLOB，而导出走的是"文本 SQL dump"
        // （`cursor.getString` 读 BLOB 再当文本写会产出乱码/破坏 SQL）；这跟 `trade_photo`
        // 以"二进制另议"排除是**同一类东西**，此前标准不一致。
        // 实测该列从未被写入（唯一写入点 DemoData 传的是 null），所以去掉**不损失任何数据**。
        "note" to listOf("id", "transaction_id", "content", "annotation"),
        "watchlist" to listOf(
            "id", "security_id", "group_name", "sort_order", "pinned", "created_at",
            // 目标价（老周 2026-09-21 加 → 三处同步：.sq / 10.sqm / 这里）
            "target_price",
        ),
        "review" to listOf("id", "period_type", "period", "content", "updated_at"),
        "fx_rate" to listOf("currency", "effective_date", "rate", "updated_at"),
        "account" to listOf("id", "name", "currency", "cash"),
        "tag" to listOf("id", "name", "category"),
        "industry_tag" to listOf("id", "name", "source"),
        "app_setting" to listOf("setting_key", "setting_value"),
        "trade_plan" to listOf(
            "id", "security_id", "side", "target_price", "discount", "planned_price",
            "quantity", "fee_rate", "note", "tag_ids", "created_at", "updated_at",
            // 完成标记（老周 2026-09-22 加 → 三处同步：.sq / 12.sqm / 这里）
            "done_at",
        ),
        // 巨潮诉讼/担保本地快照（老周 2026-09-24 加 → 三处同步：.sq / 13.sqm / 这里）
        "cninfo_risk" to listOf(
            "code", "name", "sue_count", "sue_amount", "gte_count", "gte_ratio", "updated_at",
        ),
        // 资讯收藏（老周 2026-09-25 加 → 三处同步：.sq / 14.sqm / 这里）
        "news_favorite" to listOf(
            "id", "symbol", "title", "src", "time_text", "url", "kind", "created_at",
        ),
    )

    /**
     * 明确**不参与**备份的表（附理由）。新增表时要么进 [tables]，要么进这里 ——
     * [BackupCoverageTest] 会强制二选一，防止再出现「没人发现它漏了」。
     */
    val excluded: Map<String, String> = mapOf(
        "quote" to "行情缓存，恢复后自动重新拉取",
        "daily_close" to "日线缓存，资产曲线按需重拉",
        "trade_photo" to "交易截图 BLOB；备份是 SQL 文本 dump，二进制另议（本期从简）",
    )
}
