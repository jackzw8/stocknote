package com.stocknote.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 记一笔完整流程真机用例（老周 2026-09-18 指定：第 1 个）。
 *
 * 走一遍真实录入：
 * **持仓页 → 新增交易 → 中文搜索标的 → 选中 → 填数量/费率 → 填中文备注 → 保存**，
 * 并校验两件机器能判定的事：
 *   1. 成交价被**自动填充**（行情能力）；
 *   2. 保存后**可用现金减少**（买入扣款 + 现金联动生效）。
 *
 * ⚠️ 本用例会往账本**真实写入 1 股**买入（1 股影响最小）。
 *    清理方式：见 `TradeDeleteUiTest`（第 2 个用例做删除流程，正好清掉这笔）。
 *
 * 中文输入说明：`UiObject2.text = "..."` 走 ACTION_SET_TEXT 绕开输入法，
 * 所以中文备注能正常填进去（adb shell input text 做不到）。
 */
@RunWith(AndroidJUnit4::class)
class TradeFormFlowUiTest {

    private lateinit var device: UiDevice
    private lateinit var pkg: String

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

        // 冷启动：am start 等同手工点图标（绕过 Android 10+ 后台启动限制）
        // 不做 force-stop —— 它会中止测试进程（2026-09-18 实测）
        device.pressHome()
        device.executeShellCommand("am start -n $pkg/.MainActivity")
        device.wait(Until.hasObject(By.pkg(pkg)), 15_000)
        Thread.sleep(2_000)

        // ===== 页面状态归一化（2026-09-18 关键修复）=====
        // 前序用例（如 canOpenAppSettings）会把 App 留在 设置页 等二级页：
        // am start 只把 App 带到前台，**不会重置导航栈**，直接找「持仓」tab 必失败。
        // 首页标志：Hero「总资产」或底部「统计」tab，二者任一在场即认为已归位。
        fun onHomePage(): Boolean =
            device.hasObject(By.textContains("总资产")) ||
                device.hasObject(By.textContains("统计"))

        // 先短等首屏渲染（正常冷启动 2~8 秒内就绪，不必干等 25 秒）
        if (!onHomePage()) device.wait(Until.findObject(By.textContains("总资产")), 10_000)

        // 仍不在首页 → 逐次 pressBack 退回（最多 6 次：设置/记一笔/详情最深也就 2~3 层）
        var backAttempts = 0
        while (!onHomePage() && backAttempts < 6) {
            device.pressBack()
            Thread.sleep(900)
            backAttempts++
        }
        assertTrue(
            "无法归位到首页；前台=${device.currentPackageName}；按了 $backAttempts 次返回；" +
                "现场: ${screenDump().take(240)}",
            onHomePage(),
        )

        // 首页真正就绪：跑在其它用例之后时行情/渲染更慢，等到 Hero「总资产」出现再继续，
        // 避免「元素明明在屏幕上却找不到」
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

    private fun snap(name: String) {
        runCatching {
            // ⚠️ 不能写 /sdcard/stocknote-test/：Android 12 分区存储下 instrumentation 进程
            // 无外部根目录写权限（2026-09-18 实测：目录压根建不出来，runCatching 静默吞掉）。
            // 改写应用自己的外部目录 /sdcard/Android/data/com.stocknote.app/files/，免权限。
            val dir = java.io.File(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getExternalFilesDir(null),
                "stocknote-test",
            )
            dir.mkdirs()
            device.takeScreenshot(java.io.File(dir, "$name.png"))
        }
    }

    private fun screenDump(): String = runCatching {
        repeat(3) { attempt ->
            try {
                val dump = device.findObjects(By.clazz("android.widget.TextView"))
                    .joinToString(" | ") { it.text ?: "" }
                return dump.ifEmpty { "(无可读文本节点)" }
            } catch (_: StaleObjectException) {
                Thread.sleep(800)   // 列表刷新中 → 重查（2026-09-18 实测：迭代途中节点失效）
            }
        }
        return "(多轮 stale，screenDump 放弃)"
    }.getOrDefault("(screenDump 失败)")

