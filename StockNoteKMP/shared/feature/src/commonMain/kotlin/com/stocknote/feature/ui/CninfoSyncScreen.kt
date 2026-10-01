package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stocknote.data.net.QuoteClient
import com.stocknote.data.repo.CninfoRepository
import com.stocknote.data.repo.PortfolioRepository
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.launch

/**
 * 巨潮「诉讼 / 担保」数据同步页（老周 2026-09-24）。
 *
 * ## 为什么要有这一页
 * 个股扫雷的「涉诉金额/净资产」与「对外担保/净资产」两项来自**巨潮资讯**，而那两个接口：
 *  ① 需要**动态鉴权头**（`Accept-Enckey`，AES-128-CBC 加密当前时间戳）；
 *  ② 返回的是**全市场**记录（几千只票，体积大、耗流量）。
 * 所以**不做成每次扫雷都拉** —— 改为在这里**手工同步一次**（全量覆盖写本地表 `cninfo_risk`），
 * 扫雷时只查本地（秒出、离线可用）。
 *
 * ⚠️ 同步是**全量覆盖**：先清空本地表再写入，避免残留已失效的旧记录。
 */
@Composable
fun CninfoSyncScreen(
    repo: PortfolioRepository,
    /** ⚠️ 2026-09-28 拆分：巨潮风险域（写入快照与读取同步元信息）。 */
    cninfo: CninfoRepository,
    quotes: QuoteClient,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(-1) }
    var lastAt by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refreshMeta() {
        runCatching { cninfo.meta() }.getOrNull()?.let { (c, t) ->
            count = c
            lastAt = t
        }
    }

    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        LaunchedEffect(Unit) { refreshMeta() }

        Column(
            modifier = Modifier.fillMaxSize().background(StockNoteColors.Background)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { TopBar(title = "诉讼担保查询", onBack = onBack) }

            // ---- 本地数据状态 ----
            Sec(title = "本地数据", modifier = Modifier.padding(top = 6.dp)) {
                CardBox {
                    InfoRow("已保存记录", if (count < 0) "读取中…" else "$count 条")
                    Box(Modifier.padding(horizontal = 15.dp).height(0.6.dp).background(StockNoteColors.Divider))
                    InfoRow("最后同步", lastAt ?: "尚未同步")
                }
            }

            // ---- 同步按钮 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (loading) StockNoteColors.TextTertiary else StockNoteColors.Brand)
                    .clickable(enabled = !loading) {
                        scope.launch {
                            loading = true
                            message = null
                            val result = runCatching { quotes.fetchCninfoRisk() }
                            val pair = result.getOrNull()
                            if (pair == null || (pair.first.isEmpty() && pair.second.isEmpty())) {
                                message = "拉取失败（网络或鉴权问题），本地数据保持不变，可稍后重试"
                            } else {
                                val n = runCatching { cninfo.save(pair.first, pair.second) }.getOrDefault(0)
                                message = "同步完成：诉讼 ${pair.first.size} 条、担保 ${pair.second.size} 条，已写入本地 $n 条"
                                refreshMeta()
                            }
                            loading = false
                        }
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (loading) "正在拉取全市场数据…" else "拉取并保存到本地",
                    fontSize = pageSp(14.5f),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            // ---- 结果 / 说明 ----
            message?.let {
                Sec(title = "同步结果") {
                    CardBox {
                        Text(
                            it,
                            fontSize = pageSp(12.5f),
                            color = StockNoteColors.TextSecondary,
                            modifier = Modifier.padding(15.dp),
                        )
                    }
                }
            }

            Sec(title = "说明", modifier = Modifier.padding(top = 10.dp)) {
                CardBox {
                    Text(
                        "· 数据来源：巨潮资讯「公司诉讼 / 对外担保」专题统计（全市场）。\n" +
                            "· 为什么要手工同步：该接口需动态鉴权，且返回全市场数据（体积大）；" +
                            "同步一次即可长期使用，个股扫雷只读本地、不再联网拉取。\n" +
                            "· 每次同步为**全量覆盖**：以最新数据为准，清掉本地旧记录。\n" +
                            "· 未同步时，扫雷里「涉诉金额/净资产」「对外担保/净资产」两项显示「无记录」。",
                        fontSize = pageSp(12f),
                        color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(15.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 一行「标签 + 值」（本页自用，样式与其它页面的键值行保持一致） */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = pageSp(13f), color = StockNoteColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = pageSp(13.5f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
    }
}
