package com.stocknote.data.repo

import com.stocknote.core.model.Security
import com.stocknote.core.model.WatchItem
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.platform.todayIso
import com.stocknote.data.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **自选域 Repository**（阶段 2：实现已搬迁，2026-09-29，第 5 批）。
 *
 * 为什么可以整体搬：`isWatched(3)` / `addWatch(2)` 这些"内部自用"**全部在本域内互调**
 * （`addWatch` 先查 `isWatched`），跨域依赖为零。
 *
 * ⚠️ 关键决策：**自选不级联账本** —— 移除自选只取消关注，
 * 不碰 `trade` / `position` / `note`。账本记录与"关不关注"是两回事。
 *
 * 注：`analystTargetSuggestion`（分析师目标价）虽然此前也放在"自选"分区里，
 * 但它属**行情域**（依赖 `quoteClient`），已在 [QuoteRepository]，不在此处。
 */
class WatchlistRepository internal constructor(
    private val db: StockNoteDb,
) {
    /** 自选列表（置顶固定最前，其余按手动顺序）。 */
    suspend fun all(): List<WatchItem> = withContext(Dispatchers.Default) {
        db.watchlistQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    /**
     * **最近用过的标的**（老周 2026-09-21）：标的搜索框为空时点「搜索」直接给这批。
     *
     * 口径：最近**记过账**的标的（按交易日期）∪ 最近**建过计划**的标的（按计划创建时间），
     * 按最近使用时间倒序、去重，取前 [limit] 只。空账本（全新用户）→ 回退到自选列表。
     *
     * ⚠️ 计划表 `created_at` 存的是**纪元毫秒字符串**（交易表的 `trade_date` 是 yyyy-MM-dd），
     * 两者直接字符串比较会串（`"2026-09-21" > "1758…"`），所以先统一成 yyyy-MM-dd 再排序。
     */
    suspend fun recentSecurities(limit: Int = 12): List<Security> = withContext(Dispatchers.Default) {
        fun asIsoDate(raw: String): String = when {
            raw.length >= 10 && raw[4] == '-' -> raw.take(10)
            else -> raw.toLongOrNull()
                ?.let { runCatching { com.stocknote.core.calc.CivilDate.utcMillisToIso(it) }.getOrNull() }
                .orEmpty()
                .take(10)
        }
        val secs = db.securityQueries.selectAll().executeAsList().associateBy { it.id }
        val usedAt = LinkedHashMap<String, String>()
        db.tradeQueries.selectAll().executeAsList()
            .sortedByDescending { it.trade_date }
            .forEach { tx -> if (tx.security_id !in usedAt) usedAt[tx.security_id] = tx.trade_date }
        db.tradePlanQueries.selectAll().executeAsList()
            .sortedByDescending { it.created_at }
            .forEach { p -> if (p.security_id !in usedAt) usedAt[p.security_id] = asIsoDate(p.created_at) }

        val ordered = usedAt.entries
            .sortedByDescending { it.value }
            .mapNotNull { secs[it.key]?.toDomain() }
        if (ordered.isNotEmpty()) return@withContext ordered.take(limit)

        // 空账本兜底：自选（置顶优先，与自选页同序）
        db.watchlistQueries.selectAll().executeAsList()
            .mapNotNull { secs[it.security_id]?.toDomain() }
            .take(limit)
    }

    /** 该标的是否在自选里。 */
    suspend fun isWatched(securityId: String): Boolean = withContext(Dispatchers.Default) {
        db.watchlistQueries.selectBySecurity(securityId).executeAsOneOrNull() != null
    }

    /**
     * 加入自选。幂等：已在自选则忽略（验收要求「重复添加被拦截」——
     * 数据库 unique 约束 + 这里先查一次，双重保险）。
     */
    suspend fun add(securityId: String, groupName: String = "未分组"): Boolean =
        withContext(Dispatchers.Default) {
            if (isWatched(securityId)) return@withContext false
            val order = db.watchlistQueries.selectAll().executeAsList()
                .maxOfOrNull { it.sort_order } ?: 0L
            db.watchlistQueries.insertItem(
                id = Ids.next("wl"),
                security_id = securityId,
                group_name = groupName.ifBlank { "未分组" },
                sort_order = order + 1,
                pinned = 0L,
                created_at = todayIso(),
                // ⚠️ 轻微-7（2026-09-28）：insertItem 补 target_price 后这里传 null（新加入时未设目标价）
                target_price = null,
            )
            true
        }

    /** 移除自选：只取消关注，不碰 trade/position/note（关键决策「自选不级联账本」）。 */
    suspend fun removeBySecurity(securityId: String) = withContext(Dispatchers.Default) {
        db.watchlistQueries.deleteBySecurity(securityId)
    }

    /** 置顶 / 取消置顶。 */
    suspend fun setPinned(id: String, pinned: Boolean) = withContext(Dispatchers.Default) {
        db.watchlistQueries.updatePinned(if (pinned) 1L else 0L, id)
    }

    /**
     * 设/清某只自选的目标价（null = 清除）。
     * 按 **security_id** 写：调用方（计划表单反写）手里只有标的，不一定有自选行 id。
     * 不在自选里就是 0 行受影响 —— 不会凭空创建自选（"加入自选"是用户的动作，不该被顺手代劳）。
     */
    suspend fun setTargetPriceBySecurity(securityId: String, price: Double?) =
        withContext(Dispatchers.Default) {
            db.watchlistQueries.updateTargetPriceBySecurity(price, securityId)
        }

    /** 自选里该标的的目标价；不在自选 / 未设都返回 null。 */
    suspend fun targetPriceOf(securityId: String): Double? = withContext(Dispatchers.Default) {
        db.watchlistQueries.selectBySecurity(securityId).executeAsOneOrNull()?.target_price
    }

    /** 批量移动分组。 */
    suspend fun moveGroup(ids: List<String>, groupName: String) =
        withContext(Dispatchers.Default) {
            ids.forEach { db.watchlistQueries.updateGroup(groupName.ifBlank { "未分组" }, it) }
        }

    /**
     * 批量写入自选的手动顺序（**长按拖拽排序**用，老周 2026-09-17）：
     * 按传入顺序重排 sort_order（1..n）。pinned 项由查询的 `pinned DESC` 固定在前，不受影响。
     */
    suspend fun reorder(orderedIds: List<String>) = withContext(Dispatchers.Default) {
        orderedIds.forEachIndexed { idx, id ->
            db.watchlistQueries.updateSortOrder((idx + 1).toLong(), id)
        }
    }

    /** 上移/下移一位（保留旧入口；UI 已改用拖拽）。 */
    suspend fun move(id: String, up: Boolean) = withContext(Dispatchers.Default) {
        val rows = db.watchlistQueries.selectAll().executeAsList()
        val idx = rows.indexOfFirst { it.id == id }
        if (idx < 0) return@withContext
        // pinned 行固定在最前，非 pinned 的移动只在非 pinned 区间内交换
        val pool = rows.withIndex().filter { it.value.pinned == 0L }.map { it.index }
        val pos = pool.indexOf(idx)
        if (pos < 0) return@withContext
        val target = if (up) pos - 1 else pos + 1
        if (target < 0 || target >= pool.size) return@withContext
        val a = rows[pool[pos]]
        val b = rows[pool[target]]
        db.watchlistQueries.updateSortOrder(b.sort_order, a.id)
        db.watchlistQueries.updateSortOrder(a.sort_order, b.id)
    }
}
