package com.stocknote.data.repo

import com.stocknote.core.csv.CsvCodec
import com.stocknote.core.format.Format
import com.stocknote.core.model.TradeSide
import com.stocknote.data.db.StockNoteDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * **CSV 域 Repository**（阶段 2：生成侧实现已搬迁，2026-09-29，第 6 批）。
 *
 * ⚠️ 本域与交易域一样是**混合状态**：
 *  - **已搬（4 个，本轮）**：`buildTradesCsv` / `buildCashFlowsCsv` /
 *    `buildCsvTemplate` / `buildTemplatesZip` —— 都是**纯读 + 拼字符串**，零私有依赖；
 *  - **仍留原类（2 个）**：`applyTradesCsv` / `applyCashFlowsCsv` ——
 *    依赖原类的 private `insertTradeForCsv`（CSV 专用写入，含现金联动）与 `invalidateCurveCache`，
 *    要先把那套解开。⚠️ 计划 CSV 的导入已经搬走了（见 [PlanRepository.applyCsv]）。
 *
 * 域内职责：四类 CSV 的**生成**（交易 / 出入金 / 计划模板）+ 模板 zip 包。
 * ⚠️ 表头是**单一来源**：导出与模板共用本类的 `tradeCsvHeader` / `cashFlowCsvHeader`。
 * （计划表头在 [PlanRepository.csvHeader]，因为计划 CSV 整体归计划域。）
 *
 * ⚠️ 口径提醒：交易金额一律走 `Transaction.amountOf` 的语义，不要 `price * quantity` ——
 * `CAPITALIZE`（利润转增资本）的 quantity 是 0，金额在 price 列。
 * 本类里那段 `when (tx.side)` 就是为此手工算的，别"优化"成乘法。
 */
