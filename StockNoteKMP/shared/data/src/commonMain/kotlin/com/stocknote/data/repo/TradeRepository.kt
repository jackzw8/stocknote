package com.stocknote.data.repo

import com.stocknote.core.calc.CashImpact
import com.stocknote.core.calc.CivilDate
import com.stocknote.core.calc.PositionCalculator
import com.stocknote.core.calc.TradeForms
import com.stocknote.core.csv.CsvCodec
import com.stocknote.core.format.Format
import com.stocknote.core.model.Currency
import com.stocknote.core.model.DeletePreview
import com.stocknote.core.model.FxTable
import com.stocknote.core.model.TradeQuality
import com.stocknote.core.model.TradeSide
import com.stocknote.core.model.Transaction
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.net.MarketGuess
import com.stocknote.data.platform.todayIso
import com.stocknote.data.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **交易域 Repository**（阶段 2：实现已整体搬迁，2026-09-29，第 8 批）。
 *
 * 含**交易主线**：增 / 改 / 删 / 删除预览 / CSV 专用写入 / 两类 CSV 导入。
 *
 * ## ⚠️ 为什么这一批是"整块搬"而不是一个个搬
 *
 * 交易主线依赖 4 个 private 辅助（[truncateSell] / [cashDeltaInCny] / [isOffBook] / [moneyCny]），
 * 而它们**被多个方法共用**；辅助一搬走，原类里剩下的方法立刻编不过，
 * 而原类**不能持有本类**（本类依赖它，会循环）。所以只能一起搬。
 *
 * ## ⚠️ 与 `PortfolioRepository` 的三处**两份实现**（过渡期代价，两处逻辑目前一致）
 *
 *  - [positionQuantityOf]：原类内部有 2 处调用（`addCapitalize` 等）
 *  - [cashDeltaInCny] / [rateToBaseOf]：原类 **`openingCash`（XIRR 期初锚）**在用
 *
 *  ⚠️ 这三处搬走后原类仍保留自己的实现。**改其中一处时务必同步改另一处**。
 *
 * ## ⚠️ 调用方约定
 *
 *  - 缓存失效：写操作后**由本类**调 `repo.invalidateCurveCache()`；
 *  - `db.transaction {}` 是**同步** lambda，内部只能调同步函数
 *    （[CashRepository.applyDelta] 之所以是同步的，就是这个原因）。
 *
 * ## 口径提醒（改前必读）
 *
 *  - 金额一律走 `Transaction.amountOf` 的语义，**不要 `price * quantity`**（`CAPITALIZE` 的 quantity 恒 0，金额在 price 列）；
 *  - 卖出按**该笔交易日**的持仓截断（[truncateSell]），入库与现金联动都必须用**截断后**的数量与折算后的手续费；
 *  - 账外备忘标的（`exclude_from_stats`）**不动现金、不校验现金**；
 *  - CSV 导入走 [insertTradeForCsv]：**不校验现金充足**、**不自动加自选**（与 [addTransaction] 的关键差异）。
 */
