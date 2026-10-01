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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocknote.core.model.Security
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.data.repo.SecurityRepository
import com.stocknote.data.repo.WatchlistRepository
import com.stocknote.feature.state.NewsHolder
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.delay

/**
 * 选择标的（老周 2026-09-24）：从「个股资讯」页点「切换」进来。
 *
 * 交互：默认列出**我的自选**；输入关键词则走东财 suggest 在线搜索（防抖 250ms，与记一笔/新建计划同源）。
 * 选中即回调 [onPick] 并返回资讯页（资讯页据此重新加载）。
 */
@Composable
fun NewsSymbolPickerScreen(
    repo: PortfolioRepository,
    watch: WatchlistRepository,
    quotes: QuoteClient,
    security: SecurityRepository,
    onBack: () -> Unit,
    onPick: (symbol: String, name: String) -> Unit,
) {
    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        var keyword by remember { mutableStateOf("") }
        var hits by remember { mutableStateOf<List<QuoteClient.SymbolHit>>(emptyList()) }
        var watchlist by remember { mutableStateOf<List<Security>>(emptyList()) }
        var searching by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            watchlist = NewsHolder.watchedSecurities(repo, watch, security)
        }

        // 输入防抖后在线搜索（空则回到自选列表）
        LaunchedEffect(keyword) {
            val kw = keyword.trim()
            if (kw.isEmpty()) {
                hits = emptyList()
                searching = false
                return@LaunchedEffect
            }
            searching = true
            delay(250)
            hits = runCatching { quotes.searchOnline(kw, 20) }.getOrDefault(emptyList())
            searching = false
        }

        Column(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBar(title = "选择标的", onBack = onBack)
            }

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                singleLine = true,
                placeholder = { Text("输入代码或名称，如 600519 / 茅台", fontSize = pageSp(13f)) },
            )

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (keyword.isBlank()) {
                    item {
                        Text(
                            "我的自选",
                            fontSize = pageSp(12f),
                            color = StockNoteColors.TextTertiary,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                        )
                    }
                    if (watchlist.isEmpty()) {
                        item { HintRow("还没有自选标的，可在上方搜索") }
                    } else {
                        items(watchlist, key = { it.id }) { s ->
                            SymbolRow(s.symbol, s.name, s.market.label) { onPick(s.symbol, s.name) }
                        }
                    }
                } else {
                    if (searching) {
                        item { HintRow("搜索中…") }
                    } else if (hits.isEmpty()) {
                        item { HintRow("没有搜到匹配的标的") }
                    } else {
                        items(hits, key = { it.symbol }) { h ->
                            SymbolRow(h.symbol, h.name, h.marketLabel) { onPick(h.symbol, h.name) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SymbolRow(
    symbol: String,
    name: String,
    marketLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(StockNoteColors.Surface)
            .clickable { onClick() }
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = pageSp(14f),
                fontWeight = FontWeight.Bold,
                color = StockNoteColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (marketLabel.isNotEmpty()) {
                    Text(marketLabel, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
                    Spacer(Modifier.padding(horizontal = 3.dp))
                }
                Text(symbol, fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun HintRow(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = pageSp(12.5f), color = StockNoteColors.TextTertiary)
    }
}