    /** 从「可用现金」标签往上找，取其所在行里的 ¥ 金额（Compose 行数不定，多试几层） */
    private fun cashText(): String? {
        var node: UiObject2 = device.findObject(By.text("可用现金")) ?: return null
        repeat(4) {
            val parent = node.parent ?: return null
            node = parent
            val yen = parent.children
                .mapNotNull { it.text }
                .firstOrNull { it.startsWith("¥") && it.length > 4 }
            if (yen != null) return yen
        }
        return null
    }

    private fun money(s: String?): Double? =
        s?.removePrefix("¥")?.replace(",", "")?.trim()?.toDoubleOrNull()

    /**
     * 带重试的候选查找：搜索结果是「本地秒回 + 联网异步补」两段式，列表会被刷新，
     * findObjects 拿到的节点在读 text 途中可能被回收 → StaleObjectException（2026-09-18 实测）。
     * 每轮**重新查询**、不死握旧引用；找到即返回。
     */
    private fun findCandidateByText(part: String): UiObject2? {
        repeat(6) {
            try {
                val hit = device.findObjects(By.clazz("android.widget.TextView"))
                    .firstOrNull { it.text?.contains(part) == true }
                if (hit != null) return hit
            } catch (_: StaleObjectException) {
                // 列表刚被刷新 → 稍等重查
            }
            Thread.sleep(1_500)
        }
        return null
    }

    /** 带重试读取全部输入框文本（表单校验/重组也可能让节点中途失效） */
    private fun editTextTexts(): List<String> {
        repeat(5) {
            try {
                return device.findObjects(By.clazz("android.widget.EditText"))
                    .mapNotNull { it.text }
            } catch (_: StaleObjectException) {
                // 重查
            }
            Thread.sleep(1_000)
        }
        return emptyList()
    }

    /**
     * 从 LabeledField 的 desc 锚点定位真正的 EditText 节点并读写其文本。
     * ⚠️ 真实层级（2026-09-18 用 dumpTree 抓到）：Compose 把
     * `Modifier.semantics { contentDescription = label }` 映射成 **EditText 的子虚拟视图**，
     * 即「EditText 是父、锚点是子」—— 与直觉相反！
     *   EditText text=1257.12
     *     └─ View desc=成交价（CNY） text=null   ← By.desc 命中的是它
     * 所以必须**沿父链向上**找 EditText；向下钻是找不到的（曾因此读了 12 轮空字符串）。
     */
    private fun editTextUnder(desc: String): UiObject2? {
        repeat(5) {
            try {
                val anchor = device.findObject(By.desc(desc))
                if (anchor != null) {
                    // 自身 → 沿父链向上找 EditText（最多 6 层）
                    var node: UiObject2? = anchor
                    var hops = 0
                    while (node != null && hops < 6) {
                        if (node.className == "android.widget.EditText") return node
                        node = node.parent
                        hops++
                    }
                    // 兜底：万一某类字段结构相反，再向子树找一次
                    editTextIn(anchor)?.let { return it }
                }
            } catch (_: StaleObjectException) {
                // 重查
            }
            Thread.sleep(1_000)
        }
        return null
    }

    /** 深度优先在锚点子树里找 EditText 类节点 */
    private fun editTextIn(node: UiObject2): UiObject2? {
        if (node.className == "android.widget.EditText") return node
        for (child in node.children) {
            editTextIn(child)?.let { return it }
        }
        return null
    }

    /** 读单个字段当前文本（经锚点定位 EditText；读不到返回 null） */
    private fun readField(desc: String): String? =
        runCatching { editTextUnder(desc)?.text }.getOrNull()