class TradeRepository internal constructor(
    private val db: StockNoteDb,
    private val repo: PortfolioRepository,
    /**
     * ⚠️ **现金域**（第 7 批）：买卖对账户现金的增减走 `cash.applyDelta`。
     * ⚠️ `applyDelta` **是同步函数** —— 有的调用点在 `db.transaction {}` 内部
     *（事务 lambda 同步，调不了 suspend）。见 [CashRepository.applyDelta] 的 KDoc。
     */
    private val cash: CashRepository,
    /** **标的域**（第 11 批）：CSV 导入建档时的 findOrCreateSecurity。 */
    private val security: SecurityRepository,
) {

    // ================================================================
    // 查询
    // ================================================================

    /** 某标的的全部交易。 */
    suspend fun transactionsOf(securityId: String): List<Transaction> = withContext(Dispatchers.Default) {
        db.tradeQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
    }

    /** 交易总笔数。 */
    suspend fun tradeCount(): Long = withContext(Dispatchers.Default) {
        db.tradeQueries.countAll().executeAsOne()
    }

    /** 全部流水（升序）。绩效统计（FIFO 配平仓）的数据源。 */
    suspend fun allTransactions(): List<Transaction> = withContext(Dispatchers.Default) {
        db.tradeQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    /**
     * 某标的**持仓数量**（成交表单「最多可卖」用）。
     *
     * ⚠️ 这是持仓派生值，不是交易表里的字段 —— 由**全量重放**算出（唯一事实源）。
     * @param excludeTxId 排除某笔（编辑时排除自己，避免"用改后的持仓限制改后的卖出"）
     * @param asOfDate 只算到某日为止的持仓；null = 当前持仓（大多数调用方用这个）
     */
    suspend fun positionQuantityOf(
        securityId: String,
        excludeTxId: String? = null,
        asOfDate: String? = null,
    ): Double =
        withContext(Dispatchers.Default) {
            val sec = db.securityQueries.selectById(securityId).executeAsOneOrNull()?.toDomain()
                ?: return@withContext 0.0
            val txs = db.tradeQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
                .filter { excludeTxId == null || it.id != excludeTxId }
                .filter { asOfDate == null || it.tradeDate <= asOfDate }
            val divs = db.dividendQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
            PositionCalculator
                .replay(security = sec, transactions = txs, dividends = divs).quantity
        }

    // ================================================================
    // 交易主线：增 / 改 / 删 / 预览
    // ================================================================

    /** 新增一笔交易，返回它的 id。 */
    suspend fun addTransaction(
        securityId: String,
        side: TradeSide,
        quantity: Double,
        price: Double,
        fee: Double,
        tradeDate: String,
        note: String? = null,
        emotion: String? = null,
        score: Int? = null,
        tags: List<String> = emptyList(),
        quality: TradeQuality? = null,
        /** 成交日汇率（原币 → CNY，REQ-ACC-15）；null = A股 或未记录 → 折算回退最新汇率 */
        fxRate: Double? = null,
        /** 印花税（原币金额，REQ-ACC-17）：由 `StampDuty.dutyOf` 算好传入，界面只展示不可改。 */
        stampDuty: Double = 0.0,
    ): String = withContext(Dispatchers.Default) {
        // 现金联动（老周 2026-09-16）：买入从现金扣（价×量＋费），卖出把回款加回现金。
        // 买入前校验可用现金，不足直接拒绝保存（UI 提示先去存入现金）。
        // 按标的币种折算本位币后再增减现金（港股/美股必须折算——老周 2026-09-16 修复）
        // ⚠️ 2026-09-18 审查修复（老周拍板）：卖出**超卖截断**。
        // 重放会把卖出截到实际持仓（表单提示"超出部分不会成交"），但此前现金联动与
        // 入库记录都按录入全量计算 → 持仓被截断而现金全额回款，账本失衡。
        // 现统一：入库、现金联动都用截断后的数量；手续费按实际成交比例折算
        // （与券商实际收费口径一致）。
        // H7 修复（2026-09-27）：截断抽到 [truncateSell]，与 updateTransaction **共用同一份实现**
        // （此前只有新增路径截断，编辑路径完全没有）。
        // M16 修复（2026-09-27）：按**该笔交易的日期**取持仓（补录历史卖出时不能用今天的持仓当上限）。
        val (qty, feeUsed) = truncateSell(securityId, side, quantity, fee, asOfDate = tradeDate)
        // 折算用**本笔的成交日汇率**（REQ-ACC-15 收口，老周 2026-09-29）：与成本/盈亏同源
        // 印花税一并计入现金（REQ-ACC-17）：A股买入为 0、卖出万分之5；港股买卖各千分之1
        val cashDelta = cashDeltaInCny(securityId, side, qty, price, feeUsed, fxRate = fxRate, stampDuty = stampDuty)
        // 账外备忘标的（场外基金「不计入统计」）：既不校验现金、也不增减现金 ——
        // 否则「纯备忘记一笔」会被现金余额卡住，或凭空少掉一笔现金（老周 2026-09-20）
        val offBook = isOffBook(securityId)
        if (side == TradeSide.BUY && !offBook) {
            val need = -cashDelta
            val available = cash.availableCashNow()
            if (available + 1e-6 < need) {
                error("现金不足：本次买入需要 ${moneyCny(need)}，当前可用现金 ${moneyCny(available)}。请先到「现金 · 出入金」存入现金。")
            }
        }

        val id = Ids.next("tx")
        // M6 修复（2026-09-27）：整段写入包进事务 —— 「取 seq → 插交易 → 改现金」必须原子。
        // 否则：① 并发下 nextSeq 可能取到重复序号（replayOrder 依赖 seq 稳定）；
        //       ② 中途抛错会留下"交易已入库但现金没动"（或反之）的半截状态。
        db.transaction {
            val seq = db.tradeQueries.nextSeq().executeAsOne()
            db.tradeQueries.upsert(
                id = id,
                security_id = securityId,
                side = side.name,
                quantity = qty,
                price = price,
                fee = feeUsed,
                stamp_duty = stampDuty,
                trade_date = tradeDate,
                seq = seq,
                note = note?.trim()?.ifEmpty { null },
                emotion = emotion?.trim()?.ifEmpty { null },
                score = score?.toLong(),
                tag_ids = tags.joinToString(",").ifEmpty { null },
                quality = quality?.name,
                fx_rate = fxRate,
            )
            // 现金联动放在交易入库**之后**（老周 2026-09-18 审查顺带修）：
            // 此前先动现金再入库，入库若失败现金已扣、交易却没存。入库几乎不会失败，反过来更稳。
            if (!offBook) cash.applyDelta(cashDelta)
        }
        // 自动加自选（老周 2026-09-16）：记过一笔的标的若不在自选里就加入（去重由唯一索引兜底）。
        // ⚠️ 放在**事务外**：`db.transaction {}` 是同步 lambda，内部不能调用 suspend 函数；
        //    它属"锦上添花"，失败用 runCatching 吞掉即可。
        // ⚠️ 自选域虽已搬进 WatchlistRepository（第 5 批），这里仍用**就地写库** ——
        //    与搬迁前的写法完全一致，行为零变化最要紧。
        runCatching {
            if (db.watchlistQueries.selectBySecurity(securityId).executeAsOneOrNull() == null) {
                val order = db.watchlistQueries.selectAll().executeAsList()
                    .maxOfOrNull { it.sort_order } ?: 0L
                db.watchlistQueries.insertItem(
                    id = Ids.next("wl"),
                    security_id = securityId,
                    group_name = "未分组",
                    sort_order = order + 1,
                    pinned = 0L,
                    created_at = todayIso(),
                    target_price = null,
                )
            }
        }
        repo.invalidateCurveCache()
        id
    }

    /**
     * 编辑。security_id **不可改**（改标的等于删除后另录，避免聚合口径混乱）；
     * 保存后不做任何增量修补，下次读取整标的重放。
     */
    suspend fun updateTransaction(
        txId: String,
        side: TradeSide,
        quantity: Double,
        price: Double,
        fee: Double,
        tradeDate: String,
        note: String? = null,
        emotion: String? = null,
        score: Int? = null,
        tags: List<String> = emptyList(),
        quality: TradeQuality? = null,
        fxRate: Double? = null,
        /** 印花税（原币金额，REQ-ACC-17）：与新增同口径，按"改后"的市场/方向/量重算后传入。 */
        stampDuty: Double = 0.0,
    ) = withContext(Dispatchers.Default) {
        val existing = db.tradeQueries.selectById(txId).executeAsOneOrNull()
            ?: error("找不到待编辑的交易: $txId")
        // H7 修复（2026-09-27）：与 addTransaction **同口径** —— 编辑卖出也要按「**剔除本笔后**的持仓」截断。
        // 此前编辑路径完全没有截断：把卖出从 100 股改成 1000 股（实际持仓 200）时，
        // 现金按 1000 股回款、重放只卖 200 股 → 差额永久留在现金里，且没有任何提示。
        val (qty, feeUsed) = truncateSell(
            securityId = existing.security_id,
            side = side,
            quantity = quantity,
            fee = fee,
            excludeTxId = existing.id,
            // M16 修复（2026-09-27）：按**编辑后**的交易日期取持仓（改日期时上限随之变化）
            asOfDate = tradeDate,
            // 真机回归修复（2026-09-27，2026-09-29 改为保底）：至少允许保持本笔**原数量** ——
            // 否则改一笔"把持仓清零的卖出"（如清仓后补备注）会被误判成"无可卖持仓"。
            // 仅在**原方向就是卖出**时需要（买入不占可卖额度）。
            keepAtLeastQty = if (TradeSide.valueOf(existing.side) == TradeSide.SELL) existing.quantity else 0.0,
        )
        // 现金联动：先回滚旧交易对现金的影响，再按新值应用（老周 2026-09-16）
        // 旧值与新值都按标的币种折算本位币（港股/美股必须折算）
        val oldDelta = cashDeltaInCny(
            securityId = existing.security_id,
            side = TradeSide.valueOf(existing.side),
            quantity = existing.quantity,
            price = existing.price,
            fee = existing.fee,
            // 回滚旧值用**原来的**成交日汇率（与当初写入时同一口径）
            fxRate = existing.fx_rate,
            // 同理：回滚要用**库里那笔的**印花税，而不是本次重算的
            stampDuty = existing.stamp_duty,
        )
        // 编辑态标的不允许改（security_id 不可改），新值用 existing 的标的（数量/手续费已按实际成交截断）
        // 新值用**本次提交的**成交日汇率（用户改日期时表单会重新带出该日汇率）
        val newDelta = cashDeltaInCny(existing.security_id, side, qty, price, feeUsed, fxRate = fxRate, stampDuty = stampDuty)
        // 账外备忘标的：不校验、不动现金（与 addTransaction 同口径，老周 2026-09-20）
        val offBook = isOffBook(existing.security_id)
        if (side == TradeSide.BUY && !offBook) {
            val need = -newDelta
            val available = cash.availableCashNow() - oldDelta   // 回滚旧值后再判断
            if (available + 1e-6 < need) {
                error("现金不足：本次买入需要 ${moneyCny(need)}，可用现金 ${moneyCny(available)}。请先存入现金。")
            }
        }
        // H7 / M6 修复：写库与改现金放进同一事务，且**入库后再动现金**
        // （与 addTransaction 的规则对齐；此前是先动现金、后入库，顺序相反）。
        db.transaction {
            db.tradeQueries.upsert(
                id = existing.id,
                security_id = existing.security_id,
                side = side.name,
                quantity = qty,
                price = price,
                fee = feeUsed,
                stamp_duty = stampDuty,
                trade_date = tradeDate,
                seq = existing.seq,
                note = note?.trim()?.ifEmpty { null },
                emotion = emotion?.trim()?.ifEmpty { null },
                score = score?.toLong(),
                tag_ids = tags.joinToString(",").ifEmpty { null },
                quality = quality?.name,
                fx_rate = fxRate,
            )
            if (!offBook) cash.applyDelta(newDelta - oldDelta)
        }
        repo.invalidateCurveCache()
    }

    /** 删除一笔交易（会重算持仓）。 */
    suspend fun deleteTransaction(id: String) = withContext(Dispatchers.Default) {
        // 现金联动：删除时把这笔对现金的影响回滚（买入退钱、卖出扣回）（老周 2026-09-16）
        val row = db.tradeQueries.selectById(id).executeAsOneOrNull()
        // M6 / M14 修复（2026-09-27）：
        // ① 现金回滚 + 照片 + 笔记 + 交易本体放进**同一事务**（避免"钱退了但交易还在"的半截状态）；
        // ② 顺带级联删除该笔的 note —— 此前只删了 trade_photo，孤儿笔记会继续被展示与备份。
        // ⚠️ `db.transaction {}` 是**同步** lambda，内部不能调用 suspend 函数 ——
        // 因此先把「回滚金额 + 是否账外」算好，事务内只做同步写入。
        var back = 0.0
        var offBook = true   // 默认按账外处理（row == null 时本就不该动现金）
        if (row != null) {
            back = cashDeltaInCny(
                securityId = row.security_id,
                side = TradeSide.valueOf(row.side),
                quantity = row.quantity,
                price = row.price,
                fee = row.fee,
                // 回滚必须用**当初写入时的**汇率，否则删除后会留下换算差额（老周 2026-09-29）
                fxRate = row.fx_rate,
                // 印花税同理：用库里那笔的金额回滚
                stampDuty = row.stamp_duty,
            )
            // 账外备忘标的从未动过现金 → 删除时也不能退/扣（老周 2026-09-20）
            offBook = isOffBook(row.security_id)
        }
        db.transaction {
            if (row != null && !offBook) cash.applyDelta(-back)
            // 截图与笔记联动删除（截图 2026-09-17；笔记级联 2026-09-27）
            db.tradePhotoQueries.deleteByTx(id)
            db.noteQueries.deleteByTransaction(id)
            db.tradeQueries.deleteById(id)
        }
        repo.invalidateCurveCache()
    }

    /**
     * **一键清空某标的的全部流水**（老周 2026-09-30）。
     *
     * 清掉：全部交易（连附属的**笔记 / 截图**）+ 全部**分红送股**。
     * **标的本身保留**（持仓自然归零；标的的名称 / 市场 / 每手 / 账外标记都不动），
     * 因此这是个"这只票重记一遍"的干净起点。
     *
     * ⚠️ 现金必须**同事务反向冲销**：交易对 `account.cash` 的影响按**各笔自己的成交日汇率**回滚
     * （与 [deleteTransaction] 同一口径；账外备忘标的从未动过现金，一律跳过）。
     * 分红不写 `account.cash`（可用现金直接按 dividend 表求和），删除即自动消失。
     *
     * @return 被清掉的交易笔数（供界面提示）
     */
    suspend fun clearSecurityLedger(securityId: String): Int = withContext(Dispatchers.Default) {
        val rows = db.tradeQueries.selectBySecurity(securityId).executeAsList()
        val offBook = isOffBook(securityId)
        // 先算好总回滚额（suspend 调用不能在 db.transaction 的同步 lambda 里做）
        var back = 0.0
        if (!offBook) {
            rows.forEach { r ->
                back += cashDeltaInCny(
                    securityId = r.security_id,
                    side = TradeSide.valueOf(r.side),
                    quantity = r.quantity,
                    price = r.price,
                    fee = r.fee,
                    fxRate = r.fx_rate,
                    stampDuty = r.stamp_duty,
                )
            }
        }
        db.transaction {
            if (!offBook && back != 0.0) cash.applyDelta(-back)
            rows.forEach { r ->
                db.tradePhotoQueries.deleteByTx(r.id)
                db.noteQueries.deleteByTransaction(r.id)
            }
            db.tradeQueries.deleteBySecurity(securityId)
            db.dividendQueries.deleteBySecurity(securityId)
        }
        repo.invalidateCurveCache()
        rows.size
    }

    /**
     * 删除预告（REQ-ACC-09）：对「剔除这一笔」的集合做一次重放。
     * 与实际删除共用 [PositionCalculator.replay]，预告结果 = 实际结果。
     */
    suspend fun previewAfterDelete(securityId: String, txId: String): DeletePreview? =
        withContext(Dispatchers.Default) {
            val row = db.securityQueries.selectById(securityId).executeAsOneOrNull() ?: return@withContext null
            val security = row.toDomain()
            val txs = db.tradeQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
            if (txs.none { it.id == txId }) return@withContext null

            val cached = db.quoteQueries.selectBySymbol(security.symbol).executeAsOneOrNull()?.price
            // 分红必须与真实持仓同口径传入，否则预告成本与页面成本对不上（差 0.5 元/股）
            val divs = db.dividendQueries.selectBySecurity(securityId).executeAsList().map { it.toDomain() }
            TradeForms.previewAfterDelete(security, txs, txId, cached, divs)
        }

    // ================================================================
    // CSV 导入（两类）
    // ================================================================

    /**
     * **CSV 导入专用的交易写入**（M8 修复，2026-09-27）。
     *
     * 与 [addTransaction] 的三点关键区别：
     *  1. **不做"现金充足"校验**：CSV 是整本账的搬运，**行序不代表资金先后** ——
     *     先导交易再导出入金（或反过来）都是正常用法，不该让买入行被跳过。
     *     此前每行都走 `addTransaction` 的校验，于是"先导交易"时**所有买入行直接失败**。
     *  2. **按「该笔交易日期的持仓」截断卖出**（而不是"当前持仓"）：调用方须**先把记录按日期排序**，
     *     否则乱序导入会把历史卖出截成 0 —— 成交被静默吞掉。
     *  3. **不做自动加自选**：批量导入时每行都去查一次自选纯属浪费，导入完成后界面统一刷新即可。
     *
     * ⚠️ **现金联动保留**：`account.cash` 是"买卖增减的账户现金"，必须同步维护，
     * 否则可用现金（= 账户现金 + 出入金 + 分红）会算错。
     */
    private suspend fun insertTradeForCsv(
        securityId: String,
        side: TradeSide,
        quantity: Double,
        price: Double,
        fee: Double,
        tradeDate: String,
        note: String?,
        emotion: String?,
        score: Int?,
        tags: List<String>,
        quality: TradeQuality?,
        fxRate: Double?,
        /** 印花税（CSV 里的「印花税」列，REQ-ACC-17）；旧文件没有该列时传 0。 */
        stampDuty: Double,
    ) {
        // 用 as-of 该交易日的持仓截断（M16 加的 asOfDate 正好用在这里）
        val (qty, feeUsed) = truncateSell(securityId, side, quantity, fee, asOfDate = tradeDate)
        val offBook = isOffBook(securityId)
        // CSV 带「外币折算汇率」列时同口径（REQ-ACC-15 收口，老周 2026-09-29）
        val cashDelta = cashDeltaInCny(securityId, side, qty, price, feeUsed, fxRate = fxRate, stampDuty = stampDuty)
        db.transaction {
            db.tradeQueries.upsert(
                id = Ids.next("tx"),
                security_id = securityId,
                side = side.name,
                quantity = qty,
                price = price,
                fee = feeUsed,
                stamp_duty = stampDuty,
                trade_date = tradeDate,
                seq = db.tradeQueries.nextSeq().executeAsOne(),
                note = note?.trim()?.ifEmpty { null },
                emotion = emotion?.trim()?.ifEmpty { null },
                score = score?.toLong(),
                tag_ids = tags.joinToString(",").ifEmpty { null },
                quality = quality?.name,
                fx_rate = fxRate,
            )
            if (!offBook) cash.applyDelta(cashDelta)
        }
    }

    /** 解析并导入交易 CSV（SAF 选文件用）—— 老周 2026-09-17。 */
    suspend fun applyTradesCsv(text: String): PortfolioRepository.CsvImportResult =
        withContext(Dispatchers.Default) {
            val records = CsvCodec.toRecords(CsvCodec.parse(text))
            if (records.isEmpty()) error("文件没有数据行（只有表头或为空）")

            val skipped = mutableListOf<String>()
            val existing = db.tradeQueries.selectAll().executeAsList()
                .map { listOf(it.trade_date, it.security_id, it.side, it.quantity, it.price) }.toMutableList()
            val secs = db.securityQueries.selectAll().executeAsList()

            // 成交日汇率缓存（REQ-ACC-15 + CSV 导入，老周 2026-09-22）：
            // 按日期去重 —— 同一批导入里同一天只查一次网络，避免 N 行 = N 次请求
            val fxCache = mutableMapOf<String, Double?>()
            // 印花税率（REQ-ACC-17）：键名与 SettingsRepository 的 STAMP_*_KEY 一致（同一份本地数据）；
            // 仅在「旧 CSV 没有印花税列」时用于补算，缺省取法定值。
            val stampAShareRate = runCatching {
                db.settingQueries.selectByKey("stamp_duty_a_share").executeAsOneOrNull()
                    ?.setting_value?.toDoubleOrNull()
            }.getOrNull() ?: com.stocknote.core.calc.StampDuty.A_SHARE_SELL_RATE
            val stampHkRate = runCatching {
                db.settingQueries.selectByKey("stamp_duty_hk").executeAsOneOrNull()
                    ?.setting_value?.toDoubleOrNull()
            }.getOrNull() ?: com.stocknote.core.calc.StampDuty.HK_RATE
            var ok = 0
            // M8 修复（2026-09-27）：**先按成交日排序再导入** —— CSV 行序未必是时间序，
            // 而卖出截断依赖「该日之前的持仓」，乱序导入会把历史卖出截成 0（成交被静默吞掉）。
            // 排序时**保留原始行号**，报错信息仍指向用户文件里的真实行。
            val ordered = records.mapIndexed { i, rec -> i to rec }
                .sortedBy { it.second["日期"].orEmpty() }
            ordered.forEach { (rawIdx, r) ->
                val lineNo = rawIdx + 2 // 表头占第 1 行
                val date = r["日期"].orEmpty()
                val symbol = r["标的代码"].orEmpty()
                val name = r["标的名称"].orEmpty()
                val sideText = r["方向"].orEmpty()
                val qty = r["数量"].orEmpty().toDoubleOrNull()
                val price = r["成交价"].orEmpty().toDoubleOrNull()
                val fee = r["手续费"].orEmpty().toDoubleOrNull() ?: 0.0
                // 印花税（REQ-ACC-17）：文件有「印花税」列就用它；
                // 旧文件（本列上线前导出，15 列）没有该列 → 下面按市场/方向/税率**自动补算**，
                // 与「外币交易汇率按交易日期自动补齐」是同一思路，免得老文件导进来税全是 0。
                val dutyFromFile = r["印花税"]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                // 币种**以导入文件为准**（老周 2026-09-22）：不再用代码前缀去猜。
                // 文件未给或写错时才落到本位币 CNY（这是明确的缺省，不是猜测）。
                val currencyText = r["币种"].orEmpty().trim().uppercase()
                val fileCurrency = Currency.entries.firstOrNull { it.code == currencyText } ?: Currency.CNY

                // 方向（REQ-ACC-16，老周 2026-09-20）：导出会写「转增资本」，导入必须认回来 ——
                // 否则「导出 → 导入」的备份还原会**静默丢掉全部转增记录**（且重放结果随之偏移）。
                // 转增是账内重分类而非成交：数量恒 0，金额落在「成交价」列（见 Transaction.amountOf 约定）。
                val isCapitalize = sideText.contains("转增") || sideText.equals("CAPITALIZE", true)
                val side: String? = when {
                    isCapitalize -> TradeSide.CAPITALIZE.name
                    sideText.contains("买") || sideText.equals("BUY", true) || sideText.equals("B", true) -> TradeSide.BUY.name
                    sideText.contains("卖") || sideText.equals("SELL", true) || sideText.equals("S", true) -> TradeSide.SELL.name
                    else -> null
                }

                val reason = when {
                    date.isEmpty() -> "日期为空"
                    runCatching { CivilDate.parseIso(date) }.isFailure -> "日期格式应为 yyyy-MM-dd"
                    symbol.isEmpty() -> "标的代码为空"
                    name.isEmpty() -> "标的名称为空"
                    side == null -> "方向应为 买入/卖出/转增资本"
                    // 转增不改股数 → 数量列允许为空或 0，但金额必须为正；其余方向两者都要为正
                    isCapitalize && (price == null || price <= 0) -> "转增金额非法"
                    !isCapitalize && (qty == null || qty <= 0) -> "数量非法"
                    !isCapitalize && (price == null || price <= 0) -> "成交价非法"
                    fee < 0 -> "手续费为负"
                    else -> null
                }
                if (reason != null) {
                    skipped += "第 $lineNo 行：$reason"
                    return@forEach
                }
                // 转增的股数一律落 0（与 addCapitalize 同形）；导入不做「是否仍持仓」的前置校验，
                // 因为 CSV 行序不代表时间线，交给重放按时间自然生效（0 持仓时该笔被忽略）。
                val qtyUsed = if (isCapitalize) 0.0 else qty!!
                val sideName = side!!

                // 标的：已存在则复用（币种以**库中记录**为准，那是事实）；否则按代码前缀推断市场、
                // 用**文件里的币种**建档（老周 2026-09-22：不再 guessCurrencyOf(symbol)）
                val secId = secs.firstOrNull { it.symbol == symbol }?.id
                    ?: runCatching {
                        security.findOrCreateSecurity(
                            symbol = symbol,
                            name = name,
                            market = MarketGuess.of(symbol),
                            currency = fileCurrency,
                        ).id
                    }.getOrNull()
                if (secId == null) {
                    skipped += "第 $lineNo 行：标的创建失败（$symbol）"
                    return@forEach
                }

                // 折算用汇率：以**标的实际币种**为准（本位币为 null → 不写列，折算本就恒等）；
                // 按交易日取市场汇率，按日期缓存。拉不到则留空 → 折算回退最新汇率（不阻塞导入）。
                val secCurrency = secs.firstOrNull { it.symbol == symbol }?.currency
                    ?.let { code -> Currency.entries.firstOrNull { it.code == code } }
                    ?: fileCurrency
                val tradeFx: Double? = if (secCurrency == Currency.CNY) {
                    null
                } else {
                    fxCache.getOrPut(date) {
                        runCatching { repo.fxRateOn(date, secCurrency) }.getOrNull()
                    }
                }

                // 印花税（REQ-ACC-17）：文件值优先；旧文件缺列 → 按**标的实际市场 + 方向 + 税率**补算
                val rowMarket = secs.firstOrNull { it.id == secId }?.market
                    ?.let { m -> com.stocknote.core.model.Market.entries.firstOrNull { it.name == m } }
                    ?: MarketGuess.of(symbol)
                val stampDuty = dutyFromFile ?: com.stocknote.core.calc.StampDuty.dutyOf(
                    market = rowMarket,
                    isBuy = sideName == TradeSide.BUY.name,
                    // 上面的 reason 校验已排除 null（"成交价非法"），这里可安全断言
                    price = price!!,
                    quantity = qtyUsed,
                    aShareRate = stampAShareRate,
                    hkRate = stampHkRate,
                )

                // 去重：同 日期+标的+方向+数量+价格（转增的数量恒 0，靠金额区分多笔）
                val key = listOf(date, secId, sideName, qtyUsed, price!!)
                if (existing.any { it == key }) {
                    skipped += "第 $lineNo 行：重复记录（已跳过）"
                    return@forEach
                }
                existing += key

                runCatching {
                    // M8 修复（2026-09-27）：改走 **CSV 专用入口** —— 不校验现金充足、不做"当前持仓"截断、
                    // 不自动加自选（详见 [insertTradeForCsv] 的 KDoc）
                    insertTradeForCsv(
                        securityId = secId,
                        side = TradeSide.valueOf(sideName),
                        quantity = qtyUsed,
                        price = price,
                        fee = fee,
                        tradeDate = date,
                        note = r["备注/理由"].orEmpty().ifEmpty { "CSV 导入" },
                        emotion = r["情绪"].orEmpty().ifEmpty { null },
                        score = r["执行评分"].orEmpty().toIntOrNull(),
                        tags = r["标签"].orEmpty().split(';', '；').map { it.trim() }.filter { it.isNotEmpty() },
                        quality = when {
                            r["质量评级"].orEmpty().contains("神") -> TradeQuality.GREAT
                            r["质量评级"].orEmpty().contains("昏") -> TradeQuality.BLUNDER
                            else -> null
                        },
                        // 成交日汇率（REQ-ACC-15）：CSV 里没有这一列，按交易日期自动补齐
                        fxRate = tradeFx,
                        // 印花税（REQ-ACC-17）：文件列或按规则补算（见上面的 stampDuty）
                        stampDuty = stampDuty,
                    )
                    ok++
                }.onFailure { e -> skipped += "第 $lineNo 行：写入失败（${e.message ?: "未知"}）" }
            }
            repo.invalidateCurveCache()
            PortfolioRepository.CsvImportResult(ok, skipped)
        }

    /** 解析并导入出入金 CSV（SAF 选文件用）—— 老周 2026-09-17。去重：同 日期+类型+金额+币种。 */
    suspend fun applyCashFlowsCsv(text: String): PortfolioRepository.CsvImportResult =
        withContext(Dispatchers.Default) {
            val records = CsvCodec.toRecords(CsvCodec.parse(text))
            if (records.isEmpty()) error("文件没有数据行（只有表头或为空）")

            val skipped = mutableListOf<String>()
            val existing = db.cashFlowQueries.selectAll().executeAsList()
                // 去重键**含币种**（老周 2026-09-22）：否则港币 1000 与人民币 1000 会被误判成同一行
                .map { Triple(it.flow_date, it.type, kotlin.math.abs(it.amount_orig)) }
                .map { listOf(it.first, it.second, it.third.toString()) }
                .toMutableList()
            var ok = 0
            records.forEachIndexed { idx, r ->
                val lineNo = idx + 2
                val date = r["日期"].orEmpty()
                val typeText = r["类型"].orEmpty()
                val amount = r["金额"].orEmpty().toDoubleOrNull()
                // 币种**以导入文件为准**（老周 2026-09-22）：不再硬编码 CNY。
                // 文件未给或写错时才落到本位币 CNY（不是"猜"，是明确的缺省）。
                val currencyText = r["币种"].orEmpty().trim().uppercase()
                val currency = Currency.entries.firstOrNull { it.code == currencyText } ?: Currency.CNY
                val isDeposit = typeText.contains("存") || typeText.equals("DEPOSIT", true)
                val type = if (isDeposit) "DEPOSIT" else "WITHDRAW"

                val reason = when {
                    date.isEmpty() -> "日期为空"
                    runCatching { CivilDate.parseIso(date) }.isFailure -> "日期格式应为 yyyy-MM-dd"
                    !(typeText.contains("存") || typeText.contains("取") ||
                        typeText.equals("DEPOSIT", true) || typeText.equals("WITHDRAW", true)) -> "类型应为 存入/取出"
                    amount == null || amount <= 0 -> "金额非法"
                    else -> null
                }
                if (reason != null) {
                    skipped += "第 $lineNo 行：$reason"
                    return@forEachIndexed
                }
                val key = listOf(date, type, amount!!.toString(), currency.code)
                if (existing.any { it == key }) {
                    skipped += "第 $lineNo 行：重复记录（已跳过）"
                    return@forEachIndexed
                }
                existing += key

                // 汇率（REQ-ACC-15 同源）：按**发生日**取该币种汇率；本位币恒 1.0。
                // 拉不到时用 FxTable 兜底（**绝不把港币当人民币**），并把这件事记进备注与提示里。
                var fxUsed = 1.0
                var fxWarn: String? = null
                if (currency != Currency.CNY) {
                    val fetched = runCatching { repo.fxRateOn(date, currency) }.getOrNull()
                    if (fetched != null && fetched > 0.0) {
                        fxUsed = fetched
                    } else {
                        fxUsed = FxTable.convertWith(1.0, currency, Currency.CNY, emptyMap())
                        fxWarn = "汇率拉取失败，已用兜底汇率 $fxUsed"
                    }
                }

                runCatching {
                    cash.addCashFlow(
                        accountId = db.accountQueries.selectAll().executeAsList().firstOrNull()?.id ?: "acc_a",
                        flowDate = date,
                        isDeposit = isDeposit,
                        amountOrig = amount,
                        currency = currency,
                        fxRate = fxUsed,
                        note = (r["备注"].orEmpty().ifEmpty { "CSV 导入" }) + (fxWarn?.let { "（$it）" } ?: ""),
                    )
                    ok++
                    if (fxWarn != null) skipped += "第 $lineNo 行：${currency.code} $fxWarn，请复核"
                }.onFailure { e -> skipped += "第 $lineNo 行：写入失败（${e.message ?: "未知"}）" }
            }
            repo.invalidateCurveCache()
            PortfolioRepository.CsvImportResult(ok, skipped)
        }

    // ================================================================
    // 交易截图（REQ-ACC-11）
    // BLOB 存加密库（与账本同库同加密），一笔交易最多 1 张；UI 层选图后压缩再入库。
    // ⚠️ **不参与加密备份**（备份是 SQL 文本 dump，BLOB 需额外编码，本期从简）。
    // ================================================================

    /** 保存/覆盖某笔交易的截图。 */
    suspend fun saveTradePhoto(txId: String, mime: String, bytes: ByteArray) =
        withContext(Dispatchers.Default) {
            db.tradePhotoQueries.upsert(
                tx_id = txId,
                mime = mime,
                bytes = bytes,
                created_at = todayIso(),
            )
        }

    /** 取某笔交易的截图（无则 null）。 */
    suspend fun tradePhotoOf(txId: String): ByteArray? = withContext(Dispatchers.Default) {
        db.tradePhotoQueries.selectByTx(txId).executeAsOneOrNull()?.bytes
    }

    /** 有截图的交易数（设置页/关于页展示用）。 */
    suspend fun tradePhotoCount(): Long = withContext(Dispatchers.Default) {
        db.tradePhotoQueries.countAll().executeAsOne()
    }

    /** 删除某笔交易的截图。 */
    suspend fun deleteTradePhoto(txId: String) = withContext(Dispatchers.Default) {
        db.tradePhotoQueries.deleteByTx(txId)
    }

    // ================================================================
    // 交易质量评级（REQ-ANA-04）
    // ================================================================

    /** 标记/取消质量评级。传 null 取消。 */
    suspend fun setTradeQuality(txId: String, quality: TradeQuality?) =
        withContext(Dispatchers.Default) {
            db.tradeQueries.updateQuality(quality?.name, txId)
        }

    /** 神操作 / 昏招 各自的笔数（分析页入口角标用）。 */
    suspend fun qualityCounts(): Map<String, Long> = withContext(Dispatchers.Default) {
        db.tradeQueries.selectQualityCounts().executeAsList()
            .associate { (it.quality ?: "") to it.cnt }
    }

    // ================================================================
    // private 辅助（原属 PortfolioRepository，第 8 批随交易主线一起搬来）
    // ================================================================

    /**
     * 卖出**超卖截断**：返回 `实际成交量 → 按比例折算后的手续费`（H7 修复，2026-09-27）。
     *
     * ⚠️ [addTransaction] 与 [updateTransaction] **共用这一份实现** —— 此前只有新增路径做了截断，
     * 编辑路径完全没有：把卖出从 100 股改成 1000 股（实际持仓 200）时，现金按 1000 股回款、
     * 重放只卖 200 股，差额**永久留在现金里**，且没有任何提示。
     */
    private suspend fun truncateSell(
        securityId: String,
        side: TradeSide,
        quantity: Double,
        fee: Double,
        excludeTxId: String? = null,
        /** M16 修复（2026-09-27）：按该日期（含）之前的持仓截断（补录历史卖出时用）；null = 当前持仓 */
        asOfDate: String? = null,
        /**
         * **编辑场景**：本笔**原来的数量**，作为上限的**保底值**（老周 2026-09-29 修正；
         * 2026-09-27 那版是"加回"，见下面的 ⚠️）。
         *
         * ⚠️ 起因（2026-09-27）：`updateTransaction` 传了 `excludeTxId = 本笔`，上限变成
         * "**剔除本笔后**的持仓"。若改的正是**把持仓清零的那笔卖出**（典型：清仓后想补备注），
         * 且按日重放算不出持仓（如卖出日期早于买入），就会被判"当前无可卖持仓"
         * → **连改个备注都做不到**。所以至少要允许保持**原数量**不变。
         *
         * ⚠️ 但**不能无脑相加**：`excludeTxId` 剔除本笔后，本笔占的额度**本来就没被扣过**，
         * 再 `+ 原数量` 等于放行超卖 —— 入库数量 > 实际持仓，现金按全量回款、重放只卖得掉持仓，
         * 差额**永久留在现金里**（正是 H7 要防的失衡）。
         * 故取 `max(剔除本笔后的持仓, 原数量)`：既能改备注，也绝不放大上限。
         * 界面「最多可卖」与这里**同一公式**（`TradeFormHolder.sellableLimitOf`），两边不会打架。
         * （原方向是买入时传 0，因为买入不占"可卖"额度。）
         */
        keepAtLeastQty: Double = 0.0,
    ): Pair<Double, Double> {
        if (side != TradeSide.SELL) return quantity to fee
        val holding = kotlin.math.max(positionQuantityOf(securityId, excludeTxId, asOfDate), keepAtLeastQty)
        if (quantity <= holding + 1e-9) return quantity to fee
        if (holding <= 1e-9) {
            error("当前无可卖持仓，无法登记卖出。请先买入，或检查方向是否选反。")
        }
        return holding to fee * holding / quantity
    }

    /**
     * 某个标的的「原币 → CNY」折算率（CNY 标的 = 1.0）。
     *
     * ⚠️ **优先级（老周 2026-09-29 收口 REQ-ACC-15）**：
     *  1. **本笔交易的成交日汇率** [fxRate] —— 用户在表单填/自动回填的那个，`trade.fx_rate` 列；
     *  2. 设置里维护的**最新汇率**（`fx_rate` 表最新一条）；
     *  3. [FxTable] 固定兜底（HKD 0.90 / USD 7.1）。
     *
     * 此前现金折算**完全无视** `trade.fx_rate`：即使用户手填了成交日汇率，买入扣的现金仍按
     * "最新汇率"算 → 早年交易的成本（用当年汇率）与现金（用今天汇率）口径打架。
     * 现在两者同源，成交日汇率填什么，现金就按什么扣。
     *
     * ⚠️ 与 `PortfolioRepository.rateToBaseOf` **同源**（那份被原类 `openingCash` 使用）。
     */
    private suspend fun rateToBaseOf(securityId: String, fxRate: Double? = null): Double {
        val code = runCatching {
            db.securityQueries.selectById(securityId).executeAsOneOrNull()?.currency
        }.getOrNull() ?: Currency.CNY.code
        if (code == Currency.CNY.code) return 1.0
        // 本笔的成交日汇率优先（外币；非正数视为未填 —— 与 parseFxRate 的校验口径一致）
        if (fxRate != null && fxRate > 0.0) return FxTable.roundRate(fxRate)
        val cur = Currency.entries.firstOrNull { it.code == code } ?: return 1.0
        return repo.latestFxRates()[code] ?: FxTable.rate(cur, Currency.CNY)
    }

    /**
     * 一笔交易对**现金账户（CNY）**的影响 —— 已按标的币种折算本位币。
     *
     * ⚠️ 2026-09-16 修复：此前直接用原币数值增减 CNY 现金（买港股按港元数字当人民币扣，
     * 少扣约 15%）。现统一走 [CashImpact.cashDeltaOf]。
     * ⚠️ 与 `PortfolioRepository.cashDeltaInCny` **同源**（那份被原类 `openingCash` 使用）。
     */
    private suspend fun cashDeltaInCny(
        securityId: String,
        side: TradeSide,
        quantity: Double,
        price: Double,
        fee: Double,
        /** 本笔交易的成交日汇率（`trade.fx_rate`）；非空且 > 0 时优先于最新汇率。 */
        fxRate: Double? = null,
        /** 印花税（原币金额，REQ-ACC-17）：与手续费同等计入现金，缺省 0（历史数据）。 */
        stampDuty: Double = 0.0,
    ): Double = CashImpact.cashDeltaOf(
        side = side.name,
        quantity = quantity,
        price = price,
        fee = fee,
        rateToBase = rateToBaseOf(securityId, fxRate),
        stampDuty = stampDuty,
    )

    /**
     * 该标的是否「完全账外（纯备忘）」——即场外基金里勾了「不计入统计与分析」的。
     *
     * 用途：所有**现金联动**与**现金校验**的地方都要先问一句（老周 2026-09-20）。
     * 账外标的的买卖**不进出现金账户**，只在平行的备忘账里记份额/成本。
     */
    private fun isOffBook(securityId: String): Boolean =
        db.securityQueries.selectById(securityId).executeAsOneOrNull()?.exclude_from_stats == 1L

    /** 金额文案（本位币）。统一走 core 的 Format，避免各写一份。 */
    private fun moneyCny(v: Double): String = Format.money(v)
}
