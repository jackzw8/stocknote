package com.stocknote.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 财联社 7×24 快讯数据源的契约测试（老周 2026-10-04）。
 *
 * ## 钉住的四件事
 *  1. **签名算法**：`sign = MD5(SHA1(参数按 key 字典序拼成 k=v&k=v))`。
 *     错一点点服务端就回 `errno=10012 签名错误`，现象只是"快讯一直加载失败"——
 *     很难往哈希/排序上想，所以这里把文档 §4 的**实例值**直接钉死，
 *     并且**本机实调该签名返回 `errno=0`**（2026-10-04）。
 *  2. **`rn` 上限 50**：实测 **51 起返回空列表且 `errno=0`**（静默失败），
 *     ⇒ 必须夹住，否则页面会突然显示"暂无快讯"。
 *  3. **不发 `last_time`**：实测该接口**忽略**它（任何取值都返回同一批），
 *     把它写进请求只是徒增误解 —— 这里用测试把"不出现该参数"固定下来。
 *  4. **防御式解析**：结构变化返回 null / 跳过坏条目，绝不抛异常。
 */
class FlashNewsSourceTest {

    /** 文档 §8 的实测响应（字段与顺序照抄，2 条 + 1 条用来测坏数据）。 */
    private val realResponse = """
    {
      "errno": 0,
      "msg": "",
      "data": {
        "roll_data": [
          {
            "id": 2497651,
            "title": "2026国庆档总票房破6亿",
            "brief": "【2026国庆档总票房破6亿】财联社10月4日电，据网络平台数据",
            "content": "【2026国庆档总票房破6亿】财联社10月4日电，据网络平台数据",
            "ctime": 1791089176,
            "modified_time": 1791089176,
            "reading_num": 20349,
            "comment_num": 0,
            "level": "A",
            "is_top": 1,
            "shareurl": "https://api3.cls.cn/share/article/2497651?os=web&sv=8.4.6&app=CailianpressWeb",
            "share_img": "https://img.cls.cn/share/roll.png",
            "tags": []
          },
          {
            "id": 2497640,
            "title": "国庆假期前3天高速公路新能源汽车充电近300万次",
            "brief": "【…】财联社10月4日电",
            "content": "【…】财联社10月4日电",
            "ctime": 1791087131,
            "reading_num": 12000,
            "level": "C",
            "is_top": 0,
            "shareurl": "https://api3.cls.cn/share/article/2497640"
          },
          { "id": 2497639, "brief": "", "content": "只有正文、没有标题也没有摘要" }
        ]
      }
    }
    """.trimIndent()

    // ------------------------------------------------------------------ 签名
    @Test
    fun `签名等于文档给出的实例值`() {
        // 文档 §4 的实例：app=CailianpressWeb&os=web&rn=10&sv=8.4.6
        // 期望值由 Python hashlib 独立算出，且本机实调该签名接口返回 errno=0
        assertEquals(
            "2aef5b58b5776913be1d8989ec2e5ebc",
            FlashNewsSource.sign(FlashNewsSource.params(rn = 10)),
        )
    }

    @Test
    fun `请求参数固定为四件套_不夹带 last_time`() {
        val p = FlashNewsSource.params(rn = 20)
        assertEquals(setOf("app", "os", "sv", "rn"), p.keys, "实测 last_time 被接口忽略，别发它")
        assertEquals("CailianpressWeb", p["app"])
        assertEquals("web", p["os"])
        assertEquals("8.4.6", p["sv"])
        assertEquals("20", p["rn"])
        assertFalse(p.containsKey("last_time"))
    }

    @Test
    fun `rn 必须夹在 1 到 50_超过 50 接口会静默返回空列表`() {
        // 实测：rn=50 → 50 条；rn=51/55/100 → count=0 且 errno=0（静默失败）
        assertEquals(50, FlashNewsSource.safeRn(51))
        assertEquals(50, FlashNewsSource.safeRn(1000))
        assertEquals("50", FlashNewsSource.params(rn = 999)["rn"])
        assertEquals(1, FlashNewsSource.safeRn(0))
        assertEquals(1, FlashNewsSource.safeRn(-5), "下限也是 1（接口要的是条数）")
        assertEquals(20, FlashNewsSource.safeRn(20))
    }

    // ------------------------------------------------------------------ 解析
    @Test
    fun `解析实测响应`() {
        val page = assertNotNull(FlashNewsSource.parse(realResponse))
        // 第 3 条没有标题也没有摘要 → 跳过
        assertEquals(2, page.items.size)

        val first = page.items[0]
        assertEquals(2497651L, first.id)
        assertEquals(1791089176L, first.ctime)
        assertEquals("2026国庆档总票房破6亿", first.title)
        assertEquals("A", first.level)
        assertTrue(first.isTop)
        assertEquals(20349, first.readingNum)
        assertTrue(first.shareUrl.startsWith("https://api3.cls.cn/share/article/"))
        assertEquals(first.content, first.body, "有正文时 body 用正文")

        // 接口按**新→旧**返回 —— 列表页直接照序展示，解析不能打乱它
        assertEquals(1791087131L, page.items[1].ctime)
    }

    @Test
    fun `缺标题时退回摘要_两样都没有才跳过`() {
        val text = """
        {"errno":0,"data":{"roll_data":[
          {"id":1,"brief":"只有摘要","ctime":100},
          {"id":2,"title":"","brief":"","ctime":101}
        ]}}
        """.trimIndent()
        val page = assertNotNull(FlashNewsSource.parse(text))
        assertEquals(1, page.items.size)
        assertEquals("只有摘要", page.items[0].title)
        assertEquals("只有摘要", page.items[0].body, "没有正文时 body 退回摘要")
    }

    @Test
    fun `接口报错或结构异常返回 null_不装作没有数据`() {
        // errno != 0（如 10012 签名错误）—— 必须算"失败"，否则页面会显示"暂无快讯"
        assertNull(FlashNewsSource.parse("""{"errno":10012,"msg":"签名错误","data":null}"""))
        assertNull(FlashNewsSource.parse("""{"errno":1,"msg":"x"}"""))
        // 不是 JSON / 拦截页
        assertNull(FlashNewsSource.parse("<html>404</html>"))
        assertNull(FlashNewsSource.parse(""))
        // 结构在但没有 data
        assertNull(FlashNewsSource.parse("""{"errno":0}"""))
    }

    @Test
    fun `空列表是正常结果_要的是空页而不是失败`() {
        val page = assertNotNull(FlashNewsSource.parse("""{"errno":0,"msg":"","data":{"roll_data":[]}}"""))
        assertTrue(page.items.isEmpty())
    }
}
