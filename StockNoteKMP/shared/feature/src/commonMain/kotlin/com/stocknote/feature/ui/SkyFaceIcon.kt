package com.stocknote.feature.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.stocknote.core.model.SkyAttitude
import com.stocknote.feature.theme.SkyEarthPalette

/** 三档表情（FR-SE-03，老周 2026-10-09）：哭脸 / 平脸 / 笑脸。 */
enum class SkyFace { BEAR, NEUTRAL, BULL }

/**
 * **三档判断的表情图标** —— Canvas 自绘（圆脸 + 两点眼睛 + 一条嘴）。
 *
 * ⚠️ **不用 emoji 字符**（`Text("🙂")`）：Android / iOS / 桌面三端字形与配色不一致
 * （Android 的哭脸是蓝色的），且无法灰化出「未选中」态 —— 自绘是唯一能保证三端一致的做法。
 *
 * 选中：脸底填档位色、五官白色；未选中：浅灰底 + 灰五官（= 未判断）。
 * 几何取自原型 `sky-earth.html` 的 SVG symbol（viewBox 24），逐点换算，保证与原型一致。
 */
@Composable
fun SkyFaceIcon(
    face: SkyFace,
    selected: Boolean,
    sizeDp: Int = 22,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) face.color() else SkyEarthPalette.FaceIdleBg
    val ink = if (selected) Color.White else SkyEarthPalette.FaceIdleInk

    Canvas(modifier.size(sizeDp.dp)) {
        val s = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f

        // 圆脸（原型 r = 11/24）
        drawCircle(bg, radius = s * 11f / 24f, center = Offset(cx, cy))

        // 两点眼睛（原型 (8.4, 9.4) / (15.6, 9.4)，r = 1.5/24）
        val eyeR = s * 1.5f / 24f
        drawCircle(ink, eyeR, Offset(cx - s * 3.6f / 24f, cy - s * 2.6f / 24f))
        drawCircle(ink, eyeR, Offset(cx + s * 3.6f / 24f, cy - s * 2.6f / 24f))

        // 嘴（stroke 1.7/24）
        val mouth = Path()
        when (face) {
            // 原型 M7.2 15.8 Q12 12.4 16.8 15.8：中间上拱 = 嘴角向下（哭）
            SkyFace.BEAR -> {
                mouth.moveTo(cx - s * 4.8f / 24f, cy + s * 3.8f / 24f)
                mouth.quadraticTo(cx, cy + s * 0.4f / 24f, cx + s * 4.8f / 24f, cy + s * 3.8f / 24f)
            }
            // 原型 M8 15.2 L16 15.2：一条水平直线
            SkyFace.NEUTRAL -> {
                mouth.moveTo(cx - s * 4f / 24f, cy + s * 3.2f / 24f)
                mouth.lineTo(cx + s * 4f / 24f, cy + s * 3.2f / 24f)
            }
            // 原型 M7.2 14 Q12 17.6 16.8 14：中间下弯 = 嘴角向上（笑）
            SkyFace.BULL -> {
                mouth.moveTo(cx - s * 4.8f / 24f, cy + s * 2f / 24f)
                mouth.quadraticTo(cx, cy + s * 5.6f / 24f, cx + s * 4.8f / 24f, cy + s * 2f / 24f)
            }
        }
        drawPath(mouth, ink, style = Stroke(width = s * 1.7f / 24f, cap = StrokeCap.Round))
    }
}

/**
 * 一行三个脸（列表行右侧 / 预览页 / 详情页当前档位）。
 *
 * ⚠️ 每个脸的**可点区域撑到 40×40dp**（需求 FR-SE-03：图标小也要好点）。
 * [current] == null = 未判断 → 三个都是灰的（**未判断 ≠ 中性**）。
 */
@Composable
fun SkyFaceRow(
    current: SkyAttitude?,
    onPick: (SkyAttitude) -> Unit,
    big: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        FACES.forEach { (face, attitude) ->
            val source = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(interactionSource = source, indication = null) { onPick(attitude) },
                contentAlignment = Alignment.Center,
            ) {
                SkyFaceIcon(face, selected = current == attitude, sizeDp = if (big) 28 else 22)
            }
        }
    }
}

private val FACES = listOf(
    SkyFace.BEAR to SkyAttitude.BEARISH,
    SkyFace.NEUTRAL to SkyAttitude.NEUTRAL,
    SkyFace.BULL to SkyAttitude.BULLISH,
)

private fun SkyFace.color(): Color = when (this) {
    SkyFace.BEAR -> SkyEarthPalette.VeryBearish
    SkyFace.NEUTRAL -> SkyEarthPalette.Neutral
    SkyFace.BULL -> SkyEarthPalette.VeryBullish
}
