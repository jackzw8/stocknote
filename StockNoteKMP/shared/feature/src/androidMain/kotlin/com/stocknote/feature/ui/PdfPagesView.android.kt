package com.stocknote.feature.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * ## 交互（老周 2026-10-04 追加）
 * **双指缩放（1×~[MAX_ZOOM]×）+ 拖动查看**，手势分派见 [PdfPageList]（自己写的手势处理，
 * 因为"缩放"和"列表滚动"是同一个手势，不能用 `transformable` 一把梭）。
 *
 * ## 三端现状（别以为漏了）
 *  - **Android**：本文件（WebView 没有 PDF 能力，只能自己渲染）；
 *  - **iOS**：`WKWebView` **原生就能渲染 PDF**（自带的缩放/拖动也是原生的），无需额外代码（真机已确认）；
 *  - **桌面（JavaFX）**：JavaFX WebView 无 PDF 能力，本轮**未做**（桌面版不在默认交付范围内）。
 */
private const val PDF_UA = "Mozilla/5.0 (Linux; Android 13) StockNote"

/** PDF 页 bitmap 的宽度上限：太宽会 OOM（＝屏宽 × [MAX_RENDER_SCALE]，正常远低于此）。 */
private const val MAX_RENDER_WIDTH = 2160

/** 双指缩放的上限（再大只是把位图拉大，字会糊）。 */
private const val MAX_ZOOM = 4f

/**
 * 位图**重渲染**的倍率上限。
 *
 * 手势落定后按 `屏宽 × 倍率` 重新出一道更清晰的图（放大后文字才不糊）；
 * 夹在 2 倍是为了**内存** —— 一页 2× 屏宽约 20MB+（ARGB_8888），再高容易 OOM。
 */
private const val MAX_RENDER_SCALE = 2f

