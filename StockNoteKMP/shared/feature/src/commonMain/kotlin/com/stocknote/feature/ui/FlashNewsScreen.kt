package com.stocknote.feature.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocknote.core.format.Format
import com.stocknote.data.net.FlashNewsSource
import com.stocknote.data.platform.formatLocalDateTime
import com.stocknote.data.platform.todayIso
import com.stocknote.feature.state.FlashNewsHolder
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.launch

/**
 * **7×24 快讯**页（老周 2026-10-04）—— 探索页 →「7×24 快讯」。
 *
 * 数据：财联社电报（[FlashNewsSource] / `QuoteClient.fetchFlashNews`）。
 * 交互：下拉刷新 · 触底自动加载更多 · **点某条展开/收起全文**（快讯正文通常三五句话）。
 *
 * 口径：
 *  - 时间按**设备本地时区**格式化（`formatLocalDateTime`）——**不手工 +8**：
 *    `ctime` 是 Unix 秒，本身没有时区，加 8 反而会在非北京时区的设备上算错。
 *  - 当天只显示 `HH:mm`，隔天补 `MM-dd`。
 */
@OptIn(ExperimentalMaterial3Api::class) // PullToRefreshBox 仍是实验 API（统计页同样处理）
@Composable
fun FlashNewsScreen(
    holder: FlashNewsHolder,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        val scope = rememberCoroutineScope()
        // 只在"从没成功加载过"时自动拉（失败则下次进来会重试，成功则保留列表，避免每次进来闪一下空页）
        LaunchedEffect(Unit) { holder.bootstrap() }

        val listState = rememberLazyListState()
        // 滚到接近底部 → 追加下一批（canLoadMore 内部已做去重与边界判断）
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (last >= holder.items.size - 3) scope.launch { holder.loadMore() }
                }
        }

        // 展开的那一条（同时只展开一条，避免整屏都是长文）
        var expandedId by remember { mutableStateOf<Long?>(null) }

        PullToRefreshBox(
            isRefreshing = holder.refreshing,
            onRefresh = { scope.launch { holder.load() } },
            modifier = modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TopBar(title = "7×24 快讯", onBack = onBack)
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Color.White)
                                .clickable { scope.launch { holder.load() } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "🔄",
                                fontSize = pageSp(14f),
                                modifier = Modifier.semantics { contentDescription = "刷新快讯" },
                            )
                        }
                    }
                }

                holder.error?.let { message ->
                    item { ErrorBanner(message = message, onRetry = { scope.launch { holder.load() } }) }
                }

                when {
                    holder.loading -> item { FlashHint("正在加载快讯…") }

                    holder.items.isEmpty() -> {
                        // 有错误时上面已经给了带「重试」的提示条，这里不再叠一句
                        if (holder.error == null) item { FlashHint("暂无快讯") }
                    }

                    else -> {
                        items(holder.items, key = { it.id }) { flash ->
                            FlashRow(
                                flash = flash,
                                expanded = expandedId == flash.id,
                                onClick = {
                                    expandedId = if (expandedId == flash.id) null else flash.id
                                },
                            )
                        }
                        item {
                            val moreError = holder.moreError
                            when {
                                holder.loadingMore -> FlashHint("正在加载更多…")
                                moreError != null -> FlashRetryHint(moreError) { scope.launch { holder.loadMore() } }
                                holder.canLoadMore -> FlashHint("上滑加载更多")
                                // 接口对 rn 的硬上限是 50（超过会静默返回空），翻到头就如实说明
                                holder.atApiLimit -> FlashHint("已经到底了（接口最多提供最近 50 条）")
                                else -> FlashHint("已经到底了")
                            }
                        }
                    }
                }

                item {
                    ProtoFoot(
                        "快讯来自财联社电报（非官方接口，仅供个人参考）· 下拉刷新 · 点击条目展开全文",
                    )
                }
            }
        }
    }
}

/** 一条快讯：时间（+置顶/重要标记）· 阅读数 · 标题 · 正文（可展开）。 */
@Composable
private fun FlashRow(
    flash: FlashNewsSource.Flash,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(StockNoteColors.Surface)
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                flashTimeLabel(flash.ctime),
                fontSize = pageSp(13f),
                fontWeight = FontWeight.Bold,
                color = StockNoteColors.Brand,
            )
            if (flash.isTop) {
                Spacer(Modifier.width(6.dp))
                FlashTag("置顶", StockNoteColors.Brand)
            }
            // ⚠️ 文档只写了「level = 重要级别（A/B/C）」，没定义各级含义 ——
            // 这里按"A 最高"处理，**只做视觉标记、不改内容**；若与财联社实际口径不符，改这一处即可。
            if (flash.level.equals("A", ignoreCase = true)) {
                Spacer(Modifier.width(6.dp))
                FlashTag("重要", StockNoteColors.Up)
            }
            Spacer(Modifier.weight(1f))
            val reading = readingLabel(flash.readingNum)
            if (reading.isNotEmpty()) {
                Text(reading, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
            }
        }

        Spacer(Modifier.height(7.dp))
        Text(
            flash.title,
            fontSize = pageSp(16f),
            fontWeight = FontWeight.Medium,
            color = StockNoteColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // 正文常以「【标题】…」开头 ⇒ 去掉与标题重复的前缀；去掉后为空就整段不显示
        val body = bodyWithoutTitlePrefix(flash.title, flash.body)
        if (body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                fontSize = pageSp(13.5f),
                lineHeight = pageSp(20f),
                color = StockNoteColors.TextSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FlashTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = pageSp(10f), fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun FlashHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary)
    }
}

@Composable
private fun FlashRetryHint(text: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$text（点此重试）",
            fontSize = pageSp(12.5f),
            color = StockNoteColors.Brand,
            modifier = Modifier.clickable { onRetry() }.padding(6.dp),
        )
    }
}

/**
 * 时间标签：当天 `HH:mm`、隔天 `MM-dd HH:mm`。
 *
 * ⚠️ 一律走**设备本地时区**（`formatLocalDateTime`）—— `ctime` 是 Unix 秒，
 * 手工 "+8 小时"只在设备本来就是北京时区时才凑巧对，换台机器就错。
 */
private fun flashTimeLabel(ctimeSec: Long): String {
    if (ctimeSec <= 0L) return ""
    val full = formatLocalDateTime(ctimeSec * 1000L) // "yyyy-MM-dd HH:mm"
    val today = todayIso()
    return if (full.startsWith(today)) full.substringAfter(' ') else full.substring(5)
}

/**
 * 去掉正文开头与标题重复的「【标题】」前缀（财联社正文惯例），避免同句话出现两遍。
 *
 * 只在**确实是同一句话**时才去掉（前缀与标题互相包含），否则原样返回 —— 宁可重复一下，
 * 也别把"【某部门】"这类真内容误删。
 */
internal fun bodyWithoutTitlePrefix(title: String, body: String): String {
    if (!body.startsWith("【")) return body
    val end = body.indexOf('】')
    if (end <= 1) return body
    val inner = body.substring(1, end).trim()
    val same = inner == title.trim() || title.contains(inner) || inner.contains(title.trim())
    return if (same) body.substring(end + 1).trimStart() else body
}

/** 阅读数：1 万以内原样，超过用「x.x万」（走 `fixedPlain` —— **不带隐私遮罩**，这不是盈亏数字）。 */
internal fun readingLabel(num: Int): String = when {
    num <= 0 -> ""
    num < 10_000 -> "$num 阅读"
    else -> Format.fixedPlain(num / 10_000.0, 1) + "万阅读"
}
