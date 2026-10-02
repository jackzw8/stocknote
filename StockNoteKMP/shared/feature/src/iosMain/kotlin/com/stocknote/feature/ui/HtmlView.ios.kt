@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.stocknote.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.stocknote.data.platform.nativeLog
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIApplication
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * iOS 端「用浏览器打开」：`UIApplication.openURL`（与 [HtmlView] 同一实现，抽出来共用）。
 *
 * 详情页顶部那个按钮一直在用它 —— 公告的「点击下载文件 / PDF」在内嵌网页里做不了，
 * 有个交给浏览器的出口最省事。
 */
@Composable
actual fun rememberBrowserOpener(): (String) -> Unit =
    remember {
        { url: String ->
            NSURL.URLWithString(url)?.let { nsUrl ->
                UIApplication.sharedApplication.openURL(nsUrl)
            }
        }
    }

/**
 * iOS 端**内嵌**网页视图（老周 2026-10-02，替换原来的"跳系统浏览器"）。
 *
 * ## 为什么以前是跳浏览器
 * WKWebView 要靠 `UIKitView` 做 UIKit 互操作（原生视图 + 生命周期），是 iOS 侧风险最高的一块，
 * 当时先把「链接可用」保住，留了这个 TODO。现在补齐。
 *
 * ## 三个必须守住的点（对齐 Android / 桌面两端的既有结论）
 *  1. **同一份清理脚本**：[CleanPageJs] 来自 commonMain，Android 在 `onPageFinished` 注入、
 *     桌面在 `loadWorker` 成功时注入，iOS 在 `didFinishNavigation` 注入。脚本自带
 *     25×400ms 定时重复与滚动监听，**注入一次就够**（SPA 后插节点也能盖住）。
 *  2. **delegate 必须被 remember 住**：`navigationDelegate` 是 **weak** 引用，
 *     匿名对象会被 ARC 提前回收 → 回调永不触发。所以用具名类 + `remember`。
 *  3. **URL 变了才 load**：详情页复用同一个 Composable，`update` 每次重组都会跑，
 *     无脑 load 会不停刷屏（Android 侧踩过同样的坑）。
 */
@Composable
actual fun HtmlView(url: String, modifier: Modifier) {
    val openBrowser = rememberBrowserOpener()
    // 加载失败要**看得见**（2026-09-30 桌面版的教训：留一片空白，用户会以为应用坏了）
    val failure = remember { mutableStateOf<String?>(null) }

    val delegate = remember {
        WebViewNavigationDelegate(
            onFinished = { webView ->
                webView.evaluateJavaScript(CleanPageJs) { _, error ->
                    nativeLog(
                        "[SN_CLEAN] iOS 注入清理脚本：" +
                            (error?.let { "失败 " + it.localizedDescription } ?: "已执行"),
                    )
                }
            },
            onFailed = { reason -> failure.value = reason },
        )
    }

    Box(modifier) {
        UIKitView(
            factory = {
                WKWebView().apply {
                    navigationDelegate = delegate
                    loadRequest(NSURLRequest(NSURL(string = url)))
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { webView ->
                if (webView.URL?.absoluteString != url) {
                    webView.loadRequest(NSURLRequest(NSURL(string = url)))
                }
            },
            onRelease = { webView ->
                // 与桌面版 View 销毁时摘监听对称：解绑 delegate（weak，置空即解绑）+ 停加载
                webView.navigationDelegate = null
                webView.stopLoading()
            },
            properties = UIKitInteropProperties(
                // ⚠️ 必须 NonCooperative：让 WKWebView **独占**触摸。Cooperative（默认）会先把手势
                //    交给 Compose 判定 150ms，正文页滚动会明显发涩 —— 与桌面/Android 的手感对齐。
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                // 网页是原生视图，关掉无障碍会让「旁白」读不到正文
                isNativeAccessibilityEnabled = true,
            ),
        )

        failure.value?.let { reason ->
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("网页加载失败", textAlign = TextAlign.Center)
                    Text(reason, textAlign = TextAlign.Center)
                    TextButton(onClick = { openBrowser(url) }) { Text("用系统浏览器打开") }
                }
            }
        }
    }
}

/**
 * WKWebView 的导航委托 —— 对应 Android 的 `WebViewClient.onPageFinished`
 * 与桌面版的 `loadWorker` 状态监听。
 *
 * ⚠️ 做成**具名类**而不是在 `factory` 里写匿名 `object`：`navigationDelegate` 是 weak，
 * 匿名对象没有强引用会被 ARC 立刻回收，回调从此不触发（排查起来极难，因为不报任何错）。
 *
 * `@Suppress("CONFLICTING_OVERLOADS")`：`WKNavigationDelegateProtocol` 里有多个
 * `webView(_:didXxxNavigation:)`，在 Kotlin 看来签名完全相同 —— 用 `@ObjCSignatureOverride`
 * 告诉编译器"它们其实是不同的 ObjC 选择器"（这是 K/N 的固定写法）。
 */
@Suppress("CONFLICTING_OVERLOADS")
private class WebViewNavigationDelegate(
    private val onFinished: (WKWebView) -> Unit,
    private val onFailed: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinished(webView)
    }

    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        onFailed(withError.localizedDescription)
    }
}
