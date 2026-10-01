package com.stocknote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 中文搜索真机用例（老周 2026-09-18 指定的验收动作）：
 * **打开记一笔 → 在标的搜索框输入中文 → 点搜索**。
 *
 * ### 核心验证点：中文能不能“打”进输入框
 * - `adb shell input text` **不支持中文**（这是裸 adb 脚本绕不过去的死穴）；
 * - 小米拼音输入法还会把带空格/句点的英文吃掉，只上屏第一段；
 * - UiAutomator 的 `UiObject2.text = "贵州茅台"` 走 `AccessibilityNodeInfo.ACTION_SET_TEXT`，
 *   **直接设置文本、完全绕开输入法** —— 中文 / 空格 / 标点都能进，且不需要键盘交互。
 *
 * 本用例把这条能力钉死：输入后回读输入框内容，断言中文确实上屏；再点搜索，断言出候选。
 *
 * 运行：`gradle :androidApp:connectedDebugAndroidTest`（需真机在线、且**未锁屏**）。
 */
@RunWith(AndroidJUnit4::class)
class SearchChineseUiTest {

    private lateinit var device: UiDevice
    private lateinit var pkg: String

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        device.wakeUp()
        device.swipe(540, 2000, 540, 800, 200)
        Thread.sleep(1_500)

        // 锁屏时 UiAutomator 会作用在锁屏上层，轻则找不到元素、重则误点系统应用 → fail-fast
        val km = InstrumentationRegistry.getInstrumentation()
            .targetContext.getSystemService(android.app.KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            throw IllegalStateException(
                "设备处于锁屏状态，无法安全执行 UI 测试。请解锁并保持亮屏后重跑。",
            )
        }

        // am start 等同手工点图标（绕过 Android 10+ 后台启动限制）。
        // 不做 force-stop —— 它会中止测试进程；隔离改由 tearDown 退回首页完成。
        device.pressHome()
        device.executeShellCommand("am start -n $pkg/.MainActivity")
        device.wait(Until.hasObject(By.pkg(pkg)), 15_000)
        Thread.sleep(6_000)
    }

    private fun snap(name: String) {
        runCatching { device.takeScreenshot(java.io.File("/sdcard/stocknote-test/$name.png")) }
    }

    /** 当前屏幕所有可见文本（失败诊断用）—— 任何异常都不该让用例因诊断代码而失败 */
    private fun screenDump(): String = runCatching {
        device.findObjects(By.clazz("android.widget.TextView"))
            // it.text 可能为 null（Compose 的图标/空节点）→ 必须兜底，否则这里 NPE，
            // 表现为用例跑到一半、failure 里没有 message（2026-09-18 踩到）
            .joinToString(" | ") { it.text ?: "" }
            .ifEmpty { "(无可读文本节点)" }
    }.getOrDefault("(screenDump 失败)")

    @Test
    fun typeChineseInSearchBox_andSearch() {
        // ===== ① 进持仓页（记一笔入口在持仓页头部）=====
        // ⚠️ 不能用 `.clickable(true)`：底部 tab 的可点击节点是**父容器**，文本子节点本身
        //    clickable=false（实测这样匹配永远为空）。直接按文本找、点它的中心即可 ——
        //    启动时在统计页，页面上只有 tab 这一个「持仓」（页面标题是「统计」），不会歧义。
        val holdingsTab = device.wait(Until.findObject(By.text("持仓")), 15_000)
        assertTrue("未找到可点击的「持仓」tab", holdingsTab != null)
        holdingsTab!!.click()
        device.waitForIdle()
        Thread.sleep(3_000)

        // ===== ② 点「＋ 记一笔」=====
        val add = device.wait(Until.findObject(By.desc("新增交易")), 15_000)
        assertTrue(
            "未找到记一笔入口（add_trade_button）；现场: ${screenDump().take(200)}",
            add != null,
        )
        add!!.click()
        device.waitForIdle()
        Thread.sleep(3_000)

        // 确认真的进了记一笔表单
        val form = device.wait(Until.findObject(By.textContains("选择标的")), 15_000)
        assertTrue(
            "记一笔表单未打开；前台=${device.currentPackageName}；现场: ${screenDump().take(200)}",
            form != null,
        )

        // ===== ③ 在搜索框输入中文（本用例的核心）=====
        val box = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 15_000)
        assertTrue("未找到标的搜索框", box != null)
        box!!.text = KEYWORD_CN            // ← ACTION_SET_TEXT，绕开输入法
        device.waitForIdle()
        Thread.sleep(800)

        // 回读：确认中文真的上屏了
        val typed = device.findObject(By.clazz("android.widget.EditText"))?.text.orEmpty()
        snap("30_chinese_typed")
        assertTrue(
            "搜索框未成功输入中文，实际内容=[$typed]",
            typed.contains("茅台"),
        )

        // ===== ④ 点搜索 =====
        val btn = device.wait(Until.findObject(By.desc("搜索标的")), 15_000)
        assertTrue(
            "未找到搜索按钮（security_search_button）；现场: ${screenDump().take(200)}",
            btn != null,
        )
        btn!!.click()
        device.waitForIdle()
        Thread.sleep(5_000)     // 等本地检索 + 联网候选

        // ===== ⑤ 断言出候选（本地「贵州茅台」或联网命中）=====
        val dump = screenDump()
        snap("31_search_result")
        assertTrue(
            "中文搜索未出现候选；现场: ${dump.take(300)}",
            dump.contains("茅台"),
        )
    }

    @org.junit.After
    fun tearDown() {
        // 用例间隔离：退回**统计页首页**，别把 App 停在记一笔页 ——
        // 否则下一个用例 am start 只是把残留页面栈带回前台，统计页断言必然失败。
        runCatching {
            repeat(5) {
                device.pressBack()
                Thread.sleep(400)
            }
            Thread.sleep(800)
        }
    }

    companion object {
        /** 中文关键字：故意用中文，钉住「输入法无关的文本设置」这条能力 */
        private const val KEYWORD_CN = "贵州茅台"
    }
}
