package com.stocknote.feature.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.stocknote.data.log.SnLog

/**
 * Android 端：内嵌 WebView 打开资讯正文（老周 2026-09-24）。
 *
 * 说明：
 *  - 腾讯的详情页是 **Vue 单页应用**（服务端只回空壳 HTML），所以**必须开 JS**才能渲染出正文；
 *    这也正是"直接抓 URL 拿不到内容、只能内嵌网页"的原因。
 *  - ⚠️ **导航白名单**（P1-20，2026-10-02）：内嵌页没有地址栏，页内链接不能随便放行 ——
 *    规则三端共用一份，见 commonMain 的 `InAppNavigationHosts`。
 *  - ⚠️ 用 `AndroidView` 的 `factory` 只创建一次、`update` 里 loadUrl：
 *    不要每次重组都重建 WebView（会不断重新加载、闪屏）。
 *  - 页面加载完成后注入 [CleanPageJs]，去掉腾讯正文页里的**自选股推广条**（老周 2026-09-24 提出）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun HtmlView(url: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // ⚠️ P1-20：这三项**显式**关掉。它们的系统默认值随 API 版本变化
                //（minSdk 24 ~ 35 并不一致），不写死就等于"取运行设备的默认"，安全基线不可控。
                // 正文页是纯 https 的 SPA，用不到文件/内容访问，关掉不影响渲染（真机已验证）。
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowUniversalAccessFromFileURLs = false

                webViewClient = object : WebViewClient() {
                    /**
                     * P1-20：**导航白名单**（内嵌页没有地址栏，必须自己挡一道）。
                     *
                     * - 白名单内的 https → 留在本 WebView（原本"站内跳转不跳出应用"的意图）；
                     * - 其它 http(s) → 交给系统浏览器（有地址栏，用户能自己判断）；
                     * - 非 http(s)（`intent://` / `market://` …）→ **拦下且不转交**
                     *   （这类 URL 能拉起任意组件，转交出去等于把攻击面原样递过去）。
                     *
                     * ⚠️ `loadUrl()` 直接加载**不经过**这里 —— 所以 App 自己给的正文 URL 天然放行，
                     * 不用像 iOS 那样额外记一份主文档 URL（两端差异见 commonMain 的注释）。
                     */
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val target = request.url.toString()
                        return when {
                            isInAppNavigationAllowed(target) -> false
                            isHttpUrl(target) -> {
                                SnLog.i(NAV_LOG_TAG, "站外链接交给系统浏览器：$target")
                                openInSystemBrowser(view.context, target)
                                true
                            }
                            else -> {
                                SnLog.i(NAV_LOG_TAG, "已拦下非 http(s) 导航：$target")
                                true
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        // 诊断：确认回调确实到了、脚本确实执行了（拿 evaluateJavascript 的回调）
                        println("[SN_CLEAN] onPageFinished url=" + finishedUrl)
                        view.evaluateJavascript(CleanPageJs) { result ->
                            println("[SN_CLEAN] evalResult=" + result)
                        }
                    }
                }
                // 兜底注入：SPA 首次 onPageFinished 未必会到（实测有时压根不触发），
                // 延迟再注入一次，不依赖回调时序；脚本自身幂等（style 按 id 去重）。
                postDelayed({
                    runCatching {
                        evaluateJavascript(CleanPageJs) { result ->
                            println("[SN_CLEAN] evalDelay=" + result)
                        }
                    }
                }, 1500)
                // 只为把清理脚本的执行结果打到 logcat（排查用），不接管页面行为
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        println("[SN_CLEAN] " + m.message())
                        return true
                    }
                }
                // 公告正文常见形态是「独立文件 + 点击下载文件」（老周 2026-09-24 问的下载）：
                // WebView 自身**不支持文件下载**（不设监听则点击毫无反应），这里交给系统处理
                // （弹出的选择器里用户可用浏览器/下载器打开或保存 PDF）。
                // ⚠️ P1-20：只放行 http(s)；非 http(s) 的"下载链接"（含 `intent://`）
                // **不处理**，避免把任意 component 拉起。
                setDownloadListener { downloadUrl, _, _, _, _ ->
                    if (isHttpUrl(downloadUrl)) {
                        openInSystemBrowser(ctx, downloadUrl)
                    } else {
                        SnLog.i(NAV_LOG_TAG, "已忽略非 http(s) 下载链接：$downloadUrl")
                    }
                }
            }
        },
        update = { view ->
            // 只在 URL 变化时加载（AndroidView.update 每次重组都会调用）
            if (view.url != url) view.loadUrl(url)
        },
        // ⚠️ L4（P1-20 顺带，2026-10-02）：`factory` 里那个 postDelayed 的兜底注入此前
        // **没有配对清理、也没有释放 WebView** —— 在 1.5 秒内退出详情页时，回调仍会打到
        // 已销毁的 WebView 上（持有引用 + 无谓工作）。
        // `removeCallbacks(null)` = 移除该 view 上所有待执行回调。
        onRelease = { view ->
            view.removeCallbacks(null)
            view.stopLoading()
            view.destroy()
        },
    )
}

/**
 * Android 端「交给系统浏览器/选择器」—— [rememberBrowserOpener] 与 [HtmlView] 的导航拦截共用。
 *
 * ⚠️ 只用来转交 `http(s)`（调用方已用 [isHttpUrl] 把关）：把 `intent://` 这类 URL 交给
 * `ACTION_VIEW` 正是 P1-20 要关掉的那条攻击面。
 */
internal fun openInSystemBrowser(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { SnLog.w(NAV_LOG_TAG, "转交系统浏览器失败：$url", it) }
}

/**
 * Android 端「用浏览器打开」：交给系统处理（浏览器能下载 PDF、能处理
 * WebView 点不动的链接，见 commonMain 的注释）。
 */
@Composable
actual fun rememberBrowserOpener(): (String) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(ctx) {
        { url: String -> openInSystemBrowser(ctx, url) }
    }
}

// 清理脚本已提升到 commonMain（`CleanPageJs`，见 HtmlView.kt）—— 桌面端要注入**同一份**，
// 避免两端"清理范围不一致"（老周 2026-09-30：桌面版红框里的「相关资讯」+ 底部下载条没被去掉）。
