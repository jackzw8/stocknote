package com.stocknote.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import com.stocknote.data.AppContainer
import com.stocknote.feature.App
import com.stocknote.feature.AppStartupError
import com.stocknote.feature.nav.AppNav
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * Android 唯一的一个 Activity。
 *
 * 整个 Android 外壳就这么多代码 —— 页面、状态、账本逻辑全部在 shared 模块里，
 * iOS 外壳将来也是同样的形状（SwiftUI 里包一层 UIViewControllerRepresentable）。
 *
 * 已知取舍（M1 处理，不影响 M0 验证）：
 *   建库与种子数据目前跑在主线程。数据量只有十几行，冷启动耗时可忽略；
 *   但正式版本要挪到后台并加启动占位，否则首次启动会有可感知的停顿。
 */
class MainActivity : ComponentActivity() {

    private var container: AppContainer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 平台上下文由 StockNoteApplication 注入（Application.onCreate 早于本方法）
        // 演示数据只在 debug 构建播种 —— **release 对外版必须是空账本**（老周 2026-09-16：
        // 真机装 release 看到演示数据，正式版不能带任何测试数据）
        val startup = runCatching {
            // 演示数据由构建参数控制（默认 false）—— 老周 2026-09-17：
            // debug 版同样不要种子数据，便于验证"全新空账本"路径
            AppContainer().also { if (BuildConfig.SEED_DEMO_DATA) it.seedDemoDataIfEmpty() }
        }
        container = startup.getOrNull()

        // ===== 全屏绘制（edge-to-edge，老周 2026-09-17）=====
        // 必须让 Compose 内容**绘制到状态栏/导航栏区域**：否则窗口层启动图占满整屏、
        // Compose 层只能画到状态栏下方 → 切换时图片整体下移一个状态栏高度，肉眼可见"跳一下"。
        // 开启后由各页面自己用 statusBarsPadding / navigationBarsPadding 避开系统栏。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // ===== 系统文件选择器（SAF）注入（老周 2026-09-17）=====
        // CSV 导入导出 / 备份恢复走系统文件选择器；必须在 Activity 创建期注册 launcher
        com.stocknote.feature.state.FileBridgeHolder.impl = AndroidFileBridge(this)

