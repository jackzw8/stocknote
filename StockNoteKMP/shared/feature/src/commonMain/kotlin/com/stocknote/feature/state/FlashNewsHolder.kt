package com.stocknote.feature.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.stocknote.data.AppContainer
import com.stocknote.data.net.FlashNewsSource
import com.stocknote.data.net.QuoteClient

/**
 * 财联社 **7×24 快讯**页状态（老周 2026-10-04）。
 *
 * 数据来自 [QuoteClient.fetchFlashNews]（签名与字段见 [FlashNewsSource]）。
 *
 * ## 分页：**不是游标翻页，而是"把 rn 加大再重取"**
 * 文档说可以用 `last_time` 翻页，但**本机实调证明这条接口完全忽略它**
 *（任何取值都返回同一批，详见 [FlashNewsSource] 的类注释）；
 * 而接口对 `rn` 是敏感的（1..50），所以"加载更多"＝ `rn += 20` 重新取一批，
 * 再按 id 去重、只追加**比当前最旧那条更旧**的条目。这样能真的往前翻，
 * 代价是每翻一次多传一点（快讯都是短文本，可接受）。
 *
 * ⚠️ `rn` 上限 50 是**硬约束**：实测 51 起接口返回**空列表且 errno=0**（静默失败）⇒
 * [FlashNewsSource.safeRn] 夹住，翻到 50 就收口（[canLoadMore] 变 false）。
 *
 * ## 错误处理
 * [error] = 页面级（一条都还没有时用它，页面给「重试」）；[moreError] = 加载更多失败
 * （已有列表还在，只在底部提示一句）。刻意**不静默** —— P1-35 的同一条原则。
 */
class FlashNewsHolder(private val quotes: QuoteClient) {

    var items by mutableStateOf<List<FlashNewsSource.Flash>>(emptyList())
        private set

    /** 首次加载（整页转圈）；已有数据时走 [refreshing] */
    var loading by mutableStateOf(false)
        private set

    /** 下拉刷新 / 手动刷新进行中（PullToRefreshBox 的指示器） */
    var refreshing by mutableStateOf(false)
        private set

    var loadingMore by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var moreError by mutableStateOf<String?>(null)
        private set

    /** 当前请求的 `rn`（"加载更多"就是把它加大） */
    private var batch = FlashNewsSource.DEFAULT_RN
    private var endReached = false

    /** 成功加载过（进页面只自动拉一次；失败则下次进来还会重试） */
    private var loadedOnce = false

    val canLoadMore: Boolean get() = !endReached && batch < FlashNewsSource.MAX_RN

    /** 是否已顶到接口的条数上限（底部提示据此说实话） */
    val atApiLimit: Boolean get() = batch >= FlashNewsSource.MAX_RN

    /** 进页面时调用：只在没成功加载过时拉一次，返回本页不清空已有数据。 */
    suspend fun bootstrap() {
        if (loadedOnce) return
        load()
    }

    /** 拉**最新一批**（首屏 / 下拉刷新 / 错误重试都走它），并把分页重置回第一批。 */
    suspend fun load() {
        if (loading || refreshing) return
        val first = items.isEmpty()
        if (first) loading = true else refreshing = true
        error = null
        moreError = null

        val page = quotes.fetchFlashNews(FlashNewsSource.DEFAULT_RN)
        if (page == null) {
            if (first) error = "快讯加载失败，请检查网络后重试" else moreError = "刷新失败，请稍后重试"
        } else {
            items = page.items
            batch = FlashNewsSource.DEFAULT_RN
            endReached = page.items.isEmpty()
            loadedOnce = true
        }

        loading = false
        refreshing = false
    }

    /** 触底追加更旧的快讯（[canLoadMore] 内部已做边界判断）。 */
    suspend fun loadMore() {
        if (loading || refreshing || loadingMore || !canLoadMore) return
        val next = FlashNewsSource.safeRn(batch + STEP)
        if (next <= batch) {
            endReached = true
            return
        }
        loadingMore = true
        moreError = null

        val page = quotes.fetchFlashNews(next)
        if (page == null) {
            moreError = "加载更多失败，点击重试"
        } else {
            // ⚠️ 两次请求之间可能有新快讯进来（列表是"新→旧"），
            //    只追加**比当前最旧那条还旧**的；否则新快讯会被塞到列表末尾、顺序就乱了。
            val known = items.mapTo(HashSet()) { it.id }
            val oldest = items.lastOrNull()?.ctime
            val fresh = page.items.filter {
                it.id != 0L && it.id !in known && (oldest == null || it.ctime <= oldest)
            }
            if (fresh.isEmpty()) {
                // 没有更旧的了（或已被夹到上限）→ 到底，别拿同一个 rn 反复请求
                endReached = true
            } else {
                items = items + fresh
                batch = next
                if (next >= FlashNewsSource.MAX_RN) endReached = true
            }
        }

        loadingMore = false
    }

    private companion object {
        /** 每次"加载更多"把 `rn` 增加多少。 */
        const val STEP = 20
    }
}

/**
 * 顶层持有（与 `NewsHolder` 同法）：快讯页是从探索页 push 进去的独立路由，
 * holder 若 remember 在路由分支里，离开（返回探索页）就会被销毁 → 再进来又是白页重拉。
 */
@Composable
fun rememberFlashNewsHolder(container: AppContainer): FlashNewsHolder =
    remember(container) { FlashNewsHolder(container.quoteClient) }
