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
 * 真机 UI 自动化测试（老周 2026-09-16：执行 9/14 就提出但一直被搁置的方案）。
 *
 * **为什么用 UiAutomator 而不是裸 adb 脚本**（9/14 踩过的三个坑）：
 *   1. `adb shell input text` **不支持中文**（备注是必填中文场景）；
 *   2. 带空格/句点的英文会被小米**拼音输入法吃掉**（只上屏第一段）；
 *   3. `keyevent` 连发被节流，键盘收起/展开时序不稳。
 * UiAutomator 的 `UiObject2.text = "..."` 走 **AccessibilityNodeInfo.ACTION_SET_TEXT**，
 * 直接设置文本、**完全绕开输入法** —— 中文/空格/标点都能输入，且不需要键盘交互。
 *
 * 运行方式：`gradle :androidApp:connectedDebugAndroidTest`（需真机/模拟器在线）。
 */
@RunWith(AndroidJUnit4::class)
class TradeFlowUiTest {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // 唤醒 + 上滑解锁（adb 自动化常见坑：屏幕熄灭时元素永远找不到）
        device.wakeUp()
        device.swipe(540, 2000, 540, 800, 200)
        Thread.sleep(1_500)
        // 从任意状态拉起 App 到前台。
        // ⚠️ 关键（老周 2026-09-16 排查）：不能用 targetContext.startActivity ——
        //    Android 10+ 的「后台 Activity 启动限制」会从 instrumentation 上下文
        //    静默拦截（所以之前"程序启动不了主 App"，只能手工点桌面图标）。
        //    改用 am start（等同前台启动，等同手工点），一定可靠。
        device.pressHome()
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        // ⚠️ 用例间隔离靠**各用例自己的 tearDown 退回首页**（见 SearchChineseUiTest）。
        //    不要在这里 am force-stop：instrumentation 环境下它会把测试进程一起干掉，
        //    表现为用例无堆栈地中途消失（2026-09-18 实测踩到）。
        device.executeShellCommand("am start -n $pkg/.MainActivity")
        // 等首屏（要拉行情，给足时间）
        device.wait(Until.hasObject(By.pkg(pkg)), 15_000)

        // ===== 锁屏检测（fail-fast）=====
        // 教训：设备锁屏时，UiAutomator 查询/点击都作用在锁屏上层 —— 轻则全部找不到，
        // 重则误点桌面图标把「设置」等系统应用打开（老周 2026-09-16 亲眼所见）。
        val km = InstrumentationRegistry.getInstrumentation()
            .targetContext.getSystemService(android.app.KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            throw IllegalStateException(
                "设备处于锁屏状态，无法安全执行 UI 测试。" +
                    "请把这台测试机锁屏改为『无』（设置→密码与安全），或手动解锁后保持亮屏再跑。",
            )
        }
        Thread.sleep(6_000)
        snap("00_after_launch")
    }

    @org.junit.After
    fun tearDown() {
        // 用例间隔离：退回首页，避免把本用例停留的页面（设置页等）带给下一个用例。
        // 教训（2026-09-18）：canOpenAppSettings 停在设置页 → 下一个用例 am start 后
        // 仍从设置页开始，「持仓」tab 找不到。不要用 force-stop（会中止测试进程）。
        runCatching {
            repeat(5) {
                device.pressBack()
                Thread.sleep(400)
            }
            Thread.sleep(800)
        }
    }

    /** 截图到 /sdcard/stocknote-test/（失败/关键步骤诊断用，跑完 adb pull） */
    private fun snap(name: String) {
        runCatching {
            val f = java.io.File("/sdcard/stocknote-test/$name.png")
            device.takeScreenshot(f)
        }
    }

    /** 打印当前前台包名（判断到底在哪个 App） */
    private fun frontPkg(): String =
        device.currentPackageName

    /** 把当前屏幕上所有可见文本拍下来（失败诊断用） */
    private fun screenDump(): String =
        device.findObjects(By.clazz("android.widget.TextView"))
            // it.text 可能为 null（Compose 的图标/空节点）→ 必须兜底，否则这里 NPE，
            // 表现为用例跑到一半、failure 里没有 message（2026-09-18 踩到）
            .joinToString(" | ") { it.text ?: "" }
            .ifEmpty { "(无可读文本节点)" }

    /** 冒烟：App 起得来 + 三个主 tab 在 */
    @Test
    fun appStarts_andThreeTabs() {
        // 统计页应有「总资产」字样（Hero 卡）
        val hero = device.wait(Until.findObject(By.textContains("总资产")), 20_000)
        assertTrue("统计页未渲染出『总资产』", hero != null)

        // 底部三个 tab（文字或图标）
        val hasStatistic = device.hasObject(By.textContains("统计"))
        val hasHolding = device.hasObject(By.textContains("持仓"))
        // ⚠️ 第五个 tab 于 2026-10-08 由「分析」改名为「看天看地」（内容也换了）
        val hasAnalysis = device.hasObject(By.textContains("看天看地"))
        assertTrue("底部导航缺少 tab（统计分析/持仓/看天看地）", hasStatistic && hasHolding && hasAnalysis)
    }

    /**
     * 打开 App 内设置页（用 contentDescription 精确定位）。
     *
     * ⚠️ 教训（老周 2026-09-16 亲眼所见）：用 `By.textContains("⚙")` 匹配会在错误界面
     * 误点图标，把 **MIUI 系统设置**打开 —— 无障碍语义（semantics）才是精确定位方式。
     * 断言也改用 App 特有文案「数据安全」（MIUI 设置里没有这个词）。
     */
    @Test
    fun canOpenAppSettings() {
        val gear = device.wait(Until.findObject(By.desc("打开设置")), 15_000)
        assertTrue("未找到 App 设置按钮（app_settings_button）", gear != null)
        gear!!.click()
        device.waitForIdle()
        Thread.sleep(3_000)

        // ⚠️ Compose 合并语义节点后，findObject(By.textContains) 对标题子串匹配不可靠
        //（数据安全在视口下方时永远匹配不到）；screenDump 快照能完整读到内容 → 用快照断言。
        val dump = screenDump()
        val opened = dump.contains("CSV")   // 纯 ASCII，无编码歧义；设置页 CSV 区块独有
        if (!opened) snap("22_settings_fail")
        assertTrue(
            "App 设置页未打开；前台=${frontPkg()}；现场: ${dump.take(300)}",
            opened,
        )
    }
}
