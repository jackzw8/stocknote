package com.stocknote.data.repo

import com.stocknote.core.model.TagDef
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.platform.todayIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **标签管理域 Repository**（阶段 2：实现已搬迁，2026-09-29，第 4 批）。
 *
 * 叶子域（原类内部零自用），可整体搬走。
 *
 * ⚠️ **关键口径：标签的名字就是 id**。因此"重命名"实际是**换主键**，
 * 必须连带把引用它的地方一起改 —— 而且必须**逐笔精确处理**，
 * 不能用 SQL `REPLACE`（会把「平台突破」里的「突破」也误伤）。
 *
 * ⚠️ 引用点有**两处**：`trade.tag_ids` 与 `trade_plan.tag_ids`。
 * 2026-09-28 的 M11 修复就是补上了后者 —— 此前删标签只清交易，计划里会留下
 * "幽灵标签"（筛选下拉看得到、点进去 0 条）。
 */
class TagRepository internal constructor(
    private val db: StockNoteDb,
) {
    /** 全部标签（名字即 id）。 */
    suspend fun all(): List<TagDef> = withContext(Dispatchers.Default) {
        db.tagQueries.selectAll().executeAsList()
            .map { TagDef(id = it.id, name = it.name, category = it.category) }
    }

    /** 新增标签（名字即 id）。已存在返回 false。 */
    suspend fun add(name: String): Boolean = withContext(Dispatchers.Default) {
        val n = name.trim()
        if (n.isEmpty()) return@withContext false
        if (db.tagQueries.selectAll().executeAsList().any { it.id == n }) return@withContext false
        db.tagQueries.upsert(id = n, name = n, category = "STRATEGY")
        true
    }

    /**
     * 重命名：换主键（tag.id = 名字）+ 批量改 `trade.tag_ids` CSV。
     * 逐笔精确处理 CSV（不用 SQL REPLACE，避免「突破」误伤「平台突破」这类子串）。
     */
    suspend fun rename(oldId: String, newNameRaw: String) = withContext(Dispatchers.Default) {
        val newName = newNameRaw.trim()
        if (newName.isEmpty() || newName == oldId) return@withContext
        db.tagQueries.upsert(id = newName, name = newName, category = "STRATEGY")
        db.tradeQueries.selectAll().executeAsList().forEach { row ->
            val tags = row.tag_ids?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@forEach
            if (oldId !in tags) return@forEach
            val updated = tags.map { if (it == oldId) newName else it }.distinct()
            db.tradeQueries.updateTagIds(updated.joinToString(","), row.id)
        }
        db.tagQueries.deleteById(oldId)
    }

    /** 删除标签：从 tag 表移除 + 清理**交易与交易计划**里的引用。 */
    suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        db.tradeQueries.selectAll().executeAsList().forEach { row ->
            val tags = row.tag_ids?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@forEach
            if (id !in tags) return@forEach
            val updated = tags.filterNot { it == id }
            db.tradeQueries.updateTagIds(updated.joinToString(",").ifEmpty { null }, row.id)
        }
        // ⚠️ M11 修复（2026-09-28）：**交易计划里也存标签**，此前只清了 `trade` ——
        // 删掉标签后计划里仍留着它的 id（"幽灵标签"），计划筛选下拉会出现该标签、
        // 点进去 0 条，用户以为是 bug。
        val now = todayIso()
        db.tradePlanQueries.selectAll().executeAsList().forEach { plan ->
            val tags = plan.tag_ids.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (id !in tags) return@forEach
            val updated = tags.filterNot { it == id }
            db.tradePlanQueries.updateTagIds(updated.joinToString(","), now, plan.id)
        }
        db.tagQueries.deleteById(id)
    }
}
