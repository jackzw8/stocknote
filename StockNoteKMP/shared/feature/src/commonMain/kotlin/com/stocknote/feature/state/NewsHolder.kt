package com.stocknote.feature.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stocknote.core.model.Security
import com.stocknote.data.net.NewsSource
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.repo.NewsFavoriteRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.WatchlistRepository
import com.stocknote.data.repo.PortfolioRepository

/**
 * 个股资讯页的状态（老周 2026-09-24）。
 *
 * 职责：当前标的（symbol/name）· 当前分类（资讯/公告/研报）· 分页列表 （items/page/totalPage）
 * · 加载与错误态。数据全部来自 [QuoteClient.fetchStockNews]（腾讯自选股接口）。
 *
 * 分页口径：接口 1-based 页码 + 每页 20 条；滚动到底且未到最后一页时 [loadMore]。
 * 换标的 / 换分类都**重置到第 1 页**（不叠加）。
 */
/**
 * ⚠️ **修 bug（2026-09-28，老周报）：资讯详情返回后标的被重置。**
 *
 * 此前 `NewsHolder` 是在 `App.kt` 的 `is AppRoute.NewsList ->` 分支里 `remember` 的 ——
 * **路由一切换（进资讯详情）该分支的组合就被销毁，holder 连同 `symbol` 一起丢**；
 * 返回时 holder 重建，`bootstrap()` 又选回"自选第一只"→ 用户看到的是
 * 「列表回来了、但标的换了」，也就是"没有退回到真正的上一页"。
 *
 * 提升到 **App 顶层**（与 `rememberPlanHolder` 等一致）后，holder 跨路由组合保留，
 * 返回时仍在看用户刚选的那只标的。
 */
@androidx.compose.runtime.Composable
fun rememberNewsHolder(
    container: com.stocknote.data.AppContainer,
): NewsHolder = androidx.compose.runtime.remember(container) {
    NewsHolder(container.portfolio, container.news, container.watch, container.quoteClient, container.security)
}

