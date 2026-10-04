package com.stocknote.feature.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **内嵌网页导航白名单的契约测试**（P1-20，老周 2026-10-02）。
 *
 * ## 钉住的不变量
 * 资讯正文是**内嵌的第三方网页**（腾讯 `gu.qq.com` 详情页），而内嵌页**没有地址栏** ——
 * 页内链接能不能放行、放行到哪，**只能靠这套规则**（`HtmlView.kt` 的
 * [InAppNavigationHosts] / [isInAppNavigationAllowed] / [isHttpUrl]），三端共用同一份实现。
 *
 * 所以这里把它做成**可回归的**（真机上"点外链是否走浏览器"不好自动测，但判据本身可以）：
 *  - 白名单内的 https → `true`（留在内嵌页）；
 *  - 白名单外的 http(s) → `false` 但 [isHttpUrl] `true`（交系统浏览器，有地址栏可判断）；
 *  - 非 http(s)（`intent://` / `market://` / `weixin://` / `tel:`…）→ 两者都 `false`
 *    （**拦下且不转交** —— 这类 URL 能拉起任意组件，转交出去等于把攻击面原样递过去）；
 *  - 页面内部的本地 scheme（`about:` / `data:` / `blob:` / `javascript:`）→ `true`。
 *
 * ⚠️ 最容易翻车的一条：host 匹配必须**后缀 + 点号**。少了点号，`gu.qq.com.evil.com`
 * 就会被当成站内页放行，白名单等于没写 —— 见 [后缀匹配必须带点号_否则白名单可被绕过]。
 */
class HtmlViewNavigationTest {

    @Test
    fun `白名单内的域放行`() {
        assertTrue(isInAppNavigationAllowed("https://gu.qq.com/a/20261002"))
        assertTrue(isInAppNavigationAllowed("https://a.gu.qq.com/x")) // 子域
        assertTrue(isInAppNavigationAllowed("https://finance.eastmoney.com/news"))
        assertTrue(isInAppNavigationAllowed("https://www.cninfo.com.cn/new/disclosure"))
        assertTrue(isInAppNavigationAllowed("https://pgdt.gtimg.cn/141/2026/x.jpg"))
    }

    @Test
    fun `后缀匹配必须带点号_否则白名单可被绕过`() {
        // 攻击者可控的域，绝不能命中白名单
        assertFalse(isInAppNavigationAllowed("https://gu.qq.com.evil.com/x"))
        assertFalse(isInAppNavigationAllowed("https://eastmoney.com.evil.com/x"))
        // 只是"包含"白名单串，也不是站内
        assertFalse(isInAppNavigationAllowed("https://evil-gu.qq.com/x"))
        assertFalse(isInAppNavigationAllowed("https://notgtimg.cn/x"))
    }

    @Test
    fun `腾讯公告文件域与东财文件域也放行`() {
        // ⚠️ 2026-10-03 真机回归：公告 PDF 落在 `file.finance.qq.com`，当时不在白名单 ⇒
        // 老周点一次公告就被甩到系统浏览器（日志里同一条刷了 23 行）。这两个域现已补进白名单。
        assertTrue(
            isInAppNavigationAllowed(
                "https://file.finance.qq.com/finance/hs/pdf/2026/08/15/1225475868.PDF",
            ),
        )
        assertTrue(isInAppNavigationAllowed("https://pdf.dfcfw.com/pdf/H2_AN2026_1.pdf"))
    }

    @Test
    fun `PDF 链接可被识别_交给内置阅读器`() {
        // 老周 2026-10-04：公告点「文件」跳到 PDF —— 白名单里的文件域会让它留在 WebView 里，
        // 而 Android 的 WebView 渲染不了 PDF ⇒ 必须能把它认出来、改用内置阅读器。
        assertTrue(
            isPdfUrl("https://file.finance.qq.com/finance/hs/pdf/2026/08/15/1225475868.PDF"),
            "腾讯公告 PDF（真机日志里的那条，扩展名大写）",
        )
        assertTrue(isPdfUrl("https://pdf.dfcfw.com/pdf/H2_AN2026_1.pdf"), "东财公告 PDF")
        assertTrue(isPdfUrl("https://x.com/a/b.pdf?v=2#page=3"), "带 query / fragment 也算")
        assertTrue(isPdfUrl("  HTTPS://X.COM/A/B.PDF  "), "大小写与前后空格都要吃掉")

        // 非 PDF 绝不能误判（正文页 / 图片仍走 WebView）
        assertFalse(isPdfUrl("https://gu.qq.com/resources/shy/news/detail-v2/index.html#/?id=nos1&s=b"))
        assertFalse(isPdfUrl("https://pgdt.gtimg.cn/141/2026/x.jpg"))
        assertFalse(isPdfUrl("https://x.com/a/pdf"))      // 只是路径里有 pdf 字样
        assertFalse(isPdfUrl("https://x.com/a.pdfx"))
        assertFalse(isPdfUrl("intent://x/#Intent;end"))   // 非 http(s) 不接管
        assertFalse(isPdfUrl(""))
    }

