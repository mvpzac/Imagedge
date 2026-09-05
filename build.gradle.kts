// Imagedge 根构建脚本
// 规范：版本统一见 gradle/libs.versions.toml（规范第 6 条）

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// 全模块通用编译选项：UTF-8 编码、JVM 21（规范第 2 条）
// 注意：Kotlin 2.3 起 kotlinOptions 已移除，必须用 compilerOptions DSL
// 注意：此处 jvmTarget 必须与各模块 java/compileOptions 的 VERSION_21 一致，
//       否则 Gradle 的 JVM-target 一致性校验会直接失败
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}
