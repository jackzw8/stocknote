package com.stocknote.feature.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *  - **公告 PDF 走内置阅读器**（老周 2026-10-04）：WebView 渲染不了 PDF，
 *    命中 [isPdfUrl] 的导航不交给 WebView，改为显示 [PdfPagesView]（网页仍留在下层，关掉即回）。
 */
@Composable
actual fun HtmlView(url: String, modifier: Modifier) {
    // ---- 内置 PDF 阅读（老周 2026-10-04）----
    // 公告里的「文件」是 PDF，而 **WebView 渲染不了 PDF** → 由 [isPdfUrl] 识别、切到内置阅读器。
    // ⚠️ 用 Box 把网页**留在组合里**（只是被阅读器盖住）：关掉 PDF 后回到原网页，不重新加载、
    // 用户在公告列表里的滚动位置也不会丢。
    var pdfUrl by remember { mutableStateOf<String?>(null) }

    Box(modifier) {
        NewsWebView(
            url = url,
            onPdf = { pdfUrl = it },
            modifier = Modifier.fillMaxSize(),
        )
        pdfUrl?.let { target ->
            PdfPagesView(
                url = target,
                onClose = { pdfUrl = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 本地 HTML 用的**真 URL**。只作内容标识：请求由 [LocalHtmlView] 的拦截器**就地应答**，
 * **不会**真的走网络（本机没有这个域）。
 */
private const val LOCAL_HTML_URL = LOCAL_HTML_BASE_URL + "local-html.html"

/**
 * Android 端：内嵌**本地 HTML 字符串**（老周 2026-10-04，探索页「星际战机」小游戏）。
 *
 * ## ⚠️ 为什么**不能**用 `loadDataWithBaseURL`（真机踩坑，2026-10-04）
 * 首版就是用它，结果真机打开**一片黑**（只剩我们自己的底色）：该 API 内部是把内容
 * **拼成一个 `data:` URL** 再交给内核，于是 HTML 里的 `#` 会被当成 URL 的 **fragment** ——
 * 本页第一个 `#` 在第 6 行 `<meta name="theme-color" content="#05070f">`，
 * 文档**在那里就被截断**，只剩半个 `<head>`、没有 body，自然什么都不渲染。
 * （`loadData` 同样如此，所以它才要求调用方自己先做 URL 编码。）
 *
 * ## 现在的做法：给它一个**真 URL**，由 `shouldInterceptRequest` 就地回内容
 * 让 WebView 加载 `https://stocknote.local/local-html.html`，在拦截回调里用内存中的 HTML 应答。
 * 好处：
 *  1. **完全不经过 URL 编码** ⇒ 任何字符（`#` / `%` / `&` / 中文）都原样送达；
 *  2. 页面拿到**正常 https origin** ⇒ 游戏存最高分的 `localStorage` 才可用
 *     （`data:` 或裸 `loadData` 是不透明源，访问会抛异常）；
 *  3. 与官方 `WebViewAssetLoader` 同一套路，只是内容来自字符串而不是 assets。
 *
 * ## ⚠️ 第二个坑：页面自己的**百分比高度链会算成 0**
 * 加载成功、DOM 完整、尺寸也对，屏幕仍可能一片空白 —— 那是页面侧的坑：本页
 * `html,body{height:100%} → #stage{height:100%}` 在 WebView 里解析成 0，连 `100vh` 也量到 0px
 * （而 `documentElement.clientHeight` 是正常的），于是 `#stage{overflow:hidden}` 把子元素全剪掉。
 * 解法是给**显式像素高度**，见 `PlaneShooterScreen` 的 `ViewportFitJs`。
 * **排查手法（别靠截图猜）**：`evaluateJavascript` 量 `documentElement.clientHeight` /
 * `body.clientHeight` / `#stage` 的 rect 与 `getComputedStyle().height`，一眼就能看出来。
 *
 * ⚠️ 加载放在 `update`（**挂载之后**）而不是 `factory`：与资讯正文那条已跑通的路保持一致 ——
 * 视图还没 attach 就发起加载，行为没有保证。
 * ⚠️ `html` 是常量（`PlaneShooterHtml`）时加载一次即可：靠 `view.url` 比对避免每次重组重刷。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun LocalHtmlView(html: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowUniversalAccessFromFileURLs = false
                // 游戏音效在 pointerdown / 点「开始远征」时建 AudioContext（已是用户手势），
                // 这里再放开一道，避免个别机型把手势判定得过严而整局无声。
                settings.mediaPlaybackRequiresUserGesture = false
                // 只为把页面的 console 打进运行日志（排查用），不接管行为
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        SnLog.i(GAME_LOG_TAG, m.message())
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    /**
                     * 主文档请求就地应答（见本函数的说明）。
                     *
                     * ⚠️ 这个回调在 WebView 的 **IO 线程**上执行 —— 这里只读捕获的常量、返回内存流，
                     * 不碰任何 UI；其余请求（favicon 之类）返回 null 交回 WebView 自己处理。
                     */
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        if (request.url.toString() != LOCAL_HTML_URL) return null
                        return WebResourceResponse(
                            "text/html",
                            "utf-8",
                            java.io.ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
                        )
                    }

                    // 加载生命周期只记运行日志（与资讯正文页的 [SN_CLEAN] 一个套路）：
                    // 「黑屏 / 白屏」这类问题，第一步就是确认页面到底有没有加载、有没有报错。
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        SnLog.i(GAME_LOG_TAG, "onPageStarted url=$url")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        SnLog.i(GAME_LOG_TAG, "onPageFinished url=$url")
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        // 只记**主文档**：子资源（favicon 之类）本来就不存在、必然报错，记了只会刷屏
                        if (request.isForMainFrame) {
                            SnLog.i(
                                GAME_LOG_TAG,
                                "onReceivedError ${request.url} code=${error.errorCode} ${error.description}",
                            )
                        }
                    }
                }
            }
        },
        update = { view ->
            if (view.url != LOCAL_HTML_URL) view.loadUrl(LOCAL_HTML_URL)
        },
        onRelease = { view ->
            view.removeCallbacks(null)
            view.stopLoading()
            view.destroy()
        },
    )
}

