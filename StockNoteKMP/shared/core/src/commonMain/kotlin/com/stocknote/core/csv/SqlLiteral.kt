package com.stocknote.core.csv

/**
 * SQL 文本字面量转义（**H1 修复**配套，2026-09-27）。
 *
 * ## 为什么需要它
 * 加密备份的 data 段是一份"文本 SQL dump"：**一条 INSERT 占一行**，恢复端按 `lines()` 切分后逐条执行。
 * 如果值里保留了**裸换行**，一条语句会被切成两半 —— 执行半截 SQL 会抛错，整个恢复事务 ROLLBACK，
 * 于是**整份备份都无法恢复**（而 checksum 仍会通过，因为它校验的是同一个字符串）。
 *
 * 备注（`trade.note`，必填多行输入）、复盘内容（`review.content`）里出现换行**太正常了**，
 * 所以这是一个"正常使用就必然踩到"的缺陷。
 *
 * ## 做法
 * 把 `\n` / `\r` 转成 SQL 表达式拼接：`'abc' || char(10) || 'def'` ——
 * 值照旧写进一行，运行时由 SQLite 还原成真正的换行。单引号仍按标准 SQL 转义为 `''`。
 *
 * ⚠️ **顺序要紧**：先把 `\r\n` 当整体处理，再处理单独的 `\n` / `\r`，否则 Windows 换行会变成两个 char()。
 * ⚠️ 这是**纯函数**，放在 core 以便单测（见 `SqlLiteralTest`）。
 *
 * ⚠️ **L5（2026-09-27 记录，本次不改行为）**：所有值（含 REAL / INTEGER）都写成**带引号字符串**，
 * 依赖 SQLite 的**列亲和性**在 INSERT 时转回数值 —— 目前工作正常，但相对脆弱（换成不遵守亲和性的
 * 引擎/中间层就会退化成文本）。彻底做法是改用**参数化 INSERT**，但那要连备份格式一起动
 * （属较大改造），老周 2026-09-27 决定：本次只记录。
 */
object SqlLiteral {

    /** 单换行 → SQL 表达式片段（用 char(10) 而不是 '\n'，后者在 SQL 字符串里不是转义序列） */
    private const val LF = "' || char(10) || '"
    private const val CR = "' || char(13) || '"
    private const val CRLF = "' || char(13) || char(10) || '"

    /**
     * 把 [s] 转成**单行**的 SQL 字符串字面量（含两侧单引号）。
     *
     * 例：`SqlLiteral.of("a\nb")` → `'a' || char(10) || 'b'`
     *     `SqlLiteral.of("it's")`   → `'it''s'`
     *     `SqlLiteral.of("")`       → `''`
     */
    fun of(s: String): String {
        val escaped = s
            .replace("'", "''")
            .replace("\r\n", CRLF)
            .replace("\n", LF)
            .replace("\r", CR)
        return "'$escaped'"
    }
}
