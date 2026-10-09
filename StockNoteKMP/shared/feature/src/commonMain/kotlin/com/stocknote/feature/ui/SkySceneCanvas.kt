package com.stocknote.feature.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.stocknote.core.model.SkyLevel
import com.stocknote.feature.theme.SkyEarthPalette

/**
 * 看天看地 · **天气情景图 ×2**（FR-SE-06 / 技术说明书 §7.3）。
 *
 * 两组**各画各的**（老周 2026-10-08 定：「天气情景图也是各是各的」）：
 *  - [SkySceneCanvas]：天空主题 —— 天空渐变 + 太阳 / 云 / 雨 + 远山轮廓；
 *  - [EarthSceneCanvas]：地面主题 —— 天空窄条 + 土壤 / 水位 / 作物。
 *
 * ⚠️ Compose Canvas **自绘**，不引 PNG、不用三方图表库（既有约定；iOS 资源目录踩过坑）。
 * ⚠️ 颜色全部来自 `SkyEarthPalette`、**不复用行情红涨绿跌**（需求明令）。
 * ⚠️ 静态图，一期不做动画（技术说明书 §7.3 注）。
 */

/** 情景图绘制参数：档位 → 样式 的纯数据映射（便于预览与单测；颜色仍唯一来自 SkyEarthPalette）。 */
internal data class SceneStyle(
    val skyTop: Color,
    val skyBottom: Color,
    val sunVisible: Boolean,
    /** 太阳半径比（相对 minDimension）。 */
    val sunScale: Float,
    val cloudCount: Int,
    val cloudAlpha: Float,
    /** 雨丝条数（只有很悲观有）。 */
    val rainLines: Int,
    val hillColor: Color,
    val soil: Color,
    val water: Color,
    val crop: Color,
    /** 地组水位比 0~1（0.15 干裂 / 0.5 半水位 / 0.85 高水位）。 */
    val groundLevel: Float,
    /** 地组作物高度比 0~1。 */
    val cropHeight: Float,
)

/** 五档 → 绘制参数（技术说明书 §7.3 表格；未判断 = 中性底版、无太阳无雨、作物不画）。 */
internal fun sceneStyleOf(level: SkyLevel): SceneStyle = when (level) {
    SkyLevel.UNJUDGED -> SceneStyle(
        skyTop = SkyEarthPalette.SkyGreyTop, skyBottom = SkyEarthPalette.SkyGreyBottom,
        sunVisible = false, sunScale = 0f, cloudCount = 1, cloudAlpha = 0.35f, rainLines = 0,
        hillColor = SkyEarthPalette.HillGrey,
        soil = SkyEarthPalette.SoilLight, water = SkyEarthPalette.WaterMid, crop = SkyEarthPalette.CropSparse,
        groundLevel = 0.5f, cropHeight = 0f,
    )
    SkyLevel.VERY_BEARISH -> SceneStyle(
        skyTop = SkyEarthPalette.SkyStormTop, skyBottom = SkyEarthPalette.SkyStormBottom,
        sunVisible = false, sunScale = 0f, cloudCount = 5, cloudAlpha = 0.9f, rainLines = 60,
        hillColor = SkyEarthPalette.HillStorm,
        soil = SkyEarthPalette.SoilDry, water = SkyEarthPalette.WaterLow, crop = SkyEarthPalette.CropSparse,
        groundLevel = 0.15f, cropHeight = 0f,
    )
    SkyLevel.BEARISH -> SceneStyle(
        skyTop = SkyEarthPalette.SkyCloudyTop, skyBottom = SkyEarthPalette.SkyCloudyBottom,
        sunVisible = false, sunScale = 0f, cloudCount = 4, cloudAlpha = 0.75f, rainLines = 0,
        hillColor = SkyEarthPalette.HillCloudy,
        soil = SkyEarthPalette.SoilPale, water = SkyEarthPalette.WaterLow, crop = SkyEarthPalette.CropSparse,
        groundLevel = 0.35f, cropHeight = 0.25f,
    )
    SkyLevel.NEUTRAL -> SceneStyle(
        skyTop = SkyEarthPalette.SkyGreyTop, skyBottom = SkyEarthPalette.SkyGreyBottom,
        sunVisible = true, sunScale = 0.10f, cloudCount = 2, cloudAlpha = 0.5f, rainLines = 0,
        hillColor = SkyEarthPalette.HillGrey,
        soil = SkyEarthPalette.SoilLight, water = SkyEarthPalette.WaterMid, crop = SkyEarthPalette.CropSparse,
        groundLevel = 0.5f, cropHeight = 0.4f,
    )
    SkyLevel.BULLISH -> SceneStyle(
        skyTop = SkyEarthPalette.SkyWarmTop, skyBottom = SkyEarthPalette.SkyWarmBottom,
        sunVisible = true, sunScale = 0.13f, cloudCount = 1, cloudAlpha = 0.55f, rainLines = 0,
        hillColor = SkyEarthPalette.HillWarm,
        soil = SkyEarthPalette.SoilWet, water = SkyEarthPalette.WaterHigh, crop = SkyEarthPalette.CropMid,
        groundLevel = 0.7f, cropHeight = 0.7f,
    )
    SkyLevel.VERY_BULLISH -> SceneStyle(
        skyTop = SkyEarthPalette.SkyClearTop, skyBottom = SkyEarthPalette.SkyClearBottom,
        sunVisible = true, sunScale = 0.17f, cloudCount = 1, cloudAlpha = 0.6f, rainLines = 0,
        hillColor = SkyEarthPalette.HillClear,
        soil = SkyEarthPalette.SoilRich, water = SkyEarthPalette.WaterDeep, crop = SkyEarthPalette.CropRich,
        groundLevel = 0.85f, cropHeight = 1f,
    )
}

