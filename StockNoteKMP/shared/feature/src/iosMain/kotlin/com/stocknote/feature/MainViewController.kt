@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.stocknote.feature

import androidx.compose.ui.window.ComposeUIViewController
import com.stocknote.data.AppContainer
import com.stocknote.data.platform.nativeLog
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import platform.Foundation.NSBundle
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 入口。
 *
 * 关键点：**Composable 不能被 Swift 直接调用**，必须在 Kotlin 侧用
 * `ComposeUIViewController { ... }` 包成一个 UIViewController，再由 Swift 用
 * `UIViewControllerRepresentable` 嵌进 SwiftUI。这就是「共享 UI」在 iOS 上的接法。
 *
 * ⚠️ 2026-10-01：真机（iPad Pro 11" 3代 / iOS 26.5）点图标**启动约 87ms 就 SIGABRT**。
 * 崩溃在**后台队列**（`com.apple.root.utility-qos`）上是未捕获异常
 * （`.ips` 里 `__cxa_throw` → `std::__terminate` → `abort`），但堆栈全是裸偏移、无符号名：
 *   - Kotlin 官方 FAQ 明确说：**异常到达 iOS 层时原始 Kotlin 堆栈已经丢失**；
 *   - 没有 dSYM，本机是 Windows（编不了 iOS），无法符号化。
 * 所以这里装了未捕获异常钩子，把异常**弹到屏幕上**——这是唯一还走得通的取证方式。
 */

private var crashDiagnosticsInstalled = false

/**
 * 装**未捕获异常钩子**（`kotlin.native.setUnhandledExceptionHook`）。
 *
 * ⚠️ 为什么必须显式装：这个钩子是**进程级**的，**任何线程**上未捕获的 Kotlin 异常都会被它接住 ——
 * 而我们的崩溃恰恰发生在**后台线程**，外层 `try/catch` 根本抓不到。
 *
 * ⚠️⚠️ 为什么最终选择「弹到屏幕上」而不是写日志（三条路都试过、全堵死）：
 *  1. **`println`** 走 stdout —— iOS 上 App 独立运行时 **stdout 被丢弃**，不进 syslog；
 *  2. **`NSLog`** 确实进了 syslog —— 但 **iOS 统一日志把动态字符串参数脱敏成 `<private>`**
 *     （2026-10-01 实测：爱思实时日志里我们 App 的每条输出都是 `<private>`，一个字符都看不到）。
 *     要让内容公开得改用 `os_log` 的 `%{public}` 格式，那是 C 宏 + vararg，K/N 上代价过高；
 *  3. **写沙盒文件**（`Documents/stocknote.log`）—— 取不出来：`UIFileSharingEnabled` 在
 *     爱思自签重写 Info.plist 时被丢掉，爱思「浏览」是灰的、iPad「文件」里也没这个文件夹。
 * ⇒ 只剩「**显示在屏幕上、用户截图**」这一条路。
 *
 * hook 返回后 K/N 会照常终止进程（与原行为一致），这里只负责把现场打出来。
 */
@OptIn(ExperimentalNativeApi::class)
private fun installCrashDiagnostics() {
    if (crashDiagnosticsInstalled) return
    crashDiagnosticsInstalled = true
    setUnhandledExceptionHook { throwable ->
        val text = buildString {
            append("Uncaught Kotlin exception: ")
            append(throwable::class.simpleName ?: "?")
            append(": ")
            appendLine(throwable.message ?: "(无消息)")
            appendLine()
            append(throwable.stackTraceToString())
        }
        // 仍然落盘 + 打日志（NSLog 在 syslog 里会被脱敏，但留着无害，万一以后有别的取法）
        nativeLog(text)
        // ⭐ 关键：弹到屏幕上，老周截图即可
        showCrashOnScreen(text)
        // 阻塞住等截图 —— 进程本来马上就会被终止，这几秒是「最后的取证窗口」。
        // 12 秒足够解锁、截图；再长 watchdog 可能直接杀进程。
        platform.posix.sleep(12u)
    }
    nativeLog("[启动] 已装好未捕获异常钩子")
}