/** 逐页列表的左右内边距（横向平移的边界要用到它）。 */
private val PDF_H_PADDING = 12.dp

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

        // 底部提示固定放在**缩放区之外**（老周 2026-10-04）：不会被一起放大，也不会
        // 跟着缩放平移"跑"（它本来就不是页面内容）。
        if (h != null && !loading && error == null) {
            Text(
                text = "共 ${h.pageCount} 页 · 双指放大、放大后拖动看",
                fontSize = pageSp(11f),
                color = StockNoteColors.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * 逐页列表 —— 支持**双指缩放 + 拖动查看**（老周 2026-10-04）。
 *
 * ## 为什么自己写手势，不用 `transformable`
 * 缩放要和**列表滚动**共存，两者天生抢同一个手势，必须自己分派：
 *  - **1x + 单指** → **不消费**，交给 LazyColumn 自己滚（滚动/惯性保持原生手感）；
 *  - **双指** → 捏合缩放（横向按捏合中心锚定、纵向用滚动量补偿），并**消费**事件，
 *    否则第一根手指会先被列表当成拖动、缩放时页面乱跑；
 *  - **放大后 + 单指** → 横向平移 + 纵向滚动（`pan / scale`，手指与内容才 1:1）。
 *
 * ⚠️ 判断放在 `PointerEventPass.Initial`：必须比 LazyColumn 的滚动**先看到**事件才能抢下来。
 *
 * ## 纵向只有一个模型：滚列表 + **动态底部留白**（两次 bug 的最终解）
 * 图层以**视口左上角**为锚点缩放 ⇒ 屏幕上只看得到滚动窗口**最上面 `1/scale` 那一段**
 *（屏幕 `[0, H]` ↔ 视口布局坐标 `[0, H/scale]`）。所以：
 *  - 不补留白时，最大滚动量只有 `内容高 − H`，放大后**文档尾部永远差 `H − H/scale` 看不到**
 *    —— 老周 2026-10-04 报的「多页 PDF 最后一页看不到」就是这个；
 *  - 补一块 `H − H/scale` 的**底部留白**后，最大滚动量放宽到 `内容高 − H/scale`，
 *    刚好够把"文档末尾"滚到屏幕底。
 *
 * 而**单页 PDF**（内容本来比视口矮、滚动区间为 0）在放大后也会因为这块留白而变得可滚动
 *（`内容高 + H − H/scale > H` 恰好等价于 `内容高 × scale > H`，也就是"确实有内容超出屏幕"）——
 * 所以**不需要**再搞一套"图层偏移"的分支：「放大后没法上滑」与「最后一页看不到」同一个修法解决。
 *
 * ## 清晰度
 * 拖动过程中缩放的是**已渲染的位图**（略糊但不掉帧）；**松手后**按落定倍率重渲染
 * 更清晰的位图（[renderScale]，上限 [MAX_RENDER_SCALE]）。
 *
 * ## 坐标约定
 * 图层用 **左上角** 作缩放锚点（`TransformOrigin(0f, 0f)`）⇒ `屏幕 = 布局 × scale + 平移`
 * —— 下面的锚定 / 夹取算式都基于这条式子。
 */
@Composable
private fun PdfPageList(holder: PdfHolder) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = constraints.maxWidth.coerceIn(1, MAX_RENDER_WIDTH).toFloat()
        val viewportHeight = constraints.maxHeight.coerceAtLeast(1).toFloat()
        // 页面左右各留一点内边距；横向平移的边界按**真实内容宽度**算，拉到底才不会露白边
        val padPx = with(LocalDensity.current) { PDF_H_PADDING.toPx() }
        val contentWidth = (viewportWidth - 2 * padPx).coerceAtLeast(1f)

        var scale by remember(holder) { mutableFloatStateOf(1f) }
        var offsetX by remember(holder) { mutableFloatStateOf(0f) }
        // 位图按「手势落定后」的倍率渲染：拖动中重画会掉帧
        var renderScale by remember(holder) { mutableFloatStateOf(1f) }
        val listState = rememberLazyListState()

        // 横向平移的合法区间（见「坐标约定」）：内容放大后要始终盖满视口
        fun clampX(x: Float, s: Float): Float {
            if (s <= 1f) return 0f
            val lo = viewportWidth - (padPx + contentWidth) * s
            val hi = -padPx * s
            return if (lo > hi) 0f else x.coerceIn(lo, hi)
        }

        // ---- 纵向：用「动态底部留白」把滚动区间撑够（推导见 KDOC「纵向只有一个模型」）----
        // scale = 1 时为 0（等于没补）；放大后补 `H − H/scale`，让文档末尾能滚到屏幕底。
        val extraBottomDp = with(LocalDensity.current) {
            (viewportHeight * (1f - 1f / scale)).coerceAtLeast(0f).toDp()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 放大后必须裁掉溢出，否则会盖到底下的顶部栏上
                .clipToBounds()
                .pointerInput(holder, viewportWidth) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break

                            val zoomChange = if (pressed > 1) event.calculateZoom() else 1f
                            val pan = event.calculatePan()
                            // 1x 下的单指拖动 = 正常滚动：不消费，让 LazyColumn 处理
                            if (pressed == 1 && scale <= 1f) continue
                            if (zoomChange == 1f && pan == Offset.Zero) continue

                            val oldScale = scale
                            val newScale = (oldScale * zoomChange).coerceIn(1f, MAX_ZOOM)
                            if (newScale != oldScale) {
                                // ⚠️ 用"上一帧"的捏合中心：zoomChange 本身就是按上一帧→当前帧算的
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (centroid != Offset.Unspecified) {
                                    // 横向：让捏合中心下的那一点保持不动
                                    val contentX = (centroid.x - offsetX) / oldScale
                                    offsetX = clampX(centroid.x - contentX * newScale, newScale)
                                    // 纵向：缩放把内容"撑高"了，用滚动量补偿 ——
                                    // 缩放前落在屏幕 cy 的那一点，缩放后要还落在 cy
                                    // （文档点 p 在屏幕上的位置 = (p − 滚动量) × scale）
                                    listState.dispatchRawDelta(
                                        centroid.y / oldScale - centroid.y / newScale,
                                    )
                                }
                            }
                            scale = newScale
                            offsetX = if (newScale <= 1f) 0f else clampX(offsetX + pan.x, newScale)
                            if (pan.y != 0f) {
                                // 纵向拖动 = 滚文档（按 1/scale 折算，手指与内容才 1:1）；
                                // 手指下滑是"往回滚"，故取负。
                                // 滚得动多少由底部动态留白保证（见 KDOC），单页放大后同样能滚。
                                listState.dispatchRawDelta(-pan.y / newScale)
                            }

                            event.changes.forEach { it.consume() }
                        }
                        // 手势结束：按落定倍率重渲染，放大后的字才清晰
                        renderScale = scale.coerceIn(1f, MAX_RENDER_SCALE)
                    }
                },
        ) {
            val renderWidth = (viewportWidth * renderScale).toInt().coerceIn(1, MAX_RENDER_WIDTH)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        // 以左上角为锚点（见「坐标约定」）
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                // ⚠️ 底部留白必须随 scale 变化（见「纵向」注释）：放大后不放宽滚动区间，
                //    文档尾部就永远差 `H − H/scale` 看不到
                contentPadding = PaddingValues(
                    start = PDF_H_PADDING,
                    end = PDF_H_PADDING,
                    top = 12.dp,
                    bottom = 12.dp + extraBottomDp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(holder.pageCount) { index -> PdfPageItem(holder, index, renderWidth) }
            }

            // 放大后才出现的浮动标签：显示倍数，点一下复位
            if (scale > 1f) {
                Text(
                    "${(scale * 100).toInt()}% · 点击复位",
                    fontSize = pageSp(11f),
                    color = StockNoteColors.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .clickable {
                            scale = 1f
                            offsetX = 0f
                            renderScale = 1f
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun PdfPageItem(holder: PdfHolder, index: Int, targetWidth: Int) {
    // 渲染放后台线程（主线程渲染大页会掉帧）；条目滚出屏幕后会被回收，滚回来重渲染。
    // ⚠️ 刻意**不用 `produceState(key = targetWidth)`**：缩放后 targetWidth 一变，produceState 会先把
    //    值清成初始的 null ⇒ 页面闪一下「渲染中…」再出图。这里**保留旧位图**，新图好了再替换，视觉无感
    //    （同一页不同倍率的宽高比一致，替换时布局尺寸不变，不会跳动）。
    var bitmap by remember(holder, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(holder, index, targetWidth) {
        val rendered = withContext(Dispatchers.Default) {
            runCatching { holder.render(index, targetWidth) }.getOrNull()
        }
        if (rendered != null) bitmap = rendered
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
