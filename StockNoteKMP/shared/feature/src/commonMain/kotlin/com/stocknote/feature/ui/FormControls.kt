package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.feature.theme.StockNoteColors

/**
 * 表单控件。M1 的表单元素就这几样，不引三方输入库；
 * OutlinedTextField 走 Material3 默认，只把配色对齐到设计系统。
 */

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    readOnly: Boolean = false,
    errorText: String? = null,
    numeric: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            // 自动化测试锚点（老周 2026-09-18）：UiAutomator 看不到 Compose 的 testTag，
            // 只能靠无障碍描述定位。用 label 本身当描述 —— 既是自然语言（TalkBack 可读），
            // 又天然唯一（"数量（股）" / "成交价（CNY）" / "手续费率（万分之，必填）"）。
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = label },
            readOnly = readOnly,
            singleLine = true,
            placeholder = {
                if (placeholder.isNotEmpty()) {
                    Text(placeholder, fontSize = pageSp(14f), color = StockNoteColors.TextTertiary)
                }
            },
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            ),
            textStyle = TextStyle(
                fontSize = pageSp(15f),
                fontWeight = if (readOnly) FontWeight.Medium else FontWeight.Normal,
                color = if (readOnly) StockNoteColors.TextSecondary else StockNoteColors.TextPrimary,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = StockNoteColors.Brand,
                unfocusedBorderColor = StockNoteColors.Divider,
                disabledBorderColor = StockNoteColors.Divider,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                disabledContainerColor = StockNoteColors.Surface,
            ),
            shape = RoundedCornerShape(10.dp),
        )
        if (errorText != null) {
            Text(
                text = errorText,
                fontSize = pageSp(11f),
                color = StockNoteColors.Up,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 单选芯片行。用「选中项加底色 + 边框加粗」表达选中态，
 * 不只靠颜色 —— 色盲用户也能分辨。
 */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    labelOf: (T) -> String = { it.toString() },
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (selected) StockNoteColors.Brand else Color.White,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .border(
                        width = if (selected) 1.5.dp else 0.5.dp,
                        color = if (selected) StockNoteColors.Brand else StockNoteColors.Divider,
                        shape = RoundedCornerShape(10.dp),
                    )
                    // 修复：v2.1.1 组件化时丢失了点击处理，导致所有单选 chips（方向/情绪/存取/周期等）不可选
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelOf(option),
                    fontSize = pageSp(13f),
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) Color.White else StockNoteColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
fun InfoCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tone: Color = Color(0xFFDDE7FB),
    textColor: Color = StockNoteColors.BrandDark,
) {    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = tone),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = pageSp(13f), fontWeight = FontWeight.Medium, color = textColor)
            Spacer(Modifier.padding(top = 6.dp))
            Text(message, fontSize = pageSp(12f), color = textColor, lineHeight = pageSp(18f))
        }
    }
}

@Composable
fun FieldError(message: String) {
    Text(
        text = message,
        fontSize = pageSp(12f),
        color = StockNoteColors.Up,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/**
 * 多行文本输入（备注/交易理由用）。maxLength > 0 时显示字数计数并拦截超长输入。
 */
@Composable
fun MultiLineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    maxLength: Int = 0,
    errorText: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            if (maxLength > 0) {
                Text(
                    "${value.length}/$maxLength",
                    fontSize = pageSp(11f),
                    color = if (value.length >= maxLength) StockNoteColors.Up else StockNoteColors.TextTertiary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { if (maxLength == 0 || it.length <= maxLength) onValueChange(it) },
            // 与 LabeledField 同款锚点：用 label 作无障碍描述（"交易理由 / 备注（必填）"）
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = label },
            minLines = 3,
            maxLines = 6,
            placeholder = {
                if (placeholder.isNotEmpty()) {
                    Text(placeholder, fontSize = pageSp(13f), color = StockNoteColors.TextTertiary)
                }
            },
            isError = errorText != null,
            textStyle = TextStyle(fontSize = pageSp(13.5f), color = StockNoteColors.TextPrimary, lineHeight = pageSp(20f)),
        )
        errorText?.let { FieldError(it) }
    }
}

/**
 * 日期选择字段：只读展示 + 点击弹 Material3 日期选择器。
 * maxIso 非空时禁选晚于该日的日期（「不能大于当天」= 传 todayIso()）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxIso: String? = null,
    errorText: String? = null,
) {
    var showPicker = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .border(0.5.dp, StockNoteColors.Divider, RoundedCornerShape(10.dp))
                .clickable { showPicker.value = true }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value.ifEmpty { "点选日期" },
                fontSize = pageSp(15f),
                color = if (value.isEmpty()) StockNoteColors.TextTertiary else StockNoteColors.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text("📅", fontSize = pageSp(15f))
        }
        errorText?.let { FieldError(it) }
    }

    if (showPicker.value) {
        val maxMs: Long? = maxIso?.let { com.stocknote.core.calc.CivilDate.isoToUtcMillis(it) }
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = com.stocknote.core.calc.CivilDate.isoToUtcMillis(value),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    maxMs == null || utcTimeMillis <= maxMs
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showPicker.value = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            onValueChange(com.stocknote.core.calc.CivilDate.utcMillisToIso(ms))
                        }
                        showPicker.value = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker.value = false }) { Text("取消") }
            },
        ) {
            androidx.compose.material3.DatePicker(state = pickerState)
        }
    }
}

/**
 * 小动作按钮（老周 2026-09-21）：用在输入框旁边的「获取」「取自选」这类**次要动作**上。
 *
 * 与 [PrimaryButton] 的分工：主按钮一个页面只有一个（保存），这些是贴着字段的小操作，
 * 所以做成浅蓝底 + 蓝字的小胶囊，禁用时灰掉但仍占位（避免点击时布局跳动）。
 */
@Composable
fun MiniActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) Color(0xFFEAF1FE) else Color(0xFFF1F3F7))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = pageSp(12f),
            fontWeight = FontWeight.Bold,
            color = if (enabled) StockNoteColors.Brand else StockNoteColors.TextTertiary,
            maxLines = 1,
        )
    }
}
