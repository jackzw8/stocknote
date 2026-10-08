package com.stocknote.feature.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stocknote.feature.theme.StockNoteColors

/**
 * **星际战机**小游戏页（老周 2026-10-04）—— 探索页 →「星际战机」。
 *
 * 玩法全在 HTML 里（`PlaneShooterHtml`，含 10 关 / 剧情 / 母舰 Boss），
 * 这里只负责给它一个容器：顶栏（返回）+ WebView 本体。
 *
 * 为什么走 WebView 而不是用 Compose Canvas 重写：游戏本身就是一份自洽的 H5 canvas 实现
 *（约 2000 行，含弹幕 / 粒子 / 音效 / 关卡编排），照搬能保证**手感与逻辑原样**，
 * 重写等于重做一份、且难以对齐。
 *
 * 口径：
 *  - 内容全内联、**离线可玩**，不发任何网络请求；
 *  - 最高分存在 WebView 的 `localStorage`（[LOCAL_HTML_BASE_URL] 提供的 origin）。
 */
@Composable
fun PlaneShooterScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalPageTextScale provides PAGE_TEXT_SCALE) {
        Column(modifier.fillMaxSize().background(StockNoteColors.Background)) {
            // 与资讯详情 / 7×24 快讯同一套顶栏写法
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBar(title = "星际战机", onBack = onBack)
            }

            // 游戏本体。底色与游戏自带背景一致（#04060d），避免 WebView 首帧前闪一下白。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF04060D)),
            ) {
                LocalHtmlView(html = PlaneShooterPage, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * **视口高度适配脚本**（2026-10-04 修「除了标题全是黑屏」，真机实测换来的）。
 *
 * ## 现象
 * Android WebView（实测 Chromium 96 / Redmi K30 / Android 12）里，本页
 * `html,body{height:100%}` → `#stage{height:100%}` 这条**百分比高度链会一路算成 0**：
 * `body.clientHeight = 0`、`getComputedStyle(html).height = "0px"`，连 `100vh` 都量到 `0px`
 * —— 而同一时刻 `documentElement.clientHeight` 却是正常的 `756`（视口高度是对的）。
 * 等于"CSS 看到的高度是 0"。于是 `#stage{overflow:hidden}` 把**所有子元素全剪掉**：
 * DOM 完整、元素尺寸正常（日志探针：`bodyLen=66714 stage=yes overlay=yes`），
 * 屏幕上却一片空白 —— 真机截屏那块区域是**纯色、216 万像素零变化**。
 *
 * ## 做法
 * 绕开 `%` / `vh`，用 `window.innerHeight` 给出**显式像素高度**，打破这个循环；尺寸变化时重设。
 * `#stage` 高度一变，游戏自己的 `ResizeObserver` 就会重跑 `resize()` 把 canvas 铺满 ——
 * 所以这里**不能**自己派发 `resize` 事件，否则会和自己的监听自激成死循环。
 * 三端共用同一份：iOS / 桌面上这段只是把同样的值再写一遍，无副作用。
 */
private const val ViewportFitJs = """
<script>
(function(){
  function fit(){
    var h = window.innerHeight || 0;
    if (h <= 0) return;
    h = h + 'px';
    var d = document.documentElement, b = document.body;
    d.style.height = h; b.style.height = h;
    var s = document.getElementById('stage');
    if (s) { s.style.height = h; }
  }
  fit();
  window.addEventListener('resize', fit);
  window.addEventListener('orientationchange', fit);
  window.addEventListener('load', fit);
  document.addEventListener('DOMContentLoaded', fit);
  setTimeout(fit, 300); setTimeout(fit, 1000);
})();
</script>
"""

/** 真正交给 WebView 的页面 = 游戏 HTML + [ViewportFitJs]（插在 `</body>` 之前）。 */
internal val PlaneShooterPage: String =
    PlaneShooterHtml.replace("</body>", ViewportFitJs + "</body>")
