package com.stocknote.feature.state

import com.stocknote.core.model.Currency
import com.stocknote.core.model.Market
import com.stocknote.core.model.TradeSide
import com.stocknote.data.AppContainer
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.platform.todayIso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **`AppStateHolder.loadEquity` 的「失败也必须收敛」契约测试**（P1-21，老周 2026-10-02）。
 *
 * ## 钉住的不变量
 * `loadEquity()` 无论在哪一步炸（算曲线 / 取年内流水 / 写状态），
 * **结束时 `equityLoading` 必须为 false，且 `equityError` 必须有值**。
 *
 * 改前：整段里只有 `equityCurveCached` 包了 `runCatching`，而「今年收益」段的
 * `container.cash.cashFlowsSince(...)` 是**裸调** —— 抛异常 → 协程体直接结束 →
 * `equityLoading` 永久停在 true、`equityError` 仍是 null：
 * 统计页「最大回撤」一直转圈且**没有任何文案**，启动图放行条件也卡到 5 秒上限。
 *
 * ## 怎么把「YTD 那步失败」造出来（不改生产代码、不加注入点）
 *  1. 造一份**能算出曲线**的最小账本（标的 + 买入 + 收盘价），先跑一次 `loadEquity()` 让
 *     `PortfolioRepository` **把曲线写进它自己的缓存**；
 *  2. 然后把 `cash_flow` 表**删掉**（`DROP TABLE`）—— 这一步只影响后面读年内流水的 YTD 段，
 *     而第 2 次 `loadEquity()` 走的是**缓存**、根本不再读库，所以曲线那步一定成功；
 *  3. 于是异常**必然**落在 `cashFlowsSince` 上 —— 正是 P1-21 要兜的那一处。
 *
 * ⚠️ 用真内存库 + 真 `AppContainer`（`jvm` 侧未设 `stocknote.db.path` 时是内存库，schema 由本测试建）。
 * ⚠️ 全程**不联网**：先往加密 KV 写「资产曲线上次成功拉取日 = 今天」，让 `equityCurve` 走
 * `skipNetwork` 分支（凭据 key 见 `PortfolioRepository.CURVE_FETCH_DAY_KEY`）。
 */
class AppStateHolderEquityTest {

    /** `PortfolioRepository.CURVE_FETCH_DAY_KEY`（private，这里照抄字符串）。 */
    private val curveFetchDayKey = "curve_fetch_day"

    private fun newContainer(): AppContainer {
        val container = AppContainer()
        // AppContainer 只建仓储、**不建表**（jvm 测试分支返回的是空内存库）
        StockNoteDb.Schema.create(container.driver)
        return container
    }

    /** 造一份能算出资产曲线的最小账本：账户 + 标的 + 一笔买入 + 一段收盘价。 */
    private suspend fun seed(container: AppContainer) {
        container.db.accountQueries.upsert("acc-equity", "曲线测试账户", "CNY", 0.0)
        container.cash.addCashFlow("acc-equity", "2026-09-01", true, 100_000.0, Currency.CNY, 1.0)
        // ⚠️ 只用容器上**公开**的域仓储 —— SecurityRepository / TradeRepository 的构造器是
        // `internal`（`internal` 只对本 Gradle 模块可见，feature 模块的测试拿不到）。
        val security = container.security
            .findOrCreateSecurity("sh600519", "贵州茅台", Market.A_SHARE, Currency.CNY)
        container.trade.addTransaction(security.id, TradeSide.BUY, 100.0, 10.0, 0.0, "2026-09-01")

        // 收盘价：曲线要求「至少一只标的有历史收盘」，否则直接返回"算不出来"。
        // 多给几天，保证算出的点数 > 0。
        listOf(
            "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04",
            "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10",
        ).forEach { container.db.fxRateQueries.upsertClose("sh600519", it, 12.0) }

        // ⚠️ 关键：让曲线走「今天已拉过 → 跳过网络」分支，测试全程零网络请求。
        container.keyStore.putString(curveFetchDayKey, todayIso())
    }

    /** 等状态满足条件（超时即失败：改前那个"永久 loading"就表现为这里超时）。 */
    private suspend fun awaitTerminal(holder: AppStateHolder, what: String, predicate: (AppUiState) -> Boolean) {
        withTimeout(30_000) {
            holder.state.first(predicate)
        }
        // 只是为了在超时/失败信息里带上意图（withTimeout 会抛 TimeoutCancellationException）
        assertTrue(predicate(holder.state.value), what)
    }

    @Test
    fun `年内流水读取失败时_加载必须收敛并给出错误文案`() = runBlocking {
        val container = newContainer()
        seed(container)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val holder = AppStateHolder(container, scope)

        // 1) 先让 snapshot 就绪（loadEquity 的「今年收益」段依赖 state.snapshot；refreshQuotes=false = 只用本地）
        holder.load(refreshQuotes = false)
        awaitTerminal(holder, "snapshot 加载完成") { !it.loading }
        assertTrue(holder.state.value.snapshot != null, "前置：snapshot 必须就绪")

        // 2) 第一次算曲线 → 成功，并让 PortfolioRepository 缓存住这条曲线
        holder.loadEquity()
        awaitTerminal(holder, "第一次曲线算完") { it.equityPoints.isNotEmpty() || it.equityError != null }
        val afterFirst = holder.state.value
        assertTrue(
            afterFirst.equityPoints.isNotEmpty(),
            "前置：这份夹具必须能算出曲线，否则测不到 YTD 那一段（equityError=${afterFirst.equityError}）",
        )
        assertEquals(null, afterFirst.equityError, "前置：第一次应该成功")

        // 3) 弄坏「年内流水」这条路 —— 注意曲线已在缓存里，第 2 次不会再读库算曲线
        container.driver.execute(null, "DROP TABLE cash_flow", 0)

        // 4) 第二次：异常必然落在 cashFlowsSince 上（P1-21 要兜的那一处）
        holder.loadEquity()
        awaitTerminal(holder, "失败后必须给出 equityError（改前会永久 loading）") { it.equityError != null }

        val failed = holder.state.value
        assertFalse(failed.equityLoading, "⚠️ 兜底后 equityLoading 必须收敛为 false")
        val err = failed.equityError
        assertTrue(err != null && err.isNotEmpty(), "⚠️ 必须有给用户看的错误文案，不能静默")
        // 上一次算好的曲线不能被清掉（清掉会把界面从「有数据」打回「待数据」）
        assertTrue(failed.equityPoints.isNotEmpty(), "上次成功的曲线应保留")
    }

    @Test
    fun `曲线本身算不出来时也要收敛并写明原因`() = runBlocking {
        // 空账本：曲线返回的 reason 必须写进 equityError，且 loading 收敛（这条改前就是好的，作对照）
        val container = newContainer()
        val holder = AppStateHolder(container, CoroutineScope(Dispatchers.Default + SupervisorJob()))

        holder.loadEquity()
        awaitTerminal(holder, "空账本的曲线失败也要收敛") { !it.equityLoading && it.equityError != null }

        assertTrue(holder.state.value.equityError?.isNotEmpty() == true)
        assertTrue(holder.state.value.equityPoints.isEmpty())
    }
}
