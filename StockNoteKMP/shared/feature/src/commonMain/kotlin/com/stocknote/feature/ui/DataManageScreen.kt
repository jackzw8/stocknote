package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocknote.feature.theme.StockNoteColors

/**
 * 数据管理页（老周 2026-09-22）。
 *
 * 设置页瘦身：把原先堆在设置页里的「CSV 导入 / 导出」与「备份与恢复」合并到这一页，
 * 入口移到设置 → 功能 → 数据管理（与标签管理同一形式）。
 *
 * 两件事都属「与外部交换数据」，放一起更好找：
 * - **备份 / 恢复**：整库加密快照（.snbk），恢复是**全量覆盖**；
 * - **CSV**：与 Excel 等外部表格交换的单表数据，逐行校验、重复跳过。
 */
@Composable
fun DataManageScreen(
    holder: SettingsHolder,
    onBack: () -> Unit,
    onFilePicked: () -> Unit = {},
    /** App 版本号：写进导出的运行日志头部（由外壳 `App(versionLabel = …)` 传入） */
    versionLabel: String = "",
) {
    val state by holder.state.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(StockNoteColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(title = "数据管理", onBack = onBack) }

        // ---- 备份与恢复（REQ-SEC-03）----
        item {
            Sec(title = "备份与恢复") {
                CardBox {
                    Text(
                        "导出加密备份文件到自选位置（系统文件选择器），请自行妥善保存；" +
                            "恢复会全量覆盖当前账本。",
                        fontSize = 11.sp, color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                    )
                    SettingEntryRow(
                        glyph = "⬆️",
                        tint = Color(0xFFEAF7F1),
                        title = "加密备份导出",
                        sub = "需设置密码，生成 .snbk 文件",
                    ) { holder.toggleBackup(mode = BackupMode.EXPORT) }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    SettingEntryRow(
                        glyph = "⬇️",
                        tint = Color(0xFFEAF1FE),
                        title = "备份恢复导入",
                        sub = "恢复时用系统文件选择器选择 .snbk 文件",
                    ) { holder.toggleBackup(mode = BackupMode.IMPORT) }
                    state.backupMessage?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = StockNoteColors.Brand,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        // ---- CSV 导入 / 导出（老周 2026-09-16）----
        item {
            Sec(title = "CSV 导入 / 导出", more = "外部数据交换") {
                CardBox {
                    Text(
                        "与 Excel 等外部表格交换数据：导出交易记录 / 出入金 / **交易计划**为 CSV；" +
                            "导入逐行校验、重复自动跳过。币种以文件里的「币种」列为准；" +
                            "外币交易的折算汇率按交易日期自动补齐。",
                        fontSize = 11.sp, color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                    )
                    SettingEntryRow("⬆️", Color(0xFFEAF7F1), "导出交易记录 CSV", "15 列，含标的/方向/数量/价格/费用/标签") {
                        holder.exportCsv("trades") { onFilePicked() }
                    }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    SettingEntryRow("⬆️", Color(0xFFEAF7F1), "导出出入金 CSV", "日期 / 类型 / 金额 / 币种 / 备注") {
                        holder.exportCsv("cashflows") { onFilePicked() }
                    }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    // 交易计划导出（老周 2026-09-21）：计划不写账本，单独一份 CSV 方便外部编辑
                    SettingEntryRow("⬆️", Color(0xFFEAF7F1), "导出交易计划 CSV", "13 列，含标的/方向/目标价/折扣/计划价/数量") {
                        holder.exportCsv("plans") { onFilePicked() }
                    }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    // 模板改成一次性 zip（老周 2026-09-21）：三个模板一起拿，不用点三次
                    SettingEntryRow("📦", Color(0xFFF1EFE8), "下载空白模板（zip）", "交易记录 / 出入金 / 交易计划 三个模板一次下载") {
                        holder.downloadTemplates { onFilePicked() }
                    }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    SettingEntryRow("⬇️", Color(0xFFEAF1FE), "导入交易记录 CSV", "系统文件选择器选文件 · 去重：同日期+标的+方向+数量+价格") {
                        holder.importCsv("trades") { onFilePicked() }
                    }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    SettingEntryRow("⬇️", Color(0xFFEAF1FE), "导入出入金 CSV", "系统文件选择器选文件 · 去重：同日期+类型+金额+币种") {
                        holder.importCsv("cashflows") { onFilePicked() }
                    }
                    Box(Modifier.padding(horizontal = 15.dp).height(1.dp).background(StockNoteColors.Divider))
                    SettingEntryRow("⬇️", Color(0xFFEAF1FE), "导入交易计划 CSV", "系统文件选择器选文件 · 去重：同标的+方向+计划价+数量") {
                        holder.importCsv("plans") { onFilePicked() }
                    }
                    state.csvMessage?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = StockNoteColors.Brand,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        // ---- 运行日志（老周 2026-10-01）----
        item {
            Sec(title = "运行日志", more = "排查问题") {
                CardBox {
                    Text(
                        "App 出现异常（行情拉不到、数字算不对、备份恢复失败等）时，" +
                            "把这份日志导出后发给开发者，即可定位到具体是哪一步、哪只标的出的问题。",
                        fontSize = 11.sp, color = StockNoteColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                    )
                    SettingEntryRow(
                        glyph = "📋",
                        tint = Color(0xFFF1EFE8),
                        title = "导出运行日志",
                        sub = "含取数失败原因 / 异常堆栈 / 设备与版本信息（不含密码与账本内容）",
                    ) { holder.exportLog(versionLabel) { onFilePicked() } }
                    state.logMessage?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = StockNoteColors.Brand,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        item {
            ProtoFoot("备份文件内含全部账本数据，请妥善保存密码；CSV 只含单个表，适合核对与外部编辑")
        }
    }

    // ---- 备份密码弹窗（导出 / 导入共用）----
    if (state.backupMode != BackupMode.NONE) {
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { holder.closeBackup() },
            title = {
                Text(
                    if (state.backupMode == BackupMode.EXPORT) "设置备份密码" else "输入备份密码",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    if (state.backupMode == BackupMode.IMPORT) {
                        Text(
                            "输入密码后点「恢复」，系统会让你选择 .snbk 备份文件。",
                            fontSize = 12.sp, color = StockNoteColors.TextTertiary,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    LabeledField(
                        label = "密码",
                        value = pwd,
                        onValueChange = { pwd = it },
                        placeholder = "至少 6 位",
                    )
                    state.backupMessage?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = StockNoteColors.Up,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { holder.runBackup(pwd) { onFilePicked() } },
                    enabled = pwd.length >= 6,
                ) { Text(if (state.backupMode == BackupMode.EXPORT) "导出" else "恢复") }
            },
            dismissButton = {
                TextButton(onClick = { holder.closeBackup() }) { Text("取消") }
            },
        )
    }
}
