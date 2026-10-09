package com.stocknote.core.io

import com.stocknote.core.model.SkyAttitude
import com.stocknote.core.model.SkyDomain

/**
 * 种子清单里的一条（解析产物）—— 预览页可直接勾选 / 编辑 / 删 / 排序（FR-SE-11）。
 *
 * [attitude] 为 null 有两种可能：AI 没给档位、或给了无法识别的词（如「值得关注」）——
 * 一律标「未识别档位」，让用户自己点一下（**宁缺勿猜，不丢条目**）。
 */
data class SkySeedItem(
    val title: String,
    val attitude: SkyAttitude?,
    /** 理由（AI 给的「依据」），可为 null。 */
    val note: String?,
    /** 标题超过 [SkySeedParser.TITLE_MAX] 被截断（预览页标黄提示用）。 */
    val titleTruncated: Boolean = false,
)

/**
 * 解析出来的**一段**（一个 `SEED:` 头行 + 其下的条目）。
 *
 * ⚠️ **一份文本可以含多段**（H-1，老周 2026-10-09 的真实文件就是「看天 10 条 + 看地 5 条」装在
 * 一个 txt 里）—— 段与段靠下一个 `SEED:` 头行分隔，各段归各自的域与上限（天 10 / 地 5）。
 */
data class SkySeedSegment(
    val domain: SkyDomain,
    /** `SEED:EARTH|名称|代码` 里的名称；天组 / 未给为 null。 */
    val name: String?,
    /** `SEED:EARTH|名称|代码` 里的代码；天组 / 未给为 null。 */
    val symbol: String?,
    val items: List<SkySeedItem>,
    /** 本段内无法解析被忽略的噪声行数（不含空行 / `#` 注释 / 围栏 / 表格分隔线）。 */
    val skippedLines: Int,
    /** `# 信息截至 2026-10-08` 里的日期；本段没有则 null。 */
    val infoDate: String?,
)

/** 解析结果。失败给人话原因，不猜。 */
sealed interface SkySeedResult {

    /**
     * 解析成功（**不保证**条数在组上限内 —— 截断是上层预览页的事，见技术说明书 §5.4 边界原则）。
     *
     * [segments] 至少 1 段；单段文本（日常只问一组）时 size == 1。
     * 下面几个便捷属性都取**第一段** —— 单段场景让调用方少写一层（既有调用方也靠它们保持兼容）。
     */
    data class Ok(
        val segments: List<SkySeedSegment>,
    ) : SkySeedResult {

        /** 第一段（日常单段文本即全部内容）。 */
        val primary: SkySeedSegment get() = segments.first()

        val domain: SkyDomain get() = primary.domain
        val name: String? get() = primary.name
        val symbol: String? get() = primary.symbol
        val items: List<SkySeedItem> get() = primary.items
        val skippedLines: Int get() = primary.skippedLines
        val infoDate: String? get() = primary.infoDate
    }

    /** 没法解析（空输入 / 没识别到 `SEED:` 头行）。[reason] 是直接给用户看的人话。 */
    data class Invalid(val reason: String) : SkySeedResult
}

/**
 * 种子清单解析器（FR-SE-11 / 技术说明书 §5）：**纯文本 → 条目**，纯函数、可单测。
 *
 * AI 的返回常常自带装饰（Markdown 表格、`-` 列表、代码块围栏、寒暄、免责声明），
 * 解析必须容错，但**宁缺勿猜**：档位词不认识就标「未判断」，让用户自己点；
 * 一行实在切不出结构就忽略（计入 [SkySeedSegment.skippedLines]），绝不报错中断。
 *
 * ⚠️ 边界原则：**解析器只负责解析，不做业务截断**（上限拦截在 Holder / 预览页）——
 * UI 要能告诉用户「多出来的没勾上」，而不是悄悄吞掉。
 *
 * ⚠️ **多段**（H-1）：一份文本里出现第二个 `SEED:` 头行时必须**开启新段**，
 * 而不是把它当数据行解析 —— 否则会产出「标题 = `SEED:EARTH`」的垃圾条目，
 * 还会把地组条目灌进天组（老周真机文件就是这么被污染的，2026-10-09）。
 *
 * 交换格式（需求说明书附录 C）：
 * ```
 * SEED:SKY
 * 1|美联储降息路径与美元流动性|中性|9月降25bp，点阵图分歧仍然较大
 * # 信息截至 2026-10-08
 * SEED:EARTH|腾讯控股|00700
 * 1|游戏 pipeline 与版号|乐观|版号常态化，储备新游多于去年同期
 * ```
 */
