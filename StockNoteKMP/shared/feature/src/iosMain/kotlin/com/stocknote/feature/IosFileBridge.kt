@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.stocknote.feature

import androidx.compose.ui.graphics.ImageBitmap
import com.stocknote.data.platform.nativeLog
import com.stocknote.feature.state.FileBridge
// ⚠️ 这三个是**扩展函数**，必须显式 import（2026-10-04 CI 实测：漏了会报
// `Unresolved reference 'toKString' / 'usePinned' / 'addressOf'` ——
// `platform.posix.fopen(...)` 那种**顶层函数**可以全限定写，扩展不行）。
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS 的**文件桥**（2026-10-03，老周真机报「导出日志失败：当前平台未接入系统文件选择器」）。
 *
 * ## 为什么之前是空的
 * `FileBridgeHolder.impl` 只在 Android（SAF launcher）与桌面（Swing 文件对话框）注入过，
 * iOS 一直没有实现 ⇒ **导出运行日志、导出 CSV、备份导出、下载模板** 在 iPad 上**全都不通**
 *（不只是日志那一处）。
 *
 * ## 两个方向的做法（导入是 2026-10-04 补的，P1-36）
 *  - **导出**：POSIX（`fopen`/`fwrite`/`fclose`）写进沙盒 `Documents/`——**不碰 UIKit**，
 *    零 API 风险，且老周确认能拿到该目录的文件（他 2026-10-03 报「/Documents 下没有文件」，
 *    说明他**能浏览到**该目录）。⚠️ 与 P1-22 不冲突：P1-22 挪走的是**滚动日志**
 *    （`Library/Caches/stocknote.log`）；这里写的是用户**主动导出**才产生的一次性文件。
 *  - **导入**：`UIDocumentPickerViewController`（见 [openFile]）。它的 API 写法**逐字照抄成熟库
 *    FileKit**，因为本机是 Windows、iOS 编译只能靠 CI（一轮 26 分钟起），自推 K/N 命名已经白跑过两轮。
 *    读取内容仍走 POSIX（`fread`），避开 `NSData`/`NSString` 那批"名字存在但对不上"的坑。
 *
 * ⚠️ 返回值里带上 `Documents/` 前缀：调用方会把返回值直接显示给用户
 *（「已导出：Documents/xxx」），这样他才知道去哪儿拿 —— 其它平台只返回文件名。
 */
class IosFileBridge : FileBridge {

    /**
     * 强引用住选择器的 delegate。
     *
     * ⚠️ `UIDocumentPickerViewController.delegate` 是 **weak** 的，delegate 一旦被回收，
     * 回调**永远不触发**且**不报任何错** —— 这类问题排查起来极难（本项目在 WKWebView 上踩过同一个坑）。
     */
    private var retainedDelegate: IosDocumentPickerDelegate? = null

    override suspend fun saveFile(suggestedName: String, mimeType: String, content: String): String? =
        write(suggestedName, content.encodeToByteArray())

    override suspend fun saveBytes(suggestedName: String, mimeType: String, bytes: ByteArray): String? =
        write(suggestedName, bytes)

    /**
     * 「选择文件」—— `UIDocumentPickerViewController(forOpeningContentTypes = …)`（P1-36，2026-10-04）。
     *
     * ⚠️ API 写法**逐条照抄成熟库 FileKit**（`filekit-dialogs` 的 iOS 实现），不自己推 K/N 命名：
     *  - 构造：`UIDocumentPickerViewController(forOpeningContentTypes = List<UTType>)`；
     *  - delegate：`NSObject(), UIDocumentPickerDelegateProtocol`，方法名与参数类型见 [IosDocumentPickerDelegate]；
     *  - 挂起桥接：`suspendCancellableCoroutine` + `withContext(Dispatchers.Main)`（UI 必须主线程）；
     *  - ⚠️ **delegate 必须被强引用**（见 [retainedDelegate]），否则回调永不触发且不报错。
     *
     * 为什么用 `UTTypeItem`（任意文件）而不是按 mime 过滤：本 App 要导入的只有 CSV 与 `.snbk`（备份，
     * 内容是 Base64 文本），放开类型反而少一层"系统过滤和用户预期不一致"的麻烦。
     */
    override suspend fun openFile(mimeType: String): Pair<String, String>? =
        withContext(Dispatchers.Main) {
            val urls = presentDocumentPicker() ?: return@withContext null
            val url = urls.firstOrNull() ?: return@withContext null
            val name = url.lastPathComponent ?: "所选文件"

            // ⚠️ 从「文件」App 选来的 URL 是 **security-scoped** 的：不声明访问范围就直接读会失败。
            url.startAccessingSecurityScopedResource()
            try {
                name to readTextByPosix(url.path)
            } finally {
                url.stopAccessingSecurityScopedResource()
            }
        }

