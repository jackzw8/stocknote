package com.stocknote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 编辑交易完整流程真机用例（老周 2026-09-18 指定：修改流程）。
 *
 * 走一遍真实修改：
 * **持仓页 → 点进标的详情 → 左滑逐笔交易 → 点「✏️ 修改」→ 编辑表单
 * （标的名只读、按钮变「保存修改」）→ 改数量/备注 → 保存 → 回详情页回读验证。**
 *
 * 依赖：账本里已有一笔「贵州茅台」的买入（由 `TradeFormFlowUiTest` 写入；
 * 若已被删除用例清掉，本用例的进入详情步骤会失败并明确提示）。
 *
 * 编辑验证点（机器能判定的）：
 *   1. 编辑表单打开、标的只读锁定（标题「编辑交易」）；
 *   2. 保存按钮文案变为「保存修改」；
 *   3. 改备注后保存 → 详情页回读新备注（REQ-ACC-09 编辑落库生效）。
 */
@RunWith(AndroidJUnit4::class)
class TradeEditFlowUiTest {

    private lateinit var device: UiDevice
    private lateinit var pkg: String
    private val H = UiTestHelpers

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        device.wakeUp()
        device.swipe(540, 2000, 540, 800, 200)
        Thread.sleep(1_500)

        val km = InstrumentationRegistry.getInstrumentation()
            .targetContext.getSystemService(android.app.KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            throw IllegalStateException("设备处于锁屏状态，无法安全执行 UI 测试。请解锁并保持亮屏后重跑。")
        }

        device.pressHome()
        device.executeShellCommand("am start -n $pkg/.MainActivity")
        device.wait(Until.hasObject(By.pkg(pkg)), 15_000)
        Thread.sleep(2_000)

        // 页面状态归一化：前序用例可能停在设置页/记一笔页，am start 只恢复残留栈
        fun onHomePage(): Boolean =
            device.hasObject(By.textContains("总资产")) || device.hasObject(By.textContains("统计"))