object SkySeedParser {

    /** 标题上限（FR-SE-02）。超长**截断**并在预览页标黄，不丢条目。 */
    const val TITLE_MAX = 30

    /** 理由上限（FR-SE-04）。 */
    const val NOTE_MAX = 200

    private val SERIAL_REGEX = Regex("""^\d+[.、)]?$""")

    /** 行级列表符号前缀：`- ` / `* ` / `• `（要求后跟空白，避免吃掉「-5%」这类标题）。 */
    private val BULLET_PREFIX_REGEX = Regex("""^[-*•]\s+""")

    /**
     * 段级序号前缀：`1. ` / `1、` / `1)`—— 用于「序号与标题同段」的写法（如 `4、出口增速`）。
     * 1~2 位数字（年份等 4 位数不误伤）+ 标点 + 后面不是数字（`3.5` 不误伤）。
     */
    private val SEG_SERIAL_PREFIX_REGEX = Regex("""^\d{1,2}[.、)](?=\D|$)""")

    private val DATE_REGEX = Regex("""(\d{4}-\d{2}-\d{2})""")

    /** 表头行的列名（出现任意一个即视为 Markdown 表头，不是条目）。 */
    private val HEADER_WORDS = setOf("序号", "编号", "档位", "判断")

    // 档位同义词（技术说明书 §5.3）：二档词合并到相邻主档，**不引入第四档**。
    private val BEARISH_WORDS = setOf("悲观", "偏悲观", "看空", "负面", "利空", "bearish")
    private val NEUTRAL_WORDS = setOf("中性", "中立", "观望", "不明确", "neutral")
    private val BULLISH_WORDS = setOf("乐观", "偏乐观", "看多", "正面", "利好", "bullish")

    /**
     * 解析一段粘贴文本（**可含多段** `SEED:`，见类注释）。
     *
     * @param raw 用户从 AI 返回里整段粘贴的内容（含各种装饰都行）。
     */
    fun parse(raw: String): SkySeedResult {
        val text = raw
            .removePrefix("\uFEFF")            // BOM
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = text.split('\n')

        val segments = mutableListOf<SkySeedSegment>()
        var header: Header? = null
        var items = mutableListOf<SkySeedItem>()
        var skipped = 0
        var infoDate: String? = null

        /** 收尾当前段（头行之后的行已按当段归属解析完）。 */
        fun flush() {
            val h = header ?: return
            segments += SkySeedSegment(
                domain = h.domain,
                name = h.name,
                symbol = h.symbol,
                items = items.toList(),
                skippedLines = skipped,
                infoDate = infoDate,
            )
            items = mutableListOf()
            skipped = 0
            infoDate = null
        }

        for (line in lines) {
            val t = line.trim()
            val newHeader = headerOf(t)
            when {
                // ⚠️ 新段头行（H-1 核心）：第二个 SEED: 必须开新段，**绝不能**落进 parseRow
                newHeader != null -> {
                    flush()
                    header = newHeader
                }
                // 首个头行之前的寒暄 / 说明：丢弃（既有行为）
                header == null -> Unit
                t.isEmpty() -> Unit                       // 空行忽略
                t.startsWith("```") -> Unit               // 代码块围栏忽略
                t.startsWith("#") -> {                    // 注释行忽略；但「信息截至」要取出来（每段各取第一条）
                    if (infoDate == null) {
                        infoDate = DATE_REGEX.find(t)?.groupValues?.get(1)
                    }
                }
                isTableSeparator(t) -> Unit               // Markdown 表格分隔行 |---| 忽略
                else -> {
                    val item = parseRow(t)
                    if (item != null) items.add(item) else skipped++
                }
            }
        }
        flush()

        if (segments.isEmpty()) {
            return SkySeedResult.Invalid(
                "没有识别到清单开头（应为 SEED:SKY 或 SEED:EARTH|名称|代码），请检查是否复制了完整内容",
            )
        }
        return SkySeedResult.Ok(segments)
    }

