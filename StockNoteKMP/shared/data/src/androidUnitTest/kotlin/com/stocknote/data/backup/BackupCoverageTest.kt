package com.stocknote.data.backup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 备份清单 vs 数据库 schema 的**逐表 + 逐列核对**（老周 2026-09-21 加的护栏，M13/L4 扩展）。
 *
 * 起因：`trade_plan` 整表漏在备份之外 —— 表现是「备份后新加一条计划，恢复后那条计划还在」，
 * 既没被导出、也没在恢复时被清空。这类漏法**不会报错**，只能靠对照 schema 才发现。
 *
 * 做法：直接读 `*.sq` 里的 `CREATE TABLE`（这是唯一的事实源），逐张表检查它
 * 「要么在 [BackupSchema.tables] 里，要么在 [BackupSchema.excluded] 里并写明理由」；
 * 再逐列比对 `BackupSchema.columns` 与建表语句。
 *
 * ⚠️ **L12 修复（2026-09-27）**：本文件从 `commonTest` 移到这里（`androidUnitTest`）——
 * 它用 `java.io.File` 读源码目录，而 `java.io` 在 Kotlin/Native（iOS）**不存在**，
 * 放在 commonTest 会让 iOS 目标**编译失败**。这类"读仓库文件"的断言本质是**构建期检查**，
 * 在 JVM 侧跑一次即可，iOS 侧不需要（也无法）执行。
 */
class BackupCoverageTest {

    /**
     * **允许不进备份的列**（L4，2026-09-27）：BLOB 列装不进"文本 SQL dump"
     * （`cursor.getString` 读 BLOB 再当文本写会破坏 SQL），故与 `trade_photo` 一致排除。
     * 这里必须**显式登记**，否则会被上面的逐列比对当成"漏列"而报红。
     */
    private val notBackedUpColumns = mapOf(
        "note" to setOf("screenshot"),
    )

    /** 从 schema 文件里抠出全部表名。 */
    private fun schemaTables(): Set<String> {
        val dir = schemaDir()
        val sqFiles = dir.listFiles { f: File -> f.name.endsWith(".sq") }?.toList().orEmpty()
        assertTrue(sqFiles.isNotEmpty(), "找不到 *.sq：${dir.absolutePath}")
        return sqFiles
            .flatMap { f ->
                Regex("""CREATE TABLE\s+([A-Za-z_][A-Za-z0-9_]*)""")
                    .findAll(f.readText())
                    .map { it.groupValues[1] }
                    .toList()
            }
            .toSet()
    }

