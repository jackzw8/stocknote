package com.stocknote.app

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 自选股「长按拖拽排序」真机测试（老周 2026-09-23：排序还是有问题，要求真机自测）。
 *
 * **adb shell input 的实测结论（2026-09-23 修正）**：
 *  - ✅ `input motionevent` 序列（`DOWN x y` → sleep 1.3s → `MOVE x y`×N → `UP x y`）**能触发**
 *    Compose 的长按拖拽 —— 本次拖拽 bug 就是用它 + `println`/logcat 定位的，
 *    比构建并安装本测试包快得多，日常排查优先用它；
 *    （注意：它注入的 positionChange 会重复计一次，dy 偏大，验逻辑够用、验精确数值不够。）
 *  - ❌ `input swipe` 不行：匀速滑动，前 500ms 位移就超过 touchSlop → 长按判定失败，会被当成列表滚动。
 *  - ⚠️ 本测试包受 MIUI 限制暂时装不上（`INSTALL_FAILED_USER_RESTRICTED`，新包名要设备上点确认），
 *    且 UTP 在安装失败后会**卸载 App**（数据全丢）——运行前请确认设备允许安装 test 包。
 *    保留此文件用于将来做**断言式**回归（现在的手动验证是无断言的）。
 *
 * 正解（若能装）：`uiAutomation.injectInputEvent` 手工构造**同一 downTime** 的
 * DOWN → 静止 800ms → MOVE×N → UP 序列 —— 与真实手指等价。
 *
 * 运行：`gradle :androidApp:connectedDebugAndroidTest --tests "*WatchlistDragUiTest*"`
 */
@RunWith(AndroidJUnit4::class)
class WatchlistDragUiTest {

    private lateinit var device: UiDevice
    private val inst get() = InstrumentationRegistry.getInstrumentation()

    /** 演示自选股的名字（用于按屏幕顺序还原列表） */
    private val names = listOf(
        "贵州茅台", "腾讯控股", "宁德时代", "五粮液", "沪深300ETF", "苹果", "标普500", "小米集团",
    )

    @Before
    fun setUp() {
        device = UiDevice.getInstance(inst)
        device.wakeUp()
        device.pressHome()
        val pkg = inst.targetContext.packageName
        // 用 am start（不是 targetContext.startActivity）：Android 10+ 后台启动限制会静默拦截
        device.executeShellCommand("am start -n $pkg/.MainActivity")
        device.wait(Until.hasObject(By.pkg(pkg)), 15_000)
        SystemClock.sleep(3_000)
    }

    /** 当前自选列表的**视觉顺序**（按文本中心 y 升序） */
    private fun orderedRows(): List<Pair<String, Int>> =
        names.mapNotNull { n ->
            device.findObject(By.textContains(n))?.let { n to it.visibleBounds.exactCenterY().toInt() }
        }.sortedBy { it.second }

    private fun inject(ev: MotionEvent) {
        inst.uiAutomation.injectInputEvent(ev, true)
    }

    /**
     * 真实手指等价物：按住 [holdMs] 不动（触发长按）→ 用 [steps] 步移动到终点 → 抬手。
     * ⚠️ 所有事件的 downTime 必须相同，否则系统不认作同一手势。
     */
    private fun longPressDrag(
        x1: Int, y1: Int, x2: Int, y2: Int,
        holdMs: Long = 800, moveMs: Long = 600, steps: Int = 15,
    ) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), 0))
        SystemClock.sleep(holdMs)
        for (i in 1..steps) {
            val t = SystemClock.uptimeMillis()
            val f = i.toFloat() / steps
            inject(
                MotionEvent.obtain(
                    down, t, MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, 0,
                ),
            )
            SystemClock.sleep(moveMs / steps)
        }
        val up = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), 0))
    }

    /** 进「自选管理」：底部持仓 tab → 右上 ⭐ */
    private fun openWatchlist() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.click((w * 0.375).toInt(), (h * 0.928).toInt())
        SystemClock.sleep(2_000)
        device.findObject(By.desc("自选股"))?.click()
        SystemClock.sleep(2_500)
    }

    /** 场景 1：全部视图下，把第 1 行往下拖一行（期望与第 2 行交换） */
    @Test
    fun dragFirstRowDownOneStep() {
        openWatchlist()
        UiTestHelpers.snap("drag_01_entered")

        val before = orderedRows()
        println("[[DRAG]] 进入自选管理，顺序 = $before")
        if (before.size < 2) {
            println("[[DRAG]] 自选少于 2 行，无法测试")
            return
        }
        val step = before[1].second - before[0].second
        val (fx, fy) = before[0].second.let { device.findObject(By.textContains(before[0].first))!!.visibleBounds.let { b -> b.exactCenterX().toInt() to b.exactCenterY().toInt() } }
        println("[[DRAG]] 行步进 = $step px，从 (${fx},${fy}) 拖到 (${fx},${fy + step})")

        longPressDrag(fx, fy, fx, fy + step)
        SystemClock.sleep(1_500)
        UiTestHelpers.snap("drag_02_after")

        val after = orderedRows()
        println("[[DRAG]] 拖后顺序 = $after")
        println(
            "[[DRAG]] 结论 = " + if (after.map { it.first }.take(2) == before.map { it.first }.take(2)) {
                "❌ 顺序没变（拖动未生效）"
            } else {
                "✅ 前两行已交换：" + after.map { it.first }.take(3)
            },
        )
    }

    /** 场景 2：分组视图（「重点」）下拖动 —— 过滤视图里索引与全量列表不一致，重点看这里 */
    @Test
    fun dragInsideGroupFilter() {
        openWatchlist()
        // 点「重点」分组 chip
        val chip = device.findObject(By.textContains("重点"))
        println("[[DRAG]] 分组 chip = ${chip?.visibleBounds}")
        chip?.click()
        SystemClock.sleep(1_500)
        UiTestHelpers.snap("drag_10_group")

        val before = orderedRows()
        println("[[DRAG]] 分组内顺序 = $before")
        if (before.size < 2) {
            println("[[DRAG]] 分组内少于 2 行，无法测试")
            return
        }
        val step = before[1].second - before[0].second
        val b = device.findObject(By.textContains(before[0].first))!!.visibleBounds
        longPressDrag(b.exactCenterX().toInt(), b.exactCenterY().toInt(), b.exactCenterX().toInt(), b.exactCenterY().toInt() + step)
        SystemClock.sleep(1_500)
        UiTestHelpers.snap("drag_11_group_after")

        val after = orderedRows()
        println("[[DRAG]] 分组拖后顺序 = $after")
        println(
            "[[DRAG]] 结论 = " + if (after.map { it.first }.take(2) == before.map { it.first }.take(2)) {
                "❌ 分组内顺序没变（拖动未生效）"
            } else {
                "✅ 分组内前两行已交换：" + after.map { it.first }.take(3)
            },
        )
    }
}
