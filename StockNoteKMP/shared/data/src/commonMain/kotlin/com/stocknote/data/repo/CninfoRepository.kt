package com.stocknote.data.repo

import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.platform.todayIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **巨潮风险域 Repository**（阶段 2：实现已搬迁，2026-09-28）。
 *
 * 与 [SettingsRepository] 同类：**叶子域** —— 在原类内部零自用（动手前 grep 确认计数全为 1），
 * 因此可以整体搬走而不牵动其它逻辑。
 *
 * 为什么落本地表：巨潮那两个接口需**动态鉴权**且返回**全市场**数据（体积大），
 * 若每次扫雷都联网拉不可行。故由「设置 → 诉讼担保查询」**手工同步一次**落 `cninfo_risk`，
 * 个股扫雷只读本地。
 */
class CninfoRepository internal constructor(
    private val db: StockNoteDb,
) {
    /**
     * 用巨潮拉取结果**全量覆盖**本地快照。
     *
     * ⚠️ **全量覆盖语义**：先清空再写入 —— 巨潮给的是"全市场统计"，增量合并会留下已失效的旧记录。
     *
     * @param sue 诉讼表 `代码 → (次数, 金额万元)`；@param gte 担保表 `代码 → (笔数, 占净资产%)`
     * @return 写入条数
     */
    suspend fun save(
        sue: Map<String, Pair<Int, Double?>>,
        gte: Map<String, Pair<Int, Double?>>,
    ): Int = withContext(Dispatchers.Default) {
        val now = todayIso()
        val codes = sue.keys + gte.keys
        db.transaction {
            db.cninfoRiskQueries.deleteAll()
            codes.forEach { code ->
                val s = sue[code]
                val g = gte[code]
                db.cninfoRiskQueries.upsert(
                    code = code,
                    name = "",
                    sue_count = (s?.first ?: 0).toLong(),
                    sue_amount = s?.second,
                    gte_count = (g?.first ?: 0).toLong(),
                    gte_ratio = g?.second,
                    updated_at = now,
                )
            }
        }
        codes.size
    }

    /** 读某只票的巨潮快照（个股扫雷用；无记录返回 null）。 */
    suspend fun ofCode(code: String) = withContext(Dispatchers.Default) {
        db.cninfoRiskQueries.selectByCode(code).executeAsOneOrNull()
    }

    /** 本地快照的「条数 + 最后同步日期」（设置页展示）。 */
    suspend fun meta(): Pair<Int, String?> = withContext(Dispatchers.Default) {
        val cnt = db.cninfoRiskQueries.countAll().executeAsOne().toInt()
        cnt to db.cninfoRiskQueries.latestUpdatedAt().executeAsOneOrNull()
    }
}
