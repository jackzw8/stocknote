package com.stocknote.feature.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocknote.data.log.SnLog
import com.stocknote.feature.theme.StockNoteColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android 端**内置 PDF 阅读器**（老周 2026-10-04）。
 *
 * ## 为什么需要
 * 资讯 →「公告」正文里点「文件」，会跳到公告 PDF（腾讯 `file.finance.qq.com/…/xxx.PDF`、
 * 东财 `pdf.dfcfw.com/…pdf`）。这些域名已在导航白名单里 ⇒ 导航**留在内嵌页** ——
 * 但 **Android 的 WebView 渲染不了 PDF**（能力边界，不是 bug）：
 * 页面一片空白，或者直接触发下载（`setDownloadListener` 只能把链接丢给系统浏览器，等于跳出 App）。
 *
 * ## 怎么做的
 * 用系统自带的 `android.graphics.pdf.PdfRenderer`（**API 21+，无需三方库、不联网渲染**）：
 * 把 PDF 下到缓存目录 → 逐页渲染成位图 → 在 App 内纵向翻阅。
 * 判据见 commonMain 的 [isPdfUrl]。
 *
 * ## 三端现状（别以为漏了）
 *  - **Android**：本文件（WebView 没有 PDF 能力，只能自己渲染）；
 *  - **iOS**：`WKWebView` **原生就能渲染 PDF**，无需额外代码（真机已确认）；
 *  - **桌面（JavaFX）**：JavaFX WebView 无 PDF 能力，本轮**未做**（桌面版不在默认交付范围内）。
 */
private const val PDF_UA = "Mozilla/5.0 (Linux; Android 13) StockNote"

/** PDF 页 bitmap 的宽度上限：太宽会 OOM（渲染按屏宽出图，正常远低于此）。 */
private const val MAX_RENDER_WIDTH = 2160

/**
 * 内置 PDF 阅读界面：顶部栏（返回）+ 逐页纵向列表。
 *
 * @param url PDF 地址（http/https）
 * @param onClose 返回按钮回调（回到原来的网页）
 */
@Composable
internal fun PdfPagesView(url: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var holder by remember(url) { mutableStateOf<PdfHolder?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var loading by remember(url) { mutableStateOf(true) }

    LaunchedEffect(url) {
        loading = true
        error = null
        runCatching {
            val file = withContext(Dispatchers.IO) { downloadPdf(context, url) }
            // PdfRenderer 是 native 资源，构建也放到后台线程
            holder = withContext(Dispatchers.Default) { PdfHolder(file) }
        }.onFailure {
            SnLog.w(NAV_LOG_TAG, "PDF 打开失败：$url", it)
            error = it.message ?: it::class.simpleName ?: "未知错误"
        }
        loading = false
    }

    // ⚠️ key = holder：切换/退出时释放**上一份** PdfRenderer 与文件描述符（native 资源不释放会泄漏）
    DisposableEffect(holder) {
        val h = holder
        onDispose { h?.close() }
    }

    Column(modifier.background(StockNoteColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBar(title = "公告 PDF", onBack = onClose)
        }

        val h = holder
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> HintBox("正在打开 PDF…")
                error != null -> PdfErrorBox(error!!, url)
                h != null -> PdfPageList(h)
                else -> HintBox("PDF 打不开")
            }
        }
    }
}

/** 逐页列表：每页按**屏幕宽度**出图（正文宽度内可读，无需左右拖）。 */
@Composable
private fun PdfPageList(holder: PdfHolder) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val targetWidth = constraints.maxWidth.coerceIn(1, MAX_RENDER_WIDTH)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(holder.pageCount) { index -> PdfPageItem(holder, index, targetWidth) }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "共 ${holder.pageCount} 页 · 内容来自公告原文",
                        fontSize = pageSp(11f),
                        color = StockNoteColors.TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(holder: PdfHolder, index: Int, targetWidth: Int) {
    // 渲染放后台线程（主线程渲染大页会掉帧）；条目滚出屏幕后会被回收，滚回来重渲染
    val bitmap by produceState<Bitmap?>(initialValue = null, holder, index, targetWidth) {
        value = withContext(Dispatchers.Default) {
            runCatching { holder.render(index, targetWidth) }.getOrNull()
        }
    }
    val bmp = bitmap
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "第 ${index + 1} 页",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Text(
                    "第 ${index + 1} 页渲染中…",
                    fontSize = pageSp(12f),
                    color = StockNoteColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun HintBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = pageSp(13f), color = StockNoteColors.TextSecondary)
    }
}

/** 打不开时**如实说清 + 给出口**（与 iOS/桌面的失败视图同一态度：绝不留白）。 */
@Composable
private fun PdfErrorBox(reason: String, url: String) {
    val openBrowser = rememberBrowserOpener()
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("PDF 打不开", fontSize = pageSp(15f), fontWeight = FontWeight.Bold, color = StockNoteColors.TextPrimary)
            Text(reason, fontSize = pageSp(12f), color = StockNoteColors.TextSecondary)
            Text(
                "用系统浏览器打开",
                fontSize = pageSp(13f),
                color = StockNoteColors.Brand,
                modifier = Modifier.clickable { openBrowser(url) }.padding(6.dp),
            )
        }
    }
}

/**
 * PDF 句柄：持有 `PdfRenderer` 与文件描述符，**必须 close**（native 资源）。
 *
 * ⚠️ `render` 加 `@Synchronized`：`PdfRenderer` 不是线程安全的，而多页的渲染分布在
 * 不同的协程里（滚谁渲谁），不加锁会偶发崩溃/花屏。
 */
private class PdfHolder(file: File) : AutoCloseable {

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun render(index: Int, targetWidthPx: Int): Bitmap {
        val page = renderer.openPage(index)
        try {
            val w = targetWidthPx.coerceAtLeast(1)
            val h = (w.toFloat() * page.height.toFloat() / page.width.toFloat())
                .toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // ⚠️ PDF 页没有背景 → 不先填白会得到**黑底黑字**（这个坑很容易被当成"渲染失败"）
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

/**
 * 把 PDF 下到**缓存目录**（同一份文件再次打开时直接复用，不重复下载；缓存由系统按需回收）。
 *
 * ⚠️ 带 `User-Agent` / `Referer`：公告文件在腾讯的静态文件域上，裸请求偶尔会被拒。
 */
private fun downloadPdf(context: Context, url: String): File {
    val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
    val name = "doc_" + url.hashCode().toUInt().toString(16) + ".pdf"
    val file = File(dir, name)
    if (file.length() > 0L) return file

    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 40_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", PDF_UA)
        setRequestProperty("Referer", "https://gu.qq.com/")
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        conn.inputStream.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        }
    } finally {
        runCatching { conn.disconnect() }
    }
    if (file.length() <= 0L) throw IOException("下载到的文件为空")
    return file
}
