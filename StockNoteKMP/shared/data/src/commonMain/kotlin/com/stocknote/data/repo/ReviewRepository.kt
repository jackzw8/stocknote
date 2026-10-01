package com.stocknote.data.repo

import com.stocknote.core.model.Review
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.platform.todayIso
import com.stocknote.data.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **月/季复盘域 Repository**（阶段 2：实现已搬迁，2026-09-29，第 4 批）。
 *
 * 叶子域（原类内部零自用，grep 计数全为 1），可整体搬走。
 *
 * 域内职责：复盘列表 / 保存（同周期 REPLACE）/ 删除。
 * ⚠️ 口径：同一 `(periodType, period)` 是**覆盖**语义 —— 先查已有 id，有则复用，
 * 保证「一个月只有一条复盘」。
 */
class ReviewRepository internal constructor(
    private val db: StockNoteDb,
) {
    /** 全部复盘（读取型页面用）。 */
    suspend fun all(): List<Review> = withContext(Dispatchers.Default) {
        db.reviewQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    /** 保存复盘（同一周期 REPLACE），返回 id。 */
    suspend fun save(
        periodType: String,
        period: String,
        content: String,
    ): String = withContext(Dispatchers.Default) {
        val existing = db.reviewQueries.selectByPeriod(periodType, period).executeAsOneOrNull()
        val id = existing?.id ?: Ids.next("rv")
        db.reviewQueries.upsert(
            id = id,
            period_type = periodType,
            period = period,
            content = content.trim(),
            updated_at = todayIso(),
        )
        id
    }

    /** 删除复盘。 */
    suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        db.reviewQueries.deleteById(id)
    }
}
