package com.stocknote.feature.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS 端：**暂用系统浏览器打开**资讯正文（老周 2026-09-24）。
 *
 * 为什么先这样：资讯正文只能在网页里渲染，而 WKWebView 内嵌需要 `UIKitView` 互操作，
 * 涉及 interop 配置与生命周期处理，风险比功能本身大；先保证**链接可用**（能读到内容），
 * 待 iOS 端整体过一遍时再换成内嵌 WKWebView（此处预留 TODO）。
 */
/**
 * iOS 端「用浏览器打开」：`UIApplication.openURL`（与 [HtmlView] 同一实现，抽出来共用）。
 */
@Composable
actual fun rememberBrowserOpener(): (String) -> Unit =
    androidx.compose.runtime.remember {
        { url: String ->
            NSURL.URLWithString(url)?.let { nsUrl ->
                UIApplication.sharedApplication.openURL(nsUrl)
            }
        }
    }

@Composable
actual fun HtmlView(url: String, modifier: Modifier) {
    val open = rememberBrowserOpener()
    LaunchedEffect(url) { open(url) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "已在系统浏览器中打开该资讯",
            textAlign = TextAlign.Center,
        )
    }
}
