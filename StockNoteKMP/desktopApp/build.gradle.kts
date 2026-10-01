import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/**
 * 桌面外壳（Windows / macOS / Linux）—— 老周 2026-09-30。
 *
 * 只做三件事：开窗口、注入桌面版的「文件桥」、把 jpackage 打包配好。
 * **所有业务 UI 都来自 `:shared:feature` 的 commonMain**（与 Android/iOS 同一份代码）。
 */
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared:feature"))
    // 桌面渲染引擎（Skiko）+ 窗口/AWT 集成，按当前操作系统解析
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "com.stocknote.desktop.MainKt"

        nativeDistributions {
            // ⚠️ 免安装版（`createDistributable`）**不需要 WiX**：
            //     产物 build/compose/binaries/main/app/StockNote/StockNote.exe（双击即用）
            // 下面两个格式是**安装包**，需要本机装 WiX Toolset 才能构建：
            //     `packageMsi` / `packageExe`（本轮未装 WiX，故只出免安装版）
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)

            // ⚠️⚠️ **必须显式声明这些 JDK 模块**（老周 2026-09-30 踩坑，现象极难猜）：
            // CMP 插件默认只 jlink 进 7 个模块（java.base/datatransfer/xml/prefs/desktop/
            // logging/jdk.crypto.ec）。而 `sqlite-jdbc` 走 JDBC → 需要 **java.sql**，
            // 于是启动即崩，两个不同现象让人完全想不到是同一个原因：
            //   ① 直接跑主类：`NoClassDefFoundError: java/sql/DriverManager`
            //      （堆栈指向 Platform.jvm.kt 的建表探测 —— 看着像"驱动写错了"）；
            //   ② 双击 exe：只弹一句 **"Failed to launch JVM"**（启动器不说缺哪个类）。
            // 修法就是补模块，**不是**改驱动、也不用去装什么"Java 运行库"。
            modules(
                "java.sql",          // ★ JDBC（sqlite-jdbc 必需）
                "java.naming",       // JDBC / 数据源查找
                "jdk.unsupported",   // sun.misc.Unsafe（Kotlin/Compose 常用）
                "java.management",   // 诊断与监控
                "java.instrument",   // 字节码仪器（部分库按需）
                "java.net.http",     // 现代 HTTP 客户端（网络层备用）
                // ⚠️ 下面两个是**内嵌浏览器（JavaFX WebView）必需**的，真机踩过（2026-09-30）：
                // 少了它们运行期分别报 NoClassDefFoundError，界面直接空白 —— 而这两个类
                // 在源码里根本不会被直接引用，编译器/IDE 都发现不了，只能靠真机或探针暴露。
                "jdk.unsupported.desktop", // ★ jdk.swing.interop.SwingInterOpUtils（JFXPanel↔Swing 互操作）
                "jdk.jsobject",            // ★ netscape.javascript.JSObject（WebView 的 JS 桥）
            )

            packageName = "StockNote"
            // jpackage 只接受 major.minor.patch 的纯数字版本，不能带后缀
            packageVersion = "1.1.8"
            // ⚠️ description / vendor 必须是**纯 ASCII**（老周 2026-09-30 踩坑）：
            // jpackage 按**平台默认编码**读取插件的参数文件，中文 Windows 是 GBK，
            // 而参数文件是 UTF-8 写出的 → 一遇中文就抛 `Input length = 1`（MalformedInput），
            // 表现为 createDistributable 直接失败（错误信息里只有这一行，很难猜到是编码）。
            // 中文标题不在这里配 —— 交给窗口标题（运行时字符串，与打包无关）。
            description = "StockNote Desktop"
            vendor = "StockNote"

            windows {
                menu = true
                shortcut = true
                // 桌面图标 = 手机应用图标（老周 2026-09-30）：
                // 由 `icons/build-icon.ps1` 从 androidApp 的 mipmap-xxxhdpi/ic_launcher.png
                // 生成多尺寸 ICO（16/32/48/64/128/192），改图标后重跑该脚本即可。
                iconFile.set(project.file("icons/StockNote.ico"))
            }
        }
    }
}