    /** 档位词 → 三档；不认识 → null（宁缺勿猜）。 */
    fun attitudeOf(rawWord: String): SkyAttitude? {
        val t = rawWord.trim().lowercase().removeSuffix("。").removeSuffix(".")
        return when {
            t in BEARISH_WORDS -> SkyAttitude.BEARISH
            t in NEUTRAL_WORDS -> SkyAttitude.NEUTRAL
            t in BULLISH_WORDS -> SkyAttitude.BULLISH
            else -> null
        }
    }

    // ---- 内部实现 ----

    private data class Header(val domain: SkyDomain, val name: String?, val symbol: String?)

    /** 头行：`SEED:SKY` 或 `SEED:EARTH|名称|代码`（大小写不敏感，允许前后空白）。 */
    private fun headerOf(line: String): Header? {
        val t = line.trim()
        if (t.length < 5 || !t.regionMatches(0, "SEED:", 0, 5, ignoreCase = true)) return null
        val segs = t.substring(5).replace('｜', '|').split('|').map { it.trim() }
        val domain = SkyDomain.ofRaw(segs.getOrNull(0).orEmpty()) ?: return null
        return Header(
            domain = domain,
            name = segs.getOrNull(1)?.takeIf { it.isNotEmpty() },
            symbol = segs.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }

    /** `|---|---|` 这类表格分隔行（只由 `-` / `:` / `|` / 空白组成且含 `-`）。 */
    private fun isTableSeparator(line: String): Boolean {
        val t = line.replace('｜', '|').trim()
        if (t.isEmpty() || !t.contains('-')) return false
        return t.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
    }

    /**
     * 数据行 → 条目；切不出结构返回 null（忽略该行、不报错）。
     *
     * 容错（需求说明书附录 C.3）：Markdown 表格首尾管道、列表前缀、全角竖线、
     * 序号列（不校验值，按出现顺序）、理由缺失、标题/理由超长截断。
     */
    private fun parseRow(rawLine: String): SkySeedItem? {
        // 保险（H-1）：任何 `SEED:` 开头的行都不是条目 —— 正常由 parse() 拦下；
        // 这里兜住「域不认识的段头」（如 SEED:FOO）被误当成条目的情况（不改写 skipped 语义）。
        if (rawLine.trim().regionMatches(0, "SEED:", 0, 5, ignoreCase = true)) return null

        val body = BULLET_PREFIX_REGEX.replace(rawLine.replace('｜', '|'), "")
        var segs = body.split('|').map { it.trim() }
        // Markdown 表格行的首尾空段
        if (segs.isNotEmpty() && segs.first().isEmpty()) segs = segs.drop(1)
        if (segs.isNotEmpty() && segs.last().isEmpty()) segs = segs.dropLast(1)
        // Markdown 表头行（| 序号 | 标题 | 档位 | 理由 |）—— 是噪声，不是条目
        if (segs.any { it in HEADER_WORDS }) return null
        if (segs.size < 2) return null
        // 序号列（连续序号 / 断号都不校验，排序按出现顺序）
        if (segs.first().matches(SERIAL_REGEX)) segs = segs.drop(1)
        if (segs.isEmpty()) return null
        // 首段仍带「4、出口增速」这类前缀 → 剥掉（序号与标题同段的写法）
        val strippedFirst = segs.first().replaceFirst(SEG_SERIAL_PREFIX_REGEX, "").trim()
        if (strippedFirst.isNotEmpty()) segs = listOf(strippedFirst) + segs.drop(1)
        if (segs.first().isEmpty()) return null

        val rawTitle = segs.first()
        val titleTruncated = rawTitle.length > TITLE_MAX
        val title = if (titleTruncated) rawTitle.take(TITLE_MAX) else rawTitle

        // 第 2 段是档位；识别不了就并入理由（不丢内容）
        val second = segs.getOrNull(1)
        val attitude = second?.let { attitudeOf(it) }
        val noteSegs = when {
            attitude != null -> segs.drop(2)
            second == null -> emptyList()
            else -> segs.drop(1)
        }
        val rawNote = noteSegs.joinToString("|").trim()
        val note = rawNote.take(NOTE_MAX).takeIf { it.isNotEmpty() }

        return SkySeedItem(title = title, attitude = attitude, note = note, titleTruncated = titleTruncated)
    }
}