        if (!onHomePage()) device.wait(Until.findObject(By.textContains("总资产")), 10_000)
        var backAttempts = 0
        while (!onHomePage() && backAttempts < 6) {
            device.pressBack()
            Thread.sleep(900)
            backAttempts++
        }
        assertTrue(
            "无法归位到首页；前台=${device.currentPackageName}；按了 $backAttempts 次返回；" +
                "现场: ${H.screenDump().take(240)}",
            onHomePage(),
        )
        device.wait(Until.findObject(By.textContains("总资产")), 25_000)
        Thread.sleep(2_000)
    }

    @org.junit.After
    fun tearDown() {
        runCatching {
            repeat(5) {
                device.pressBack()
                Thread.sleep(400)
            }
            Thread.sleep(800)
        }
    }

    @Test
    fun editTrade_changeNote_andVerifyPersisted() {
        // ===== ① 进持仓页 =====
        val holdingsTab = device.wait(Until.findObject(By.text("持仓")), 20_000)
        assertTrue("未找到「持仓」tab；现场: ${H.screenDump().take(240)}", holdingsTab != null)
        holdingsTab!!.click()
        device.waitForIdle()
        Thread.sleep(3_000)

        // 确认已切到持仓页：持仓页独有「新增交易」按钮（统计页没有）
        val ready = device.wait(Until.findObject(By.desc("新增交易")), 15_000)
        assertTrue("点击持仓 tab 后未出现持仓页标志（新增交易）；现场: ${H.screenDump().take(240)}", ready != null)

        // ===== ② 点进「贵州茅台」详情（精确匹配名称「贵州茅台」，避免「茅台」子串误命中）=====
        // ⚠️ 用「贵州茅台」整名而非「茅台」：统计页/首页其它位置可能也有「茅台」子串，
        // 2026-09-18 实测 findText("茅台") 点到别处进了记一笔页。
        val mt = device.findObject(By.text("贵州茅台"))
        H.snap("60_holdings")
        assertTrue(
            "持仓页未找到「贵州茅台」（可能前序用例未写入或被删除）；现场: ${H.screenDump().take(240)}",
            mt != null,
        )
        // 点整行：名称文本所在行整行可点（HoldingRow 的 onClick 绑在 StockRow 上）
        val mtb = mt!!.visibleBounds
        device.click(mtb.centerX(), mtb.centerY())
        device.waitForIdle()
        Thread.sleep(3_000)

        // 详情页标志：出现「交易记录」字样
        val detail = device.wait(Until.findObject(By.textContains("交易记录")), 15_000)
        assertTrue("未进入标的详情页；现场: ${H.screenDump().take(240)}", detail != null)
        H.snap("61_detail")

        // ===== ③ 左滑第一笔交易，露出「✏️ 修改」 =====
        // ⚠️ 滑动 y 必须对准**交易行本身**：写死 y=1450 正好落在「＋ 新增一笔（标的已预填）」
        // 大按钮上（2026-09-18 实测：横滑被识别成点击，跳进预填表单）。
        // 用交易行特有格式「股 @」（如「1 股 @ ¥1,257.12」）定位行 y。
        val tradeRow = H.findText("股 @")
        H.snap("62a_row")
        assertTrue(
            "详情页未找到交易行（「股 @」格式）；现场: ${H.screenDump().take(240)}",
            tradeRow != null,
        )

        var editBtn = H.findText("修改")
        var swipeCount = 0
        while (editBtn == null && swipeCount < 4) {
            val rowY = runCatching { tradeRow!!.visibleBounds.centerY() }.getOrDefault(2200)
            device.swipe(950, rowY, 250, rowY, 300)   // 从右往左滑，露出右侧按钮
            Thread.sleep(1_500)
            // 防御：滑错进了「记一笔（预填）」页（按钮=「保存这笔交易」）就退回重滑
            if (device.hasObject(By.text("保存这笔交易"))) {
                H.snap("62b_misfire")
                device.pressBack()
                Thread.sleep(1_500)
                swipeCount++
                continue
            }
            editBtn = H.findText("修改")
            swipeCount++
        }
        H.snap("62_after_swipe")
        assertTrue(
            "左滑后未露出「✏️ 修改」按钮（滑了 $swipeCount 次）；现场: ${H.screenDump().take(240)}",
            editBtn != null,
        )

        // 记下编辑前的备注（用于对比）；注意此刻是详情页，未必有备注文本，读不到就算了
        val noteBefore = H.findText("低吸")?.text

        editBtn!!.click()
        device.waitForIdle()
        Thread.sleep(3_000)

        // ===== ④ 断言编辑表单已打开、标的锁定、按钮变「保存修改」 =====
        val locked = device.wait(Until.findObject(By.textContains("标的（已锁定）")), 15_000)
        assertTrue("编辑表单未打开或标的未锁定；现场: ${H.screenDump().take(240)}", locked != null)
        // 标的只读：编辑态显示「贵州茅台」但不可改（提示文案在场即可判定锁定态）
        assertTrue(
            "编辑态未显示锁定提示；现场: ${H.screenDump().take(200)}",
            device.hasObject(By.textContains("不可更改")),
        )
        H.snap("63_edit_form")

        // ===== ⑤ 改备注（先改备注、再改数量，避免数量触发下移挤掉备注框） =====
        val newNote = "自动化测试：已编辑，改为网格策略加仓"
        assertTrue(
            "编辑态备注写入失败，当前=${H.readField("交易理由 / 备注（必填）")}；现场: ${H.screenDump().take(200)}",
            H.setFieldChecked("交易理由 / 备注（必填）", newNote, "64_note_set"),
        )
        // 数量改成 2（原来 1），验证编辑确实落库
        assertTrue(
            "编辑态数量写入失败，当前=${H.readField("数量（股）")}；现场: ${H.screenDump().take(200)}",
            H.setFieldChecked("数量（股）", "2", "65_qty_set"),
        )
        H.snap("66_edited")

        // ===== ⑥ 保存修改（按钮文案应为「保存修改」） =====
        var save = device.wait(Until.findObject(By.text("保存修改")), 3_000)
        var scroll = 0
        while (save == null && scroll < 5) {
            device.swipe(540, 1900, 540, 900, 300)
            Thread.sleep(1_200)
            save = device.findObject(By.text("保存修改"))
            scroll++
        }
        H.snap("67_before_save")
        assertTrue(
            "未找到「保存修改」按钮（滚动 $scroll 次后）；现场: ${H.screenDump().take(200)}",
            save != null,
        )
        save!!.click()
        device.waitForIdle()
        Thread.sleep(5_000)

        // 校验失败会弹「无法保存」
        if (device.hasObject(By.text("无法保存"))) {
            H.snap("68_save_rejected")
            val reason = H.screenDump()
            runCatching { device.findObject(By.text("知道了"))?.click() }
            fail("编辑保存被校验拦截：$reason")
        }

        // ===== ⑦ 已离开表单、回到详情页 =====
        val stillInForm = device.hasObject(By.textContains("标的（已锁定）"))
        H.snap("69_after_save")
        assertTrue("保存后仍停留在编辑表单", !stillInForm)

        // 回详情页：等待「交易记录」回来
        device.wait(Until.findObject(By.textContains("交易记录")), 15_000)
        Thread.sleep(2_000)

        // ===== ⑧ 回读验证：新备注落库、数量变 2 =====
        // ⚠️ 交易记录列表在「合并持仓」大区块下方，可能被顶到屏幕外，
        // 需要向下滚动才能读到新备注（2026-09-18 实测：没滚动时 findText 找不到）。
        var noteAfter = H.findText("网格策略")
        var verifyScroll = 0
        while (noteAfter == null && verifyScroll < 6) {
            device.swipe(540, 1800, 540, 600, 300)   // 向下滚 = 内容上移
            Thread.sleep(1_200)
            noteAfter = H.findText("网格策略")
            verifyScroll++
        }
        H.snap("70_verify")
        assertTrue(
            "编辑后的备注未在详情页出现（滚动 $verifyScroll 次后；编辑未落库？）；" +
                "现场: ${H.screenDump().take(240)}",
            noteAfter != null,
        )
        // 数量 2：详情页逐笔行显示「2 股 @ ¥...」（精确匹配单字符 "2" 永远失败——
        // 实际是「2 股 @ ¥1,257.12」一整行文本；2026-09-18 已修正断言）
        val qty2 = H.findText("2 股 @")
        assertTrue(
            "编辑后的数量未反映在详情页（未找到「2 股 @」）；现场: ${H.screenDump().take(240)}",
            qty2 != null,
        )
    }

    companion object {
        private const val KEYWORD_CN = "贵州茅台"
    }
}
