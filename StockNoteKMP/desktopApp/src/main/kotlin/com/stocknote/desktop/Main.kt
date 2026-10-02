package com.stocknote.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.stocknote.data.AppContainer
import com.stocknote.data.platform.DB_PATH_PROPERTY
import com.stocknote.data.platform.KEY_PATH_PROPERTY
import com.stocknote.feature.App
import com.stocknote.feature.state.AppStateHolder
import com.stocknote.feature.state.FileBridgeHolder
import com.stocknote.feature.state.Screen
import com.stocknote.feature.state.rememberAppStateHolder
import com.stocknote.feature.ui.JfxRuntime
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JMenuBar
import javax.swing.JMenuItem

/**
 * 桌面版入口 —— 老周 2026-09-30。
 *
 * ⚠️ 与 Android 壳（`MainActivity`）的**对应关系**，逐项对齐、不多做：
 *  | Android                        | 桌面                                   |
 *  |--------------------------------|----------------------------------------|
 *  | `AndroidPlatformContext.install` | 系统属性给出 db / key 路径（见下）      |
 *  | `AndroidFileBridge`（SAF）      | `DesktopFileBridge`（AWT 文件对话框）   |
 *  | `BuildConfig.SEED_DEMO_DATA`    | 系统属性 `stocknote.seedDemo=true`      |
 *  | 底部 tab（5 个页面）            | **菜单栏 5 个一级菜单项**（见下）       |
 *  | 应用图标（mipmap）              | ICO 嵌入 exe + 运行时窗口图标（同一张图） |
 *  | edge-to-edge / BackHandler / 启动图 / Toast 双击退出 | 无（桌面没有这些概念） |
 *
 * 数据目录（与手机端一致的口径：**本地、无账号、无云**）：
 *  - Windows：`%APPDATA%\StockNote\`
 *  - macOS / Linux：`~/.stocknote/`
 */
fun main() {
    val dataDir = desktopDataDir().apply { mkdirs() }
    System.setProperty(DB_PATH_PROPERTY, File(dataDir, "stocknote.db").absolutePath)
    System.setProperty(KEY_PATH_PROPERTY, File(dataDir, "key.properties").absolutePath)

    FileBridgeHolder.impl = DesktopFileBridge()

    // ⚠️ 提前预热 JavaFX（见 JfxRuntime 的说明）：必须**在本线程之外**启动，
    // 否则首个 JFXPanel 会在 AWT 线程上等 JavaFX → 死锁（页面空白 + 整个界面点不动）。
    JfxRuntime.prewarm()

    val container = AppContainer()
    if (System.getProperty("stocknote.seedDemo").toBoolean()) container.seedDemoDataIfEmpty()

    application {
        // ⚠️ `LocalDensity.current` 是组合调用，**不能**塞进 `remember {}` 里（编译期就报错）。
        // 另外尺寸只算一次：否则用户手动拉伸窗口后，任何一次重组都会把窗口弹回原大小。
        val density = LocalDensity.current
        val geometry = remember { windowGeometry(density) }
        val windowState = rememberWindowState(
            size = geometry.first,
            position = geometry.second,
        )
        Window(
            onCloseRequest = {
                // 关窗前把驱动与网络连接收干净（与 MainActivity.onDestroy 同口径）
                container.close()
                exitApplication()
            },
            title = "股票交易笔记",
            state = windowState,
        ) {
            // ⚠️ holder 必须在窗口内容里创建、并**同时交给菜单栏与 App**：
            // 菜单栏要调 holder.select() 才能切页，而 App 内部默认自己 remember 一份 ——
            // 两边各拿一份就会"菜单点了不动"。所以由这里统一创建、通过 shellHolder 注入。
            val shellHolder = rememberAppStateHolder(container)

            // ---- 图标：与手机应用同一个（老周 2026-09-30）----
            // ⚠️ jpackage 只把 ICO 嵌进 **exe 资源**（管资源管理器/快捷方式），
            // 窗口标题栏与任务栏看的是 **窗口自己的 iconImages**，不设就还是 JVM 默认图标。
            // 两者都要设，才能做到"任务栏 + 窗口左上角 + 文件图标"全是手机那个图标。
            DisposableEffect(Unit) {
                window.iconImages = loadWindowIcons()
                onDispose { }
            }

            // ---- 菜单栏：5 个页面做成**一级菜单项**（点一次就切页，等价手机底部 tab）----
            // ⚠️ 刻意**不用** Compose 的 `MenuBar { }` DSL：它的顶层只能放"带子项的 Menu"，
            // 做不出「统计 / 持仓 / 探索 / 计划 / 分析」这种一级即可点击的项。
            // Compose 的菜单栏本身也是 Swing 实现，这里直接把 `JMenuItem` 挂到 `JMenuBar` 上。
            DisposableEffect(Unit) {
                val bar = buildAppMenuBar(shellHolder)
                val previous = window.jMenuBar
                window.jMenuBar = bar
                onDispose { window.jMenuBar = previous }
            }

            App(
                container = container,
                versionLabel = VERSION_LABEL,
                // 「关于」页的"最低系统要求"文案由外壳给（2026-10-02）：
                // 此前该文案写死在 commonMain，桌面版也显示成 "Android 7.0+"。
                platformLabel = "Windows 10+",
                desktopShell = true,
                shellHolder = shellHolder,
            )
        }
    }
}

