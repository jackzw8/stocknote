package com.stocknote.feature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.io.File
import java.net.URI
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import kotlinx.coroutines.delay

/**
 * 桌面端「用系统浏览器打开」—— 老周 2026-09-30。
 *
 * 内嵌浏览（[HtmlView]）已经能用了，这个出口仍然保留：公告里的「下载文件 / PDF」这类操作
 * 在内嵌 WebView 里做不了，交给系统浏览器最省事。
 */
@Composable
actual fun rememberBrowserOpener(): (String) -> Unit = remember {
    { url: String -> openInSystemBrowser(url) }
}

internal fun openInSystemBrowser(url: String) {
    val ok = runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else {
            false
        }
    }.getOrDefault(false)
    if (ok) return

    runCatching {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val cmd = when {
            os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> arrayOf("open", url)
            else -> arrayOf("xdg-open", url)
        }
        ProcessBuilder(*cmd).start()
    }
}

/**
 * JavaFX 运行时状态与预热 —— ⚠️⚠️ 这个类是 2026-09-30 真机事故的直接产物，别删。
 *
 * **事故现象**：点开资讯详情 → **整片空白，而且点返回、点菜单全都没反应（界面卡死）**。
 *
 * **根因**：`JFXPanel()` 的构造会**同步等待 JavaFX 运行时启动**。而 `SwingPanel.factory` 是在
 * **AWT 事件线程（EDT）**上被调用的 —— 于是在 EDT 上等 JavaFX、JavaFX 启动又要用到 AWT，
 * 直接**死锁**：页面永远空白，界面也再不会响应任何点击。
 *
 * **修法**：把 JavaFX 预热丢到**独立后台线程**（`Platform.startup` 本身允许非 FX 线程调用），
 * 启动完成前 UI 只显示"正在启动内置浏览器…"占位，**绝不**在 EDT 上构造 JFXPanel。
 * 这样即便 JavaFX 起不来（缺库/环境异常），也只是这笔网页打不开，不会拖死整个应用。
 */
object JfxRuntime {

    /** JavaFX 就绪（已在 JavaFX 线程内被置位）。 */
    @Volatile
    var ready: Boolean = false
        private set

    /** 启动失败的原因；非 null 表示这台机器用不了内嵌浏览器（UI 降级为系统浏览器）。 */
    @Volatile
    var error: String? = null
        private set

    @Volatile
    private var started = false

    /** 可在 main() 里提前调用（用户还没点资讯就先启动好），也可由 [HtmlView] 兜底触发。 */
    @Synchronized
    fun prewarm() {
        if (started) return
        started = true
        Thread({
            runCatching {
                // 关掉"最后一个 JavaFX 窗口关闭即退出 JavaFX"，否则切走再回来会永久空白
                Platform.setImplicitExit(false)
                Platform.startup { ready = true }
            }.onFailure { e ->
                error = e.toString()
                log("JavaFX 启动失败: $e")
            }
        }, "stocknote-jfx-prewarm").apply { isDaemon = true }.start()
    }

    internal fun log(message: String) {
        runCatching {
            val dir = File(
                System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("user.home"),
                "StockNote",
            ).apply { mkdirs() }
            File(dir, "jfx.log").appendText("[${java.time.LocalDateTime.now()}] $message\n")
        }
    }
}

/** UI 用的不可变快照（避免在组合里直接读那三个 volatile 字段导致不重组）。 */
private data class JfxState(val ready: Boolean, val error: String?)

/**
 * 桌面端**内嵌**网页视图（老周 2026-09-30：资讯详情不要跳到外部浏览器）。
 *
 * 实现：`SwingPanel` 托一个 `JFXPanel`，里面是 **JavaFX WebView**（WebKit 内核，能跑 JS/SPA）。
 *
 * 为什么选 JavaFX 而不是 CEF（KCEF / JCEF）：腾讯资讯正文是 SPA，必须有真实浏览器内核；
 * 而 CEF 要额外分发 ~150MB 原生运行时（官方制品还不在 Maven 上），JavaFX 只多 ~39MB 且原生库在 jar 内。
 *
 * ⚠️ 三个必须守住的点（守不住就会出现"空白 + 界面卡死"）：
 *  1. **绝不在 EDT 上构造 JFXPanel** → 先 [JfxRuntime.prewarm]，就绪前只显示占位；
 *  2. `Platform.setImplicitExit(false)` → 否则"第一次能开、切走再回来空白"；
 *  3. `url` 变化要在 `SwingPanel.update` 里重新 `load`（详情页复用同一个 Composable）。
 */
