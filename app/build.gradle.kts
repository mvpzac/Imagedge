// :app 应用模块（Compose UI，PBF 分包：com.imagedge.camera）
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.imagedge.camera"
        minSdk = 29
        targetSdk = 36
        versionCode = 1008
        versionName = "0.1.8"

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
