package com.stocknote.feature.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 站内网页视图（老周 2026-09-24）：用于新闻正文 —— 腾讯行情资讯的正文页是 H5（SPA，
 * 服务端只给空壳、由 JS 渲染），抓 HTML 拿不到内容，所以直接内嵌网页渲染。
 *
 *  - **Android**：内嵌 [android.webkit.WebView]（站内跳转，不跳出应用）
 *  - **iOS**：内嵌 `WKWebView`（老周 2026-10-02 补齐；此前是"跳系统浏览器"的临时方案）
 *  - **桌面**：内嵌 JavaFX `WebView`（SwingPanel + JFXPanel）
 */
@Composable
expect fun HtmlView(url: String, modifier: Modifier = Modifier)

/**
 * 「用系统浏览器打开」动作（老周 2026-09-24）。
 *
 * 为什么需要它：资讯详情是内嵌网页，但**有些操作在 WebView 里做不了** ——
 * 典型是公告的「点击下载文件」（腾讯页面在网页模式下只是**复制链接**或让浏览器接管，
 * 在 App 内点了毫无反应，真机实测确认），PDF 也没法在 WebView 里渲染。
 * 给详情页一个"交给浏览器"的出口，这类链接就能正常下载/打开了。
 */
@Composable
expect fun rememberBrowserOpener(): (String) -> Unit

/**
 * 内嵌网页的**导航白名单**（P1-20，2026-10-02，来自《代码审核报告》H2）。
 *
 * ## 为什么必须自己挡一道
 * 资讯正文是**内嵌的第三方网页**，而内嵌页**没有地址栏**：被跳到钓鱼页或恶意链接时，
 * 用户根本无从分辨（看上去还在 App 里）。这是 App 内部画面，缺的正是最后一道防线。
 *
 * ## 规则（三端共用**同一份**，别各写一套）
 *  1. **只放行 `https` 且 host 在白名单内**的导航 → 留在内嵌页里（原来的"站内跳转不跳出应用"）;
 *  2. 白名单**之外**的 `http(s)` → 交给系统浏览器（`rememberBrowserOpener`，那里有地址栏可判断）;
 *  3. **非 `http(s)` 的 scheme（`intent://` / `market://` / `weixin://` / `tel:` …）一律不放行、
 *     也不转交** —— 这类 URL 能被用来拉起任意组件（`intent://` 尤其危险），
 *     转交出去等于把攻击面原样递过去;
 *  4. `about:` / `data:` / `blob:` / `javascript:` 属**页面内部的本地 scheme**，
 *     拉不起任何外部组件，放行（SPA 会用 `blob:` 渲染内容）。
 *
 * ⚠️ **只约束"页面内发起的导航"**，不约束 App 自己给的那个正文 URL
 *（否则详情页一打开就被甩进浏览器 —— iOS 的 `decidePolicyForNavigationAction`
 * 连初始加载一起问，所以那边额外记了一份"主文档 URL"，见 `HtmlView.ios.kt`）。
 *
 * ⚠️ host 匹配用**后缀 + 点号**：`gu.qq.com` 命中 `gu.qq.com` 与 `a.gu.qq.com`，
 * 但**不**命中 `gu.qq.com.evil.com` —— 少了这个点号，白名单等于没写。
 */
internal val InAppNavigationHosts = listOf(
    "gu.qq.com", // 腾讯资讯正文（资讯详情走的就是这个域）
    "eastmoney.com", // 东方财富（资讯、公告）
    "cninfo.com.cn", // 巨潮资讯（公告原文）
    "gtimg.cn", // 腾讯的行情静态资源/图片域
)

/** 页面内部的本地 scheme：拉不起外部组件，一律放行。 */
private val LocalOnlySchemes = listOf("about:", "data:", "blob:", "javascript:")

/**
 * 导航拦截的日志标签（P1-20）。
 *
 * 放 commonMain 一份、三端共用：用户报"点链接没反应 / 莫名跳走了"时，
 * 「设置 → 数据管理 → 导出运行日志」里按 `NAV` 搜就能看到拦了什么、外放了什么。
 */
internal const val NAV_LOG_TAG = "NAV"

/**
 * 该**导航**是否允许留在内嵌页里（规则见 [InAppNavigationHosts]）。
 *
 * 只对 `https` 放行：正文与其站内链接现在都是 https，http 老链交给浏览器更安全。
 */
internal fun isInAppNavigationAllowed(url: String): Boolean {
    val trimmed = url.trim()
    if (LocalOnlySchemes.any { trimmed.startsWith(it, ignoreCase = true) }) return true
    if (!trimmed.startsWith("https://", ignoreCase = true)) return false
    val host = urlHost(trimmed) ?: return false
    return InAppNavigationHosts.any { host == it || host.endsWith(".$it") }
}

