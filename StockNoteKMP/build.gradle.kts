plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    // ⚠️ 必须在这里声明（老周 2026-09-30 桌面版）：kotlin.jvm 与 kotlin.android /
    // kotlin.multiplatform 是**同一个 kotlin-gradle-plugin 的 jar**。根项目只说这后两个时，
    // 该 jar 已在 classpath、但 Gradle 记不住它的版本，子模块再 `alias(kotlinJvm)` 就报
    //   "plugin is already on the classpath with an unknown version"
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
}