/**
 * 把崩溃信息**弹成对话框**（标题写着「请截图」）。
 *
 * ⚠️ hook 跑在**后台线程**上，而动 UI 必须在主线程 —— 所以这里 `dispatch_async` 切回去，
 * 这一点很容易漏（直接在主线程外的上下文建/弹 UIViewController 会直接崩或静默失败）。
 *
 * ⚠️ 用 `keyWindow` 取根控制器：它在 iOS 13+ 标了废弃，但**启动早期**（我们的场景）
 * 它正是当前唯一可用的窗口，比遍历 `windows` 找 `isKeyWindow` 更简单可靠。
 */
private fun showCrashOnScreen(text: String) {
    dispatch_async(dispatch_get_main_queue()) {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return@dispatch_async
        val alert = UIAlertController.alertControllerWithTitle(
            title = "启动崩溃（请截图发给开发者）",
            message = text,
            preferredStyle = UIAlertControllerStyleAlert,
        )
        alert.addAction(
            // ⚠️ 第三参写成显式 lambda（而不是 `null`）：K/N 对 `((UIAlertAction?) -> Unit)?`
            // 这种可空函数类型在传 null 时推断容易出岔，给个空实现最稳。
            UIAlertAction.actionWithTitle("好", UIAlertActionStyleDefault) { _ -> },
        )
        root.presentViewController(alert, animated = true, completion = null)
    }
}

fun mainViewController(): UIViewController {
    installCrashDiagnostics()
    nativeLog("[启动] mainViewController() 进入")

    val container = try {
        currentContainer()
    } catch (t: Throwable) {
        // 同步路径上的失败（建库、NSUserDefaults 读写等）在这里就能带堆栈抓出来；
        // 异步线程上的由上面的钩子兜住。
        nativeLog("❌ AppContainer 构造失败: ${t::class.simpleName}: ${t.message}")
        nativeLog(t.stackTraceToString())
        showCrashOnScreen(
            "AppContainer 构造失败: ${t::class.simpleName}: ${t.message}\n\n${t.stackTraceToString()}",
        )
        platform.posix.sleep(12u)
        throw t
    }
    nativeLog("[启动] AppContainer 就绪，开始创建 ComposeUIViewController")

    // 版本号必须在**组合之外**算好：`bundleVersionLabel()` 每次都要读 Info.plist，
    // 没必要跟着重组反复读。
    val versionLabel = bundleVersionLabel()

    return ComposeUIViewController {
        App(
            container = container,
            versionLabel = versionLabel,
            platformLabel = IOS_MIN_VERSION,
        )
    }
}

/** 「关于」页显示的最低系统要求（老周 2026-10-02：此前该文案写死在 commonMain，iOS 也显示 Android）。 */
private const val IOS_MIN_VERSION = "iOS 14.0+"

/**
 * 从 **`Info.plist`** 读版本号，产出与 Android 同形的 `1.1.8 (58)`。
 *
 * 为什么与 Android 对称：那边取 `BuildConfig.VERSION_NAME/VERSION_CODE`，**都是打包产物**——
 * 这里绝不硬编码，否则发版时就会多出第三处会忘改的地方（规范 §3 只要求同步三处）。
 *
 * ⚠️ 老周 2026-10-02 报「iOS 上「关于」页看不到版本号」，根因就是 iOS 外壳此前
 * `App(container = …)` **没传 versionLabel**，页面走了「—」的兜底分支；
 * 同一处还暴露了「平台文案写死 Android」的问题（见 [IOS_MIN_VERSION]）。
 */
private fun bundleVersionLabel(): String = runCatching {
    val info = NSBundle.mainBundle
    val name = info.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
    val build = info.objectForInfoDictionaryKey("CFBundleVersion") as? String
    when {
        name.isNullOrBlank() -> ""
        build.isNullOrBlank() -> name
        else -> "$name ($build)"
    }
    // ⚠️ 兜底：版本号读不出来最多是「关于」页显示成 —，绝不能因此把启动搞崩
    //   （Foundation 的桥接在本项目是有前科的，见 Platform.ios.kt 的 todayIso 注释）。
}.getOrDefault("")

private var cachedContainer: AppContainer? = null

/** 懒创建 + 复用：Compose 重组时不能反复建库。 */
private fun currentContainer(): AppContainer =
    cachedContainer ?: AppContainer(databaseName = AppContainer.DEFAULT_DB_NAME).also {
        it.seedDemoDataIfEmpty()
        cachedContainer = it
    }