    /**
     * 解析某张表在 `*.sq` 里的**列名集合**（M13 扩展，2026-09-27）。
     *
     * 做法：定位 `CREATE TABLE <table> (`，向后**配平括号**取定义体（列类型可能自带括号如 `decimal(10,2)`），
     * 再按**顶层逗号**切段，每段第一个词即列名；跳过 `PRIMARY / UNIQUE / FOREIGN / CHECK / CONSTRAINT` 约束行。
     */
    private fun schemaColumns(table: String): Set<String> {
        val dir = schemaDir()
        val sqFiles = dir.listFiles { f: File -> f.name.endsWith(".sq") }?.toList().orEmpty()
        sqFiles.forEach { f ->
            // ⚠️ 先剥掉行注释（"--" 到行尾）：否则注释里的半角括号会破坏括号配平，
            //    且注释行的首词（"--"）会被当成列名 → 误报"漏列 --"与一堆假"多列"。
            val text = f.readText().lines().joinToString("\n") { it.substringBefore("--") }
            val head = Regex("CREATE TABLE\\s+$table\\s*\\(", RegexOption.IGNORE_CASE).find(text)
                ?: return@forEach
            var i = head.range.last          // 指向 '('
            var depth = 0
            val body = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                when (c) {
                    '(' -> { depth++; if (depth > 1) body.append(c) }
                    ')' -> { depth--; if (depth == 0) break else body.append(c) }
                    else -> body.append(c)
                }
                i++
            }
            val cols = splitTopLevel(body.toString()).mapNotNull { seg ->
                val s = seg.trim()
                if (s.isEmpty()) return@mapNotNull null
                val first = s.substringBefore(' ').substringBefore('\t').substringBefore('\n').trim()
                val up = first.uppercase()
                if (up in setOf("PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "CONSTRAINT")) null else first
            }.toSet()
            if (cols.isNotEmpty()) return cols
        }
        return emptySet()
    }

    /** 按**顶层**逗号切分（跳过括号内的逗号，如 `decimal(10,2)`） */
    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        s.forEach { c ->
            when (c) {
                '(' -> { depth++; cur.append(c) }
                ')' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { out += cur.toString(); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    /**
     * **列级护栏（M13，2026-09-27）**：`BackupSchema.columns` 必须与 `*.sq` 的建表列**逐列一致**
     * （`notBackedUpColumns` 里显式登记的 BLOB 列除外）。
     *
     * 此前只校验了"表名二选一"和"每张表都写了 columns"，**没做逐列比对** ——
     * 而文件头自己就写着「漏一列，恢复时该列直接落回默认值 = 静默丢数据」。
     * 现在两个方向都查：
     *  - **漏列**（schema 有、清单没写）→ 恢复时丢数据；
     *  - **多列**（清单有、schema 没有）→ 恢复时 INSERT 含不存在的列 → 整份恢复失败。
     */
    @Test
    fun 备份列清单必须与schema逐列一致() {
        val problems = mutableListOf<String>()
        BackupSchema.tables.forEach { table ->
            val declared = schemaColumns(table)
            if (declared.isEmpty()) return@forEach      // 解析不到就跳过（另有表名测试兜底）
            val listed = BackupSchema.columns[table].orEmpty().toSet()
            val exempt = notBackedUpColumns[table].orEmpty()
            val missing = declared - listed - exempt
            val ghost = listed - declared
            if (missing.isNotEmpty()) problems += "$table 漏列: $missing"
            if (ghost.isNotEmpty()) problems += "$table 多列: $ghost"
        }
        assertTrue(
            problems.isEmpty(),
            "备份列清单与 schema 不一致：\n" + problems.joinToString("\n"),
        )
    }

    @Test
    fun columns清单里不能有schema中不存在的表() {
        val ghostTables = BackupSchema.columns.keys - schemaTables()
        assertTrue(ghostTables.isEmpty(), "columns 里有多余的表：$ghostTables")
    }

    private fun schemaDir(): File {
        var cur: File? = File(".").absoluteFile
        val relative = "src/commonMain/sqldelight/com/stocknote/data/db"
        while (cur != null) {
            val candidate = File(cur, relative)
            if (candidate.isDirectory) return candidate
            cur = cur.parentFile
        }
        error("定位不到 schema 目录（$relative）")
    }

    /**
     * 先证明「解析真的抓到了表」——否则上面那条断言会因为空集合而**假绿**。
     * 这几个是长期存在的核心表，抓不到它们说明正则或路径已失效。
     */
    @Test
    fun schema能被解析出表名() {
        val tables = schemaTables()
        val expected = setOf("security", "trade", "cash_flow", "dividend", "trade_plan", "watchlist")
        assertTrue(
            tables.containsAll(expected),
            "schema 解析结果里缺少核心表：${expected - tables}（解析失效，其他断言不能当真）",
        )
    }

    @Test
    fun 每张表都要么进备份要么写明不备的理由() {
        val tables = schemaTables()
        val covered = BackupSchema.tables.toSet()
        val known = covered + BackupSchema.excluded.keys
        val missing = tables - known
        assertTrue(
            missing.isEmpty(),
            "这些表既不在备份清单也没有排除理由：$missing\n" +
                "→ 恢复备份时它们既不会被覆盖也不会被清空，会留下「恢复不掉的残留」" +
                "（trade_plan 就是这么漏的）。请加进 BackupSchema.tables 或 excluded。",
        )
    }

    @Test
    fun 备份清单里不能有schema中不存在的表() {
        val ghost = BackupSchema.tables.toSet() - schemaTables()
        assertTrue(ghost.isEmpty(), "备份清单里有 schema 中不存在的表：$ghost（表已改名/删除？）")
    }

    @Test
    fun 备份清单每张表都写了列() {
        val noColumns = BackupSchema.tables.filterNot { BackupSchema.columns.containsKey(it) }
        assertTrue(noColumns.isEmpty(), "这些表在 tables 里但没写 columns：$noColumns（导出会直接抛异常）")
    }

    @Test
    fun 排除名单必须写明理由() {
        val blank = BackupSchema.excluded.filterValues { it.isBlank() }.keys
        assertTrue(blank.isEmpty(), "排除名单里有表没写理由：$blank")
    }
}
