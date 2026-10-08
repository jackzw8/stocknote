package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocknote.feature.theme.StockNoteColors

/**
 * 探索页（老周 2026-09-24）：底部导航**正中间**的第 3 个一级页面。
 *
 * 首个功能是「个股资讯」—— 按标的查看 **资讯 / 公告 / 研报**（腾讯自选股接口）。
 * 以后新增的探索类功能都往这张入口列表里加卡片。
 *
 * 字号沿用页面 1.2 倍规范（`LocalPageTextScale` + `pageSp`，与统计/持仓/收益率分析页一致）。
 */
@Composable
fun ExploreScreen(
    onOpenNews: () -> Unit,
    onOpenFinance: () -> Unit,
    onOpenRiskScan: () -> Unit,
    /** 7×24 快讯（老周 2026-10-04）：财联社电报，市场级实时快讯 */
    onOpenFlashNews: () -> Unit,
    /** 星际战机（老周 2026-10-04）：内嵌的 H5 canvas 小游戏，纯娱乐、离线可玩 */
    onOpenGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        LazyColumn(
            modifier = modifier.fillMaxSize().background(StockNoteColors.Background),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "探索功能",
                        fontSize = pageSp(21f),
                        fontWeight = FontWeight.ExtraBold,
                        color = StockNoteColors.TextPrimary,
                    )
                }
            }
            item {
                // 「功能」分组标题已去掉（老周 2026-09-24：入口卡片直接展示，页面就一个分组不需要标题）
                Column(Modifier.padding(horizontal = 14.dp)) {
                    CardBox {
                        ExploreEntry(
                            icon = "📰",
                            title = "个股资讯",
                            subtitle = "按标的查看资讯 · 公告 · 研报",
                            onClick = onOpenNews,
                        )
                        Box(
                            Modifier
                                .padding(horizontal = 15.dp)
                                .height(0.6.dp)
                                .fillMaxWidth()
                                .background(StockNoteColors.Divider),
                        )
                        ExploreEntry(
                            icon = "📊",
                            title = "个股财务",
                            subtitle = "按标的查看核心财务指标",
                            onClick = onOpenFinance,
                        )
                        Box(
                            Modifier
                                .padding(horizontal = 15.dp)
                                .height(0.6.dp)
                                .fillMaxWidth()
                                .background(StockNoteColors.Divider),
                        )
                        ExploreEntry(
                            icon = "🚨",
                            title = "个股扫雷",
                            subtitle = "按标的做风险体检",
                            onClick = onOpenRiskScan,
                        )
                        Box(
                            Modifier
                                .padding(horizontal = 15.dp)
                                .height(0.6.dp)
                                .fillMaxWidth()
                                .background(StockNoteColors.Divider),
                        )
                        ExploreEntry(
                            icon = "⚡",
                            title = "7×24 快讯",
                            subtitle = "财联社电报 · 市场实时快讯",
                            onClick = onOpenFlashNews,
                        )
                        Box(
                            Modifier
                                .padding(horizontal = 15.dp)
                                .height(0.6.dp)
                                .fillMaxWidth()
                                .background(StockNoteColors.Divider),
                        )
                        ExploreEntry(
                            icon = "🚀",
                            title = "星际战机",
                            subtitle = "太空射击小游戏 · 10 大关卡",
                            onClick = onOpenGame,
                        )
                    }
                }
            }
        }
    }
}

/** 探索页的功能入口行：图标 + 标题 + 副标题 + › */
@Composable
private fun ExploreEntry(
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            // 老周 2026-09-25：功能行**间距加大**（13 → 19dp），字号整体放大
            .padding(horizontal = 15.dp, vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StockNoteColors.Brand.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { Text(icon, fontSize = pageSp(19f)) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = pageSp(16.5f),
                fontWeight = FontWeight.Bold,
                color = StockNoteColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = pageSp(13f), color = StockNoteColors.TextTertiary)
        }
        Text("›", fontSize = pageSp(20f), color = StockNoteColors.TextTertiary)
    }
}
