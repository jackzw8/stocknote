package com.stocknote.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * UiAutomator 真机测试的公共助手（2026-09-18 从 TradeFormFlowUiTest 提炼）。
 *
 * 沉淀了三类真机才暴露的坑，见方法注释：
 *  1. 测试截图要写**应用外部目录**（Android 12 分区存储下 /sdcard/ 根目录无写权限）；
 *  2. Compose 输入框的 desc 锚点是 EditText 的**子**节点（层级与直觉相反），
 *     必须沿父链向上找 EditText；
 *  3. 搜索候选列表异步刷新 → 遍历 findObjects 要包 StaleObjectException 重试。
 */
object UiTestHelpers {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 应用外部目录下的测试截图目录（免权限，Android 12 可靠） */
    private val snapDir: java.io.File
        get() = java.io.File(targetContext.getExternalFilesDir(null), "stocknote-test")

    fun snap(name: String) {
        runCatching {
            snapDir.mkdirs()
            device.takeScreenshot(java.io.File(snapDir, "$name.png"))
        }
    }

    /** 当前屏幕全部 TextView 文本（带 stale 重试） */
    fun screenDump(): String = runCatching {
        repeat(3) {
            try {
                val dump = device.findObjects(By.clazz("android.widget.TextView"))
                    .joinToString(" | ") { it.text ?: "" }
                return dump.ifEmpty { "(无可读文本节点)" }
            } catch (_: StaleObjectException) {
                Thread.sleep(800)
            }
        }
        return "(多轮 stale，screenDump 放弃)"
    }.getOrDefault("(screenDump 失败)")

    /**
     * 从 desc 锚点定位真正的 EditText 节点。
     * ⚠️ 真实层级（dump 整树抓到）：Compose 把
     * `Modifier.semantics { contentDescription = label }` 映射成 **EditText 的子虚拟视图**
     *（EditText 是父、锚点是子）→ 必须沿父链向上找；向下钻是找不到的。
     */
    fun editTextUnder(desc: String): UiObject2? {
        repeat(5) {
            try {
                val anchor = device.findObject(By.desc(desc))
                if (anchor != null) {
                    var node: UiObject2? = anchor
                    var hops = 0
                    while (node != null && hops < 6) {
                        if (node.className == "android.widget.EditText") return node
                        node = node.parent
                        hops++
                    }
                }
            } catch (_: StaleObjectException) {
            }
            Thread.sleep(1_000)
        }
        return null
    }

    /** 读单个字段当前文本（null = 读不到/未渲染） */
    fun readField(desc: String): String? =
        runCatching { editTextUnder(desc)?.text }.getOrNull()

    /**
     * 边看边操作（老周 2026-09-18 要求）：写入后立刻回读该框本身，不对就重写。
     * 定位不到时先上滑滚动把字段滚进视口（LazyColumn 离屏 item 不渲染）。
     */
    fun setFieldChecked(desc: String, value: String, snapTag: String): Boolean {
        repeat(5) {
            var box = editTextUnder(desc)
            if (box == null) {
                device.swipe(540, 1900, 540, 900, 300)
                Thread.sleep(1_200)
                box = editTextUnder(desc)
            }
            if (box == null) return@repeat
            runCatching { box.text = value }
            device.waitForIdle()
            Thread.sleep(800)
            val now = readField(desc)
            snap("${snapTag}_$it")
            if (now == value) return true
        }
        return false
    }

    /** 带 stale 重试，按文本片段找第一个匹配的 TextView */
    fun findText(part: String): UiObject2? {
        repeat(6) {
            try {
                val hit = device.findObjects(By.clazz("android.widget.TextView"))
                    .firstOrNull { it.text?.contains(part) == true }
                if (hit != null) return hit
            } catch (_: StaleObjectException) {
            }
            Thread.sleep(1_500)
        }
        return null
    }

    /** 按文本精确点击（Compose 文本子节点未必可点，点其可见中心） */
    fun clickText(exact: String): Boolean {
        val n = device.findObject(By.text(exact)) ?: return false
        val b = n.visibleBounds
        device.click(b.centerX(), b.centerY())
        return true
    }
}
