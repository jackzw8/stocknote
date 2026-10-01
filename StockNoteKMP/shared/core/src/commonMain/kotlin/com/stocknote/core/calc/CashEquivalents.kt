package com.stocknote.core.calc

/**
 * 现金等价物名单（REQ-ACC-07，老周 2026-09-20）。
 *
 * 用户在设置页填「代码」（如 `511660`），可填多个、逗号分隔；
 * 命中名单的标的在账务口径上归「现金」子类、**不计入持仓明细与占比**，
 * 市值并入总资产的「现金」部分（与可用现金合并展示）。
 *
 * ## 为什么要「宽松匹配」
 * 库里 `security.symbol` 的前缀形态**并不统一**：
 * - 东财搜索建档多为 `sh511660`（带 2 字母市场前缀）；
 * - 历史建档可能是无前缀的 `511660`；
 * - 美股带交易所后缀（`usAAPL.OQ`），且历史数据可能无后缀。
 *
 * 而用户填的只会是「代码」本身（`511660`）。所以匹配时把两边都展开成
 * **等价形态集合**再求交，避免「填 511660 却在库里匹配不到 sh511660」。
 *
 * ## 等价形态（对同一代码）
 * 归一化（去空白 + 转小写）后，依次产出：
 * 1. 原样：`sh511660`
 * 2. 去市场前缀：`511660`
 * 3. 再去交易所后缀：`AAPL`
 *
 * ⚠️ **不做「去前导零」归一**：`sz000001` 去零会退化成 `1`，
 * 用户若填 `1` 就会误命中一大堆标的。港股该填 `00700`（5 位规范形态）。
 */
object CashEquivalents {

    /** 市场前缀（小写）：沪 / 深 / 北交所 / 港 / 美。 */
    private val MARKET_PREFIXES = listOf("sh", "sz", "bj", "hk", "us")

    /** 分隔符：半角/全角逗号、顿号、分号、空白。 */
    private val SEPARATORS = charArrayOf(
        ',', '，', '、', ';', '；', ' ', '\u3000', '\n', '\r', '\t',
    )

    /**
     * 解析用户输入为名单条目：按分隔符切分 → 去空白 → 丢弃空项 → 去重（保序）。
     *
     * 空输入返回空列表（表示「未指定任何现金等价物」）。
     */
    fun parse(raw: String): List<String> = raw
        .split(*SEPARATORS)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** 回显用：把名单拼回 CSV（设置页输入框初值）。 */
    fun format(entries: List<String>): String = entries.joinToString(",")

    /**
     * 判断标的代码 [symbol] 是否命中名单 [entries]（任一命中即可）。
     * 名单为空 → 恒 false（未指定）。
     */
    fun matches(symbol: String, entries: List<String>): Boolean {
        if (entries.isEmpty()) return false
        val target = variants(symbol)
        if (target.isEmpty()) return false
        return entries.any { entry -> variants(entry).any { it in target } }
    }

    /**
     * 生成一个代码的全部等价形态（归一化后）。
     * 例：`usAAPL.OQ` → {`usaapl.oq`(原样), `aapl.oq`(去前缀), `aapl`(去后缀)}；
     *     `sh511660` → {`sh511660`, `511660`}。
     */
    private fun variants(raw: String): Set<String> {
        val s = raw.trim().lowercase().filterNot { it.isWhitespace() }
        if (s.isEmpty()) return emptySet()

        val out = mutableSetOf(s)

        // 去市场前缀（只去一次：`sh511660` → `511660`；`usAAPL.OQ` → `aapl.oq`）
        val prefix = MARKET_PREFIXES.firstOrNull { s.startsWith(it) && s.length > it.length }
        val noPrefix = if (prefix != null) s.removePrefix(prefix) else s
        out += noPrefix

        // 去交易所后缀（`aapl.oq` → `aapl`）
        out += noPrefix.substringBefore('.')
        out += s.substringBefore('.')

        return out.filter { it.isNotEmpty() }.toSet()
    }
}
