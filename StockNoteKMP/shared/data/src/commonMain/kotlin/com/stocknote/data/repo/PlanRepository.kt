package com.stocknote.data.repo

import com.stocknote.core.calc.CivilDate
import com.stocknote.core.csv.CsvCodec
import com.stocknote.core.model.Currency
import com.stocknote.core.model.TradePlan
import com.stocknote.core.model.TradeSide
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.net.MarketGuess
import com.stocknote.data.platform.todayIso
import com.stocknote.data.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **交易计划域 Repository**（阶段 2：实现已搬迁，2026-09-28）。
 *
 * 与其它三个域（Trade/Quote/Fx）不同，**本类不再只是转发** —— 计划的增删改查实现
 * 已从 `PortfolioRepository` 搬到这里。搬动之所以安全：调用方（PlanHolder /
 * PlanEditHolder）在先前的"阶段 1"里已经改为依赖本类，所以搬动对它们**完全无感**；
 * 且搬迁前后都跑了 core/data 单测 + 真机启动验证。
 *
 * 域内职责：计划的增删改查、完成标记。
 * ⚠️ 计划 CSV 的导入导出仍委托给 `PortfolioRepository`（**下一步**搬）——
 * 它牵涉表头定义、模板 zip 与多表查询，单独一步处理更稳。
 *
 * ⚠️ 口径提醒：计划金额合计**按币种分组**（不能跨币种相加）；计划 CSV 导入
 * **只新建不覆盖**，去重键 = 标的 + 方向 + 计划价 + 数量。
 */
