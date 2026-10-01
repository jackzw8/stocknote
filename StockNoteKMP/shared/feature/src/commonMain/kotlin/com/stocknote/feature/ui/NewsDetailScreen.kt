package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocknote.feature.theme.StockNoteColors

/**
 * 资讯详情页（老周 2026-09-24）：点列表某条后进来。
 *
 * 标题栏固定写「资讯详情」，下面单列一行显示该条新闻标题（最多两行），
 * 正文交给 [HtmlView]（Android 内嵌 WebView / iOS 系统浏览器）—— 正文页是 H5 单页应用，
 * 列表接口的 summary / llm_content 实测恒为空，所以内容只能在网页里看。
 */
@Composable
fun NewsDetailScreen(
    title: String,
    url: String,
    /** 是否已收藏（老周 2026-09-30）：与资讯列表右侧那颗星是**同一份**收藏数据 */
    favorited: Boolean = false,
    /** 切换收藏；null = 拿不到条目 id（不显示星标） */
    onToggleFavorite: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        Column(Modifier.fillMaxSize().background(StockNoteColors.Background)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBar(title = "资讯详情", onBack = onBack)
                Spacer(Modifier.weight(1f))
                // ⭐ 收藏（老周 2026-09-30）：标题栏内、🌐 左侧，与列表页同一个 ☆/⭐ 语义
                if (onToggleFavorite != null) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color.White)
                            .clickable { onToggleFavorite() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (favorited) "⭐" else "☆",
                            fontSize = pageSp(15f),
                            modifier = Modifier.semantics {
                                contentDescription = if (favorited) "取消收藏" else "收藏"
                            },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // 用浏览器打开（老周 2026-09-24）：公告的「点击下载文件」在 WebView 里点不动
                //（腾讯页面网页模式下只是复制链接/交给浏览器），PDF 也无法在 WebView 渲染 —— 给个出口。
                if (url.isNotBlank()) {
                    val openBrowser = rememberBrowserOpener()
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color.White)
                            .clickable { openBrowser(url) },
                        contentAlignment = Alignment.Center,
                    ) { Text("🌐", fontSize = pageSp(15f)) }
                }
            }

            if (title.isNotEmpty()) {
                Text(
                    text = title,
                    fontSize = pageSp(14f),
                    fontWeight = FontWeight.Bold,
                    color = StockNoteColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            if (url.isBlank()) {
                // 接口偶尔不给 url：如实说明，不留白屏
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "这条资讯没有可打开的正文链接",
                        fontSize = pageSp(13f),
                        color = StockNoteColors.TextTertiary,
                    )
                }
            } else {
                HtmlView(url = url, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}