    /**
     * 呈现选择器并等结果；**返回 null = 用户取消**。
     *
     * ⚠️⚠️ `UIDocumentPickerViewController.delegate` 是 **weak** 引用 —— delegate 被写成本地变量的话，
     * 出了作用域就被 ARC 回收，回调**永远不触发**且**不报任何错**（本项目在 WKWebView 上踩过同一个坑）。
     * 所以这里把它挂到 [retainedDelegate] 上强引用住，等回调或取消后清掉。
     */
    private suspend fun presentDocumentPicker(): List<NSURL>? = suspendCancellableCoroutine { cont ->
        val delegate = IosDocumentPickerDelegate(
            onPicked = { urls -> if (cont.isActive) cont.resume(urls) },
            onCancelled = { if (cont.isActive) cont.resume(null) },
        )
        retainedDelegate = delegate

        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem))
        picker.delegate = delegate

        val presenter = topViewController()
        if (presenter == null) {
            retainedDelegate = null
            error("取不到可用的 UIViewController，无法弹出文件选择器")
        }
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    /** 取当前最顶层的控制器：启动早期用 `keyWindow`，然后沿 `presentedViewController` 链往下走。 */
    private fun topViewController(): UIViewController? {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        var top: UIViewController = root
        while (top.presentedViewController != null) {
            top = top.presentedViewController!!
        }
        return top
    }

    /**
     * 用 POSIX 读文本（`fopen`/`fread`）—— 与写侧同一套，避开 `NSData`/`NSString` 那些
     * 在 K/N 里"名字存在但构造器/扩展对不上"的坑（本文件 2026-10-03 为此白跑过两轮 CI）。
     *
     * ⚠️ 必须**先把整份文件读进来再一次性 `decodeToString()`**：按块解码会把多字节汉字劈成两半。
     */
    private fun readTextByPosix(path: String?): String {
        val filePath = path ?: error("取不到所选文件的路径")
        val file = platform.posix.fopen(filePath, "rb") ?: error("打不开所选文件：$filePath")
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        try {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = buffer.usePinned { pinned ->
                    platform.posix.fread(pinned.addressOf(0), 1u, buffer.size.toULong(), file)
                }.toInt()
                if (read <= 0) break
                chunks += buffer.copyOf(read)
                total += read
            }
        } finally {
            platform.posix.fclose(file)
        }
        val all = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(all, offset)
            offset += chunk.size
        }
        return all.decodeToString()
    }

    /** 同上：交易截图要 `PHPicker`，本轮不接。 */
    override suspend fun pickImage(): ByteArray? =
        error("iOS 侧「选图片」还没接入（需要 PHPicker）")

    /**
     * 截图解码在本轮也返回 `null` —— 契约里写明「解码失败返回 null，
     * UI 退化为只显示『已添加』文字」，所以这是合法降级，不是抛错。
     */
    override fun decodeImage(bytes: ByteArray): ImageBitmap? = null

    /** POSIX 写文件（`fopen`/`fwrite`/`fclose`）—— 成功后返回 `Documents/<文件名>`。 */
    private fun write(name: String, payload: ByteArray): String {
        val home = platform.posix.getenv("HOME")?.toKString() ?: error("取不到沙盒 HOME")
        val path = "$home/Documents/$name"

        val file = platform.posix.fopen(path, "wb") ?: error("无法创建文件：$path")
        try {
            if (payload.isNotEmpty()) {
                val written = payload.usePinned { pinned ->
                    // fwrite(ptr, size, count, stream)：size=1、count=字节数 ⇒ 返回值即字节数
                    platform.posix.fwrite(
                        pinned.addressOf(0),
                        1u,
                        payload.size.toULong(),
                        file,
                    )
                }
                if (written.toInt() != payload.size) {
                    error("写入不完整（$written/${payload.size} 字节）")
                }
            }
            platform.posix.fflush(file)
        } finally {
            platform.posix.fclose(file)
        }

        nativeLog("[导出] 已写入 $path")
        return "Documents/$name"
    }
}

/**
 * `UIDocumentPickerViewController` 的 delegate（P1-36）。
 *
 * ⚠️ **逐字照抄成熟库 FileKit** 的 `DocumentPickerDelegate`（`filekit-dialogs/iosMain`）——
 * 方法名与参数类型在 K/N 里没有别的可靠来源：
 *  - 复数版 `documentPicker(controller, didPickDocumentsAtURLs: List<*>)` —— `NSArray` 映射成 `List<*>`，
 *    元素要 `as? NSURL` 过滤；
 *  - `documentPickerWasCancelled(controller)` —— **只有一个参数**；
 *  - 基类必须是 `NSObject`，协议是 `UIDocumentPickerDelegateProtocol`。
 *
 * ⚠️ FileKit 还实现了废弃的单数版 `didPickDocumentAtURL: NSURL` —— 本类**刻意不实现**：
 * 少一个重载就少一处签名陷阱（两个重载只差第二参类型）。
 */
private class IosDocumentPickerDelegate(
    private val onPicked: (List<NSURL>) -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onPicked(didPickDocumentsAtURLs.mapNotNull { it as? NSURL })
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancelled()
    }
}