class NewsHolder(
    private val repo: PortfolioRepository,
    /** ⚠️ 2026-09-28 拆分：资讯收藏域。 */
    private val news: NewsFavoriteRepository,
    /** ⚠️ 2026-09-29 拆分（第 5 批）：自选域。 */
    private val watch: WatchlistRepository,
    private val quotes: QuoteClient,
    /** **标的域**（第 11 批）：资讯按标的过滤。 */
    private val security: SecurityRepository,
) {
    /** 当前标的（App 侧代码，如 sh600519 / hk00700） */
    var symbol by mutableStateOf("")
        private set

    /** 当前标的名称（展示用；取自接口/本地标的表） */
    var name by mutableStateOf("")
        private set

    var kind by mutableStateOf(NewsSource.Kind.NEWS)
        private set

    var items by mutableStateOf<List<NewsSource.NewsItem>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var loadingMore by mutableStateOf(false)
        private set

    var page by mutableStateOf(1)
        private set

    var totalPage by mutableStateOf(1)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 已加载过的标记：进入页面只自动选一次默认标的，用户换过之后不再覆盖 */
    private var bootstrapped = false

    /**
     * 列表滚动位置（老周 2026-10-03）：从资讯详情**返回时定位到刚才看的那条**。
     *
     * holder 已提升到 App 顶层（跨路由不丢），而 `LazyListState` 建在列表页内部、
     * 切去详情时随组合销毁 → 把「首条可见项 + 偏移」存到 holder 上，返回时用它做初值。
     * 换标的 / 换分类（[reload] 重新加载第 1 页）时归零，避免停在上一个列表的位置。
     */
    var scrollIndex by mutableStateOf(0)
        private set

    var scrollOffset by mutableStateOf(0)
        private set

    /** 离开列表页时保存滚动位置（见 [scrollIndex]）。 */
    fun saveScroll(index: Int, offset: Int) {
        scrollIndex = index
        scrollOffset = offset
    }

    val canLoadMore: Boolean get() = !loading && !loadingMore && page < totalPage

    /**
     * 首次进入：默认用**自选列表第一只**，没有自选则留空并提示。
     * 已经在页面里手动选过标的就不再覆盖。
     */
    suspend fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        if (symbol.isEmpty()) {
            val first = watchedSecurities(repo, watch, security).firstOrNull()
            if (first != null) {
                symbol = first.symbol
                name = first.name
            }
        }
        reload()
    }

    /** 选标的（来自「选择标的」页） */
    suspend fun select(symbol: String, name: String) {
        bootstrapped = true
        this.symbol = symbol
        this.name = name
        reload()
    }

    /** 切换分类（资讯 / 公告 / 研报） */
    suspend fun setKind(kind: NewsSource.Kind) {
        if (kind == this.kind) return
        this.kind = kind
        reload()
    }

    /** 重新加载第 1 页 */
    suspend fun reload() {
        // 重新加载 = 换了标的 / 分类 → 滚动位置归零（老周 2026-10-03）
        scrollIndex = 0
        scrollOffset = 0
        if (symbol.isEmpty()) {
            items = emptyList()
            error = null
            return
        }
        loading = true
        error = null
        val result = runCatching { quotes.fetchStockNews(symbol, kind, page = 1) }
        val p = result.getOrNull()
        if (p == null) {
            error = "加载失败，请稍后重试"
            items = emptyList()
        } else {
            items = p.items
            page = 1
            totalPage = p.totalPage.coerceAtLeast(1)
            // 接口对不支持的市场会返回空；给一句人话提示，不要空白页
            error = if (p.isEmpty) "暂无${kind.label}" else null
        }
        loading = false
    }

    /** 滚动到底加载下一页（追加） */
    // ---------------------------------------------------------------- 收藏（老周 2026-09-25）

    /** 已收藏的 id 集合（用于点亮列表里的 ⭐） */
    var favoriteIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 是否处于「⭐ 收藏」视图（true 时列表显示**本地收藏**，不拉网络） */
    var showFavorites by mutableStateOf(false)
        private set

    /** 收藏列表（离线可看，按收藏时间倒序） */
    var favoriteItems by mutableStateOf<List<NewsSource.NewsItem>>(emptyList())
        private set

    /**
     * **当前打开的资讯条目**（老周 2026-09-30）。
     *
     * 详情页也要能加星，而收藏主键是 `id`（连同标题/来源/时间/链接一起写入），
     * 路由参数里只有 title/url 是不够的。所以在 push 详情路由**之前**由 App 层把整个条目放这里，
     * 详情页读它做收藏判断与切换（holder 已提升到顶层，跨路由不丢）。
     */
    var detailItem by mutableStateOf<NewsSource.NewsItem?>(null)
        private set

    /** 打开详情时暂存当前条目（见 [detailItem]）。 */
    fun setDetail(item: NewsSource.NewsItem?) {
        detailItem = item
    }

    /** 刷新收藏态（进页面 / 打开收藏视图 / 每次收藏后调用） */
    suspend fun refreshFavorites() {
        favoriteIds = runCatching { news.ids() }.getOrDefault(emptySet())
        favoriteItems = runCatching {
            news.all().map { r ->
                NewsSource.NewsItem(
                    id = r.id,
                    time = r.time_text,
                    title = r.title,
                    src = r.src,
                    url = r.url,
                    // 收藏时未冗余存摘要与重要度（详情页只用 title/url），故给默认值
                    summary = "",
                    importance = null,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 切换「⭐ 收藏」视图；打开时刷新一次收藏列表 */
    suspend fun setShowFavorites(on: Boolean) {
        showFavorites = on
        if (on) refreshFavorites()
    }

    /** 收藏 / 取消收藏一条（按 id 判断当前是否已收藏） */
    suspend fun toggleFavorite(item: NewsSource.NewsItem) {
        val fav = item.id !in favoriteIds
        runCatching {
            news.toggle(
                id = item.id,
                symbol = symbol,
                title = item.title,
                src = item.src,
                timeText = item.time,
                url = item.url,
                kind = kind.code,
                favorite = fav,
            )
        }
        refreshFavorites()
    }

    suspend fun loadMore() {
        if (!canLoadMore || symbol.isEmpty()) return
        loadingMore = true
        val next = page + 1
        val p = runCatching { quotes.fetchStockNews(symbol, kind, page = next) }.getOrNull()
        if (p != null && p.items.isNotEmpty()) {
            items = items + p.items
            page = next
            totalPage = p.totalPage.coerceAtLeast(totalPage)
        } else {
            // 拉到空页就把总页数收紧，避免无限触发
            totalPage = page
        }
        loadingMore = false
    }

    companion object {
        /**
         * 自选标的（带名称）。
         * ⚠️ `watch.all()` 返回的是 [com.stocknote.core.model.WatchItem]（只有 securityId，
         * 没有 symbol/name），而资讯接口要 symbol、列表要显示名称 → 这里按 securityId 关联 [Security]。
         */
        suspend fun watchedSecurities(repo: PortfolioRepository, watch: WatchlistRepository, security: SecurityRepository): List<Security> {
            val ids = runCatching { watch.all() }.getOrDefault(emptyList()).map { it.securityId }
            if (ids.isEmpty()) return emptyList()
            val all = runCatching { security.securities() }.getOrDefault(emptyList())
            return ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
        }
    }
}
