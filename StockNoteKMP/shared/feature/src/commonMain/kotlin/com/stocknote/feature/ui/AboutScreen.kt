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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.feature.nav.AppNav
import com.stocknote.feature.theme.StockNoteColors

/**
 * 关于本应用（REQ-TOOL-04，P0）。
 * 版本号取自打包产物常量，避免两处不一致（单一来源原则）。
 *
 * @param versionLabel 版本号，**由各平台外壳传入**（单一来源）：
 *   Android = `BuildConfig.VERSION_NAME/VERSION_CODE`；iOS = `Info.plist` 的
 *   `CFBundleShortVersionString/CFBundleVersion`；桌面 = `:desktopApp` 的常量。
 * @param platformLabel 该平台的最低系统要求（老周 2026-10-02 报「iOS 上看不到版本号」时一并修掉）：
 *   此前这里**写死了 "Android 7.0+"**，于是 iOS 一栏显示的是「— · Android 7.0+」——
 *   既没有版本号（iOS 外壳没传），平台也写错了。
 */
@Composable
private fun AboutScreenContent(versionLabel: String = "", platformLabel: String = "Android 7.0+") {
    // 版本号来自打包产物（Android 从 BuildConfig / iOS 从 Info.plist 传入），单一来源
    val versionText = if (versionLabel.isEmpty()) "— · $platformLabel" else "$versionLabel · $platformLabel"
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White)
                        .clickable { AppNav.pop() },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", fontSize = pageSp(20f), color = StockNoteColors.Brand) }
                Spacer(Modifier.size(10.dp))
                Text("关于本应用", fontSize = pageSp(18f), fontWeight = FontWeight.SemiBold, color = StockNoteColors.TextPrimary)
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // App 图标：品牌蓝对角渐变圆角底 + 白色上升折线 + 箭头（与 make_icon.py / mipmap 同源）
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    // 对角渐变底
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF6B98F7), Color(0xFF5B8BF5),
                                Color(0xFF2E6BE6), Color(0xFF1A4AB2),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                    // 白色上升折线（归一化坐标 → Canvas 像素，与 make_icon.py 的 4 个关键点一致）
                    val pts = listOf(
                        (0.191f) to (0.689f),   // 196/1024, 706/1024
                        (0.363f) to (0.578f),   // 372/1024, 592/1024
                        (0.500f) to (0.637f),   // 512/1024, 652/1024
                        (0.660f) to (0.426f),   // 676/1024, 436/1024
                    )
                    val lw = 0.078f * size.width  // 80/1024
                    val path = androidx.compose.ui.graphics.Path()
                    pts.forEachIndexed { i, (nx, ny) ->
                        val x = nx * size.width
                        val y = ny * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Color.White, style = Stroke(width = lw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    // 端帽圆
                    pts.forEach { (nx, ny) ->
                        drawCircle(Color.White, radius = lw / 2, center = Offset(nx * size.width, ny * size.height))
                    }
                    // 箭头三角形（沿最后一段方向）
                    val (ax0, ay0) = pts[pts.size - 2]
                    val (ax1, ay1) = pts[pts.size - 1]
                    val dx = ax1 - ax0; val dy = ay1 - ay0
                    val len = kotlin.math.sqrt(dx * dx + dy * dy)
                    val ux = dx / len; val uy = dy / len
                    val tipX = (ax1 + ux * 0.129f) * size.width
                    val tipY = (ay1 + uy * 0.129f) * size.height
                    val back = 0.105f * size.width
                    val wing = 0.064f * size.width
                    val px = -uy; val py = ux
                    val w1x = (tipX / size.width - ux * back / size.width + px * wing / size.width) * size.width
                    val w1y = (tipY / size.height - uy * back / size.height + py * wing / size.height) * size.height
                    val w2x = (tipX / size.width - ux * back / size.width - px * wing / size.width) * size.width
                    val w2y = (tipY / size.height - uy * back / size.height - py * wing / size.height) * size.height
                    val arrow = androidx.compose.ui.graphics.Path()
                    arrow.moveTo(tipX, tipY)
                    arrow.lineTo(w1x, w1y)
                    arrow.lineTo(w2x, w2y)
                    arrow.close()
                    drawPath(arrow, Color.White)
                }
                Spacer(Modifier.height(12.dp))
                Text("股票交易笔记", fontSize = pageSp(18f), fontWeight = FontWeight.ExtraBold, color = StockNoteColors.TextPrimary)
                Text("STOCKNOTE", fontSize = pageSp(11f), color = StockNoteColors.TextTertiary)
                Text(
                    versionText,
                    fontSize = pageSp(12f),
                    color = StockNoteColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            SectionCard(title = "一句话") {
                Text(
                    "把每一笔买卖记下来，让盈亏有数、决策有据。",
                    fontSize = pageSp(13f),
                    color = StockNoteColors.TextPrimary,
                    lineHeight = pageSp(20f),
                )
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "三步上手") {
                Text(
                    "① 记一笔 — 持仓页右上角，填标的、价格与理由\n" +
                        "② 看盈亏 — 统计页看总资产与当日盈亏；点持仓进标的页看逐笔\n" +
                        "③ 常复盘 — 分析页看收益与回撤；重要的一笔记下当时心态",
                    fontSize = pageSp(13f),
                    color = StockNoteColors.TextPrimary,
                    lineHeight = pageSp(22f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "数据都在本机，记得定期导出加密备份（设置 → 功能 → 数据管理）。",
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextTertiary,
                    lineHeight = pageSp(18f),
                )
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "能帮你做什么") {
                Text(
                    "· 记账：A股 / 港股 / 美股，多币种按汇率自动折算\n" +
                        "· 复盘：逐笔记下理由、情绪、执行评分与策略标签\n" +
                        "· 分析：资产曲线、年化收益、最大回撤、盈亏日历\n" +
                        "· 计划：给标的定目标价与折扣，到价照着执行",
                    fontSize = pageSp(12f),
                    color = StockNoteColors.TextSecondary,
                    lineHeight = pageSp(20f),
                )
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "边界与声明") {
                Text(
                    "· 只做记录，不提供投资建议\n" +
                        "· 行情来自公开接口，仅供展示，可能有延迟\n" +
                        "· 数据本地加密，无账号 / 无云端 / 无埋点\n" +
                        "· 备份密码无法找回，请自行妥善保存",
                    fontSize = pageSp(12f),
                    color = StockNoteColors.TextSecondary,
                    lineHeight = pageSp(20f),
                )
            }
        }

    }
}

/** 整页字号放大 1.2 倍（老周 2026-09-21，与统计页一致；未包的页面默认 1f 不受影响）。 */
@Composable
fun AboutScreen(versionLabel: String = "", platformLabel: String = "Android 7.0+") {
    androidx.compose.runtime.CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        AboutScreenContent(versionLabel, platformLabel)
    }
}
