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

/**
 * iOS 的**文件桥**（2026-10-03，老周真机报「导出日志失败：当前平台未接入系统文件选择器」）。
 *
 * ## 为什么之前是空的
 * `FileBridgeHolder.impl` 只在 Android（SAF launcher）与桌面（Swing 文件对话框）注入过，
 * iOS 一直没有实现 ⇒ **导出运行日志、导出 CSV、备份导出、下载模板** 在 iPad 上**全都不通**
 *（不只是日志那一处）。
 *
 * ## 这一版的做法：**写进 `Documents/`**，不碰任何 UIKit
 * 理由有三条，都是本项目已经吃过亏的地方：
 *  1. **零 API 风险**：本机是 Windows，iOS 编译只能靠 CI（一轮 26 分钟以上），
 *     而 `UIDocumentPickerViewController` 的构造器名与 delegate 方法名在 K/N 里**没有**可靠先例
 *     —— 这一轮我刻意先用 POSIX（`fopen`/`fwrite`/`fclose`，与本项目 iOS 侧既有写法一致）把导出打通；
 *  2. **老周确认能拿到该目录的文件**：他 2026-10-03 报「/Documents 下没有文件」——
 *     说明他**能浏览到** App 沙盒的 `Documents/`（爱思的文件管理或 iPad「文件」App）；
 *  3. ⚠️ 与 P1-22 **不冲突**：P1-22 挪走的是**滚动日志**（`Library/Caches/stocknote.log`，不进备份、
 *     不会在「文件」App 里乱冒东西）；这里写的是**用户主动点导出**才产生的一次性文件，
 *     落在用户能找到的地方才合理。
 *
 * ⚠️ 返回值里带上 `Documents/` 前缀：调用方会把返回值直接显示给用户
 *（「已导出：Documents/xxx」），这样他才知道去哪儿拿 —— 其它平台只返回文件名。
 */
class IosFileBridge : FileBridge {

    override suspend fun saveFile(suggestedName: String, mimeType: String, content: String): String? =
        write(suggestedName, content.encodeToByteArray())

    override suspend fun saveBytes(suggestedName: String, mimeType: String, bytes: ByteArray): String? =
        write(suggestedName, bytes)

    /**
     * 「选择文件」在 iOS 上**尚未接入**：它必须走 `UIDocumentPicker`（另一套 UIKit API），
     * 本轮不冒这个风险。如实抛错，由调用方转成「导入失败：…」的提示（不静默）。
     * ⚠️ 受影响的是 **CSV 导入 / 备份恢复**；导出方向本轮已可用。
     */
    override suspend fun openFile(mimeType: String): Pair<String, String>? =
        error("iOS 侧「选择文件」还没接入（需要 UIDocumentPicker）；导出方向已可用")

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