@Composable
actual fun HtmlView(url: String, modifier: Modifier) {
    var state by remember { mutableStateOf(JfxState(JfxRuntime.ready, JfxRuntime.error)) }

    LaunchedEffect(Unit) {
        if (state.ready || state.error != null) return@LaunchedEffect
        JfxRuntime.prewarm()
        // 最多等 10s，超时按失败降级（宁可"打不开"也不能卡住界面）
        var waited = 0
        while (!JfxRuntime.ready && JfxRuntime.error == null && waited < 10_000) {
            delay(120)
            waited += 120
            state = JfxState(JfxRuntime.ready, JfxRuntime.error)
        }
        if (!JfxRuntime.ready && JfxRuntime.error == null) {
            JfxRuntime.log("JavaFX 等待超时（10s），降级为系统浏览器")
            state = JfxState(false, "内置浏览器启动超时")
        }
    }

    when {
        state.ready -> {
            // ⚠️ "JavaFX 就绪" ≠ "WebView 能用"。真机实测（2026-09-30）：缺 javafx-controls 的实现 jar 时，
            // `WebView()` 构造会抛 NoClassDefFoundError: javafx/scene/control/Control。
            // 如果这里只接住"启动失败"而漏掉"创建失败"，用户看到的就是一个**空的 WebView 面板**——还是空白。
            // 所以创建失败也要冒到 UI 上、走同一套降级界面（让用户能一键转浏览器，我也能拿到原因）。
            var hostError by remember { mutableStateOf<String?>(null) }
            val host = remember { WebPanelHost { message -> hostError = message } }
            val detail = hostError
            if (detail != null) {
                FallbackView(url, modifier, detail)
            } else {
                SwingPanel(
                    modifier = modifier,
                    factory = { host.panel },
                    update = { host.load(url) },
                )
            }
        }

        state.error != null -> FallbackView(url, modifier, state.error!!)

        else -> Box(modifier, contentAlignment = Alignment.Center) {
            Text("正在启动内置浏览器…", textAlign = TextAlign.Center)
        }
    }
}

/**
 * 内嵌浏览器不可用时的降级界面：说清原因，并给一个"用外部浏览器打开"的出口。
 *
 * 刻意不静默、也不空白 —— 空白的后果是用户以为应用坏了（2026-09-30 就是这么报的）。
 */
@Composable
private fun FallbackView(url: String, modifier: Modifier, reason: String) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("内置浏览器不可用", textAlign = TextAlign.Center)
            Text(reason, textAlign = TextAlign.Center)
            TextButton(onClick = { openInSystemBrowser(url) }) {
                Text("用系统浏览器打开")
            }
        }
    }
}

/**
 * JFXPanel + 一个常驻 WebView。
 *
 * ⚠️ 单独抽类是为了让 `factory` 返回类型确定是 `JFXPanel`（`SwingPanel<T : Component>` 靠它推断）。
 * ⚠️ 构造它**必须**在 [JfxRuntime.ready] 为 true 之后（见 [JfxRuntime] 的说明）。
 */
private class WebPanelHost(
    /** WebView 创建失败时的回调（UI 据此降级成"用系统浏览器打开"，而不是留一片空白）。 */
    private val onError: (String) -> Unit,
) {

    val panel: JFXPanel = JFXPanel()

    private var engine: WebEngine? = null

    /** JavaFX 线程还没就绪时先记下来，初始化完再加载。 */
    private var pendingUrl: String? = null

    init {
        Platform.runLater {
            runCatching {
                val webView = WebView()
                engine = webView.engine
                // ⚠️ 注入与 Android **同一份**清理脚本（老周 2026-09-30：桌面版此前没注入，
                // 所以「相关资讯」和底部下载条还留在页面里）。
                // JavaFX 侧只需在加载完成时执行一次 —— 脚本自身会定时重复 25×400ms 并监听滚动，
                // 足够覆盖 SPA 后插节点与滚动懒加载（实测那两块分别是 3.2s 后才出现/懒加载的）。
                webView.engine.loadWorker.stateProperty().addListener { _, _, state ->
                    if (state == Worker.State.SUCCEEDED) {
                        runCatching { webView.engine.executeScript(CleanPageJs) }
                            .onFailure { JfxRuntime.log("清理脚本注入失败: $it") }
                    }
                }
                panel.scene = Scene(webView)
                pendingUrl?.let { url ->
                    pendingUrl = null
                    webView.engine.load(url)
                }
            }.onFailure { e ->
                JfxRuntime.log("WebView 初始化失败: $e")
                onError("${e::class.simpleName}: ${e.message.orEmpty()}".trim())
            }
        }
    }

    fun load(url: String) {
        val current = engine
        if (current == null) {
            pendingUrl = url
            return
        }
        // 同一个地址重复 load 会闪白屏，故先比对
        if (current.location != url) {
            Platform.runLater { runCatching { current.load(url) } }
        }
    }
}
