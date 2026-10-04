package com.stocknote.feature.state

/**
 * 文件选择桥（SAF）—— 老周 2026-09-17 要求：CSV 导入导出 / 备份恢复走**系统文件选择器**，
 * 由用户自选保存位置与文件，而不是固定写 `Documents/StockNote/`。
 *
 * shared 层不能直接用 Android 的 ActivityResult API，因此这里定义接口 + 全局持有；
 * **Android 侧在 MainActivity 注册 SAF launcher 后注入实现**。
 *
 * ⚠️ 各平台现状（2026-10-04）：
 *  - **Android**：完整（SAF，导出 + 导入 + 选图）；
 *  - **桌面**：完整（Swing 文件对话框）；
 *  - **iOS**：`IosFileBridge` —— **导出**（POSIX 写进 `Documents/`）与 **导入**（`UIDocumentPicker`
 *    选文件，CSV 导入 / 备份恢复可用）都已接入；**只有 `pickImage`（交易截图）还没接**（需要 `PHPicker`），
 *    它**如实抛错**、不静默。
 *
 * 未注入实现时（历史上 iOS 就是这样）调用会报「当前平台未接入系统文件选择器」。
 */
interface FileBridge {

    /** 让用户选保存位置并写入；取消返回 null */
    suspend fun saveFile(suggestedName: String, mimeType: String, content: String): String?

    /**
     * 与 [saveFile] 相同，但写的是**二进制**内容（模板 zip 包用）—— 老周 2026-09-21。
     * 平台没实现时抛错，由调用方转成「导出失败：…」的提示，不静默。
     */
    suspend fun saveBytes(suggestedName: String, mimeType: String, bytes: ByteArray): String? =
        error("当前平台未接入二进制文件保存")

    /**
     * 让用户选一个文件并读回文本；取消返回 null。
     * @return Pair(展示名, 内容)
     */
    suspend fun openFile(mimeType: String): Pair<String, String>?

    /**
     * 选一张图片并返回**已压缩**的 JPEG 字节（最长边约 1600，质量 85，通常 < 300KB）；
     * 取消返回 null。用于「交易截图」。
     */
    suspend fun pickImage(): ByteArray?

    /**
     * 把 JPEG/PNG 字节解码为可渲染的位图（平台实现）。
     * shared 层不引 skia，解码下沉到平台：Android 用 BitmapFactory + asImageBitmap。
     * 解码失败返回 null（UI 退化为只显示"已添加"文字）。
     */
    fun decodeImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap?
}

/** 全局持有（由平台壳注入实现） */
object FileBridgeHolder {

    // ⚠️ 2026-10-01：必须写**全限定** `kotlin.concurrent.Volatile`。
    // commonMain 里裸 `@Volatile` 解析不到 —— `kotlin.jvm.Volatile` 是 JVM 侧才有的隐式导入，
    // Kotlin/Native 上不存在 → iOS 编译报 `Unresolved reference 'Volatile'`。
    // 写法与 PortfolioRepository 里的 `cachedCurve` 保持一致。
    @kotlin.concurrent.Volatile
    var impl: FileBridge? = null

    val available: Boolean get() = impl != null

    fun require(): FileBridge = impl ?: error("当前平台未接入系统文件选择器")
}
