package com.stocknote.data.repo

import com.stocknote.core.io.SkySeedItem
import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain
import com.stocknote.core.model.SkyFactor
import com.stocknote.core.model.SkyJudgement
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.platform.nowEpochMs
import com.stocknote.data.platform.todayIso
import com.stocknote.data.util.Ids
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **看天看地域 Repository**（SE-4，老周 2026-10-09）。
 *
 * 只做 CRUD + 事务组合，**不算分**（算分是 core 的 `SkyEarthScore` 纯函数）。
 * 叶子域：只依赖 `db` + `app_setting`，不持有任何其它 Repository。
 *
 * ⚠️ **两条事务红线**（技术说明书 §3.2 注）：
 *  1. [setScore]：factor 快照与 judgement 历史**必须同事务** —— 否则会出现
 *     「分数变了但历史没留」的静默不一致；
 *  2. [removeFactor]：先删 judgement 再删 factor（**无外键，级联全靠手写**）——
 *     漏了就是孤儿行（正好是 P2-28 想做的护栏类型）。
 *
 * ⚠️ 线程一律**单线程**（`limitedParallelism(1)`）：与 DB 写串行，避免并发交叉。
 *
 * ⚠️⚠️ 但**不能用 `Dispatchers.IO`** —— 它在 K/N 的 `commonMain` 里**访问不到**
 *（2026-10-09 iOS CI 实测：`Cannot access 'val IO: CoroutineDispatcher':
 *  it is internal in 'kotlinx.coroutines.Dispatchers'`；`IO` 只在 JVM/Android 与
 *  iOS 各自的平台源集里声明，commonMain 看不到它）。
 *  要真用 IO 得走 expect/actual，属 **P2-24** 的范围；这里用 `Default` + `limitedParallelism(1)`，
 *  「DB 操作串行」这个**功能点完全一样**。
 *  ⚠️ 这个坑**本地拦不住**（JVM 有 `IO`、Android 编得过），只有 iOS 目标才会报 ——
 *  已给 `CommonMainPlatformLeakTest` 加规则，下次在 `jvmTest`（秒级）就会红。
 */
