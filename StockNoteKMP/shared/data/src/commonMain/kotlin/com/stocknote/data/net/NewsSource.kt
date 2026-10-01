package com.stocknote.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * 腾讯自选股**个股资讯**数据源（老周 2026-09-24 实测通的接口，替代此前按市场分源的东财方案）。
 *
 * ## 接口
 * ```
 * GET https://proxy.finance.qq.com/ifzqgtimg/appstock/news/info/search
 *   type    必填   0=公告 · 1=研报 · 2=资讯/新闻
 *   symbol  必填   sh600519 / sz000001 / hk00700
 *   page    可选   默认 1
 *   n       可选   默认 20
 * Headers: User-Agent: Mozilla/5.0 · Referer: https://stockapp.finance.qq.com/
 * ```
 * 返回：`{"code":0,"data":{"total_num":9999,"total_page":3333,"data":[ ... ]}}`，条目字段：
 * `time`("2026-09-24 11:20:08") · `title` · `src`(来源) · `url`(详情 H5) · `summary` ·
 * `symbols`(关联股) · `importance`(重要度) · `app_detail_link`(腾讯自选股 App 深链)。
 *
 * ## 实测踩过的坑（别再重复试）
 *  - 三个参数是**逐步校验**的，报错文案只会指出「当前缺的那个」：
 *    不带 type → `type param error`；给了 type 不给 page → `page param error`；
 *    给了 page 不给 n → 直接返回数据（n 可省）。
 *  - ⚠️ **`n` 与 `page` 必须成对**：只传 `n` 不传 `page` 会报 `page param error`。
 *  - 每页条数的参数名是 **`n`**（不是 size/limit/count/pageSize，试过全无效）。
 *  - `type=2` 的 `summary` / `llm_content` 字段**实测恒为空**，正文只能靠 `url` 指向的 H5 页 —— 所以
 *    详情页走 WebView 打开该 URL，不在本地拼正文（不要试图从列表接口取正文）。
 *  - 港股同样可用（`symbol=hk00700`），比此前"按市场分源"的方案省事得多。
 *
 * 解析一律**防御式**：字段缺失/结构变化跳过该条，绝不抛异常 —— 新闻是纯展示，
 * 接口改版最多列表变空，不能把页面带崩。
 */
object NewsSource {

    /** 新闻类型。code 即接口的 type 值。 */
    enum class Kind(val code: Int, val label: String) {
        ANNOUNCEMENT(0, "公告"),
        RESEARCH(1, "研报"),
        NEWS(2, "资讯"),
    }

    data class NewsItem(
        val id: String,
        /** 展示用时间，形如 "2026-09-24 11:20"（接口给到秒，展示截到分） */
        val time: String,
        val title: String,
        /** 来源：资讯是媒体名（新浪财经等），研报是券商名 */
        val src: String,
        /** 详情页地址（腾讯 H5）；拿不到时为 ""，此时详情页给提示而不是空白 */
        val url: String,
        val summary: String,
        /** 重要度（接口按需给，可能缺失） */
        val importance: Double?,
    )

    /** 一页结果。 */
    data class Page(
        val items: List<NewsItem>,
        val totalCount: Int,
        val totalPage: Int,
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析一页资讯。**纯函数**，便于单测（夹具抄真实响应）。
     */
    fun parse(text: String): Page {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return EMPTY_PAGE
        // code != 0 表示接口报错（如 type 不合法），如实返回空页而不是抛异常
        val code = (root["code"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (code != null && code != 0) return EMPTY_PAGE
        val data = root["data"] as? JsonObject ?: return EMPTY_PAGE
        val list = data["data"] as? JsonArray ?: return EMPTY_PAGE
        val items = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = o.str("title")
            if (title.isEmpty()) return@mapNotNull null
            NewsItem(
                id = o.str("id"),
                time = normalizeTime(o.str("time")),
                title = title,
                src = o.str("src"),
                url = o.str("url"),
                summary = o.str("summary"),
                importance = o.num("importance"),
            )
        }
        return Page(
            items = items,
            totalCount = (data["total_num"] as? JsonPrimitive)?.content?.toIntOrNull() ?: items.size,
            totalPage = (data["total_page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1,
        )
    }

    private val EMPTY_PAGE = Page(emptyList(), 0, 0)

    /**
     * 详情页地址（**统一产出可直接加载的 URL**）。老周 2026-09-24 反馈的两类问题都在这里收敛：
     *
     *  1. **研报等条目的 `url` 是明文 `http://`** → Android 9+ 默认禁止 cleartext HTTP，
     *     WebView 直接报 `ERR_CLEARTEXT_NOT_PERMITTED`（真机截图确认）→ 统一升级成 `https://`
     *     （腾讯同路径支持 https，实测同级页面 200）。
     *  2. **公告条目的 `url` 字段为空**（实测 type=0 恒为空）→ 用 `id`（形如 `nos1225475868`）
     *     拼腾讯通用详情模板（实测该模板对公告 id 也返回 200）。
     *
     * 两条信息都拿不到时返回 ""，由详情页如实提示「没有可打开的正文链接」。
     */
    fun detailUrl(item: NewsItem): String {
        var u = item.url.trim()
        if (u.isEmpty() && item.id.isNotEmpty()) {
            // ⚠️ 轻微-4 修复（2026-09-28）：id **先校验**再拼链 —— 含特殊字符（&、#、空格）会拼出坏链。
            // 共享层没有 URLEncoder，直接按腾讯 id 的实际格式（字母/数字/连字符/下划线）做白名单校验。
            if (!item.id.matches(Regex("[A-Za-z0-9_\\-]+"))) return ""
            u = "https://gu.qq.com/resources/shy/news/detail-v2/index.html#/?id=${item.id}&s=b"
        }
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        // ⚠️ 轻微-4：host 白名单 —— 接口返回的 url 只放行腾讯系域名，防止被拼成任意外链
        val host = runCatching {
            u.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        }.getOrNull()
        val trusted = host != null && (host == "qq.com" || host.endsWith(".qq.com"))
        return if (trusted) u else ""
    }

    /** "2026-09-24 11:20:08" → "2026-09-24 11:20"（长度不足时原样返回，不编造） */
    internal fun normalizeTime(raw: String): String =
        if (raw.length >= 16) raw.take(16) else raw

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
}