    @Test
    fun `同一 URL 短时间内的重复导航会被去重`() {
        // 真机实测：同一次点击，导航回调被**重复触发 23 次**。不去重就会反复弹系统浏览器 + 刷爆日志。
        // ⚠️ 本用例依赖 1.5 秒的时间窗口，只断言"紧接着的重复被吞掉"，不睡等窗口过期（避免用例变慢/变飘）。
        val url = "https://file.finance.qq.com/finance/hs/pdf/x.PDF"
        assertTrue(InAppNavigationDedupe.shouldHandle(url), "首次应当处理")
        assertFalse(InAppNavigationDedupe.shouldHandle(url), "紧随其后的重复应当被吞掉")
        assertFalse(InAppNavigationDedupe.shouldHandle(url), "连续多次重复都应被吞掉")
        // 换一个 URL 立刻算新的（用户点了另一条链接，不能吞）
        assertTrue(InAppNavigationDedupe.shouldHandle("https://example.com/a"))
    }

    @Test
    fun `白名单外的网页不放行_但仍可交给系统浏览器`() {
        assertFalse(isInAppNavigationAllowed("https://example.com/article"))
        assertTrue(isHttpUrl("https://example.com/article"))

        // 腾讯的资讯详情页域名不止一个（如 new.qq.com）——它们同样走系统浏览器（有地址栏）
        assertFalse(isInAppNavigationAllowed("https://new.qq.com/rain/a/20261002A0001"))
        assertTrue(isHttpUrl("https://new.qq.com/rain/a/20261002A0001"))
    }

    @Test
    fun `只放行 https_http 老链交给浏览器`() {
        assertFalse(isInAppNavigationAllowed("http://gu.qq.com/x"))
        // 但它是"可转交"的 http(s)，不是"该被拦下"的 scheme
        assertTrue(isHttpUrl("http://gu.qq.com/x"))
    }

    @Test
    fun `非 http_scheme_一律拦下且不转交`() {
        // P1-20 的核心攻击面：intent:// 能带 component / extra 拉起任意组件
        val intent = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        assertFalse(isInAppNavigationAllowed(intent))
        assertFalse(isHttpUrl(intent))

        for (scheme in listOf("market://details?id=x", "weixin://dl/business", "tel:10086", "mailto:a@b.c")) {
            assertFalse(isInAppNavigationAllowed(scheme), "不该放行：$scheme")
            assertFalse(isHttpUrl(scheme), "不该转交：$scheme")
        }

        // 大小写混写也一样
        assertFalse(isHttpUrl("INTENT://scan/#Intent;end"))
    }

    @Test
    fun `页面内部的本地 scheme 放行`() {
        // SPA 会用 blob:/data: 渲染内容；about:blank 是常见的内部占位导航
        assertTrue(isInAppNavigationAllowed("about:blank"))
        assertTrue(isInAppNavigationAllowed("data:text/html;charset=utf-8,%3Cp%3Ex"))
        assertTrue(isInAppNavigationAllowed("blob:https://gu.qq.com/1234-5678"))
        assertTrue(isInAppNavigationAllowed("javascript:void(0)"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(isInAppNavigationAllowed("HTTPS://GU.QQ.COM/x"))
        assertTrue(isHttpUrl("HTTPS://gu.qq.com/x"))
    }

    @Test
    fun `host_解析要能吃掉端口_账号密码_查询串与结尾点`() {
        assertEquals("gu.qq.com", urlHost("https://gu.qq.com/x"))
        assertEquals("gu.qq.com", urlHost("https://gu.qq.com:8443/x"))
        assertEquals("gu.qq.com", urlHost("https://user:pw@gu.qq.com:8443/x?y=1#z"))
        assertEquals("gu.qq.com", urlHost("https://gu.qq.com."))
        assertEquals("gu.qq.com", urlHost("https://gu.qq.com?y=1"))
        // 解析不出 host 的一律当"非站内"，宁可外放
        assertNull(urlHost(""))
        assertNull(urlHost("not a url"))
        assertNull(urlHost("https://"))
        assertNull(urlHost("https:///only-path"))
        assertFalse(isInAppNavigationAllowed("https://"))
    }
}
