package com.stocknote.data.repo

import com.stocknote.core.model.FxTable
import com.stocknote.core.calc.CashFlow
import com.stocknote.core.model.CashFlowRecord
import com.stocknote.core.model.Currency
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.db.toDomain
import com.stocknote.data.platform.todayIso
import com.stocknote.data.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **现金域 Repository**（阶段 2：核心口径已搬迁，2026-09-29，第 7 批）。
 *
 * 为什么先搬这个：`applyCashDelta` 与 `availableCash` 是**所有剩余"搬不动"方法的共同瓶颈** ——
 * `addTransaction` / `updateTransaction` / `deleteTransaction` / `insertTradeForCsv`
 * 都在用它们，而前者是原类的 private 同步函数、直接读改 `account.cash`。
 * 把这一层独立出来，那些方法才有机会继续搬。
 *
 * ⚠️ **本类刻意不依赖任何其它 Repository**（自给自足）：
 * 曾考虑注入 `FxRepository` 取汇率，但那会形成
 * `PortfolioRepository → CashRepository → FxRepository → PortfolioRepository` 的**三向循环**。
 * 汇率查询本身只有一条 SQL，本类自带一个 private `latestRates()` 即可，代价是同一句 SQL 出现两处。
 *
 * ## 口径（**改动前务必先看 `CashContractTest`**）
 *
 * **可用现金 = 账户现金 + 出入金净额 + 现金分红**
 *  - **账户现金**（`account.cash`）：**只被买卖增减**（见 [applyDelta]）；
 *  - **出入金**：在 `cash_flow` 表，**不写** `account.cash` —— 二者在 [availableCash] 里相加，
 *    若有人顺手把出入金也写进 `account.cash` 会**重复计算**；
 *  - **现金分红**：`dividend` 表，同样不写 `account.cash`；
 *    ⚠️ 且**账外备忘标的**（场外基金「不计入统计」）的分红**不进现金** ——
 *    它的买卖都不动现金，分红自然也不该动（老周 2026-09-20）。
 */