        setContent {
            // ===== 字体大小（技术方案-字体大小设置 · 方案 C 务实版，老周 2026-09-16）=====
            // 由用户在「设置 → 显示」选择 1.0 / 1.15 / 1.3 / 1.4，存 app_setting；
            // 这里从全局可观察状态取值 —— 改设置即时重组生效，无需重启。
            // **不跟随系统 fontScale**：否则"系统 1.4 × 应用 1.4"叠乘会把布局压垮。
            val fontScaleState = com.stocknote.feature.state.AppDisplaySettings.fontScale
                .collectAsState()

            // ===== 启动图（老周 2026-09-17；时长 1~3 秒，16:52 下调）=====
            // 联网数据加载完（dataReady）且至少展示 1 秒 → 关闭；最多 3 秒强制关闭。
            val dataReady = com.stocknote.feature.state.AppDisplaySettings.dataReady
                .collectAsState()
            var showSplash by remember { mutableStateOf(true) }
            // 右上角倒计时（老周 2026-09-18）：按秒从 3 递减，让用户知道还要等多久
            var splashCountdown by remember { mutableIntStateOf((SPLASH_MAX_MS / 1000).toInt()) }
            LaunchedEffect(Unit) {
                val startAt = System.currentTimeMillis()
                while (true) {
                    val elapsed = System.currentTimeMillis() - startAt
                    // 剩余整秒（向上取整）：0~999ms → 3、1000~1999ms → 2、2000~2999ms → 1
                    splashCountdown = kotlin.math.ceil((SPLASH_MAX_MS - elapsed) / 1000.0)
                        .toInt().coerceAtLeast(0)
                    if ((dataReady.value && elapsed >= SPLASH_MIN_MS) || elapsed >= SPLASH_MAX_MS) {
                        showSplash = false
                        break
                    }
                    kotlinx.coroutines.delay(100)
                }
            }
            LaunchedEffect(Unit) {
                // 启动读一次设置（此刻 container 可能还在初始化，稍后重试一次）
                repeat(3) {
                    val c = container
                    if (c != null) {
                        // ⚠️ 2026-09-28 拆分：字体缩放/费率改走设置域 SettingsRepository
                        com.stocknote.feature.state.AppDisplaySettings.loadFrom(c.settings)
                        return@LaunchedEffect
                    }
                    kotlinx.coroutines.delay(200)
                }
            }
            val sysDensity = androidx.compose.ui.platform.LocalDensity.current
            val scaled = androidx.compose.ui.unit.Density(
                density = sysDensity.density,
                fontScale = fontScaleState.value,
            )
            androidx.compose.runtime.            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides scaled,
                // 字体档位下发到全树：次要文案据此做反向补偿（技术方案-方案 C）
                com.stocknote.feature.ui.LocalAppFontScale provides fontScaleState.value,
            ) {
                // ===== 返回/侧滑手势（老周 2026-09-16 定稿）=====
                // 副页（有栈）：返回上一页；
                // 主页（栈空）：第一次提示「再滑一次退出应用」，2 秒内再滑一次才退出。
                // 手势导航的边缘侧滑与系统返回键都走 OnBackPressedDispatcher，BackHandler 均可捕获。
                var exitArmed by remember { mutableStateOf(false) }
                LaunchedEffect(exitArmed) {
                    if (exitArmed) {
                        kotlinx.coroutines.delay(2000)
                        exitArmed = false
                    }
                }
                BackHandler(enabled = true) {
                    if (AppNav.canPop) {
                        AppNav.pop()
                    } else if (exitArmed) {
                        finish()
                    } else {
                        exitArmed = true
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "再滑一次退出应用",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                // ===== 启动图覆盖层（全屏，含状态栏区域）=====
                Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                // 顶部留出状态栏/刘海空隙，避免页面内容被遮挡（老周 2026-09-16）
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    val ready = container
                    if (ready != null) {
                        App(
                            container = ready,
                            versionLabel = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        )
                    } else {
                        val cause = startup.exceptionOrNull()
                        AppStartupError(
                            message = cause?.let { "${it::class.java.simpleName}: ${it.message ?: "(无消息)"}" }
                                ?: "未知错误",
                        )
                    }
                }

                // 淡出（老周 2026-09-17）：数据就绪后 300ms 淡出，避免生硬消失
                androidx.compose.animation.AnimatedVisibility(
                    visible = showSplash,
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(300),
                    ),
                ) {
                    SplashOverlay(
                        countdown = splashCountdown,
                        onSkip = { showSplash = false },
                    )
                }
                } // 启动图覆盖层 Box
            }
        }
    }

    override fun onDestroy() {
        container?.close()
        container = null
        super.onDestroy()
    }
}

/**
 * 启动图：项目文档「股票交易笔记启动图-国风红金」，Crop 填满全屏（含状态栏区域）。
 *
 * 右上角（老周 2026-09-18）：**倒计时 + 关闭按钮** ——
 * 让用户知道还要等几秒，也可以直接跳过。
 */
@Composable
private fun SplashOverlay(countdown: Int, onSkip: () -> Unit) {
    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(Color(0xFF170C0A)),
    ) {
        // 与窗口层 windowBackground **同一张图 + 同一种缩放**（FillBounds = 窗口背景的默认 fill），
        // 否则两层缩放方式不同会在衔接瞬间产生"向两边扩大"的视觉跳变（老周 2026-09-17 反馈）
        Image(
            painter = painterResource(R.drawable.splash_window),
            contentDescription = "启动图",
            contentScale = ContentScale.FillBounds,
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        )

        Row(
            modifier = androidx.compose.ui.Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 倒计时：3 → 2 → 1
            Box(
                modifier = androidx.compose.ui.Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$countdown",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(androidx.compose.ui.Modifier.width(10.dp))
            // 关闭：直接跳过启动图（用户主动点击，立即生效）
            Box(
                modifier = androidx.compose.ui.Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                    .clickable { onSkip() }
                    .semantics { contentDescription = "splash_close_button" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 启动图时长（毫秒）：下限 1 秒、上限 3 秒（老周 2026-09-17 定，2026-09-18 提为常量） */
private const val SPLASH_MIN_MS = 1000L
private const val SPLASH_MAX_MS = 3000L