class PlanRepository internal constructor(
    private val db: StockNoteDb,
    /** ⚠️ 第 11 批：CSV 导入建档用（标的域已迁出原类）。 */
    private val legacy: PortfolioRepository,
    /** **标的域**（第 11 批）：计划 CSV 导入时的 findOrCreateSecurity。 */
    private val security: SecurityRepository,
) {
    /** 全部计划（列表按创建时间倒序）。 */
    suspend fun list(): List<TradePlan> = withContext(Dispatchers.Default) {
        db.tradePlanQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    /** 单条计划（编辑回填用）。 */
    suspend fun byId(id: String): TradePlan? = withContext(Dispatchers.Default) {
        db.tradePlanQueries.selectById(id).executeAsOneOrNull()?.toDomain()
    }

    /** 某标的的全部计划。 */
    suspend fun bySecurity(securityId: String): List<TradePlan> = withContext(Dispatchers.Default) {
        db.tradePlanQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
    }

    /**
     * 新建 / 更新计划。INSERT OR REPLACE 语义 —— 编辑时调用方必须把原 `createdAt`
     * 原样带回，否则创建时间被覆盖、列表顺序会跳。
     */
    suspend fun upsert(plan: TradePlan) = withContext(Dispatchers.Default) {
        db.tradePlanQueries.insertItem(
            id = plan.id,
            security_id = plan.securityId,
            side = plan.side.name,
            target_price = plan.targetPrice,
            discount = plan.discount,
            planned_price = plan.plannedPrice,
            quantity = plan.quantity,
            fee_rate = plan.feeRate,
            note = plan.note,
            tag_ids = plan.tags.joinToString(","),
            created_at = plan.createdAt,
            updated_at = plan.updatedAt,
            done_at = plan.doneAt,
        )
    }

    /** 删除计划：只删这一行，不影响账本（计划本就不在账本里）。 */
    suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        db.tradePlanQueries.deleteById(id)
    }

    /**
     * 行上快捷标记 / 取消完成：完成写当天（yyyy-MM-dd），取消清回 NULL。
     * ⚠️ 只动 `done_at`，**不碰 updated_at** —— 这不是「编辑」，不该让列表看起来被动过。
     */
    suspend fun setDone(id: String, done: Boolean) = withContext(Dispatchers.Default) {
        // ⚠️ 生成签名按 SQL 语句顺序：SET 的参数在前、WHERE 的 id 在后
        db.tradePlanQueries.setDoneAt(
            done_at = if (done) todayIso() else null,
            id = id,
        )
    }

    // ================================================================
    // 计划 CSV（第 6 批搬迁，2026-09-29）
    // 口径（老周 2026-09-21，共 14 列，**可缺列**）：
    //  - `计划ID` 仅导出时给出便于对照；导入**一律新建**（不按 id 覆盖，避免别人的 id 顶掉本地计划）
    //  - `标的代码 / 标的名称 / 币种`：标的不存在时按这三列建档（市场按代码前缀推断）
    //  - `折扣 / 计划价` 都写出来：导入**不重算**，以文件里的计划价为准
    //  - `完成日期`：空 = 进行中；yyyy-MM-dd = 完成那天
    // ================================================================

    private val csvHeader = listOf(
        "计划ID", "标的代码", "标的名称", "方向", "目标价", "折扣", "计划价",
        "数量", "费率(万分之)", "币种", "备注", "标签", "创建日期", "完成日期",
    )

    /** 生成交易计划 CSV 文本（SAF 导出用，不落盘）。 */
    suspend fun buildCsv(): String = withContext(Dispatchers.Default) {
        val secs = db.securityQueries.selectAll().executeAsList().associateBy { it.id }
        val rows = mutableListOf(csvHeader)
        db.tradePlanQueries.selectAll().executeAsList()
            .sortedBy { it.created_at }   // 创建时间正序，与人读列表的顺序一致
            .forEach { p ->
                val sec = secs[p.security_id]
                rows += listOf(
                    p.id,
                    sec?.symbol ?: "",
                    sec?.name ?: "",
                    if (p.side == TradeSide.SELL.name) "卖出" else "买入",
                    CsvCodec.num(p.target_price),
                    p.discount?.let { CsvCodec.num(it) } ?: "",
                    CsvCodec.num(p.planned_price),
                    CsvCodec.num(p.quantity),
                    CsvCodec.num(p.fee_rate),
                    sec?.currency ?: "CNY",
                    p.note,
                    p.tag_ids,
                    p.created_at,
                    p.done_at ?: "",
                )
            }
        CsvCodec.build(rows)
    }

    /**
     * 导入交易计划 CSV。逐行校验，坏行跳过并给出「第 N 行：原因」；
     * 去重策略 = **跳过重复**（同 标的 + 方向 + 计划价 + 数量 视为同一份计划）。
     */
    suspend fun applyCsv(text: String): PortfolioRepository.CsvImportResult = withContext(Dispatchers.Default) {
        val records = CsvCodec.toRecords(CsvCodec.parse(text))
        if (records.isEmpty()) error("文件没有数据行（只有表头或为空）")

        val skipped = mutableListOf<String>()
        val symbolToId = db.securityQueries.selectAll().executeAsList()
            .associate { it.symbol to it.id }
            .toMutableMap()
        val existing = db.tradePlanQueries.selectAll().executeAsList()
            .map { listOf(it.security_id, it.side, CsvCodec.num(it.planned_price), CsvCodec.num(it.quantity)) }
            .toMutableList()
        val today = todayIso()
        var ok = 0

        records.forEachIndexed { idx, r ->
            val lineNo = idx + 2 // 表头占第 1 行
            val symbol = r["标的代码"].orEmpty().trim()
            val name = r["标的名称"].orEmpty().trim()
            val sideText = r["方向"].orEmpty().trim()
            val target = r["目标价"].orEmpty().toDoubleOrNull()
            val planned = r["计划价"].orEmpty().toDoubleOrNull()
            val qty = r["数量"].orEmpty().toDoubleOrNull()
            val discount = r["折扣"].orEmpty().toDoubleOrNull()
            val feeRate = r["费率(万分之)"].orEmpty().toDoubleOrNull() ?: 0.0
            val currencyText = r["币种"].orEmpty().trim().uppercase()
            val createdAtText = r["创建日期"].orEmpty().trim()
            // 完成日期（老周 2026-09-22）：可缺列 / 可为空 = 进行中；给了就必须是 yyyy-MM-dd
            val doneAtText = r["完成日期"].orEmpty().trim()

            val side = when {
                sideText.contains("买") || sideText.equals("BUY", true) -> TradeSide.BUY
                sideText.contains("卖") || sideText.equals("SELL", true) -> TradeSide.SELL
                else -> null
            }
            val reason = when {
                symbol.isEmpty() -> "标的代码为空"
                name.isEmpty() -> "标的名称为空"
                side == null -> "方向应为 买入/卖出"
                target == null || target <= 0 -> "目标价非法"
                planned == null || planned <= 0 -> "计划价非法"
                qty == null || qty <= 0 -> "数量非法"
                feeRate < 0 -> "费率不能为负"
                createdAtText.isNotEmpty() &&
                    runCatching { CivilDate.parseIso(createdAtText) }.isFailure ->
                    "创建日期格式应为 yyyy-MM-dd"
                doneAtText.isNotEmpty() &&
                    runCatching { CivilDate.parseIso(doneAtText) }.isFailure ->
                    "完成日期格式应为 yyyy-MM-dd"
                else -> null
            }
            if (reason != null) {
                skipped += "第 $lineNo 行：$reason"
                return@forEachIndexed
            }

            // 标的：已存在则复用（币种以库中记录为准）；否则按代码前缀推断市场 +
            // 文件里的币种（没给就按市场缺省）建档。
            // ⚠️ 建档走标的域（第 11 批已迁出原类）。
            val market = MarketGuess.of(symbol)
            val fileCurrency = Currency.entries.firstOrNull { it.code == currencyText } ?: market.defaultCurrency
            val secId = symbolToId[symbol]
                ?: runCatching {
                    security.findOrCreateSecurity(symbol = symbol, name = name, market = market, currency = fileCurrency)
                }.getOrNull()?.also { symbolToId[symbol] = it.id }?.id
            if (secId == null) {
                skipped += "第 $lineNo 行：标的创建失败（$symbol）"
                return@forEachIndexed
            }

            val sideName = side!!.name
            val key = listOf(secId, sideName, CsvCodec.num(planned!!), CsvCodec.num(qty!!))
            if (existing.any { it == key }) {
                skipped += "第 $lineNo 行：重复计划（已跳过）"
                return@forEachIndexed
            }
            existing += key

            runCatching {
                // ⚠️ 这里直接用**本类的** upsert（原先是原类的 private 同名方法）
                upsert(
                    TradePlan(
                        id = Ids.next("plan"),
                        securityId = secId,
                        side = side,
                        targetPrice = target!!,
                        discount = discount,
                        plannedPrice = planned,
                        quantity = qty,
                        feeRate = feeRate,
                        note = r["备注"].orEmpty().ifEmpty { "CSV 导入" },
                        tags = r["标签"].orEmpty().split(';', '；', ',').map { it.trim() }
                            .filter { it.isNotEmpty() },
                        createdAt = createdAtText.ifEmpty { today },
                        updatedAt = today,
                        doneAt = doneAtText.ifEmpty { null },
                    ),
                )
                ok++
            }.onFailure { e -> skipped += "第 $lineNo 行：写入失败（${e.message ?: "未知"}）" }
        }
        PortfolioRepository.CsvImportResult(ok, skipped)
    }
}
