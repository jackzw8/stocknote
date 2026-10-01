package com.stocknote.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import com.stocknote.feature.state.FileBridge
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android 侧 SAF（Storage Access Framework）文件桥 —— 老周 2026-09-17。
 *
 * 用系统文件选择器：
 *  - 导出/备份：`CreateDocument` —— 用户自选保存位置与文件名
 *  - 导入/恢复：`OpenDocument` —— 用户自选文件
 *
 * 必须在 Activity **创建期**（onCreate）构造，因为 `registerForActivityResult` 要求
 * 在 STARTED 之前注册；协程侧用 `suspendCancellableCoroutine` 把回调桥接成挂起函数。
 */
class AndroidFileBridge(private val activity: ComponentActivity) : FileBridge {

    private var saveCont: CancellableContinuation<String?>? = null
    private var openCont: CancellableContinuation<Pair<String, String>?>? = null
    private var savePayload: ByteArray = ByteArray(0)

    private val saveLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val cont = saveCont
        saveCont = null
        if (cont == null) return@registerForActivityResult
        if (uri == null) {
            cont.resume(null)   // 用户取消
        } else {
            cont.resume(
                runCatching {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(savePayload)
                    } ?: error("无法写入所选位置")
                    displayName(uri).ifEmpty { "已保存" }
                }.getOrElse { e -> "写入失败：${e.message ?: "未知错误"}" },
            )
        }
    }

    private val openLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val cont = openCont
        openCont = null
        if (cont == null) return@registerForActivityResult
        if (uri == null) {
            cont.resume(null)   // 用户取消
        } else {
            cont.resume(
                runCatching {
                    val text = activity.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("无法读取所选文件")
                    displayName(uri).ifEmpty { "所选文件" } to text
                }.getOrNull(),
            )
        }
    }

    override suspend fun saveFile(suggestedName: String, mimeType: String, content: String): String? =
        save(suggestedName, content.toByteArray(Charsets.UTF_8))

    /** 二进制保存（模板 zip 包）—— 老周 2026-09-21 */
    override suspend fun saveBytes(suggestedName: String, mimeType: String, bytes: ByteArray): String? =
        save(suggestedName, bytes)

    private suspend fun save(suggestedName: String, payload: ByteArray): String? =
        suspendCancellableCoroutine { cont ->
            savePayload = payload
            saveCont = cont
            cont.invokeOnCancellation { saveCont = null }
            runCatching { saveLauncher.launch(suggestedName) }.onFailure {
                saveCont = null
                cont.resume(null)
            }
        }

    override suspend fun openFile(mimeType: String): Pair<String, String>? =
        suspendCancellableCoroutine { cont ->
            openCont = cont
            cont.invokeOnCancellation { openCont = null }
            val types = if (mimeType.isBlank()) arrayOf("*/*") else arrayOf(mimeType, "*/*")
            runCatching { openLauncher.launch(types) }.onFailure {
                openCont = null
                cont.resume(null)
            }
        }

    // ---------------------------------------------------------------- 选图（交易截图）

    private var imgCont: CancellableContinuation<ByteArray?>? = null

    private val imageLauncher = activity.registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        val cont = imgCont
        imgCont = null
        if (cont == null) return@registerForActivityResult
        if (uri == null) {
            cont.resume(null)
        } else {
            cont.resume(runCatching { loadCompressedJpeg(uri) }.getOrNull())
        }
    }

    override suspend fun pickImage(): ByteArray? = suspendCancellableCoroutine { cont ->
        imgCont = cont
        cont.invokeOnCancellation { imgCont = null }
        runCatching { imageLauncher.launch("image/*") }.onFailure {
            imgCont = null
            cont.resume(null)
        }
    }

    /**
     * 读图并压缩：先按尺寸采样（inSampleSize）到最长边 ≤1600，再 JPEG 质量 85 编码。
     * 一张 4000×3000 的手机截图约压到 100~250KB，存库无压力。
     */
    private fun loadCompressedJpeg(uri: Uri): ByteArray {
        val raw = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选图片")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        var maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > 1600) sample *= 2

        val bmp = BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: error("无法解码所选图片")

        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** 解码为 Compose 位图（交易截图预览用） */
    override fun decodeImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? {
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return null
        return runCatching { bmp.asImageBitmap() }.getOrNull()
    }

    /** 取 SAF 文档的展示文件名 */
    private fun displayName(uri: Uri): String =
        runCatching {
            activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
            } ?: ""
        }.getOrDefault("")
}