/** 天组：天空主题情景图（接收五档中任一档，含未判断的灰底态）。 */
@Composable
fun SkySceneCanvas(level: SkyLevel, modifier: Modifier = Modifier) {
    val style = remember(level) { sceneStyleOf(level) }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(style.skyTop, style.skyBottom)))
        if (style.sunVisible) drawSun(style.sunScale)
        drawClouds(style.cloudCount, style.cloudAlpha)
        if (style.rainLines > 0) drawRain(style.rainLines)
        drawHills(style.hillColor)
    }
}

/** 地组：地面主题情景图（水位与作物随档位变化）。 */
@Composable
fun EarthSceneCanvas(level: SkyLevel, modifier: Modifier = Modifier) {
    val style = remember(level) { sceneStyleOf(level) }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.32f

        // 天空窄条
        drawRect(
            brush = Brush.verticalGradient(listOf(style.skyTop, style.skyBottom)),
            size = Size(w, horizon),
        )
        // 土壤
        drawRect(style.soil, topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
        // 水位（从底部升起；groundLevel 越小水位越低）
        val waterTop = horizon + (h - horizon) * (1f - style.groundLevel * 0.85f)
        drawRect(style.water.copy(alpha = 0.75f), topLeft = Offset(0f, waterTop), size = Size(w, h - waterTop))
        // 很悲观：土壤干裂
        if (style.groundLevel < 0.3f) drawCracks(horizon)
        // 作物（从水面长出来）
        if (style.cropHeight > 0.01f) drawCrops(style.crop, style.cropHeight, waterTop)
    }
}

// ---------------- 绘制细节 ----------------

/** 太阳（右上）：外晕 + 内盘（很乐观时再点一个内核）。 */
private fun DrawScope.drawSun(scale: Float) {
    val r = size.minDimension * scale
    val c = Offset(size.width * 0.82f, size.height * 0.26f)
    drawCircle(SkyEarthPalette.SunOuter.copy(alpha = 0.9f), radius = r * 1.7f, center = c)
    drawCircle(SkyEarthPalette.SunInner, radius = r, center = c)
    if (scale >= 0.15f) drawCircle(SkyEarthPalette.SunCore, radius = r * 0.55f, center = c)
}

/** 云：固定位置表（前 N 个），两笔椭圆叠成一朵；位置固定，重组不闪。 */
private fun DrawScope.drawClouds(count: Int, alpha: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until count.coerceAtMost(CLOUD_SPOTS.size)) {
        val (x, y, r) = CLOUD_SPOTS[i]
        val color = SkyEarthPalette.Cloud.copy(alpha = alpha.coerceIn(0f, 1f))
        drawOval(
            color,
            topLeft = Offset(w * (x - r), h * (y - r * 0.42f)),
            size = Size(w * r * 2f, h * r * 0.84f),
        )
        drawOval(
            color.copy(alpha = alpha * 0.75f),
            topLeft = Offset(w * (x - r * 0.45f), h * (y - r * 0.95f)),
            size = Size(w * r * 1.25f, h * r * 0.72f),
        )
    }
}