    /**
     * 边看边操作（老周 2026-09-18 要求）：写入后**立刻回读这个框本身**确认，
     * 不对就重新定位重写，每轮截图留痕。
     * 定位不到时先向上滚动把字段滚进可视区（LazyColumn 离屏 item 不渲染——
     * 备注框在交易日期下方、屏幕底边以下，一开始根本不存在于节点树里）。
     * 教训：SET_TEXT 在表单重组后可能打偏到别的输入框 —— 实测备注的中文
     * 被写进了手续费率框（回读却因为「任一框里有低吸二字」而误判成功）。
     */
    private fun setFieldChecked(desc: String, value: String, snapTag: String): Boolean {
        repeat(5) { attempt ->
            var box = editTextUnder(desc)
            if (box == null) {
                // 字段没渲染 → 上滑滚动，让它进入视口
                device.swipe(540, 1900, 540, 900, 300)
                Thread.sleep(1_200)
                box = editTextUnder(desc)
            }
            if (box == null) return@repeat
            runCatching { box.text = value }
            device.waitForIdle()
            Thread.sleep(800)
            val now = readField(desc)
            snap("${snapTag}_$attempt")
            if (now == value) return true
        }
        return false
    }

    /**
     * 失败诊断：把当前可访问性节点树整棵拍成文本（class/desc/text/bounds），
     * 写到应用外部目录 /sdcard/Android/data/com.stocknote.app/files/stocknote-test/<name>.txt。
     * 2026-09-18 加：成交价读到空但截图显示已填 → 需要看锚点与 EditText 的真实层级。
     */
    private fun dumpTree(name: String) {
        runCatching {
            val dir = java.io.File(
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getExternalFilesDir(null),
                "stocknote-test",
            )
            dir.mkdirs()
            val sb = StringBuilder()
            // 从任一已知文本节点向上爬到窗口根，再整树递归
            var root: UiObject2? = device.findObject(By.textContains("交易内容"))
                ?: device.findObject(By.textContains("记一笔"))
                ?: device.findObjects(By.clazz("android.view.View")).firstOrNull()
            var hops = 0
            while (root?.parent != null && hops < 40) {
                root = root.parent
                hops++
            }
            if (root == null) {
                sb.append("(找不到根节点)\n")
            } else {
                fun walk(n: UiObject2, depth: Int) {
                    val info = runCatching {
                        repeat(depth) { sb.append("  ") }
                        sb.append(n.className)
                            .append("  desc=").append(n.contentDescription)
                            .append("  text=").append(n.text)
                            .append("  bounds=").append(n.visibleBounds)
                            .append('\n')
                    }
                    if (info.isFailure) sb.append("(节点读取失败)\n")
                    runCatching { for (c in n.children) walk(c, depth + 1) }
                }
                walk(root, 0)
            }
            java.io.File(dir, "$name.txt").writeText(sb.toString())
        }
    }

