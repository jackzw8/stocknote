package com.stocknote.feature.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Android 端：内嵌 WebView 打开资讯正文（老周 2026-09-24）。
 *
 * 说明：
 *  - 腾讯的详情页是 **Vue 单页应用**（服务端只回空壳 HTML），所以**必须开 JS**才能渲染出正文；
 *    这也正是"直接抓 URL 拿不到内容、只能内嵌网页"的原因。
 *  - `WebViewClient` 用默认实现：页内跳转留在本 WebView，不会把用户甩到外部浏览器。
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
                webViewClient = object : WebViewClient() {
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
                setDownloadListener { downloadUrl, _, _, _, _ ->
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        },
        update = { view ->
            // 只在 URL 变化时加载（AndroidView.update 每次重组都会调用）
            if (view.url != url) view.loadUrl(url)
        },
    )
}

/**
 * Android 端「用浏览器打开」：交给系统处理（浏览器能下载 PDF、能处理
 * WebView 点不动的链接，见 commonMain 的注释）。
 */
@Composable
actual fun rememberBrowserOpener(): (String) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(ctx) {
        { url: String ->
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

// 清理脚本已提升到 commonMain（`CleanPageJs`，见 HtmlView.kt）—— 桌面端要注入**同一份**，
// 避免两端"清理范围不一致"（老周 2026-09-30：桌面版红框里的「相关资讯」+ 底部下载条没被去掉）。
