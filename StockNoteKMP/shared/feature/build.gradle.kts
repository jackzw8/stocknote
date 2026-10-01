import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val iosEnabled: Boolean = when (providers.gradleProperty("stocknote.ios").orNull?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> System.getProperty("os.name").startsWith("Mac")
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // ⚠️ 桌面（Compose Multiplatform Desktop，老周 2026-09-30）：
    // 与 android/ios 共用**同一份 commonMain UI**（已核实 commonMain 零 Android 专有 import），
    // 只需在 jvmMain 补两个 expect 的桌面实现（HtmlView / rememberBrowserOpener）。
    // 消费方是 `:desktopApp`（纯 JVM + compose.desktop），负责窗口外壳与 jpackage 打包。
    jvm()

    if (iosEnabled) {
        // 静态 framework，供 iosApp 用 `import SharedUI` 引用
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "SharedUI"
                isStatic = true
            }
        }
    }

    sourceSets {
        // ⚠️ 桌面端内嵌浏览（老周 2026-09-30）：JavaFX WebView —— 腾讯资讯正文是 SPA，
        // 必须有真实浏览器内核才能渲染。**只加在 jvm 变体**，Android/iOS 完全不受影响。
        //
        // ⚠️⚠️ 每个模块都**必须显式带 `:win`**，包括看起来"用不到"的 controls / media：
        // JavaFX 在 Maven 上是"**主 jar 空壳 + 平台 jar 才是真货**"的结构 ——
        // 主 jar 里几乎没有类，真正的实现（含 native）在 `-win` jar 里。
        // 只声明 web/swing/graphics/base 时，controls 会被 web **传递**带进来，但只解析到**空壳**，
        // 于是运行期 `WebView()` 构造直接抛：
        //     NoClassDefFoundError: javafx/scene/control/Control
        // （真机实测 2026-09-30：日志里就这一行，页面表现为空白）。
        jvmMain.dependencies {
            listOf(
                "javafx-base",
                "javafx-graphics",
                "javafx-controls",
                "javafx-swing",
                "javafx-media",
                "javafx-web",
            ).forEach { artifact ->
                implementation("org.openjfx:$artifact:${libs.versions.javafx.get()}:win")
            }
        }

        commonMain.dependencies {
            // api 而非 implementation：AppStateHolder 的构造参数是 AppContainer、
            // 状态里带 PortfolioSnapshot 等 core 类型，这些都在公开签名上，
            // 必须向上传给平台外壳，否则外壳编译不过。
            api(project(":shared:core"))
            api(project(":shared:data"))

            implementation(libs.kotlinx.coroutines.core)

            // 同理：androidApp / iosApp 的外壳要直接调用 App() 与 setContent
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
        }
    }
}

android {
    namespace = "com.stocknote.feature"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
