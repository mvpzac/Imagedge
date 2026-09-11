// :app 应用模块（Compose UI，PBF 分包：com.imagedge.camera）
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 签名配置：本地 keystore.properties（勿提交）存在时读取，否则 releaseSigningConfig 为 null
val keystorePropsFile = rootProject.file("keystore.properties")
val releaseSigningConfig = if (keystorePropsFile.exists()) {
    val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
    android.signingConfigs.create("release") {
        storeFile = rootProject.file(props["storeFile"] as String)
        storePassword = props["storePassword"] as String
        keyAlias = props["keyAlias"] as String
        keyPassword = props["keyPassword"] as String
    }
} else {
    null
}

android {
    namespace = "com.imagedge.camera"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.imagedge.camera"
        minSdk = 29
        targetSdk = 36
        // 0.2.0-alpha05：液态玻璃对齐官方参数（轻模糊 + 边缘折射）+ 切页性能优化 + 按钮真玻璃
        versionCode = 1014
        versionName = "0.2.0-alpha05"

        // 仅支持 64 位设备（项目决策 2026-08-29）：排除 32 位 ABI
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // 开源项目决策（2026-08-28）：不做混淆/资源收缩，保证反编译可读、便于社区审查与二次开发
            isMinifyEnabled = false
            isShrinkResources = false
            // 签名：本地存在 keystore.properties 时用正式密钥（该文件已被 .gitignore 排除），
            // 其余贡献者无此文件时 release 保持未签名，不影响 CI 构建
            signingConfig = releaseSigningConfig
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // 存量警告入基线（2026-08-31 生成），之后只对新增问题报警
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // 项目模块
    implementation(project(":core"))
    implementation(project(":ptp"))
    implementation(project(":upnp"))
    implementation(project(":liveview"))
    implementation(project(":raw"))
    implementation(project(":lut"))
    implementation(project(":motionphoto"))
    implementation(project(":share"))
    implementation(project(":image"))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 协程 / 序列化
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Liquid Glass 效果（可行性验证：仅加依赖，尚未接入任何 UI）
    implementation(libs.backdrop)

    // 图片
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // 视频播放（Media3 ExoPlayer）
    implementation(libs.androidx.exifinterface)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

/**
 * UI 规范静态检查（docs/UI-SPEC.md §6.1 决策表）。
 *
 * `feature/` 下不允许直接使用 Material3 原生控件——同一屏出现两套高度/圆角/配色，
 * 正是规范化之前的状态。所有控件必须来自 `ui/components` 的设计系统组件；
 * 组件实现本身在 `ui/` 下，不受本检查约束（AppControls 内部就是包装 M3 控件）。
 *
 * 为什么做成 Gradle 任务而不是 CI 里的 grep：本地 `./gradlew check` 与 CI 跑同一份逻辑，
 * 且输出带文件与行号，错误信息直接指向替代组件。
 */
val uiSpecCheck = tasks.register("uiSpecCheck") {
    group = "verification"
    description = "检查 feature/ 是否绕过设计系统直接使用 M3 控件（UI-SPEC §6.1）"

    val featureDir = layout.projectDirectory.dir("src/main/java/com/imagedge/camera/feature")
    inputs.dir(featureDir).withPropertyName("featureSources")

    // 裸控件 → 设计系统替代品（错误信息里直接给出该用什么）
    val banned = linkedMapOf(
        "Button" to "AppButton",
        "FilledTonalButton" to "AppButton(SECONDARY)",
        "OutlinedButton" to "AppButton(SECONDARY)",
        "ElevatedButton" to "AppButton",
        "TextButton" to "AppLink",
        "IconButton" to "AppIconButton",
        "FilterChip" to "AppChip / AppChipRow",
        "AssistChip" to "AppChip / AppLink",
        "OutlinedTextField" to "AppTextField",
        "Switch" to "AppSwitch / AppSwitchRow",
        "Slider" to "AppSlider",
        "Card" to "GlassCard",
    )

    doLast {
        val violations = mutableListOf<String>()
        featureDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    // 去掉行尾注释（保留 URL 中的 //），整行注释与文档注释直接跳过
                    val code = raw.replace(Regex("(?<!:)//.*$"), "")
                    val trimmed = code.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@forEachIndexed
                    }
                    banned.forEach { (control, replacement) ->
                        // 排除 AppButton(/GlassCard( 这类设计系统组件：
                        // 名字前面是字母、数字、下划线或点号时不算命中
                        if (Regex("(?<![\\w.])$control\\s*\\(").containsMatchIn(code)) {
                            val path = file.relativeTo(featureDir.asFile).path
                            violations += "$path:${index + 1} 直接使用了 M3 的 $control —— 请改用 $replacement"
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("UI 规范检查未通过（docs/UI-SPEC.md §6.1 组件决策表）：")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("设计系统组件：AppPage / AppSection / AppButton / AppLink / AppIconButton /")
                    append("AppChip / AppChipRow / AppSlider / AppTextField / AppSwitch(Row) / GlassCard / States")
                }
            )
        }
        logger.lifecycle("UI 规范检查通过：feature/ 下未发现裸 M3 控件")
    }
}

// 并入标准校验链路：本地 ./gradlew check 与 CI 都会执行
tasks.named("check") { dependsOn(uiSpecCheck) }
