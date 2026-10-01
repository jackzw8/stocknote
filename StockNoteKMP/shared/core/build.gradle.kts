import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// iOS 目标门控：Windows 上无法为 Apple 平台编译 Kotlin/Native。
// 默认按宿主自动判定，保证 Android 交付永远不被 iOS 拖累（可用 -Pstocknote.ios=true 强制开启）。
val iosEnabled: Boolean = when (providers.gradleProperty("stocknote.ios").orNull?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> System.getProperty("os.name").startsWith("Mac")
}

kotlin {
    jvmToolchain(17)

    // 纯 Kotlin 模块的快速单测通道；同时为将来的桌面行情预拉取工具预留
    jvm()

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    if (iosEnabled) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.stocknote.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
