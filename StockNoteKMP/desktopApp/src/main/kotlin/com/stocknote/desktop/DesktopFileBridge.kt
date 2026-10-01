package com.stocknote.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.stocknote.feature.state.FileBridge
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.Image as SkiaImage

/**
 * 桌面版文件桥 —— 老周 2026-09-30。对应 Android 的 [com.stocknote.app.AndroidFileBridge]（SAF）。
 *
 * 用 AWT `FileDialog`（系统原生对话框，Windows 上就是资源管理器那个）：CSV 导入导出、
 * 加密备份、截图选择全都复用 `FileBridge` 接口，**shared 层一行都不用改**。
 *
 * ⚠️ 与 Android 的两个已知差异（如实记录，不假装一致）：
 *  1. **没有"虚拟文档"概念**：SAF 返回 content:// URI，桌面直接给文件路径 —— 因此
 *     导出后**原地覆盖同名文件不再弹确认**（用户自己选的路径，覆盖属预期）。
 *  2. `pickImage` 的压缩在桌面用 `ImageIO` 做（Android 是 `BitmapFactory`），
 *     尺寸/质量参数保持一致（最长边 1600、JPEG 质量 85），避免同一张图在两端的体积差太多。
 */
class DesktopFileBridge : FileBridge {

    override suspend fun saveFile(suggestedName: String, mimeType: String, content: String): String? =
        save(suggestedName, content.toByteArray(Charsets.UTF_8))

    override suspend fun saveBytes(suggestedName: String, mimeType: String, bytes: ByteArray): String? =
        save(suggestedName, bytes)

    private fun save(suggestedName: String, payload: ByteArray): String? {
        val chosen = chooseFile(suggestedName, save = true) ?: return null
        return runCatching {
            chosen.parentFile?.mkdirs()
            chosen.writeBytes(payload)
            chosen.name
        }.getOrElse { "写入失败：${it.message ?: "未知错误"}" }
    }

    override suspend fun openFile(mimeType: String): Pair<String, String>? {
        val file = chooseFile("导入", save = false) ?: return null
        return runCatching {
            file.name to file.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun pickImage(): ByteArray? {
        val file = chooseFile("选择图片", save = false) ?: return null
        return runCatching { compressJpeg(file.readBytes()) }.getOrNull()
    }

    /**
     * 解码为 Compose 位图：桌面直接用 Skia（CMP 的渲染后端就是 skiko），
     * 不必像 Android 那样绕 `BitmapFactory` + `asImageBitmap`。
     */
    override fun decodeImage(bytes: ByteArray): ImageBitmap? =
        runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    /** 弹原生文件对话框；用户取消返回 null。 */
    private fun chooseFile(title: String, save: Boolean): File? {
        val dialog = FileDialog(
            null as Frame?,
            title,
            if (save) FileDialog.SAVE else FileDialog.LOAD,
        )
        if (save) dialog.file = title
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory ?: return null, name)
    }

    /**
     * 与 Android 同参数的压缩：最长边缩到 ≤1600、JPEG 质量 85。
     * ⚠️ 写 JPEG 前必须转 `TYPE_INT_RGB`：带 alpha 的图（PNG 截图）直接 encode 会失败。
     */
    private fun compressJpeg(raw: ByteArray): ByteArray {
        val src = ImageIO.read(ByteArrayInputStream(raw)) ?: error("无法解码所选图片")
        val maxSide = maxOf(src.width, src.height)
        val scale = if (maxSide > 1600) 1600.0 / maxSide else 1.0
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            // 透明区域垫白底，避免 PNG 透明处变黑块
            g.drawImage(src, 0, 0, w, h, java.awt.Color.WHITE, null)
        } finally {
            g.dispose()
        }

        val bos = ByteArrayOutputStream()
        ImageIO.write(out, "jpg", bos)
        return bos.toByteArray()
    }
}
