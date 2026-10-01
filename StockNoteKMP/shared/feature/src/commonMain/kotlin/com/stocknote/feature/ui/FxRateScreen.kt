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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.core.format.Format
import com.stocknote.feature.theme.StockNoteColors

/**
 * 汇率管理页（老周 2026-09-22）。
 *
 * 设置页瘦身：汇率管理独立成页，入口在设置 → 功能 → 汇率管理。
 *
 * 这里的汇率是**折算口径的数据真值**（fx_rate 表，每币种每生效日一条，口径为「原币 → CNY」）：
 * 持仓市值 / 盈亏 / 汇兑损益的折算都读它；表里查不到某币种时才会回落到内置兜底常量。
 *
 * 注：**交易的成交日汇率**不在这里维护 —— 它随交易录入自动带出并存在 `trade.fx_rate` 上
 * （见技术说明书 §20）。
 */
@Composable
fun FxRateScreen(
    holder: SettingsHolder,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(title = "汇率管理", onBack = onBack) }

        item {
            NoteBar("外币持仓与盈亏的折算都读这里的汇率；同一币种可有多条，按生效日取「不晚于当天」的最新一条。")
        }

        // ---- 录入 / 修正 ----
        item {
            SectionCard(title = "录入 / 修正汇率") {
                Text("币种", fontSize = 12.sp, color = StockNoteColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = listOf("HKD", "USD"),
                    selectedIndex = if (state.currency == "USD") 1 else 0,
                    onSelect = { holder.setCurrency(if (it == 0) "HKD" else "USD") },
                )
                Spacer(Modifier.height(10.dp))
                DateField(
                    label = "生效日期",
                    value = state.effectiveDate,
                    onValueChange = holder::setEffectiveDate,
                    maxIso = com.stocknote.data.platform.todayIso(),
                )
                Spacer(Modifier.height(10.dp))
                LabeledField(
                    label = "汇率（1 外币 = ? CNY）",
                    value = state.rateText,
                    onValueChange = holder::setRateText,
                    placeholder = "如 0.8536",
                    numeric = true,
                    errorText = state.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "同币种同日已有记录时会直接覆盖；保存一律按 4 位小数（0.8536）存储和计算。",
                    fontSize = 11.sp, color = StockNoteColors.TextTertiary,
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton("保存汇率", onClick = { holder.saveRate { } })
            }
        }

        // ---- 已有记录 ----
        item {
            Sec(title = "已录入汇率（${state.rates.size}）") {
                CardBox {
                    if (state.rates.isEmpty()) {
                        EmptyHint("暂无汇率记录。")
                    } else {
                        state.rates.forEachIndexed { i, r ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        // 4 位小数展示（老周 2026-09-21）：显示几位小数，库里就存几位
                                        "1 ${r.currency} = ${Format.money(r.rate, "¥", decimals = 4)}",
                                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary,
                                    )
                                    Text(
                                        "生效日 ${r.effectiveDate} · 录入于 ${r.updatedAt}",
                                        fontSize = 11.sp, color = StockNoteColors.TextTertiary,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                                Text(
                                    "🗑", fontSize = 14.sp,
                                    modifier = Modifier
                                        .clickable { holder.deleteRate(r.currency, r.effectiveDate) }
                                        .padding(6.dp)
                                        .semantics { contentDescription = "删除汇率" },
                                )
                            }
                            if (i < state.rates.lastIndex) {
                                Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                            }
                        }
                    }
                }
            }
        }

        item {
            ProtoFoot("应用到新录入的交易与最新折算；已有交易若记录了当时的成交日汇率，仍按各自汇率折算")
        }
    }

    SaveErrorDialog(
        failTick = state.saveFailTick,
        messages = listOfNotNull(state.error),
        title = "无法保存",
    )
}
