// 注意：必须显式 import。在 Gradle .kts 里 `java` 会被 JavaPluginExtension 扩展遮蔽，
// 直接写 java.util.Properties 会报 "Unresolved reference: util"。
// ⚠️ 2026-10-01：我给 debug 时间串写 java.time.LocalDateTime 时**又踩了同一个坑**
//（尽管这行注释就在眼前）—— 教训：写 java.* 之前先看这里。
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

// release 签名：从项目根的 keystore.properties 读取（不存在则 release 不签名，不影响 debug 构建）
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropsFile.exists()

android {
    namespace = "com.stocknote.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.stocknote.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 版本号由老周指定（2026-10-04：1.1.11 —— 相对 1.1.10 的内容：
        // ① **探索页新增「星际战机」小游戏**：内嵌离线 H5（canvas，10 关 + 剧情 + 母舰 Boss），
        //    新增跨平台 `LocalHtmlView`（内嵌本地 HTML 字符串）承载；
        // ② **去掉探索页底部那行说明文字**；
        // ③ 修掉小游戏真机黑屏：`loadDataWithBaseURL` 内部拼 `data:` URL 会把文档在第一个 `#` 处
        //    截断，且该页 `html/body/#stage` 的百分比高度链在 WebView 里算成 0
        //    ⇒ Android 改成"真 URL + `shouldInterceptRequest` 就地应答"，另注入显式像素高度。
        //
        // versionCode 必须单调递增（58 → 59 → 60 → 61），否则实机覆盖安装会失败
        versionCode = 61
        versionName = "1.1.11"

        // 演示数据：**默认不播种**（老周 2026-09-17：debug 版也不要种子数据，
        // 以便真实复现「全新空账本」场景）。需要演示数据时用 -PseedDemo=true 构建。
        val seedDemo = (project.findProperty("seedDemo") as String?)?.toBoolean() ?: false
        buildConfigField("boolean", "SEED_DEMO_DATA", seedDemo.toString())

        // 真机 UI 自动化（androidTest）：UiAutomator 驱动
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ⚠️ 刻意**不加** abiFilters 去掉 x86 / x86_64：
        // 应用宝 PC 版的安卓 VM 是 x86 架构，去掉后本机的装机验证链路直接断掉。
        // 体积优化（省约 4MB）留给正式发布时用 productFlavors 或 AAB 分发解决。
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                val props = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    // ⚠️ 2026-10-01（老周要求）：**debug 包的版本名追加构建时间（到分钟）** ——
    // 多次安装的 debug 包能一眼分辨是哪一次打的（上一轮他问「debug 包版本有变吗」的后续）。
    // 显示效果（「设置 → 关于」）：`1.1.8-dbg.20261001-1941 (58)`。
    // ⚠️ 取**构建机本地时间**，每次构建都变 —— 变了才能分辨，这正是目的；
    // 代价是 versionName 变化会让 manifest 重打，debug 构建基本不享受增量，可接受。
    // ⚠️ **release 完全不受影响**（老周：发布包的版本号还是按他说的来）。
    val debugStamp: String = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 老周要求：debug 版本名追加构建时间（到分钟），见上面 debugStamp 的说明。
            versionNameSuffix = "-dbg.$debugStamp"
        }
        release {
            // M0/M1 阶段先不开 R8：Compose + kotlinx.serialization + Ktor 需要一整套 keep 规则，
            // 等业务稳定后再专门做一次混淆验证，避免"为了压缩体积引入一堆运行时崩溃"。
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // release 版不播种演示数据需要读 BuildConfig.DEBUG（AGP 8 起默认不生成）
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":shared:feature"))
    implementation(libs.androidx.activity.compose)

    // 真机 UI 自动化测试（老周 2026-09-16 要求执行 9/14 的方案）
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