/** 是否是可交给系统处理（浏览器/选择器）的 `http(s)` 链接；**其它 scheme 一律不转交**。 */
internal fun isHttpUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
}

/**
 * 从 URL 里取 host。
 *
 * ⚠️ 刻意**手写字符串处理**而不是 `java.net.URI`：commonMain 禁止 JVM 专有 API，
 * 有 `CommonMainPlatformLeakTest` 把关。
 */
internal fun urlHost(url: String): String? {
    val afterScheme = url.substringAfter("://", "")
    if (afterScheme.isEmpty()) return null
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPort = authority.substringAfterLast('@') // 去掉 user:pass@
    val host = hostPort.substringBefore(':') // 去掉 :port
    return host.trim().trimEnd('.').lowercase().ifEmpty { null }
}

/**
 * 隐藏腾讯资讯正文页里**推广 / 无关模块**的注入脚本（老周 2026-09-24 提，2026-09-30 提到 commonMain 供桌面共用）。
 *
 * 覆盖的模块（都是"红框部分"）：
 *  - 顶部推广条：`.recom-logo`（含"做更好的财经信息平台"文案）+ `.recom-bg`（背景图）
 *  - 底部下载条：`<div id="down">`
 *  - **相关资讯**：`.review-stock`（整块）+ `.related-review-list`（列表项）
 *    （源码里该组件根节点是 `ref="relatedNews"`，**ref 不能用于 CSS 选择**，故选内部类名；
 *     实测这两个类在整份 bundle 里各只出现 1 次 —— 专属类，不会误伤别处。
 *     显示条件是 `news.length >= 3`，这就是"**有时候**才出现"的原因）
 *  - 微信搜一搜引导块：`.wx-sou`（后加载，实测 3.2s 才出现）
 *
 * 选择器来源：扒 `gu.qq.com` 详情页的 app.js 源码得到（**不是猜的**）。
 *
 * ⚠️ **只做 CSS 精确隐藏，不做"按文案向上找容器"的兜底**（2026-09-24 实测教训）：
 * 上一版这么干过，结果上溯到了**页面主容器**，整个正文区变空白（真机截图确认）。
 * 每加一个选择器前，都要先确认它不会命中正文容器。
 *
 * 定时重复 25 次 × 400ms（约 10 秒）+ 滚动时补注入：SPA 渲染与滚动懒加载都会后插节点。
 *
 * ⚠️ 平台接入方式不同但脚本同一份：Android 在 `onPageFinished` + 延迟兜底里 `evaluateJavascript`；
 * 桌面（JavaFX）在 `LoadWorker` 变为 `SUCCEEDED` 时 `engine.executeScript`；
 * iOS（WKWebView）在 `didFinishNavigation` 里 `evaluateJavaScript`。
 */
internal const val CleanPageJs = """
(function(){
  var STYLE_ID='stocknote_clean';
  function inject(){
    if(!document.getElementById(STYLE_ID)){
      var s=document.createElement('style'); s.id=STYLE_ID;
      s.textContent='#down,.recom-logo,.recom-bg,.review-stock,.related-review-list,.wx-sou{display:none !important;}';
      (document.head||document.documentElement).appendChild(s);
    }
  }
  function report(){
    inject();
    try{
      var d=document.getElementById('down');
      var rc=document.querySelector('.recom-logo');
      var rb=document.querySelector('.recom-bg');
      var rs=document.querySelector('.review-stock');
      var ws=document.querySelector('.wx-sou');
      var txt=(document.body&&document.body.innerText)||'';
      console.log('SN_CLEAN down=' + (d?getComputedStyle(d).display:'absent') +
        ' recom-logo=' + (rc?getComputedStyle(rc).display:'absent') +
        ' recom-bg=' + (rb?getComputedStyle(rb).display:'absent') +
        ' review-stock=' + (rs?getComputedStyle(rs).display:'absent') +
        ' wx-sou=' + (ws?getComputedStyle(ws).display:'absent') +
        ' | textlen=' + txt.length);
    }catch(e){}
  }
  report();
  // 定时重复：SPA 渲染 + 滚动懒加载都会后插节点（实测"微信搜一搜"块 1.6s 时还没有、3.2s 才出现）→ 窗口给到 10 秒
  var i=0, tm=setInterval(function(){ i++; report(); if(i>24) clearInterval(tm); }, 400);
  // 滚动时只补 CSS（不刷日志）：滚动到该位置才懒加载的块也能被盖住
  try{ window.addEventListener('scroll', inject, {passive:true}); }catch(e){}
})();
"""