/**
 * 资讯正文用的内嵌 WebView —— 从 [HtmlView] 抽出来，便于与内置 PDF 阅读器**叠放**。
 *
 * @param onPdf 命中 PDF 链接时回调（由 [HtmlView] 切到内置阅读器，而不是让 WebView 去加载）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun NewsWebView(url: String, onPdf: (String) -> Unit, modifier: Modifier = Modifier) {
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
                        // ⚠️ **PDF 必须抢在"白名单"之前判**（老周 2026-10-04）：
                        // 公告文件域（finance.qq.com / dfcfw.com）已在白名单里 ⇒ 放行的话导航会留在
                        // WebView，而 **WebView 渲染不了 PDF** → 页面空白/触发下载。这里直接接管。
                        if (isPdfUrl(target)) {
                            SnLog.i(NAV_LOG_TAG, "PDF 改用内置阅读器：$target")
                            onPdf(target)
                            return true
                        }
                        return when {
                            isInAppNavigationAllowed(target) -> false
                            isHttpUrl(target) -> {
                                // ⚠️ 去重（2026-10-03，与 iOS 同因）：同一次点击本回调可能被重复触发，
                                // 不去重会反复启动系统浏览器（详见 commonMain 的 InAppNavigationDedupe）。
                                if (InAppNavigationDedupe.shouldHandle(target)) {
                                    SnLog.i(NAV_LOG_TAG, "站外链接交给系统浏览器：$target")
                                    openInSystemBrowser(view.context, target)
                                }
                                true
                            }
                            else -> {
                                if (InAppNavigationDedupe.shouldHandle(target)) {
                                    SnLog.i(NAV_LOG_TAG, "已拦下非 http(s) 导航：$target")
                                }
                                true
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        // 诊断：确认回调确实到了、脚本确实执行了（拿 evaluateJavascript 的回调）
                        // P2-6：这些 [SN_CLEAN] 诊断改走 SnLog（进运行日志环形缓冲，「导出运行日志」可带出）；
                        // 此前是 println，只进 logcat，用户拿不到。
                        SnLog.i("SN_CLEAN", "onPageFinished url=$finishedUrl")
                        view.evaluateJavascript(CleanPageJs) { result ->
                            SnLog.i("SN_CLEAN", "evalResult=$result")
                        }
                    }
                }
                // 兜底注入：SPA 首次 onPageFinished 未必会到（实测有时压根不触发），
                // 延迟再注入一次，不依赖回调时序；脚本自身幂等（style 按 id 去重）。
                postDelayed({
                    runCatching {
                        evaluateJavascript(CleanPageJs) { result ->
                            SnLog.i("SN_CLEAN", "evalDelay=$result")
                        }
                    }
                }, 1500)
                // 只为把清理脚本的执行结果打进运行日志（排查用），不接管页面行为
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        SnLog.i("SN_CLEAN", m.message())
                        return true
                    }
                }
                // 公告正文常见形态是「独立文件 + 点击下载文件」（老周 2026-09-24 问的下载）：
                // WebView 自身**不支持文件下载**（不设监听则点击毫无反应）。
                // ⚠️ P1-20：只放行 http(s)；非 http(s) 的"下载链接"（含 `intent://`）
                // **不处理**，避免把任意 component 拉起。
                setDownloadListener { downloadUrl, _, _, _, _ ->
                    when {
                        // 公告 PDF（老周 2026-10-04）：WebView 把 PDF 当「下载」处理时会走到这里 ——
                        // 同样交给**内置阅读器**，不再丢给系统浏览器（那等于跳出 App）。
                        isPdfUrl(downloadUrl) -> {
                            SnLog.i(NAV_LOG_TAG, "PDF 下载链接改用内置阅读器：$downloadUrl")
                            onPdf(downloadUrl)
                        }
                        isHttpUrl(downloadUrl) -> openInSystemBrowser(ctx, downloadUrl)
                        else -> SnLog.i(NAV_LOG_TAG, "已忽略非 http(s) 下载链接：$downloadUrl")
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
