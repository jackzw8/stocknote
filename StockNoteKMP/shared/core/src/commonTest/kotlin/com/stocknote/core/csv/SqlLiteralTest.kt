package com.stocknote.core.csv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * **H1 修复的护栏**（2026-09-27）：确保 SQL 字面量**永远是单行**且引号正确转义。
 *
 * 对应缺陷：备份 dump 若残留裸换行，恢复端 `lines()` 会把一条 INSERT 切成两半
 * → 执行失败 → 整体 ROLLBACK → **整份备份不可恢复**（而 checksum 仍通过）。
 * 而 `trade.note` 是**必填多行输入**，正常使用几乎必然踩到 —— 所以这里必须钉死。
 */
class SqlLiteralTest {

    @Test
    fun 普通文本_加引号() {
        assertEquals("'茅台'", SqlLiteral.of("茅台"))
    }

    @Test
    fun 单引号_按标准SQL转义() {
        assertEquals("'it''s'", SqlLiteral.of("it's"))
    }

    @Test
    fun 空串() {
        assertEquals("''", SqlLiteral.of(""))
    }

    @Test
    fun 换行_转成char表达式且结果单行() {
        val out = SqlLiteral.of("第一行\n第二行")
        assertEquals("'第一行' || char(10) || '第二行'", out)
        assertFalse(out.contains('\n'), "转义后绝不能残留裸换行 —— 否则 dump 会被 lines() 切坏")
        assertFalse(out.contains('\r'), "转义后绝不能残留裸回车")
    }

    @Test
    fun Windows换行_当一个整体处理() {
        assertEquals("'a' || char(13) || char(10) || 'b'", SqlLiteral.of("a\r\nb"))
    }

    @Test
    fun 单独回车() {
        assertEquals("'a' || char(13) || 'b'", SqlLiteral.of("a\rb"))
    }

    @Test
    fun 多行加引号混合_仍单行且可还原() {
        val raw = "买入理由：\n1. 估值低\n2. 现金流好（'茅台'）"
        val out = SqlLiteral.of(raw)
        assertFalse(out.contains('\n'))
        assertFalse(out.contains('\r'))
        // 把 char() 表达式逆向替换回真实字符，应当与原文完全一致
        val restored = out
            .removePrefix("'").removeSuffix("'")
            .replace("' || char(13) || char(10) || '", "\r\n")
            .replace("' || char(10) || '", "\n")
            .replace("' || char(13) || '", "\r")
            .replace("''", "'")
        assertEquals(raw, restored)
    }

    @Test
    fun 典型交易备注_不含裸换行() {
        // 真实场景：用户在「交易理由」多行输入框里写多段
        val note = "早盘冲高回落，量能萎缩\n计划：跌破 5 日线减半仓\n\n复盘：情绪面偏弱"
        assertFalse(SqlLiteral.of(note).contains('\n'))
    }

    @Test
    fun 连续换行_每处都独立转义() {
        assertEquals("'a' || char(10) || '' || char(10) || 'b'", SqlLiteral.of("a\n\nb"))
    }
}
