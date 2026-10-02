package com.stocknote.feature.state

import com.stocknote.data.AppContainer
import com.stocknote.data.db.StockNoteDb
import com.stocknote.data.net.QuoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Holder 失败必须"有反馈"的契约测试**（P1-35，老周 2026-10-02）。
 *
 * ## 钉住的不变量
 * 仓储调用失败时，用户必须能看到原因（`error` 有值）+ 运行日志里有记录，
 * **不能**表现为"点了没反应 / 打开是空表单"。
 *
 * 改前这四处都是**裸调** `scope.launch` 里的 suspend 仓储方法：
 *  - `TradeFormHolder.start()`（编辑态 / 预填态）：`repo.loadSecurityDetail(...)` →
 *    抛异常则协程体直接结束，表单停在**空默认值**且毫无提示；
 *  - `TradeFormHolder.pickOnline()` / `createAndPick()`、`PlanEditHolder.pickOnline()`：
 *    `security.findOrCreateSecurity(...)` → 点了候选/点了新增，**界面完全没反应**。
 *
 * ## 怎么造失败
 * 建好 schema 后 **`DROP TABLE security`** —— 上述调用都要读/写 `security` 表，必然抛
 * "no such table: security"。不改生产代码、不加注入点。
 *
 * ⚠️ 真内存库 + 真 `AppContainer`（schema 由本测试建）；全程不联网。
 */
class HolderFailureFeedbackTest {

    private fun newContainerWithBrokenSecurityTable(): AppContainer {
        val container = AppContainer()
        StockNoteDb.Schema.create(container.driver)
        // 关键：把 security 表弄没 —— 所有"读/建标的"的路径都会抛
        container.driver.execute(null, "DROP TABLE security", 0)
        return container
    }

    private fun scope() = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun tradeFormHolder(container: AppContainer) = TradeFormHolder(
        repo = container.portfolio,
        settings = container.settings,
        trade = container.trade,
        fx = container.fx,
        quote = container.quote,
        tag = container.tag,
        watch = container.watch,
        emotion = container.emotion,
        security = container.security,
        scope = scope(),
    )

    private fun planEditHolder(container: AppContainer) = PlanEditHolder(
        repo = container.portfolio,
        plan = container.plan,
        settings = container.settings,
        tag = container.tag,
        watch = container.watch,
        security = container.security,
        scope = scope(),
    )

    /**
     * 等 `error` 被写上（超时即失败：改前这些路径**永远不会**写 error，
     * 症状就是这里等到 `TimeoutCancellationException`）。
     */
    private suspend fun awaitError(text: String, read: () -> String?) {
        withTimeout(15_000) {
            while (read() == null) kotlinx.coroutines.delay(10)
        }
        val message = read()
        assertTrue(
            message != null && message.isNotEmpty(),
            "$text：必须给出可见的错误文案，不能静默（改前这里 error 恒为 null）",
        )
    }

    @Test
    fun `预填态读标的详情失败必须提示_而不是空白表单`() = runBlocking {
        val container = newContainerWithBrokenSecurityTable()
        val holder = tradeFormHolder(container)

        holder.start("sid-x", null)   // 从标的详情页点「新增一笔」的入口
        awaitError("预填态 start()") { holder.state.value.error }

        val err = holder.state.value.error.orEmpty()
        assertTrue(err.contains("读取标的详情失败"), "要给出**读库失败**的原因，而不是笼统的「找不到」：$err")
        assertTrue(!err.contains("找不到标的"), "读库失败不能被当成「确实没有这个标的」：$err")
        assertNull(holder.state.value.securityId, "失败时不应有选中的标的")
    }

    @Test
    fun `编辑态读标的详情失败必须提示_且不与_找不到这笔_混淆`() = runBlocking {
        val container = newContainerWithBrokenSecurityTable()
        val holder = tradeFormHolder(container)

        holder.start("sid-x", "tx-1")   // 编辑一笔已有交易
        awaitError("编辑态 start()") { holder.state.value.error }

        val err = holder.state.value.error.orEmpty()
        assertTrue(err.contains("读取标的详情失败"), "要给出读库失败的原因：$err")
        assertTrue(!err.contains("找不到要编辑的交易"), "读库失败不能说成「这笔交易不存在」：$err")
    }

    @Test
    fun `选中联网候选时建标的失败必须提示_而不是没反应`() = runBlocking {
        val container = newContainerWithBrokenSecurityTable()
        val holder = tradeFormHolder(container)

        holder.pickOnline(QuoteClient.SymbolHit("sh600519", "贵州茅台", "A股", "CNY"))
        awaitError("TradeFormHolder.pickOnline()") { holder.state.value.error }

        val err = holder.state.value.error.orEmpty()
        assertTrue(err.contains("无法建立标的"), "要说清是哪一步失败：$err")
        assertTrue(err.contains("贵州茅台"), "文案里要带上是哪只标的：$err")
        assertNull(holder.state.value.securityId, "失败时不应把标的选上")
    }

    @Test
    fun `计划页选中联网候选建标的失败也必须提示`() = runBlocking {
        val container = newContainerWithBrokenSecurityTable()
        val holder = planEditHolder(container)

        holder.pickOnline(QuoteClient.SymbolHit("hk00700", "腾讯控股", "港股", "HKD"))
        awaitError("PlanEditHolder.pickOnline()") { holder.state.value.error }

        val err = holder.state.value.error.orEmpty()
        assertTrue(err.contains("腾讯控股"), "文案里要带上是哪只标的：$err")
    }

    /** 走一遍"正常路径"作对照：表没坏时不该产生任何错误文案（避免护栏把正常路径也判红）。 */
    @Test
    fun `表正常时选候选不应产生错误文案`() = runBlocking {
        val container = AppContainer()
        StockNoteDb.Schema.create(container.driver)
        val holder = tradeFormHolder(container)

        holder.pickOnline(QuoteClient.SymbolHit("sh600519", "贵州茅台", "A股", "CNY"))
        withTimeout(15_000) {
            holder.state.first { it.securityId != null }
        }
        assertNull(holder.state.value.error, "正常路径不应有错误文案")
        assertTrue(holder.state.value.securityId != null)
    }
}
