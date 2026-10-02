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