class CsvRepository internal constructor(
    private val db: StockNoteDb,
) {
    /** CSV 表头（导出与模板共用，单一来源）。 */
    private val tradeCsvHeader = listOf(
        "交易ID", "日期", "标的代码", "标的名称", "方向", "数量", "成交价", "手续费",
        // 印花税（REQ-ACC-17，老周 2026-09-30 加）：紧挨手续费；导出 / 模板 / 导入三处同源。
        // ⚠️ 导入侧按**表头名**取值，所以旧文件（15 列、无此列）能照常导入（缺列 → 自动补算）。
        "印花税",
        "币种", "金额合计", "备注/理由", "情绪", "执行评分", "标签", "质量评级",
    )
    private val cashFlowCsvHeader = listOf("日期", "类型", "金额", "币种", "备注")

    /** 生成交易记录 CSV 文本（15 列，SAF 导出用，不落盘）—— 老周 2026-09-17。 */
    suspend fun buildTradesCsv(): String = withContext(Dispatchers.Default) {
        val secs = db.securityQueries.selectAll().executeAsList().associateBy { it.id }
        val rows = mutableListOf(tradeCsvHeader)
        db.tradeQueries.selectAll().executeAsList()
            // ⚠️ 轻微-6（2026-09-28）：排序加 `id` 兜底 —— `nextSeq` 取 MAX(seq)+1，
            // 删掉 seq 最大的那笔后下一笔会**复用该 seq**，同日多笔时顺序不稳定。
            .sortedWith(compareBy({ it.trade_date }, { it.seq }, { it.id }))
            .forEach { tx ->
                val sec = secs[tx.security_id]
                val side = when (tx.side) {
                    TradeSide.BUY.name -> "买入"
                    TradeSide.SELL.name -> "卖出"
                    TradeSide.CAPITALIZE.name -> "转增资本"   // REQ-ACC-16
                    else -> tx.side
                }
                val gross = tx.price * tx.quantity
                val total = when (tx.side) {
                    // 金额合计 = 实际收付：买入多付印花税、卖出少收印花税（REQ-ACC-17）
                    // ⚠️ 这里 tx 是 **SQLDelight 的行对象**（列名 snake_case），不是领域 Transaction
                    TradeSide.BUY.name -> gross + tx.fee + tx.stamp_duty
                    TradeSide.SELL.name -> gross - tx.fee - tx.stamp_duty
                    // 转增资本的金额在 price 列（quantity 恒 0，不能用 price×quantity）
                    TradeSide.CAPITALIZE.name -> tx.price
                    else -> gross
                }
                rows += listOf(
                    tx.id,
                    tx.trade_date,
                    sec?.symbol ?: "",
                    sec?.name ?: "",
                    side,
                    CsvCodec.num(tx.quantity),
                    CsvCodec.num(tx.price),
                    Format.fixedPlain(tx.fee),
                    // 印花税（REQ-ACC-17）：原币金额，紧跟手续费（行对象列名 snake_case）
                    Format.fixedPlain(tx.stamp_duty),
                    sec?.currency ?: "CNY",
                    Format.fixedPlain(total),
                    tx.note ?: "",
                    tx.emotion ?: "",
                    tx.score?.toString() ?: "",
                    tx.tag_ids ?: "",
                    tx.quality ?: "",
                )
            }
        CsvCodec.build(rows)
    }

    /** 生成出入金 CSV 文本（SAF 导出用）—— 老周 2026-09-17。 */
    suspend fun buildCashFlowsCsv(): String = withContext(Dispatchers.Default) {
        val rows = mutableListOf(cashFlowCsvHeader)
        db.cashFlowQueries.selectAll().executeAsList()
            .sortedBy { it.flow_date }
            .forEach { f ->
                rows += listOf(
                    f.flow_date,
                    if (f.type == "DEPOSIT") "存入" else "取出",
                    Format.fixedPlain(abs(f.amount_orig)),
                    f.currency,
                    f.note ?: "",
                )
            }
        CsvCodec.build(rows)
    }

    /**
     * 生成空白模板 CSV 文本（表头 + 一行示例），SAF 导出用 —— 老周 2026-09-17。
     * @param kind `"cashflow"` / `"plan"` / 其它（按交易记录处理）
     */
    suspend fun buildCsvTemplate(kind: String): String = withContext(Dispatchers.Default) {
        val rows = when (kind) {
            "cashflow" -> listOf(cashFlowCsvHeader, listOf("2026-09-16", "存入", "10000", "CNY", "示例：工资转入"))
            // 交易计划模板（老周 2026-09-21；09-22 加「完成日期」共 14 列）
            // ⚠️ 2026-09-29：计划 CSV 归 PlanRepository，此处表头写**字面量** ——
            //    必须与 `PlanRepository.csvHeader` 保持一致（同一份文件格式的两端）。
            "plan" -> listOf(
                listOf(
                    "计划ID", "标的代码", "标的名称", "方向", "目标价", "折扣", "计划价",
                    "数量", "费率(万分之)", "币种", "备注", "标签", "创建日期", "完成日期",
                ),
                listOf(
                    "", "hk00700", "腾讯控股", "买入", "480", "0.9", "432", "100", "5", "HKD",
                    "示例：跌到年线分批买入", "价值投资;波段", "", "",
                ),
            )
            else -> listOf(
                tradeCsvHeader,
                listOf(
                    // ⚠️ 列序必须与 tradeCsvHeader 一致：手续费之后是**印花税**（REQ-ACC-17）
                    "", "2026-09-16", "sh600519", "贵州茅台", "买入", "100", "1680.50", "42.01", "0.00",
                    "CNY", "168092.01", "示例：突破年线买入", "冷静", "4", "价值投资;趋势", "",
                ),
            )
        }
        CsvCodec.build(rows)
    }

    /**
     * 三个模板文件的 **zip 包**（老周 2026-09-21）：交易记录 / 出入金 / 交易计划，一次下载。
     * 走 STORED 模式（不压缩）—— 模板都是几百字节的纯文本，见 [com.stocknote.core.io.SimpleZip]。
     */
    suspend fun buildTemplatesZip(): ByteArray = withContext(Dispatchers.Default) {
        com.stocknote.core.io.SimpleZip.of(
            listOf(
                com.stocknote.core.io.SimpleZip.Entry("stocknote-template-trades.csv", buildCsvTemplate("trade")),
                com.stocknote.core.io.SimpleZip.Entry("stocknote-template-cashflows.csv", buildCsvTemplate("cashflow")),
                com.stocknote.core.io.SimpleZip.Entry("stocknote-template-plans.csv", buildCsvTemplate("plan")),
            ),
        )
    }
}