/** 雨丝：确定性伪分布（同一档位每次重组画在同样位置，不闪）。 */
private fun DrawScope.drawRain(count: Int) {
    val w = size.width
    val h = size.height * 0.85f
    val color = SkyEarthPalette.Rain.copy(alpha = 0.55f)
    for (i in 0 until count) {
        val x = ((i * 37) % 101) / 101f * w
        val y = ((i * 53) % 97) / 97f * h
        drawLine(
            color,
            start = Offset(x, y),
            end = Offset(x - size.minDimension * 0.02f, y + size.minDimension * 0.09f),
            strokeWidth = 1.4f,
            cap = StrokeCap.Round,
        )
    }
}

/** 远山：两层折线轮廓（固定山形，颜色随档位）。 */
private fun DrawScope.drawHills(color: Color) {
    val w = size.width
    val h = size.height
    drawPath(hillPath(HILL_FAR, w, h, yBase = 0.58f, ySpan = 0.35f), color.copy(alpha = 0.45f))
    drawPath(hillPath(HILL_NEAR, w, h, yBase = 0.70f, ySpan = 0.30f), color.copy(alpha = 0.7f))
}

/**
 * 按 [points]（x,y 交替的相对坐标）生成「折线 + 封底到画面底部」的山形 Path。
 * 山脊 y = h × ([yBase] + p × [ySpan])。
 */
private fun hillPath(points: FloatArray, w: Float, h: Float, yBase: Float, ySpan: Float): Path {
    val path = Path()
    path.moveTo(0f, h * (yBase + points[1] * ySpan))
    var i = 0
    while (i + 1 < points.size) {
        path.lineTo(w * points[i], h * (yBase + points[i + 1] * ySpan))
        i += 2
    }
    path.lineTo(w, h)
    path.lineTo(0f, h)
    path.close()
    return path
}

/** 干裂纹（很悲观的土壤）：几条短斜线。 */
private fun DrawScope.drawCracks(horizon: Float) {
    val w = size.width
    val h = size.height
    val color = SkyEarthPalette.Crack.copy(alpha = 0.6f)
    for ((fx, fy, len) in CRACK_SPOTS) {
        val start = Offset(w * fx, horizon + (h - horizon) * fy)
        drawLine(
            color,
            start,
            Offset(start.x + w * len, start.y + h * len * 0.55f),
            strokeWidth = 1.6f,
            cap = StrokeCap.Round,
        )
    }
}

/** 作物：固定几株，两片叶 + 一根茎，高度由 cropHeight 决定。 */
private fun DrawScope.drawCrops(color: Color, cropHeight: Float, waterTop: Float) {
    val w = size.width
    val h = size.height
    val maxStem = h * 0.42f
    CROP_SPOTS.forEach { fx ->
        val baseX = w * fx
        val stem = maxStem * cropHeight
        val top = Offset(baseX, waterTop - stem)
        drawLine(color, Offset(baseX, waterTop), top, strokeWidth = 2.4f, cap = StrokeCap.Round)
        drawLine(color, top, Offset(baseX - w * 0.035f, top.y + stem * 0.28f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(color, top, Offset(baseX + w * 0.035f, top.y + stem * 0.28f), strokeWidth = 2f, cap = StrokeCap.Round)
    }
}

/** 云的位置表（相对坐标 x, y, 半径比）—— 最多 5 朵，前 N 个依次使用。 */
private val CLOUD_SPOTS = listOf(
    Triple(0.24f, 0.28f, 0.085f),
    Triple(0.44f, 0.20f, 0.062f),
    Triple(0.63f, 0.38f, 0.070f),
    Triple(0.13f, 0.47f, 0.058f),
    Triple(0.79f, 0.22f, 0.055f),
)

/** 远山折线（x 递增 0..1，y 为相对起伏 0..1）。 */
private val HILL_FAR = floatArrayOf(0f, 0.1f, 0.16f, 0.55f, 0.30f, 0.15f, 0.46f, 0.62f, 0.60f, 0.28f, 0.76f, 0.55f, 0.90f, 0.18f, 1f, 0.35f)
private val HILL_NEAR = floatArrayOf(0f, 0.3f, 0.14f, 0.7f, 0.32f, 0.25f, 0.50f, 0.72f, 0.68f, 0.4f, 0.84f, 0.66f, 1f, 0.42f)

/** 裂纹位置（相对 x, 相对土壤区 y, 长度比）。 */
private val CRACK_SPOTS = listOf(
    Triple(0.10f, 0.25f, 0.05f),
    Triple(0.33f, 0.45f, 0.06f),
    Triple(0.58f, 0.20f, 0.045f),
    Triple(0.76f, 0.55f, 0.055f),
    Triple(0.90f, 0.30f, 0.04f),
)

/** 作物位置（相对 x）。 */
private val CROP_SPOTS = listOf(0.12f, 0.30f, 0.48f, 0.66f, 0.84f)