/**
 * 「关于」页 / 导出日志里的版本号。
 *
 * ⚠️ **不要在这里手写版本号**（老周 2026-10-02）：这里原本写着常量 `"1.1.6 · 桌面版"`，
 * 而当时工程已经是 1.1.8 —— 手写必然漏改。现在从构建脚本生成的 [DesktopBuildInfo] 取，
 * 要发版只改 `desktopApp/build.gradle.kts` 顶部的 `desktopVersion` 一处。
 */
private val VERSION_LABEL: String = DesktopBuildInfo.VERSION + " · 桌面版"

/**
 * 一级菜单 = 5 个页面（与 `Screen` 顺序一致）。
 *
 * ⚠️ 刻意**不设** `accelerator`（老周 2026-09-30）：Swing 会把快捷键文字画在菜单项右侧
 * （就是截图里那串 "统计 Ctrl+1"），而这里不想要。注意 Swing **做不到"注册快捷键但不显示"** ——
 * 加速键与右侧文字是同一个东西，所以是连快捷键一起去掉；想恢复快捷键就得接受那串文字。
 *
 * ⚠️⚠️ 点菜单必须**先清空导航栈**（老周 2026-09-30 报"进了资讯详情后点菜单没反应"）：
 * 一级页面的容器（`MainTabs`）只在**路由栈为空**时才被渲染；停在二级页（资讯详情 / 标的详情…）时
 * `holder.select()` 只改了"该显示哪个 tab"，界面仍停在二级页上 —— 看起来就是"点了没反应"。
 * 必须先 `popToTabs()` 回到 tab 层再切页（与 App.kt 里底部 tab 的 onTab 同一处理）。
 */
private fun buildAppMenuBar(holder: AppStateHolder): JMenuBar {
    val bar = JMenuBar()
    Screen.entries.forEach { screen ->
        val item = JMenuItem(screen.label)
        item.addActionListener {
            com.stocknote.feature.nav.AppNav.popToTabs()
            holder.select(screen)
        }
        bar.add(item)
    }
    return bar
}

/**
 * 窗口图标：读打包进 jar 的手机图标（`desktopApp/src/main/resources/stocknote-icon.png`，
 * 就是 androidApp 的 `mipmap-xxxhdpi/ic_launcher.png`）。
 *
 * ⚠️ 提供 **32 / 64 / 192 三档**：任务栏与 Alt+Tab 用小图、窗口用大图，
 * 只给一张大图会被系统粗暴缩放、发虚。
 */
private fun loadWindowIcons(): List<Image> {
    // ⚠️ 这里**不能**写 `MainKt::class`：MainKt 是编译器按文件名生成的类，源码里引用不到
    // （报 Unresolved reference）。用匿名对象拿到同一个类加载器即可读到 jar 内资源。
    val src = runCatching {
        object {}.javaClass.getResourceAsStream("/stocknote-icon.png")?.use { ImageIO.read(it) }
    }.getOrNull() ?: return emptyList()

    return listOf(32, 64, 192).mapNotNull { size ->
        runCatching {
            val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(src, 0, 0, size, size, null)
            g.dispose()
            out as Image
        }.getOrNull()
    }
}

/**
 * 窗口占**屏幕可用区**的比例 —— 老周 2026-09-30 按他给的截图量的：
 * 宽度约占屏宽 46%、高度约占屏高的 92%（几乎满高，所以看着像竖长条）。
 *
 * ⚠️ 为什么用比例而不是写死像素：他给的图里窗口明显比之前的 800×600 窄、且几乎顶到屏幕上下边，
 * 本质诉求是"跟图里这么大"；按比例算，在 1280×720 上得约 592×626，在 1080p 上按比例放大，
 * 任何屏幕上都是"这么大"，不用为每台机器改数字。
 */
private const val WINDOW_SCREEN_W_RATIO = 0.46f
private const val WINDOW_SCREEN_H_RATIO = 0.92f

/** 拿不到屏幕信息时的兜底尺寸（dp）。 */
private val FALLBACK_WINDOW_SIZE = DpSize(600.dp, 620.dp)

/**
 * 窗口尺寸与位置：按屏幕可用区的比例算，并居中。
 *
 * ⚠️ 位置必须**自己算**：`WindowPosition(Alignment.Center)` 在混合 DPI 下会摆偏
 * （150% 缩放 + 1280×720 屏上实测被推到 x≈448px，窗口右边近 200px 出屏）。
 */
private fun windowGeometry(density: Density): Pair<DpSize, WindowPosition> {
    val work = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }.getOrNull() ?: return FALLBACK_WINDOW_SIZE to WindowPosition(Alignment.Center)

    // AWT 的 maximumWindowBounds 是**物理像素**（本机实测 1280×720），故要除以 density 换成 dp
    val screenW = with(density) { work.width.toDp() }
    val screenH = with(density) { work.height.toDp() }
    val size = DpSize(
        screenW * WINDOW_SCREEN_W_RATIO,
        screenH * WINDOW_SCREEN_H_RATIO,
    )

    val x = (screenW - size.width) / 2
    val y = (screenH - size.height) / 2
    return size to WindowPosition(
        if (x > 0.dp) x else 0.dp,
        if (y > 0.dp) y else 0.dp,
    )
}

/** 桌面数据目录：Windows 放 APPDATA，其余系统放用户主目录下的隐藏目录。 */
internal fun desktopDataDir(): File {
    val appData = System.getenv("APPDATA")
    return if (!appData.isNullOrBlank()) File(appData, "StockNote")
    else File(System.getProperty("user.home"), ".stocknote")
}
