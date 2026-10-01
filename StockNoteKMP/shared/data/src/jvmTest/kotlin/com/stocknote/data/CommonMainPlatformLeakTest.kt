package com.stocknote.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **源码卫生检查**：`commonMain` 里**不许出现 JVM 专属 API**。
 *
 * ## 为什么需要这个测试（2026-10-01 的教训）
 * run `36839514419` 的 iOS 构建**白跑一轮**，只因 `PnlCalendarScreen.kt:510` 写了一行
 * `Math.abs(...)`：
 * ```
 * e: PnlCalendarScreen.kt:510:52 Unresolved reference 'Math'.
 * > Task :shared:feature:compileKotlinIosArm64 FAILED
 * ```
 * `Math` 是 **`java.lang.Math`（JVM 专属）**，而那个文件在 **`commonMain`**。要命的地方在于：
 *
 * | 环境 | 结果 |
 * |---|---|
 * | Android / JVM | ✅ **编得过去** —— `java.lang.*` 是隐式导入，`Math` 白给 |
 * | iOS(K/N) | ❌ **Unresolved reference** —— Native 上没有这个类 |
 * | **本机（Windows）** | ⚠️ **根本编不了 iOS** → 只能靠 CI |
 *
 * ⇒ 于是它成了一个「**本地永远全绿、只有 26 分钟的 iOS CI 才能发现**」的坑，
 * 每犯一次就报废一轮构建。这个测试把它**拉回 `jvmTest`（秒级）**。
 *
 * ## 做法
 * 扫描 `shared/{core,data,feature}/src/commonMain/kotlin` 下所有 `.kt`，
 * 逐行（**剥掉注释后**）匹配 JVM 专属符号，命中即失败，并打印 `文件:行号`。
 *
 * ⚠️ 只扫 `commonMain` —— `androidMain`/`jvmMain` 用 JVM API 是**正当的**。
 * ⚠️ 注释与 KDoc 会被剥掉，所以文档里写「不要用 `Math`」不会误报。
 */
class CommonMainPlatformLeakTest {

    /** 规则：给人看的说明 → 匹配正则。**全部大小写敏感**，避免误伤小写的 `kotlin.math`。 */
    private val rules: List<Pair<String, Regex>> = listOf(
        // ⭐ 就是这次踩的那个：`Math.abs` / `Math.round` → 一律改用 kotlin.math.*
        "java.lang.Math → 改用 kotlin.math.abs / round / max / min / pow …" to
            Regex("""(?<![\w.])Math\."""),
        "java.lang.System → 时钟用 nowEpochMs()，输出用 nativeLog()" to
            Regex("""(?<![\w.])System\."""),
        "java.lang.Thread → 协程/平台隔离点里处理" to
            Regex("""(?<![\w.])Thread\."""),
        "java.* / javax.* 直接引用" to
            Regex("""(?<![\w.])javax?\."""),
        "String.format → 用 Core 里的 Format.*" to
            Regex("""String\.format\s*\("""),
        "java.lang.Objects" to
            Regex("""(?<![\w.])Objects\."""),
        "java.util.Arrays / Collections" to
            Regex("""(?<![\w.])(Arrays|Collections)\."""),
        "java.math.BigDecimal / BigInteger → 金额一律 Double + Format（见项目约定）" to
            Regex("""(?<![\w.])(BigDecimal|BigInteger)\b"""),
        "java.text.SimpleDateFormat / NumberFormat → 用 formatLocalDateTime / Format.*" to
            Regex("""(?<![\w.])(SimpleDateFormat|NumberFormat)\b"""),
        "String.valueOf → 直接用 .toString() / 模板串" to
            Regex("""String\.valueOf\s*\("""),
    )

    @Test
    fun `commonMain 不得出现 JVM 专属 API`() {
        val shared = findSharedDir()
            ?: fail("找不到 shared 目录（当前工作目录 = ${File("").absolutePath}）")

        val commonMainDirs = listOf("core", "data", "feature")
            .map { File(shared, "$it/src/commonMain/kotlin") }
            .filter { it.isDirectory }
        assertTrue(
            commonMainDirs.isNotEmpty(),
            "一个 commonMain 目录都没找到，路径假设可能已经失效：$shared",
        )

        // 文件:行号 → 命中的说明，聚合后一次性报出来（比一个一个红更省时间）
        val hits = mutableListOf<String>()
        var scanned = 0

        commonMainDirs.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    scanned++
                    val rel = file.relativeTo(shared.parentFile).path.replace('\\', '/')
                    stripComments(file.readText(Charsets.UTF_8))
                        .lineSequence()
                        .forEachIndexed { i, line ->
                            rules.forEach { (why, regex) ->
                                if (regex.containsMatchIn(line)) {
                                    hits += "  $rel:${i + 1}  →  ${line.trim()}\n      为什么不行：$why"
                                }
                            }
                        }
                }
        }

        assertTrue(scanned > 0, "一个 .kt 都没扫到，路径假设可能已经失效")
        assertTrue(
            hits.isEmpty(),
            "❌ commonMain 里出现了 JVM 专属 API（Android 编得过、iOS 必然 Unresolved，\n" +
                "   而 iOS 只有 CI 能编 —— 每次都要白等 26 分钟）：\n\n" +
                hits.joinToString("\n\n") +
                "\n\n扫过 $scanned 个文件。",
        )
    }

    /**
     * 从当前工作目录**逐级向上**找 `shared/`。
     *
     * ⚠️ 不能写死相对路径：Gradle 跑测试时 cwd 是**模块目录**（`shared/data`），
     * 而在 IDE 里跑可能是**项目根**（`StockNoteKMP`）—— 写死任何一边都会让另一边找不到。
     */
    private fun findSharedDir(): File? {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val d = dir ?: return null
            val candidate = File(d, "shared")
            if (File(candidate, "core/src/commonMain/kotlin").isDirectory) return candidate
            dir = d.parentFile
        }
        return null
    }

    /**
     * 剥掉 `//` 行注释、块注释（含 KDoc）与字符串字面量内容。
     *
     * ⚠️ 必须剥：否则文档/注释里只要提到 `Math`（比如「不要用 `Math.abs`」「java.lang.Math」）
     * 就会误报 —— 而我恰恰在修复现场写过这样的注释。
     * 字符串字面量也一并清空（`"Math.abs"` 只是文本，不是代码）。
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inStr = false
        while (i < text.length) {
            val c = text[i]
            val n = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                inLine -> if (c == '\n') { inLine = false; out.append(c) }
                inBlock -> when {
                    c == '*' && n == '/' -> { inBlock = false; i++ }
                    c == '\n' -> out.append(c)
                }
                inStr -> when {
                    c == '\\' -> i++                       // 跳过转义的下一个字符
                    c == '"' -> inStr = false
                    c == '\n' -> out.append(c)             // 容错：未闭合的串不吞掉换行
                }
                c == '/' && n == '/' -> inLine = true
                c == '/' && n == '*' -> { inBlock = true; i++ }
                c == '"' -> inStr = true
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