class SkyEarthRepository internal constructor(
    private val db: StockNoteDb,
    /** 测试可注入；默认单线程（与 DB 写串行，避免并发交叉）。 */
    private val io: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {

    // ---- 清单 ----

    /** 某一组的完整清单（按 sort_order 排序；含未判断条目）。 */
    suspend fun factors(domain: SkyDomain, scopeKey: String): List<SkyFactor> = withContext(io) {
        db.skyEarthQueries.selectGroup(domain.raw, scopeKey).executeAsList().map { it.toDomain() }
    }

    /** 组内条数（上限拦截用；拦截本身在 Holder/预览页，不在本层）。 */
    suspend fun countGroup(domain: SkyDomain, scopeKey: String): Long = withContext(io) {
        db.skyEarthQueries.countGroup(domain.raw, scopeKey).executeAsOne()
    }

    /** 新增一条关注点（追加到组尾），返回新条目 id。 */
    suspend fun addFactor(
        domain: SkyDomain,
        scopeKey: String,
        title: String,
        note: String? = null,
    ): String = withContext(io) {
        val id = Ids.next("skf")
        db.skyEarthQueries.insertFactor(
            id = id,
            domain = domain.raw,
            scope_key = scopeKey,
            title = title,
            sort_order = nextSortOrder(domain, scopeKey),
            score = null,
            note = note,
            judged_at = null,
            created_at = nowEpochMs(),
        )
        id
    }

    /** 改标题 / 依据（不动判断与历史）。 */
    suspend fun renameFactor(id: String, title: String, note: String?) = withContext(io) {
        db.skyEarthQueries.updateTitleNote(title, note, id)
    }

    /**
     * 打分 —— **一个事务写两处**：`sky_factor` 快照（score + judged_at）
     * 与 `sky_judgement` 历史（同自然日覆盖，见 `sky_judgement_day_idx` 唯一索引）。
     *
     * @param score null = 清除判断（回到未判断；一期 UI 无此入口，保留给导入覆盖等场景）。
     *   清除**不写历史**（历史只增不清）。
     */
    suspend fun setScore(id: String, score: SkyAttitude?) = withContext(io) {
        val now = nowEpochMs()
        db.transaction {
            db.skyEarthQueries.updateScore(
                score = score?.value?.toLong(),
                judged_at = if (score != null) now else null,
                id = id,
            )
            if (score != null) {
                db.skyEarthQueries.upsertJudgement(
                    id = Ids.next("skj"),
                    factor_id = id,
                    day = todayIso(),
                    score = score.value.toLong(),
                    judged_at = now,
                )
            }
        }
    }

    /** 删除一条关注点 + **级联**删除它的全部判断历史（同一事务；无外键，全靠手写）。 */
    suspend fun removeFactor(id: String) = withContext(io) {
        db.transaction {
            db.skyEarthQueries.deleteJudgementsOf(id)
            db.skyEarthQueries.deleteFactor(id)
        }
    }

    /**
     * 拖拽排序：按传入顺序重排 sort_order（1..n）。
     * 调用方传的必须是**同一组**的完整 id 序列（照搬自选域 `reorder` 的写法）。
     */
    suspend fun reorder(idsInOrder: List<String>) = withContext(io) {
        db.transaction {
            idsInOrder.forEachIndexed { idx, id ->
                db.skyEarthQueries.updateSortOrder((idx + 1).toLong(), id)
            }
        }
    }

    /** 某条关注点的判断历史（时间升序；详情页「判断阶梯线」与变更记录用它）。 */
    suspend fun judgements(factorId: String): List<SkyJudgement> = withContext(io) {
        db.skyEarthQueries.selectJudgements(factorId).executeAsList().map { it.toDomain() }
    }

    // ---- 种子导入 / 复制 ----

    /**
     * 批量导入种子条目：**追加到组尾，不覆盖、不去重**（重复由预览页把关，FR-SE-11）。
     *
     * 带档位的条目同时写一条判断历史（视为「首次判断」）—— 否则详情页会出现
     * 「factor 显示已判断、历史却为空」的不一致。未识别档位的条目照常入库（score = null）。
     *
     * @return 实际写入条数。
     */
    suspend fun importSeeds(domain: SkyDomain, scopeKey: String, items: List<SkySeedItem>): Int =
        withContext(io) {
            if (items.isEmpty()) return@withContext 0
            val now = nowEpochMs()
            val day = todayIso()
            val base = nextSortOrder(domain, scopeKey)
            db.transaction {
                items.forEachIndexed { idx, item ->
                    val id = Ids.next("skf")
                    val attitude = item.attitude
                    db.skyEarthQueries.insertFactor(
                        id = id,
                        domain = domain.raw,
                        scope_key = scopeKey,
                        title = item.title,
                        sort_order = base + idx,
                        score = attitude?.value?.toLong(),
                        note = item.note,
                        judged_at = if (attitude != null) now else null,
                        created_at = now,
                    )
                    if (attitude != null) {
                        db.skyEarthQueries.upsertJudgement(
                            id = Ids.next("skj"),
                            factor_id = id,
                            day = day,
                            score = attitude.value.toLong(),
                            judged_at = now,
                        )
                    }
                }
            }
            items.size
        }

    /**
     * 「复制其他标的的条目过来」（FR-SE-10 地组换标的的第二个出口）：
     * 只复制**标题与排序**，不带打分与依据（避免把别的标的的判断污染过来）。
     *
     * @return 复制条数。
     */
    suspend fun copyTitlesFrom(domain: SkyDomain, fromScopeKey: String, toScopeKey: String): Int =
        withContext(io) {
            val source = factors(domain, fromScopeKey)
            if (source.isEmpty()) return@withContext 0
            val now = nowEpochMs()
            val base = nextSortOrder(domain, toScopeKey)
            db.transaction {
                source.forEachIndexed { idx, f ->
                    db.skyEarthQueries.insertFactor(
                        id = Ids.next("skf"),
                        domain = domain.raw,
                        scope_key = toScopeKey,
                        title = f.title,
                        sort_order = base + idx,
                        score = null,
                        note = null,
                        judged_at = null,
                        created_at = now,
                    )
                }
            }
            source.size
        }

    /** 地组里「有清单的标的」scopeKey 列表（「复制其他标的的条目」的源选择用）。 */
    suspend fun earthScopes(): List<String> = withContext(io) {
        db.skyEarthQueries.selectEarthScopes().executeAsList()
    }

    // ---- 标的显示名（地组卡片用）----

    /** 库里的标的名；没有（未建档）→ null。 */
    suspend fun securityNameOf(symbol: String): String? = withContext(io) {
        db.securityQueries.selectBySymbol(symbol).executeAsOneOrNull()?.name
    }

    // ---- 模块级配置（app_setting，key 前缀 sky_earth.）----

    /** 当前看地标的 symbol；未设置默认腾讯控股（FR-SE-10）。 */
    suspend fun currentEarthSymbol(): String = withContext(io) {
        settingValue(KEY_EARTH_SYMBOL) ?: DEFAULT_EARTH_SYMBOL
    }

    suspend fun setCurrentEarthSymbol(symbol: String) = withContext(io) {
        db.settingQueries.upsert(KEY_EARTH_SYMBOL, symbol)
    }

    /** 天组基准 symbol（详情页同期走势用）；未设置默认沪深300。 */
    suspend fun skyBenchmark(): String = withContext(io) {
        settingValue(KEY_SKY_BENCHMARK) ?: DEFAULT_SKY_BENCHMARK
    }

    suspend fun setSkyBenchmark(symbol: String) = withContext(io) {
        db.settingQueries.upsert(KEY_SKY_BENCHMARK, symbol)
    }

    // ---- 内部 ----

    private fun nextSortOrder(domain: SkyDomain, scopeKey: String): Long =
        // SQLDelight 对 max() 单列查询生成包装类型 MaxSortOrder（字段 max，可空）
        (db.skyEarthQueries.maxSortOrder(domain.raw, scopeKey).executeAsOneOrNull()?.max ?: 0L) + 1

    private fun settingValue(key: String): String? =
        db.settingQueries.selectByKey(key).executeAsOneOrNull()?.setting_value?.takeIf { it.isNotBlank() }

    companion object {
        /** 天组 scopeKey 恒定值（地组 = 标的代码，如 `hk00700`）。 */
        const val SKY_SCOPE = ""

        const val DEFAULT_EARTH_SYMBOL = "hk00700"
        const val DEFAULT_SKY_BENCHMARK = "sh000300"

        private const val KEY_EARTH_SYMBOL = "sky_earth.earth_symbol"
        private const val KEY_SKY_BENCHMARK = "sky_earth.sky_benchmark"
    }
}
