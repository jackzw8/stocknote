package com.stocknote.core.calc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 现金等价物名单解析与匹配的单测（REQ-ACC-07，老周 2026-09-20）。
 *
 * 口径来源：需求分析说明书-2.0.md REQ-ACC-07「指定某标的为现金等价物，
 * 归入现金子类、不计入持仓明细」；匹配需容忍库内 symbol 前缀形态不统一。
 */
class CashEquivalentsTest {

    // ---------------- parse：用户输入解析 ----------------

    @Test
    fun parse_singleCode() {
        assertEquals(listOf("511660"), CashEquivalents.parse("511660"))
    }

    @Test
    fun parse_multiCodeCommaSeparated() {
        assertEquals(
            listOf("511660", "511880", "511990"),
            CashEquivalents.parse("511660,511880,511990"),
        )
    }

    @Test
    fun parse_toleratesSpacesAndChinesePunctuation() {
        assertEquals(
            listOf("511660", "511880", "511990"),
            CashEquivalents.parse(" 511660 ， 511880、511990 ; "),
        )
    }

    @Test
    fun parse_dedupKeepsOrder() {
        assertEquals(
            listOf("511660", "511880"),
            CashEquivalents.parse("511660, 511880, 511660"),
        )
    }

    @Test
    fun parse_blankGivesEmptyList() {
        assertTrue(CashEquivalents.parse("").isEmpty())
        assertTrue(CashEquivalents.parse("   ,  、  ").isEmpty())
    }

    // ---------------- format：回显 ----------------

    @Test
    fun format_joinsWithComma() {
        assertEquals("511660,511880", CashEquivalents.format(listOf("511660", "511880")))
        assertEquals("", CashEquivalents.format(emptyList()))
    }

    // ---------------- matches：空名单恒 false ----------------

    @Test
    fun matches_emptyEntriesAlwaysFalse() {
        assertFalse(CashEquivalents.matches("sh511660", emptyList()))
    }

    // ---------------- matches：老周的主场景 ----------------

    /** 用户填 511660，库里是带前缀的 sh511660 —— 这是最容易踩的坑。 */
    @Test
    fun matches_bareCodeHitsPrefixedSymbol() {
        assertTrue(CashEquivalents.matches("sh511660", listOf("511660")))
    }

    /** 反向：用户填带前缀，库里无前缀。 */
    @Test
    fun matches_prefixedEntryHitsBareSymbol() {
        assertTrue(CashEquivalents.matches("511660", listOf("sh511660")))
    }

    @Test
    fun matches_caseInsensitive() {
        assertTrue(CashEquivalents.matches("SH511660", listOf("sh511660")))
        assertTrue(CashEquivalents.matches("sh511660", listOf("SH511660")))
    }

    @Test
    fun matches_anyOfMultipleEntries() {
        assertTrue(CashEquivalents.matches("sh511660", listOf("511880", "511660")))
    }

    @Test
    fun matches_notInListIsFalse() {
        assertFalse(CashEquivalents.matches("sh600519", listOf("511660")))
        assertFalse(CashEquivalents.matches("sh600519", listOf("511660", "511880")))
    }

    // ---------------- matches：港股（5 位规范形态）----------------

    @Test
    fun matches_hkCode() {
        assertTrue(CashEquivalents.matches("hk00700", listOf("00700")))
        assertTrue(CashEquivalents.matches("hk00700", listOf("hk00700")))
    }

    // ---------------- matches：美股（带交易所后缀）----------------

    @Test
    fun matches_usSymbolWithExchangeSuffix() {
        assertTrue(CashEquivalents.matches("usAAPL.OQ", listOf("AAPL")))
        assertTrue(CashEquivalents.matches("usAAPL.OQ", listOf("usAAPL")))
    }

    // ---------------- 防误判：不去前导零 ----------------

    /**
     * `sz000001`（平安银行）绝不能因为「去前导零」而和用户填的 `1` 命中 ——
     * 这正是本实现刻意**不做**前导零归一的原因。
     */
    @Test
    fun matches_doesNotNormalizeLeadingZeros() {
        assertFalse(CashEquivalents.matches("sz000001", listOf("1")))
    }

    /** 用户填规范形态 000001 时，仍应正确命中。 */
    @Test
    fun matches_exactLeadingZeroCodeStillHits() {
        assertTrue(CashEquivalents.matches("sz000001", listOf("000001")))
    }

    /** 空条目不应命中任何标的。 */
    @Test
    fun matches_blankEntryNeverHits() {
        assertFalse(CashEquivalents.matches("sh511660", listOf("", "  ")))
    }
}
