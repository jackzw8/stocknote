package com.stocknote.data.net

import com.stocknote.core.io.Md5
import com.stocknote.core.io.Sha1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * 财联社 **7×24 快讯**（电报）数据源 —— 老周 2026-10-04，
 * 依据《财联社 7x24 快讯接口技术文档 v1.0》。
 *
 * ## 接口
 * ```
 * GET https://www.cls.cn/v1/roll/get_roll_list
 *   app=CailianpressWeb   os=web   sv=8.4.6   rn=<条数，1..50>
 *   sign = MD5( SHA1( 除 sign 外的参数按 key 字典序拼成 "k=v&k=v" ) )
 * Headers: 浏览器 User-Agent + Referer: https://www.cls.cn/telegraph + Accept: application/json
 * ```
 * 返回 `{"errno":0,"msg":"","data":{"roll_data":[ … ]}}`；列表项字段见 [Flash]。
 *
 * ## ⚠️ 实测纠正的两条（2026-10-04，本机直接打接口验证过，别再照文档试）
 *  1. **`last_time` 被接口完全忽略**：文档 §3 说"可携带上一批最后一条的时间戳翻页
 *     （建议自测确认）"—— 实测传 `last_time` 的**任何值**（最后一条 ctime、ctime−1、
 *     毫秒值、0、`lastTime` 驼峰）**返回的都是同一批**。`nodeapi/telegraphList`
 *     那条路是 404。**所以本 App 的"加载更多"不用游标翻页，而是"把 `rn` 加大再重取、
 *     按 id 去重后追加"**（见 `FlashNewsHolder`）—— 能真的往前翻，代价是每翻一次多传一点。
 *  2. **`rn > 50` 会返回空列表，且 `errno` 仍是 0**（`msg` 为空）——**静默失败**：
 *     如果放任 rn 变大，页面会突然显示"暂无快讯"，看着像应用坏了。
 *     故 [MAX_RN] 硬夹到 50（[safeRn]）。
 *
 * ## 其它
 *  - `ctime` 是 **Unix 秒**。展示成本地时间＝把时间戳按**设备本地时区**格式化，
 *    **不要**手工 +8 —— 时间戳本身没有时区（见 `FlashNewsScreen.flashTimeLabel`）。
 *  - 标题可能含 **`\xa0`（不换行空格）**，解析时按空白 `trim()` 掉首尾。
 *
 * ## 免责
 * 非官方接口，地址/参数/签名规则可能随时调整；本项目只做个人自用展示。
 *
 * 解析一律**防御式**：字段缺失/结构变化返回 null 或跳过该条，绝不抛异常。
 */
object FlashNewsSource {

    /** 固定参数（文档 §3）：app / os / sv 是接口要求的固定值。 */
    const val APP = "CailianpressWeb"
    const val OS = "web"
    const val SV = "8.4.6"

    /** 接口域名与 Referer（缺 Referer 会被拒）。 */
    const val REFERER = "https://www.cls.cn/telegraph"

    /** 浏览器 UA —— 文档明确：非浏览器 UA 可能被拒。 */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** 每批条数默认值。 */
    const val DEFAULT_RN = 20

    /**
     * 每批条数上限 —— **实测 51 起接口就返回空列表且 `errno=0`**（静默失败），
     * 所以这里必须硬夹住，绝不能让它涨到 51+。
     */
    const val MAX_RN = 50

    /** 把 `rn` 夹进合法区间（[MAX_RN] 的说明见上）。 */
    fun safeRn(rn: Int): Int = rn.coerceIn(1, MAX_RN)

    /** 一条快讯。 */
    data class Flash(
        val id: Long,
        /** 发布时间，**Unix 秒**（展示时按设备本地时区格式化，别手工 +8） */
        val ctime: Long,
        val title: String,
        /** 正文（通常以「【标题】…」开头） */
        val content: String,
        /** 摘要（实测与 content 基本一致） */
        val brief: String,
        /** 重要级别（A / B / C，可能为空）。⚠️ 文档只给了字段，没定义各级含义 —— UI **不臆测**，只做标记 */
        val level: String,
        /** 是否置顶（接口给 1 / 0） */
        val isTop: Boolean,
        val readingNum: Int,
        val shareUrl: String,
    ) {
        /** 正文优先、退回摘要（两者实测一致，任缺其一都能显示）。 */
        val body: String get() = content.ifBlank { brief }
    }

    /** 一页快讯（**接口按新→旧返回**）。 */
    data class Page(val items: List<Flash>)

    val EMPTY_PAGE = Page(emptyList())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 组装请求参数（**不含 sign**）。 */
    fun params(rn: Int = DEFAULT_RN): Map<String, String> = mapOf(
        "app" to APP,
        "os" to OS,
        "sv" to SV,
        "rn" to safeRn(rn).toString(),
    )

    /**
     * 计算 `sign`：除 sign 外的参数按 **key 字典序** 拼成 `k=v&k=v`，取 **SHA1** 的
     * 40 位十六进制串，再对该串取 **MD5**。
     *
     * ⚠️ 字典序用 `sortedBy { it.key }`（源码点序 = ASCII 序，与文档 §4 一致）。
     * 本机实调 `errno=0` 验证通过（见 `FlashNewsSourceTest`）。
     */
    fun sign(params: Map<String, String>): String {
        val joined = params.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        return Md5.hex(Sha1.hex(joined))
    }

    /**
     * 解析响应。**纯函数**，便于单测（夹具抄文档 §8 的真实响应）。
     *
     * @return `null` = 网络/结构不可用（页面据此显示「加载失败 + 重试」，与
     *         "接口正常但没有数据"（空页）区分开）。
     */
    fun parse(text: String): Page? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        // errno != 0 是接口报错（如 10012 签名错误）→ 如实算失败，别装作"没有数据"
        val errno = (root["errno"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (errno != null && errno != 0) return null
        val data = root["data"] as? JsonObject ?: return null
        val list = data["roll_data"] as? JsonArray ?: return Page(emptyList())
        val items = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val brief = o.str("brief")
            // 标题缺了就退回摘要（接口偶尔只给其中一边）；两边都没有 → 这条没意义，跳过
            val title = o.str("title").ifBlank { brief }
            if (title.isEmpty()) return@mapNotNull null
            Flash(
                id = o.long("id") ?: 0L,
                ctime = o.long("ctime") ?: 0L,
                title = title,
                content = o.str("content"),
                brief = brief,
                level = o.str("level"),
                isTop = o.long("is_top") == 1L,
                readingNum = (o.long("reading_num") ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                shareUrl = o.str("shareurl"),
            )
        }
        return Page(items)
    }

    // ⚠️ `trim()` 会去掉 `\xa0`（NBSP 属 Kotlin 的 `Char.isWhitespace()`）——
    //    财联社标题里确实出现过 NBSP，顺手清掉首尾，避免渲染出怪空隙。
    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
}
