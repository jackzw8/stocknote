package com.stocknote.data.repo

import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.platform.todayIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **资讯收藏域 Repository**（阶段 2：实现已搬迁，2026-09-28）。
 *
 * 同为**叶子域**（原类内部零自用，grep 计数全为 1），可整体搬走。
 *
 * 域的由来（老周 2026-09-25）：资讯来自腾讯接口、随时可能失效或被删，
 * 收藏是**把内容快照落到本地**（标题/来源/时间/链接/类型都存下来），
 * 因此收藏列表**离线可看**，不依赖接口还活着。
 */
class NewsFavoriteRepository internal constructor(
    private val db: StockNoteDb,
) {
    /**
     * 收藏 / 取消收藏一条资讯。
     * @param favorite true=收藏（写入，重复收藏按 id 覆盖）；false=取消（按 id 删除）
     */
    suspend fun toggle(
        id: String,
        symbol: String,
        title: String,
        src: String,
        timeText: String,
        url: String,
        kind: Int,
        favorite: Boolean,
    ) = withContext(Dispatchers.Default) {
        if (favorite) {
            db.newsFavoriteQueries.upsert(
                id = id,
                symbol = symbol,
                title = title,
                src = src,
                time_text = timeText,
                url = url,
                kind = kind.toLong(),
                created_at = todayIso(),
            )
        } else {
            db.newsFavoriteQueries.deleteById(id)
        }
    }

    /** 已收藏的资讯 id 集合（列表页用来点亮 ⭐）。 */
    suspend fun ids(): Set<String> = withContext(Dispatchers.Default) {
        db.newsFavoriteQueries.selectIds().executeAsList().toSet()
    }

    /** 收藏列表（按收藏时间倒序），供「⭐ 收藏」分类展示（离线可看）。 */
    suspend fun all() = withContext(Dispatchers.Default) {
        db.newsFavoriteQueries.selectAll().executeAsList()
    }

    /** 收藏条数。 */
    suspend fun count(): Int = withContext(Dispatchers.Default) {
        db.newsFavoriteQueries.countAll().executeAsOne().toInt()
    }
}
