package com.stocknote.feature.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 快讯列表的两个**纯展示函数**回归（老周 2026-10-04）。
 *
 * 为什么不放在页面里靠肉眼：这两处都是"看着对、其实错"的地方 ——
 * ① 财联社正文惯例以「【标题】」开头，标题已单列，不去重就是同一句话出现两遍；
 *   但**去重不能过头**（`【某部门】`这类是真内容，删了就丢信息）；
 * ② 阅读数上万要缩写，`fixedPlain` 又**不受隐私遮罩影响**（这里不是盈亏数字）。
 */
class FlashNewsFormatTest {

    @Test
    fun `去掉正文里与标题重复的方括号前缀`() {
        assertEquals(
            "财联社10月4日电，据网络平台数据",
            bodyWithoutTitlePrefix("2026国庆档总票房破6亿", "【2026国庆档总票房破6亿】财联社10月4日电，据网络平台数据"),
        )
        // 标题是前缀的一部分（或反之）也算同一句
        assertEquals(
            "财联社10月4日电",
            bodyWithoutTitlePrefix("国庆档总票房破6亿", "【2026国庆档总票房破6亿】财联社10月4日电"),
        )
        // 去掉前缀后没内容了 → 返回空串（页面据此不显示正文行）
        assertEquals("", bodyWithoutTitlePrefix("标题", "【标题】").trim())
    }

    @Test
    fun `不是同一句话的方括号前缀不能删`() {
        // 宁可重复一下，也别把「【某部门】」这类真内容误删
        assertEquals("【商务部】就某问题答记者问", bodyWithoutTitlePrefix("答记者问", "【商务部】就某问题答记者问"))
        // 不以【开头 → 原样
        assertEquals("财联社10月4日电", bodyWithoutTitlePrefix("标题", "财联社10月4日电"))
        assertEquals("", bodyWithoutTitlePrefix("标题", ""))
    }

    @Test
    fun `阅读数上万才缩写_且不受隐私遮罩影响`() {
        assertEquals("", readingLabel(0), "没有阅读数就不显示")
        assertEquals("999 阅读", readingLabel(999))
        assertEquals("9999 阅读", readingLabel(9_999))
        assertEquals("1.2万阅读", readingLabel(12_000))
        assertEquals("2.0万阅读", readingLabel(20_349))
    }
}