class CashRepository internal constructor(
    private val db: StockNoteDb,
) {
    /**
     * 每币种最新生效汇率（code → 兑 CNY）。
     *
     * ⚠️ 与 `PortfolioRepository.latestFxRates` **同源同 SQL**（那边被 11 处使用，暂未迁出）。
     * 两处若将来要合并，前提是先解决上面类注释提到的循环问题。
     */
    private suspend fun latestRates(onOrBefore: String = todayIso()): Map<String, Double> =
        withContext(Dispatchers.Default) {
            db.fxRateQueries.latestPerCurrency(onOrBefore, onOrBefore).executeAsList()
                .associate { it.currency to it.rate }
        }

    /** 账外标的（「不计入统计与分析」）的 securityId 集合。 */
    private fun offBookSecurityIds(): Set<String> =
        db.securityQueries.selectAll().executeAsList()
            .filter { it.exclude_from_stats == 1L }
            .map { it.id }
            .toSet()

    /**
     * **可用现金**（本位币）。
     *
     * 现统一到这个函数，取两个口径中**更贴近事实**的那个：
     *  - 账户：**全部账户**折本位币（外币账户也是钱）；
     *  - 分红：**dividend 表**口径（钱已实际到账，不该因后来清仓就消失）。
     */
    suspend fun availableCash(base: Currency = Currency.CNY): Double = withContext(Dispatchers.Default) {
        val rates = latestRates()
        // ① 账户现金：全部账户，各按自身币种折本位币（此前 loadSnapshot 只取 base 币种 → 外币账户被吞）
        val cash = db.accountQueries.selectAll().executeAsList().sumOf { a ->
            val cur = Currency.entries.firstOrNull { it.code == a.currency } ?: Currency.CNY
            FxTable.convertWith(a.cash, cur, base, rates)
        }
        // ② 出入金净额：非本位币按汇率折算（原来只累加 CNY，外币出入金会被忽略）
        val flowNet = db.cashFlowQueries.selectAll().executeAsList().sumOf { f ->
            val cur = Currency.entries.firstOrNull { it.code == f.currency } ?: Currency.CNY
            FxTable.convertWith(f.amount_orig, cur, base, rates)
        }
        // ③ 现金分红（dividend 表口径）：⚠️ 账外备忘标的的分红**不进现金**（老周 2026-09-20）
        val offBook = offBookSecurityIds()
        val divCash = db.dividendQueries.selectAll().executeAsList()
            .filter { it.type == "CASH" && it.security_id !in offBook }
            .sumOf { it.quantity * it.per_share * it.fx_rate }
        cash + flowNet + divCash
    }

    /** 当前可用现金（本位币）。保留此名以兼容既有调用点，实现已统一到 [availableCash]。 */
    suspend fun availableCashNow(): Double = availableCash()

    /**
     * **把一笔现金变动记到账户上**（买卖联动的唯一入口）。
     *
     * ⚠️ **刻意保持同步**（非 suspend）：4 个调用点里有的**在 `db.transaction {}` 内部** ——
     * 事务 lambda 是同步的，调不了 suspend 函数。搬迁时曾改成 suspend，编译立刻报
     * 「Suspension functions can only be called within coroutine body」，正好印证了这一点。
     * 本函数只做纯 db 读写，没有真正的挂起需求，同步是自然的。
     *
     * ⚠️ 账外标的（[offBookSecurityIds]）**不要**调用本方法（调用点自己判断 `!offBook`）。
     */
    fun applyDelta(delta: Double) {
        if (delta == 0.0) return
        // L9 修复（2026-09-27）：此前是 `selectAll().firstOrNull()`，而 Account.sq 的 selectAll 带
        // `ORDER BY name` → 实际取到的是"**按名称排序的第一个**账户"，多账户上线后会写错账户。
        // 现在：**优先本位币账户**（买卖联动本就记在本位币账上），没有本位币账户才退回第一个。
        val accounts = db.accountQueries.selectAll().executeAsList()
        val account = accounts.firstOrNull { it.currency == Currency.CNY.code } ?: accounts.firstOrNull()
        if (account == null) {
            // ⚠️ 2026-09-17 修复（老周真机实测发现，BUG-03）：
            // 此前无账户时**直接 return**，把交易对现金的影响**静默丢弃** ——
            // 全新账本（release 空库 / 新用户）买入不扣现金，而持仓市值照常计入，
            // 于是总资产虚增（实测：入金 10 万后买 100 股腾讯，现金仍是 10 万）。
            // 演示数据带种子账户，所以历次测试都没暴露。现改为**自动建默认主账户**并写入。
            db.accountQueries.upsert(
                id = Ids.next("acc"),
                name = "A股账户（含港股通）",   // 老周 2026-09-17 定：与设置页/产品口径一致
                currency = Currency.CNY.code,
                cash = delta,
            )
            return
        }
        db.accountQueries.updateCash(account.cash + delta, account.id)
    }

    // ================================================================
    // 出入金 CRUD（第 7 批下，2026-09-29）
    //
    // ⚠️ **本类不做曲线缓存失效**（`invalidateCurveCache` 留在原类、计数 18）。
    // 写操作会改变资产曲线，原类的转发方法负责调用失效 —— 这是刻意分工：
    // 本类保持"纯 db + 现金口径"，缓存这种跨域关注点不混进来。
    // ================================================================

    /**
     * 新增一笔出入金，返回 id。
     *
     * ⚠️ 调用方历史上硬编码 `accountId = "acc_a"`，与实际账户 id（`Ids.next("acc")` 生成）不符。
     * 现在**以库里第一个账户为准**，参数仅作无账户时的兜底。
     */
    suspend fun addCashFlow(
        accountId: String,
        flowDate: String,
        isDeposit: Boolean,
        amountOrig: Double,
        currency: Currency,
        fxRate: Double,
        note: String? = null,
    ): String = withContext(Dispatchers.Default) {
        val id = Ids.next("cf")
        val signed = if (isDeposit) amountOrig else -amountOrig
        val realAccountId = db.accountQueries.selectAll().executeAsList().firstOrNull()?.id ?: accountId
        db.cashFlowQueries.upsert(
            id = id,
            account_id = realAccountId,
            flow_date = flowDate,
            type = if (isDeposit) "DEPOSIT" else "WITHDRAW",
            amount_orig = signed,
            currency = currency.code,
            // L3 修复（2026-09-27）：与其他写库路径一致，汇率统一收敛 4 位小数
            fx_rate = FxTable.roundRate(fxRate),
            amount_base = signed * FxTable.roundRate(fxRate),
            note = note?.trim()?.ifEmpty { null },
        )
        id
    }

    /**
     * 编辑一笔出入金。
     *
     * ⚠️ **H4 修复（2026-09-28）**：此前 `amount_base` 被直接写成原币金额（`= signed`），
     * 且 UPDATE 语句里没有 `currency` / `fx_rate` —— 编辑一笔**外币流水**（如 CSV 导入的港币入金）
     * 会把本位币金额**改成原币数字**（1 万港元从 8555 变 10000），**可用现金虚增、收益率跟着偏**。
     * 现在：先读回原行，**币种与汇率原样保留**，`amount_base = 原币金额 × 汇率`（CNY 汇率恒 1）。
     * 注：改日期不重取新汇率（沿用该笔原汇率快照），避免编辑动作引入额外网络依赖。
     */
    suspend fun updateCashFlow(
        id: String,
        flowDate: String,
        isDeposit: Boolean,
        amountOrig: Double,
        note: String? = null,
    ) = withContext(Dispatchers.Default) {
        // 读原行：拿到 account_id / currency / fx_rate（这三列此前会因 UPDATE 漏列而**保持不变**，
        // 但在"部分列更新"写法下调用方无从知晓；改为显式带回，语义更清楚也不会丢）
        val old = db.cashFlowQueries.selectById(id).executeAsOneOrNull()
        val accountId = old?.account_id ?: db.cashFlowQueries.selectAll().executeAsList()
            .firstOrNull()?.account_id ?: "acc_a"
        val currency = old?.currency ?: Currency.CNY.code
        // 外币沿用原汇率快照；CNY 恒为 1（与 addCashFlow 的 CNY 路径一致）
        val fxRate = if (currency == Currency.CNY.code) 1.0 else (old?.fx_rate ?: 1.0)
        val signed = if (isDeposit) amountOrig else -amountOrig
        db.cashFlowQueries.updateFlow(
            flow_date = flowDate,
            type = if (isDeposit) "DEPOSIT" else "WITHDRAW",
            amount_orig = signed,
            // 本位币口径与 addCashFlow 完全一致：原币金额 × 汇率（汇率统一 4 位收敛）
            amount_base = signed * FxTable.roundRate(fxRate),
            note = note?.trim()?.ifEmpty { null },
            account_id = accountId,
            currency = currency,
            fx_rate = fxRate,
            id = id,
        )
    }

    /** 删除一笔出入金。 */
    suspend fun deleteCashFlow(id: String) = withContext(Dispatchers.Default) {
        db.cashFlowQueries.deleteById(id)
    }

    /** 出入金流水（倒序）。 */
    suspend fun cashFlows(): List<CashFlowRecord> = withContext(Dispatchers.Default) {
        db.cashFlowQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    /** 出入金笔数。 */
    suspend fun cashFlowCount(): Long = withContext(Dispatchers.Default) {
        db.cashFlowQueries.countAll().executeAsOne()
    }

    /**
     * `dateIso` **之后**的出入金（XIRR 现金流用）。
     *
     * 符号约定与 `AnalysisHolder` 的账户口径一致：存入是资金流入账户 → 对投资者是负现金流。
     *
     * ⚠️ 这里必须是**严格大于**（不能用 `>=`）：调用方传的是「曲线起算日」，而**起算日的资产快照
     * 是时点存量，已经包含了当天及之前的所有事件** —— 再算一遍当天那笔出入金就会**重复扣减**。
     * （真机实测踩到：起算日落在第一笔入金当天，50 万被重复计入，今年收益从 +4.5 万错成 −45 万。）
     */
    suspend fun cashFlowsSince(dateIso: String): List<CashFlow> = withContext(Dispatchers.Default) {
        val rates = latestRates()
        db.cashFlowQueries.selectAll().executeAsList()
            .filter { it.flow_date > dateIso }
            .map { f ->
                val cur = Currency.entries.firstOrNull { it.code == f.currency } ?: Currency.CNY
                val base = kotlin.math.abs(FxTable.convertWith(f.amount_orig, cur, Currency.CNY, rates))
                CashFlow(
                    dateIso = f.flow_date,
                    amount = if (f.type == "DEPOSIT") -base else base,
                )
            }
            .sortedBy { it.dateIso }
    }
}
