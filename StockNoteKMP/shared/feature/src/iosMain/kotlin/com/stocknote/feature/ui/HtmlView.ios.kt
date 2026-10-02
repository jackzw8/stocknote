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
import com.stocknote.data.log.SnLog
import com.stocknote.data.platform.nativeLog
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIApplication
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
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
// ⚠️ 必须 OptIn：`UIKitView` 本身没有实验性注解，但 **`UIKitInteropProperties` 的构造函数带
//    `@ExperimentalComposeUiApi`**（已按 CMP **1.11.1** 的源码逐行确认）—— 漏了直接编译失败。
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun HtmlView(url: String, modifier: Modifier) {
    val openBrowser = rememberBrowserOpener()
    // 加载失败要**看得见**（2026-09-30 桌面版的教训：留一片空白，用户会以为应用坏了）
    val failure = remember { mutableStateOf<String?>(null) }

    val delegate = remember {
        WebViewNavigationDelegate(
            onFinished = { webView ->
                webView.evaluateJavaScript(CleanPageJs) { _, error ->
                    // ⚠️ 只取 `code`，不用 `localizedDescription`（同上：避免用到需要额外 import 的
                    //    Foundation 扩展属性）。日志里有个错误码足够定位。
                    nativeLog(
                        "[SN_CLEAN] iOS 注入清理脚本：" +
                            (if (error == null) "已执行" else "失败 code=" + error.code),
                    )
                }
            },
            onFailed = { reason -> failure.value = reason },
            // P1-20：白名单外的链接交给系统浏览器（iOS 侧就是 UIApplication.openURL）
            onExternalNavigation = { target -> openBrowser(target) },
        )
    }

    Box(modifier) {
        UIKitView(
            factory = {
                WKWebView().apply {
                    navigationDelegate = delegate
                    // ⚠️ P1-20：iOS 的 `decidePolicyForNavigationAction` **连初始加载一起问**
                    //（Android 的 `shouldOverrideUrlLoading` 只问页内导航）→ 必须先把"App 自己给的
                    // 正文 URL"登记好，否则只要正文域名不在白名单（如资讯来自 `new.qq.com`），
                    // 详情页一打开就会被判成"站外"、直接甩进浏览器。
                    delegate.allowDocumentLoad(url)
                    loadRequest(NSURLRequest(NSURL(string = url)))
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { webView ->
                if (webView.URL?.absoluteString != url) {
                    delegate.allowDocumentLoad(url)
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
 *
 * ⚠️ 编译时会有一条警告：`Suppression of error 'CONFLICTING_OVERLOADS' ... the compiler behavior
 * is UNSPECIFIED and WILL NOT BE PRESERVED`。**这是预期的**：原因就是上面那个固有的签名冲突，
 * 业界通用的成熟库（如 compose-webview-multiplatform）用的也是这套写法。真出问题的触发条件是
 * **升 Kotlin 大版本**时 —— 到时候优先回来重测这里，别去猜别处。
 */
@Suppress("CONFLICTING_OVERLOADS")
private class WebViewNavigationDelegate(
    private val onFinished: (WKWebView) -> Unit,
    private val onFailed: (String) -> Unit,
    /** P1-20：白名单外的 `http(s)` 链接 → 交给系统浏览器。 */
    private val onExternalNavigation: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    /**
     * App 自己给的那个正文 URL（P1-20）。
     *
     * ⚠️ 必须单独放行：iOS 的 `decidePolicyForNavigationAction` **连初始加载一起问**，
     * 只按白名单判的话，正文域名一旦不在白名单就会被判成"站外"。
     *
     * 写入发生在 `loadRequest` 之前、读取在导航回调里，**都在主线程**；
     * delegate 本身被 `remember` 持有，不用额外同步。
     */
    private var documentUrl: String? = null

    /** 登记「本次要加载的主文档」，供导航策略无条件放行（见 [documentUrl] 的说明）。 */
    fun allowDocumentLoad(url: String) {
        documentUrl = url.trim()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinished(webView)
    }

    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        // ⚠️ 刻意只取 `code`（NSError 的**声明属性**，稳）。不用 `localizedDescription`：
        //    Foundation 上有一批属性在 K/N 里是「需要显式 import 的顶层扩展」，
        //    本项目已经在那上面白跑过 3 轮 CI（见 Platform.ios.kt 的 todayIso 注释）。
        //    错误码足够定位（如 -1004 连不上、-1001 超时），用户也有「用系统浏览器打开」的出口。
        onFailed("无法加载该网页（错误码 ${withError.code}）")
    }

    /**
     * P1-20：**主文档加载中途失败**。此前只覆盖了「开始前失败」
     *（[didFailProvisionalNavigation]）—— 中途断网时页面就干在那儿，既没提示也没有出口。
     *
     * ⚠️ 它与上面那个方法在 Kotlin 里**签名完全相同**（`(WKWebView, WKNavigation?, NSError)`），
     * 必须用 `@ObjCSignatureOverride` 表明"这是两个不同的 ObjC 选择器"。
     */
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError,
    ) {
        onFailed("网页加载中断（错误码 ${withError.code}）")
    }

    /**
     * P1-20：**导航白名单**（规则与 Android 共用 commonMain 的 `InAppNavigationHosts`）。
     *
     * - 主文档 URL、白名单内的 https → 放行；
     * - 其它 `http(s)` → 取消 + 交给系统浏览器；
     * - 非 `http(s)`（`intent://` / `market://` …）→ 取消，**不转交**（这正是攻击面本身）。
     */
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL?.absoluteString
        when {
            target == null ->
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)

            target.trim() == documentUrl ->
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)

            isInAppNavigationAllowed(target) ->
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)

            isHttpUrl(target) -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                SnLog.i(NAV_LOG_TAG, "iOS 站外链接交给系统浏览器：$target")
                onExternalNavigation(target)
            }

            else -> {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                SnLog.i(NAV_LOG_TAG, "iOS 已拦下非 http(s) 导航：$target")
            }
        }
    }
}