    @Test
    fun addBuyTrade_chineseNote_andCashDecreases() {
        // ===== ① 进持仓页，记下当前可用现金 =====
        val holdingsTab = device.wait(Until.findObject(By.text("持仓")), 20_000)
        if (holdingsTab == null) snap("39_no_holdings_tab")
        assertTrue(
            "未找到「持仓」tab；前台=${device.currentPackageName}；现场: ${screenDump().take(240)}",
            holdingsTab != null,
        )
        holdingsTab!!.click()
        device.waitForIdle()
        Thread.sleep(3_000)

        val cashBefore = money(cashText())
        assertTrue(
            "读不到可用现金（before）；现场: ${screenDump().take(200)}",
            cashBefore != null,
        )

        // ===== ② 点「新增交易」 =====
        val add = device.wait(Until.findObject(By.desc("新增交易")), 15_000)
        assertTrue("未找到记一笔入口（新增交易）", add != null)
        add!!.click()
        device.waitForIdle()
        Thread.sleep(3_000)

        val form = device.wait(Until.findObject(By.textContains("选择标的")), 15_000)
        assertTrue("记一笔表单未打开", form != null)

        // ===== ③ 中文搜索并选中标的 =====
        // 搜索框=「搜索」标签的 LabeledField；SET_TEXT 不保证成功（2026-09-18 实测某轮静默失败
        // 导致空词搜索、无候选）→ 设完**回读校验**，不成功重设，最多 5 轮
        var searchBox: UiObject2? = null
        var setSearchTries = 0
        while (setSearchTries < 5) {
            searchBox = editTextUnder("搜索")
                ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
            if (searchBox == null) break
            runCatching { searchBox.text = KEYWORD_CN }
            device.waitForIdle()
            Thread.sleep(600)
            val current = runCatching { searchBox.text }.getOrDefault("")
            if (current.contains(KEYWORD_CN)) break
            setSearchTries++
        }
        assertTrue("未找到标的搜索框", searchBox != null)
        snap("39b_search_typed")
        assertTrue(
            "中文没输进搜索框（试了 $setSearchTries 轮），当前内容=[${runCatching { searchBox!!.text }.getOrDefault("")}]",
            runCatching { searchBox!!.text }.getOrDefault("").contains(KEYWORD_CN),
        )

        val searchBtn = device.wait(Until.findObject(By.desc("搜索标的")), 15_000)
        assertTrue("未找到搜索按钮（搜索标的）", searchBtn != null)
        searchBtn!!.click()
        device.waitForIdle()
        Thread.sleep(4_000)   // 等本地检索 + 联网候选

        // 选中第一个包含关键字的候选（带 stale 重试）；点击用**坐标**（TextView 本身
        // 未必可点，点它的可见中心等价于手工点列表项）
        val candidate = findCandidateByText(CANDIDATE_CN)
        snap("40_before_pick")
        if (candidate == null) {
            dumpTree("46_no_cand_tree")   // 无候选 → 整树 dump 看搜索到底进了什么词/什么页面
        }
        assertTrue(
            "中文搜索未出现候选；现场: ${screenDump().take(240)}",
            candidate != null,
        )
        val cb = candidate!!.visibleBounds
        device.click(cb.centerX(), cb.centerY())
        device.waitForIdle()
        Thread.sleep(4_000)   // 选中后会自动拉行情填成交价

        // ===== ④ 核心断言 1：成交价被自动填充 =====
        // 行情填充走网络，可能有延迟 → 轮询等待最多 ~18 秒；每轮留痕，
        // 既能区分「点击候选没生效」也能区分「行情拉取慢/失败」
        var priceText = ""
        var priceAttempts = 0
        while (priceAttempts < 12) {
            priceText = runCatching {
                editTextUnder("成交价（CNY）")?.text?.trim().orEmpty()
            }.getOrDefault("")
            if (priceText.toDoubleOrNull()?.let { it > 0.0 } == true) break
            snap("43_price_wait_$priceAttempts")
            priceAttempts++
            Thread.sleep(1_500)
        }
        snap("44_price_final")
        if (priceText.toDoubleOrNull()?.let { it > 0.0 } != true) {
            dumpTree("45_tree")   // 读不到价格 → 整树 dump，定位锚点与 EditText 真实层级
        }
        assertTrue(
            "成交价未被自动填充（预期行情价），等待 $priceAttempts 轮，实际=[$priceText]",
            priceText.toDoubleOrNull()?.let { it > 0.0 } == true,
        )

        // ===== ⑤ 填数量与中文备注（每个框写完立即回读该框本身，防打偏）=====
        // ⚠️ 顺序关键：必须**先备注、后数量**！填数量的瞬间表单会多出「手续费金额」行，
        // 下方内容整体下移，贴着屏幕底边的备注框随即被 LazyColumn 释放（离屏销毁）——
        // 2026-09-18 实测：此时找备注框要么找不到、要么兜底错抓费率框（老周亲眼看见
        // 备注中文进了费率框）。先写备注就没有这个问题。
        // 记下手续费率初始值（表单按设置预填：A股 2.5 / 港股 5），保存前必须保持不变
        val feeInitial = readField("手续费率（万分之，必填）")

        assertTrue(
            "备注中文写入失败（4 轮回读都不对），当前=${readField("交易理由 / 备注（必填）")}，" +
                "现场: ${screenDump().take(200)}",
            setFieldChecked("交易理由 / 备注（必填）", NOTE_CN, "48_note_set"),
        )
        assertTrue(
            "数量写入失败（4 轮回读都不对），当前=${readField("数量（股）")}，" +
                "现场: ${screenDump().take(200)}",
            setFieldChecked("数量（股）", QTY, "47_qty_set"),
        )
        snap("41_filled")

        // ===== ⑥ 保存 =====
        // 保存前全面复核（边看边操作）：三框逐一确认，费率被污染就当场恢复
        val feeNow = readField("手续费率（万分之，必填）")
        if (feeNow != feeInitial) {
            assertTrue(
                "手续费率被污染且无法恢复：初始=$feeInitial 当前=$feeNow",
                setFieldChecked("手续费率（万分之，必填）", feeInitial ?: "2.5", "49_fee_restore"),
            )
        }
        assertTrue(
            "保存前复核失败：数量应为 $QTY，实际=${readField("数量（股）")}；" +
                "备注应含「$NOTE_KEY」，实际=${readField("交易理由 / 备注（必填）")}；" +
                "费率=$feeInitial；现场: ${screenDump().take(200)}",
            readField("数量（股）") == QTY &&
                readField("交易理由 / 备注（必填）")?.contains(NOTE_KEY) == true,
        )
        snap("50_form_ready")

        // 按钮在表单最底部，视口内可能看不到（2026-09-18 实测：备注栏贴着屏幕底，
        // 保存按钮在下面）→ 找不到就上滑滚动，最多 5 次
        var save = device.wait(Until.findObject(By.text("保存这笔交易")), 3_000)
        var scrollCount = 0
        while (save == null && scrollCount < 5) {
            device.swipe(540, 1900, 540, 900, 300)   // 上滑 = 内容上移，露出底部
            Thread.sleep(1_200)
            save = device.findObject(By.text("保存这笔交易"))
            scrollCount++
        }
        snap("45_before_save")
        assertTrue(
            "未找到保存按钮（滚动 $scrollCount 次后）；现场: ${screenDump().take(200)}",
            save != null,
        )
        save!!.click()
        device.waitForIdle()
        Thread.sleep(5_000)   // 保存 + 回持仓页

        // 校验失败会弹「无法保存」→ 截图、点「知道了」、带着弹窗内容明确失败
        if (device.hasObject(By.text("无法保存"))) {
            snap("51_save_rejected")
            val reason = screenDump()
            runCatching { device.findObject(By.text("知道了"))?.click() }
            fail("保存被表单校验拦截：$reason")
        }

        // ===== ⑦ 核心断言 2：已离开表单（保存成功）=====
        val stillInForm = device.hasObject(By.textContains("选择标的"))
        snap("42_after_save")
        assertTrue("保存后仍停留在记一笔表单（可能校验未通过）", !stillInForm)

        // ===== ⑧ 核心断言 3：可用现金减少 =====
        val cashAfter = money(cashText())
        assertTrue(
            "保存后读不到可用现金；现场: ${screenDump().take(200)}",
            cashAfter != null,
        )
        assertTrue(
            "买入后可用现金应减少：before=$cashBefore after=$cashAfter",
            cashAfter!! < cashBefore!!,
        )
    }

    companion object {
        private const val KEYWORD_CN = "贵州茅台"
        private const val CANDIDATE_CN = "茅台"
        private const val QTY = "1"
        private const val NOTE_KEY = "低吸"
        private const val NOTE_CN = "自动化测试：低吸一笔，稍后由删除用例清掉"
    }
}
