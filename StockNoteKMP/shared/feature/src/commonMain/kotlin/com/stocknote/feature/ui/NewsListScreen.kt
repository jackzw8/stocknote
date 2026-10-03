package com.stocknote.feature.ui

import com.stocknote.data.repo.SecurityRepository
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocknote.data.net.NewsSource
import com.stocknote.feature.state.NewsHolder
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.launch

/**
 * 个股资讯列表页（老周 2026-09-24）。
 *
 * 结构：顶部栏 → **标的行**（点击去选标的）→ **分类切换**（资讯/公告/研报）→ 资讯列表。
 * 点某条 → [onOpenDetail] 进详情页（WebView 打开腾讯 H5 正文）。
 *
 * 分页：滚动到接近底部自动加载下一页；接口 1-based 页码、每页 20 条。
 */
@Composable
fun NewsListScreen(
    holder: NewsHolder,
    repo: com.stocknote.data.repo.PortfolioRepository,
    watch: com.stocknote.data.repo.WatchlistRepository,
    quotes: com.stocknote.data.net.QuoteClient,
    security: com.stocknote.data.repo.SecurityRepository,
    onBack: () -> Unit,
    onOpenDetail: (NewsSource.NewsItem) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 「切换标的」用**页内切换视图**实现，而不是再开一个路由：
    // 路由间回传选中值要额外做状态传递，页内切换直接改 holder 更省事、也不会出现返回栈错乱。
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        NewsSymbolPickerScreen(
            repo = repo,
            quotes = quotes,
            watch = watch,
            security = security,
            onBack = { picking = false },
            onPick = { symbol, name ->
                picking = false
                scope.launch { holder.select(symbol, name) }
            },
        )
        return
    }

    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        // 返回时定位到之前看的那条（老周 2026-10-03）：初值取 holder 上存的位置，
        // 离开本页（进详情）时在 onDispose 里存回去 —— holder 跨路由保留、列表 state 不保留。
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = holder.scrollIndex,
            initialFirstVisibleItemScrollOffset = holder.scrollOffset,
        )
        DisposableEffect(Unit) {
            onDispose {
                holder.saveScroll(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                )
            }
        }

        // 进页面：默认选自选第一只并加载，同时刷新收藏态（老周 2026-09-25 收藏功能）
        LaunchedEffect(Unit) {
            holder.bootstrap()
            holder.refreshFavorites()
        }

        // 滚到接近底部 → 追加下一页（canLoadMore 内部已做去重与边界判断）
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (last >= holder.items.size - 2) scope.launch { holder.loadMore() }
                }
        }

        Column(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
            // ---- 顶部栏 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBar(title = "个股资讯", onBack = onBack)
            }

            // ---- 当前标的（点击换标的）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(StockNoteColors.Surface)
                    .clickable { picking = true }
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("标的", fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (holder.name.isNotEmpty()) holder.name else "请选择标的",
                    fontSize = pageSp(15f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.TextPrimary,
                )
                if (holder.symbol.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(holder.symbol, fontSize = pageSp(11.5f), color = StockNoteColors.TextTertiary)
                }
                Spacer(Modifier.weight(1f))
                Text("切换 ›", fontSize = pageSp(12.5f), color = StockNoteColors.Brand)
            }

            Spacer(Modifier.height(10.dp))

            // ---- 分类切换（资讯 / 公告 / 研报）----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NewsSource.Kind.entries.forEach { k ->
                    // 处于「⭐ 收藏」视图时，三个分类 chip 全部落选
                    val on = !holder.showFavorites && holder.kind == k
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) StockNoteColors.Brand else StockNoteColors.Surface)
                            .clickable { scope.launch { holder.setShowFavorites(false); holder.setKind(k) } }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            k.label,
                            fontSize = pageSp(13.5f),
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) androidx.compose.ui.graphics.Color.White else StockNoteColors.TextSecondary,
                        )
                    }
                }
                // 「⭐ 收藏」chip（老周 2026-09-25）：切到本地收藏列表
                run {
                    val on = holder.showFavorites
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (on) StockNoteColors.Brand else StockNoteColors.Surface)
                            .clickable { scope.launch { holder.setShowFavorites(true) } }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            if (holder.favoriteIds.isEmpty()) "⭐ 收藏"
                            else "⭐ 收藏 ${holder.favoriteIds.size}",
                            fontSize = pageSp(13.5f),
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) androidx.compose.ui.graphics.Color.White else StockNoteColors.TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---- 列表 ----
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 收藏视图（老周 2026-09-25）：显示**本地收藏**——离线可看、不分页
                if (holder.showFavorites) {
                    if (holder.favoriteItems.isEmpty()) {
                        item { CenterHint("还没有收藏的资讯，点列表右侧的 ☆ 即可收藏") }
                    } else {
                        items(holder.favoriteItems, key = { "fav-" + it.id }) { n ->
                            NewsRow(n, favorited = true, onToggleFav = { scope.launch { holder.toggleFavorite(n) } }) {
                                onOpenDetail(n)
                            }
                        }
                        item { CenterHint("共 ${holder.favoriteItems.size} 条收藏") }
                    }
                } else if (holder.loading) {
                    item { CenterHint("正在加载…") }
                } else if (holder.items.isEmpty()) {
                    item { CenterHint(holder.error ?: "暂无内容") }
                } else {
                    items(holder.items, key = { it.id.ifEmpty { it.title + it.time } }) { n ->
                        NewsRow(
                            item = n,
                            favorited = n.id in holder.favoriteIds,
                            onToggleFav = { scope.launch { holder.toggleFavorite(n) } },
                        ) { onOpenDetail(n) }
                    }
                    item {
                        val hint = when {
                            holder.loadingMore -> "正在加载更多…"
                            holder.canLoadMore -> "上滑加载更多"
                            else -> "已经到底了"
                        }
                        CenterHint(hint)
                    }
                }
            }
        }
    }
}

/** 一条资讯：标题（最多 2 行）+ 来源 · 时间 + 右侧收藏星（老周 2026-09-25：字号放大 + 收藏） */
@Composable
private fun NewsRow(
    item: NewsSource.NewsItem,
    favorited: Boolean,
    onToggleFav: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(StockNoteColors.Surface)
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                item.title,
                fontSize = pageSp(16f),
                fontWeight = FontWeight.Medium,
                color = StockNoteColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // 收藏星：空心 ☆ / 实心 ⭐（点一下切换，不影响点行进详情）
            Text(
                text = if (favorited) "⭐" else "☆",
                fontSize = pageSp(17f),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onToggleFav() }
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.src.isNotEmpty()) {
                Text(item.src, fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary)
                Spacer(Modifier.width(4.dp))
            }
            Text(item.time, fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary)
            Spacer(Modifier.weight(1f))
            if (item.url.isNotEmpty()) {
                Text("›", fontSize = pageSp(17f), color = StockNoteColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary)
    }
}
